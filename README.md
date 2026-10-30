# Praksis-Modules-Mail

The mail modules of [Praksis](https://github.com/tuhde/Praksis), as module archives:

| Module | Provides |
|---|---|
| `imap` | the source `imap.inbox` (new mail, with IDLE), `mail.folders`, `mail.list`, `mail.search`, `mail.get`, `mail.mark`, `mail.move`, `mail.delete`, `mail.attachment`, `mail.append`, `mail.accounts`; a header cache in SQLite; several accounts |
| `smtp` | `mail.send`, `mail.compose`, `mail.reply`, `mail.forward`, `mail.senders`; TLS, a limit per hour that survives a crash, repeat protection |
| `contacts` | an address book that learns from mail: `contacts.add`, `contacts.search`, `contacts.remove` |
| `notify` | `notify.show`: a desktop notification, rate limited, never through a shell |

The `maildir` module (a Maildir directory as a mail backend, and the reference for the contract
below) stays in the Praksis core and ships with its runtime image.

## Build and install

Build the core first (`./mvnw install` in `../Praksis`: it makes the runtime image and the
artifacts these modules compile against), then:

```sh
./mvnw verify        # unit tests, the archives, and the integration tests against mail servers
```

Each module directory then has its archive in `target/<name>.praksis-module`: a zip with
`module.toml`, the module jar and the jars it needs. Install it with the command line of Praksis:

```sh
praksis modules install praksis-module-imap/target/imap.praksis-module
praksis modules list
```

The command unpacks the module below the Praksis home directory and accepts it in `modules.lock`.
It is code that runs with the rights of the daemon: install what you trust. Settings go into
`praksis.toml` (`[module.imap]`, `[module.smtp]`, ...; several accounts as `[module.imap.work]`),
passwords through a credential binding in `policy.toml`. The ruleset
[Praksis-Mail](https://github.com/tuhde/Praksis-Mail) describes both in detail.

## The mail contract

Every mail backend (`maildir` in the core, `imap` here) provides the same names, so one mail
ruleset works with all of them. Two backends cannot be enabled together (`enabled_modules`).

- **Event `mail.received`**: `message` (an opaque id of the mail that only the backend
  understands), `folder`, `size`, `account` (optional), and the headers `from`, `to`, `cc`,
  `subject`, `message_id`, `date`, `list_id`, `precedence` and `x_spam_flag` (empty if absent). The
  idempotency key names the mail, so a restart announces what is still there and the store drops
  the repeat.
- **Event `mail.changed`**: something in a folder changed (new, removed, flags); views follow it.
- **`mail.move(message, folder)`**: moves a mail to a folder by name, creating the folder, and
  answers with the new `message`. Repeating a move whose mail already is there succeeds.
- **Reading**: `mail.folders`, `mail.list` (paging, sorting, `threaded`), `mail.search`,
  `mail.get` (headers, text, HTML as safe blocks, attachments), `mail.attachment` (bytes, with a
  size limit). The HTML is turned into text and blocks by the shared library
  `praksis-mail-html`: no script, no style, no remote content unless a person allows it for that
  mail.
- **Changing**: `mail.mark` (`seen`, `flagged`), `mail.delete` (destructive, a grant of its own),
  `mail.append` (puts a message into a folder; the SMTP module's copy in Sent).
- **Accounts**: a mail names its account in its id; capabilities that take no mail take an
  optional `account`, which grants can limit.
- **A mail id is opaque.** Rules pass `event.data.message` on and never look inside it.

The exact parameters, results and effect classes of every capability are in the `module.toml` of
the module.

## Safety notes

- `mail.send` has the effect class `external`: it always needs a grant, for rules as for people.
  A grant limits only the arguments it lists (list `to`, `cc` **and** `bcc` to limit recipients).
- Header values with a line break are refused (header injection), the sender must be the
  configured `from` or one of `allowed_from`, and the limit per hour counts all callers.
- The IMAP module refuses a server without UIDPLUS rather than expunge mails that a person marked
  as deleted; a mail is found again after a move by its Message-ID.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
