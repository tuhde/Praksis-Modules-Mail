// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContactsTest {
  private static CapabilityContext context(boolean dry) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dry;
      }

      @Override
      public String idempotencyKey() {
        return "k";
      }

      @Override
      public Optional<Event> event() {
        return Optional.empty();
      }
    };
  }

  @TempDir Path data;

  private final List<Capability> opened = new ArrayList<>();

  @AfterEach
  void tearDown() {
    opened.forEach(Capability::close);
  }

  private <T extends Base> T make(T capability) throws Exception {
    capability.configure(Map.of());
    capability.useDataDirectory(data);
    opened.add(capability);
    return capability;
  }

  private Map<String, Object> add(String addresses) throws Exception {
    return make(new ContactsAdd()).invoke(context(false), Map.of("addresses", addresses));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> search(String query, Object... limit) throws Exception {
    Map<String, Object> args = new HashMap<>(Map.of("query", query));
    if (limit.length > 0) {
      args.put("limit", limit[0]);
    }
    return (List<Map<String, Object>>)
        make(new ContactsSearch()).invoke(context(false), args).get("items");
  }

  @Test
  void addressesOfAHeaderListAreCountedAndTheNewestNameIsKept() throws Exception {
    assertThat(add("Anna Schmidt <Anna@Example.org>, bob@example.org, Carl <carl@work.example>"))
        .containsEntry("seen", 3L)
        .containsEntry("added", 3L);
    assertThat(add("\"Schmidt, Anna\" <anna@example.org>"))
        .containsEntry("seen", 1L)
        .containsEntry("added", 0L);

    List<Map<String, Object>> found = search("anna");
    assertThat(found).hasSize(1);
    assertThat(found.get(0))
        .containsEntry("address", "anna@example.org")
        .containsEntry("seen", 2L)
        .containsEntry("name", "Schmidt, Anna")
        .containsEntry("display", "\"Schmidt, Anna\" <anna@example.org>");
    // a plain address has no name to show
    assertThat(search("bob").get(0)).containsEntry("display", "bob@example.org");
  }

  @Test
  void noReplyAddressesGarbageAndGroupsAreLeftOut() throws Exception {
    Map<String, Object> r =
        add(
            "noreply@shop.example, no-reply@x.example, MAILER-DAEMON@x.example, "
                + "bounces+123@list.example, donotreply@x.example, person@example.org");
    assertThat(r).containsEntry("seen", 1L).containsEntry("added", 1L);
    assertThat(add("not an address, <<<>>>, ,")).containsEntry("seen", 0L);
    assertThat(add("")).containsEntry("seen", 0L);
    assertThat(add("Team: a@example.org, b@example.org;")).containsEntry("added", 0L);
    assertThat(search("")).extracting(m -> m.get("address")).containsExactly("person@example.org");
    // a real person whose name has the word is not left out
    assertThat(add("replyer@example.org").get("added")).isEqualTo(1L);
  }

  @Test
  void aSearchMatchesTheStartOfWordsInNameAddressAndDomainAndRanksByUse() throws Exception {
    add("Anna Schmidt <anna@example.org>");
    add("Anne-Marie Dubois <am.dubois@work.example>");
    add("Bob <bob@annex.example>");
    add("Anna Schmidt <anna@example.org>");
    add("Anna Schmidt <anna@example.org>");

    // first name, last name, a part of a double name, local part, domain
    List<Map<String, Object>> ann = search("ann");
    assertThat(ann.get(0)).containsEntry("address", "anna@example.org"); // seen three times
    assertThat(ann)
        .extracting(m -> m.get("address"))
        .containsExactlyInAnyOrder(
            "anna@example.org", "am.dubois@work.example", "bob@annex.example");
    assertThat(search("schm"))
        .extracting(m -> m.get("address"))
        .containsExactly("anna@example.org");
    assertThat(search("marie"))
        .extracting(m -> m.get("address"))
        .containsExactly("am.dubois@work.example");
    assertThat(search("dubois")).hasSize(1);
    assertThat(search("work"))
        .extracting(m -> m.get("address"))
        .containsExactly("am.dubois@work.example");
    // every word must match, in any order
    assertThat(search("schmidt anna")).hasSize(1);
    assertThat(search("anna dubois")).isEmpty();
    // not in the middle of a word
    assertThat(search("chmidt")).isEmpty();
    assertThat(search("xyz")).isEmpty();
    // the most used first when nothing is typed, and the limit
    assertThat(search("").get(0)).containsEntry("address", "anna@example.org");
    assertThat(search("", 2)).hasSize(2);
    assertThat(search("", 0)).hasSize(1); // at least one
    // wildcards are only characters
    assertThat(search("%")).isEmpty();
    assertThat(search("a'; drop table contact; --")).isEmpty();
    assertThat(search("anna")).isNotEmpty();
  }

  @Test
  void theBookSurvivesAndAContactCanBeForgotten() throws Exception {
    add("Anna <anna@example.org>");
    opened.forEach(Capability::close);
    opened.clear();

    assertThat(search("anna")).hasSize(1); // a new instance reads the same file

    Map<String, Object> removed =
        make(new ContactsRemove()).invoke(context(false), Map.of("address", "ANNA@example.org"));
    assertThat(removed).containsEntry("removed", true);
    assertThat(search("anna")).isEmpty();
    assertThat(
            make(new ContactsRemove())
                .invoke(context(false), Map.of("address", "anna@example.org")))
        .containsEntry("removed", false);
  }

  @Test
  void aDryRunWritesNothing() throws Exception {
    Map<String, Object> r =
        make(new ContactsAdd()).invoke(context(true), Map.of("addresses", "a@example.org"));
    assertThat(r).containsEntry("seen", 1L).containsEntry("added", 0L);
    assertThat(search("")).isEmpty();
    add("a@example.org");
    assertThat(make(new ContactsRemove()).invoke(context(true), Map.of("address", "a@example.org")))
        .containsEntry("removed", false);
    assertThat(search("")).hasSize(1);
  }

  @Test
  void theBookNeedsADirectoryAndNoSettings() throws Exception {
    ContactsSearch without = new ContactsSearch();
    without.configure(Map.of());
    assertThatThrownBy(() -> without.invoke(context(false), Map.of("query", "")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("needs a data directory");
    assertThatThrownBy(() -> new ContactsSearch().configure(Map.of("x", 1)))
        .hasMessageContaining("unknown contacts setting 'x'");
  }

  @Test
  void theBookIsBoundedAndForgetsTheLeastUsedFirst() throws Exception {
    StringBuilder many = new StringBuilder("keep@example.org, keep@example.org, keep@example.org");
    for (int i = 0; i < Book.MAX_CONTACTS + 50; i++) {
      many.append(", p").append(i).append("@example.org");
    }
    add(many.toString());

    Book book = Book.open(data);
    try {
      assertThat(book.count()).isLessThanOrEqualTo(Book.MAX_CONTACTS);
    } finally {
      book.release();
    }
    assertThat(search("keep")).hasSize(1); // seen most: stays
  }
}
