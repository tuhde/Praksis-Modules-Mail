// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Sends a mail. The effect class is {@code external}: it leaves the machine and cannot be taken
 * back, so it always needs a grant, from a person's command as well as from a rule.
 *
 * <p>What protects against a flood and a repeat: no more than {@code max_per_hour} mails in any
 * hour, over all callers; the number of recipients and the size of a mail are limited; and a call
 * that is repeated with the same idempotency key (after a crash of the daemon) answers with the
 * mail that was sent and sends nothing. A mail is recorded after the server accepted it, so a crash
 * in between can send it twice, never lose the record of one that was sent.
 *
 * <p>A dry run checks everything and sends nothing, and does not use up the limit. TLS is required
 * unless the server is on this machine; the certificate and the host name are checked.
 */
public final class MailSend implements Capability {
  /** One account: its settings, the way it builds mails and its record of what it sent. */
  private static final class Account {
    final String name;
    final SmtpSettings settings;
    final Composer composer;
    Outbox outbox;

    Account(String name, SmtpSettings settings) {
      this.name = name;
      this.settings = settings;
      this.composer = new Composer(settings);
    }
  }

  private final Map<String, Account> accounts = new LinkedHashMap<>();
  private Credentials credentials;
  private Path directory;

  // The account of the call in progress; a call is made with the lock held.
  private Account current;
  private SmtpSettings settings;
  private Composer composer;
  private Outbox outbox;

  @Override
  public void useUploadsDirectory(java.nio.file.Path directory) {
    accounts.values().forEach(a -> a.composer.uploads(directory));
  }

  @Override
  public String name() {
    return "mail.send";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    accounts.clear();
    Accounts.parse(config, SmtpSettings::parse)
        .forEach((name, s) -> accounts.put(name, new Account(name, s)));
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    this.credentials = credentials;
  }

  @Override
  public synchronized void useDataDirectory(Path directory) {
    this.directory = directory;
  }

  @Override
  public synchronized void close() {
    for (Account a : accounts.values()) {
      if (a.outbox != null) {
        a.outbox.close();
        a.outbox = null;
      }
    }
    outbox = null;
  }

  private Outbox outbox() throws CapabilityException {
    if (outbox == null) {
      try {
        // two accounts may log in as the same user: the name is part of the file
        outbox =
            Outbox.open(
                directory,
                (Accounts.DEFAULT.equals(current.name) ? "" : current.name + ".")
                    + settings.account());
        current.outbox = outbox;
      } catch (SQLException e) {
        // sending without a record would make the limit and the repeat protection worthless
        throw new CapabilityException("cannot open the outbox: " + e.getMessage(), true, e);
      }
    }
    return outbox;
  }

  @Override
  public synchronized Map<String, Object> invoke(
      CapabilityContext context, Map<String, Object> args) throws CapabilityException {
    if (accounts.isEmpty()) {
      throw new CapabilityException("mail.send is not configured", false);
    }
    current = accounts.get(Accounts.choose(accounts, args.get("account")));
    settings = current.settings;
    composer = current.composer;
    outbox = current.outbox;
    Composer.Composed mail = composer.compose(args, true);
    try {
      long now = System.currentTimeMillis();
      Optional<Outbox.Sent> before = outbox().byKey(context.idempotencyKey());
      if (before.isPresent()) {
        return result(before.get().messageId(), before.get().raw(), before.get().recipients());
      }
      int sent = outbox().lastHour(now);
      if (sent >= settings.maxPerHour()) {
        throw new CapabilityException(
            "the limit of "
                + settings.maxPerHour()
                + " mails per hour is reached for "
                + settings.account()
                + "; the next can be sent at "
                + Instant.ofEpochMilli(outbox().nextFree(now)),
            false);
      }
      if (!context.dryRun()) {
        transmit(mail);
        outbox()
            .record(
                context.idempotencyKey(),
                System.currentTimeMillis(),
                new Outbox.Sent(mail.messageId(), mail.raw(), mail.recipients().size()));
      }
      return result(mail.messageId(), mail.raw(), mail.recipients().size());
    } catch (SQLException e) {
      throw new CapabilityException("outbox: " + e.getMessage(), true, e);
    }
  }

  private static Map<String, Object> result(String messageId, byte[] raw, int recipients) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("message_id", messageId);
    out.put("raw", raw);
    out.put("recipients", (long) recipients);
    return out;
  }

  private void transmit(Composer.Composed mail) throws CapabilityException {
    String password = settings.user().isPresent() ? password() : null;
    Session session = Session.getInstance(properties());
    String protocol = settings.security() == SmtpSettings.Security.SSL ? "smtps" : "smtp";
    try (Transport transport = session.getTransport(protocol)) {
      transport.connect(settings.host(), settings.port(), settings.user().orElse(null), password);
      MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(mail.raw()));
      transport.sendMessage(message, mail.recipients().toArray(new InternetAddress[0]));
    } catch (MessagingException e) {
      throw new CapabilityException(
          "smtp " + settings.account() + ": " + e.getMessage(), isTemporary(e), e);
    }
  }

  /** A refused address or a refused login will not work on a second try; a lost connection may. */
  private static boolean isTemporary(MessagingException e) {
    return !(e instanceof jakarta.mail.AuthenticationFailedException
        || e instanceof jakarta.mail.SendFailedException);
  }

  private String password() throws CapabilityException {
    if (settings.credential().isEmpty()) {
      return Secrets.read(settings.passwordCommand());
    }
    if (credentials == null) {
      throw new CapabilityException(
          "credential '"
              + settings.credential().get()
              + "' cannot be read: the daemon gave this module no way to get secrets",
          false);
    }
    return credentials.secret(settings.credential().get());
  }

  private Properties properties() throws CapabilityException {
    Properties p = new Properties();
    String timeout = String.valueOf(settings.timeout().toMillis());
    String prefix = settings.security() == SmtpSettings.Security.SSL ? "mail.smtps." : "mail.smtp.";
    p.put(prefix + "connectiontimeout", timeout);
    p.put(prefix + "timeout", timeout);
    p.put(prefix + "writetimeout", timeout);
    p.put(prefix + "ssl.checkserveridentity", "true");
    p.put(prefix + "ssl.protocols", "TLSv1.2 TLSv1.3");
    p.put(prefix + "auth", String.valueOf(settings.user().isPresent()));
    switch (settings.security()) {
      case SSL -> p.put(prefix + "ssl.enable", "true");
      case STARTTLS -> {
        p.put(prefix + "starttls.enable", "true");
        p.put(prefix + "starttls.required", "true");
      }
      case NONE -> {}
    }
    if (settings.caFile().isPresent() && settings.security() != SmtpSettings.Security.NONE) {
      p.put(prefix + "ssl.socketFactory", socketFactory(settings.caFile().get()));
    }
    return p;
  }

  /** A socket factory that trusts the system's authorities and those in the PEM file. */
  private static SSLSocketFactory socketFactory(Path pem) throws CapabilityException {
    try {
      KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
      keys.load(null, null);
      int n = 0;
      TrustManagerFactory system =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      system.init((KeyStore) null);
      for (TrustManager tm : system.getTrustManagers()) {
        if (tm instanceof X509TrustManager x509) {
          for (var cert : x509.getAcceptedIssuers()) {
            keys.setCertificateEntry("system" + n++, cert);
          }
        }
      }
      try (InputStream in = Files.newInputStream(pem)) {
        for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
          keys.setCertificateEntry("ca" + n++, cert);
        }
      }
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(keys);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return context.getSocketFactory();
    } catch (GeneralSecurityException | IOException e) {
      throw new CapabilityException("cannot use ca_file " + pem + ": " + e.getMessage(), false, e);
    }
  }
}
