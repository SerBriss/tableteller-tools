package dev.tableteller.journal;

import static dev.tableteller.journal.JournalKind.location;
import static dev.tableteller.journal.JournalKind.npc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JournalToolsTest {
    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    private static TestOracle open(int... rolls) {
        var oracle = TestOracle.withRolls(rolls);
        oracle.createCampaign("Ashfall");
        return oracle;
    }

    @Test
    void kindIsRequired() {
        assertThatThrownBy(() -> open().journalTools.journalWrite("Ashfall", null, "Innkeeper Varo", "Runs the inn.", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("kind is required; one of npc, location, item, fact, note");
    }

    @Test
    void titleMustNotBeBlank() {
        assertThatThrownBy(() -> open().journalTools.journalWrite("Ashfall", npc, " ", "Runs the inn.", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("title must not be blank");
    }

    @Test
    void bodyMustNotBeBlank() {
        assertThatThrownBy(() -> open().journalTools.journalWrite("Ashfall", npc, "Innkeeper Varo", " ", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("body must not be blank");
    }

    @Test
    void writeRecordsANewEntry() {
        var written = open().journalTools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null);
        assertThat(written.replaced()).isFalse();
        assertThat(written.previousBody()).isNull();
        assertThat(written.summary()).isEqualTo("Recorded npc 'Innkeeper Varo' (scene 0).");
    }

    @Test
    void writeRecordsTheCurrentSceneNumber() {
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the inn");
        var written = oracle.journalTools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null);
        assertThat(written.summary()).isEqualTo("Recorded npc 'Innkeeper Varo' (scene 1).");
        assertThat(oracle.journalTools.journalGet("Ashfall", written.entryId().toString(), null).entry().sceneNumber())
            .isEqualTo(1);
    }

    @Test
    void writingAnExistingKindAndTitleReplacesIt() {
        var tools = open().journalTools;
        var first = tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null);
        var second = tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Sold the inn.", null, null);
        assertThat(second.entryId()).isEqualTo(first.entryId());
        assertThat(second.replaced()).isTrue();
        assertThat(second.previousBody()).isEqualTo("Runs the inn.");
        assertThat(second.summary())
            .isEqualTo("Replaced npc 'Innkeeper Varo'; check previous_body for anything the new text dropped.");
    }

    @Test
    void replacesIgnoringCaseIncludingCyrillic() {
        var tools = open().journalTools;
        var first = tools.journalWrite("Ashfall", location, "Таверна", "A", null, null);
        var second = tools.journalWrite("Ashfall", location, " таверна ", "B", null, null);
        assertThat(second.replaced()).isTrue();
        assertThat(second.previousBody()).isEqualTo("A");
        assertThat(second.entryId()).isEqualTo(first.entryId());
        var entry = tools.journalGet("Ashfall", null, "ТАВЕРНА").entry();
        assertThat(entry.title()).isEqualTo("Таверна");
        assertThat(entry.body()).isEqualTo("B");
    }

    @Test
    void replaceKeepsTagsWhenOmittedAndReplacesThemWhenGiven() {
        var tools = open().journalTools;
        var id = tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", List.of("inn"), null).entryId().toString();
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Sold the inn.", null, null);
        assertThat(tools.journalGet("Ashfall", id, null).entry().tags()).containsExactly("inn");
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Left town.", List.of("road"), null);
        assertThat(tools.journalGet("Ashfall", id, null).entry().tags()).containsExactly("road");
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Came back.", List.of(), null);
        assertThat(tools.journalGet("Ashfall", id, null).entry().tags()).isEmpty();
    }

    @Test
    void tagsAreTrimmedAndRepeatsDropped() {
        var tools = open().journalTools;
        var id = tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", List.of(" inn ", "", "inn", "Varo"), null)
            .entryId().toString();
        assertThat(tools.journalGet("Ashfall", id, null).entry().tags()).containsExactly("inn", "Varo");
    }

    @Test
    void sameTitleUnderAnotherKindIsAnotherEntry() {
        var tools = open().journalTools;
        var person = tools.journalWrite("Ashfall", npc, "Varo", "The innkeeper.", null, null);
        var place = tools.journalWrite("Ashfall", location, "Varo", "A village.", null, null);
        assertThat(place.replaced()).isFalse();
        assertThat(place.entryId()).isNotEqualTo(person.entryId());
    }

    @Test
    void getsByIdAndByTitle() {
        var tools = open().journalTools;
        var id = tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", List.of("inn"), null).entryId();
        var byId = tools.journalGet("Ashfall", id.toString(), null);
        var byTitle = tools.journalGet("Ashfall", null, "innkeeper varo");
        assertThat(byTitle).isEqualTo(byId);
        assertThat(byId.entry()).extracting(JournalEntry::entryId, JournalEntry::kind, JournalEntry::title,
                JournalEntry::body, JournalEntry::tags)
            .containsExactly(id, npc, "Innkeeper Varo", "Runs the inn.", List.of("inn"));
        assertThat(byId.summary()).isEqualTo("npc 'Innkeeper Varo', last written in scene 0.");
    }

    @Test
    void getNeedsEntryIdOrTitle() {
        assertThatThrownBy(() -> open().journalTools.journalGet("Ashfall", null, " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("journal_get needs entry_id or title");
    }

    @Test
    void getTakesOnlyOneOfEntryIdAndTitle() {
        var tools = open().journalTools;
        var id = tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null).entryId().toString();
        assertThatThrownBy(() -> tools.journalGet("Ashfall", id, "Innkeeper Varo"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("pass entry_id or title, not both");
    }

    @Test
    void entryIdMustBeAnId() {
        assertThatThrownBy(() -> open().journalTools.journalGet("Ashfall", "abc", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("entry_id 'abc' is not an id; use an entry_id from journal_write or journal_search");
    }

    @Test
    void unknownEntryId() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> open().journalTools.journalGet("Ashfall", id.toString(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no journal entry with id " + id);
    }

    @Test
    void unknownTitle() {
        assertThatThrownBy(() -> open().journalTools.journalGet("Ashfall", null, "Varo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no journal entry titled 'Varo'; find it with journal_search");
    }

    @Test
    void titleOnTwoKindsAsksForTheId() {
        var tools = open().journalTools;
        var person = tools.journalWrite("Ashfall", npc, "Varo", "The innkeeper.", null, null).entryId();
        var place = tools.journalWrite("Ashfall", location, "Varo", "A village.", null, null).entryId();
        assertThatThrownBy(() -> tools.journalGet("Ashfall", null, "varo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'varo' is the title of 2 entries: location " + place + ", npc " + person + "; pass entry_id");
    }

    @Test
    void getNeverReturnsAnotherCampaignsEntry() {
        var oracle = open();
        var id = oracle.journalTools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null).entryId();
        oracle.createCampaign("Iron Coast");
        assertThatThrownBy(() -> oracle.journalTools.journalGet("Iron Coast", id.toString(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no journal entry with id " + id);
        assertThatThrownBy(() -> oracle.journalTools.journalGet("Iron Coast", null, "Innkeeper Varo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no journal entry titled 'Innkeeper Varo'; find it with journal_search");
    }

    @Test
    void renameAnEntryThatExists() {   // the playtests are in Russian; search must find the entry by its new name
        var tools = open().journalTools;
        var first = tools.journalWrite("Ashfall", npc, "Священник с приёма", "A priest with Jacques's photo.",
            List.of("Квадрилатеро"), null);
        var renamed = tools.journalWrite("Ashfall", npc, "священник с приёма", "Отец Себастьен, архивист.", null,
            "Отец Себастьен");
        assertThat(renamed.entryId()).isEqualTo(first.entryId());
        assertThat(renamed.replaced()).isTrue();
        assertThat(renamed.previousBody()).isEqualTo("A priest with Jacques's photo.");
        assertThat(renamed.summary()).isEqualTo("Renamed npc 'Священник с приёма' to 'Отец Себастьен'; "
            + "check previous_body for anything the new text dropped.");
        var entry = tools.journalGet("Ashfall", null, "отец себастьен").entry();
        assertThat(entry.entryId()).isEqualTo(first.entryId());
        assertThat(entry.title()).isEqualTo("Отец Себастьен");
        assertThat(entry.body()).isEqualTo("Отец Себастьен, архивист.");
        assertThat(entry.tags()).containsExactly("Квадрилатеро");
        assertThatThrownBy(() -> tools.journalGet("Ashfall", null, "Священник с приёма"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no journal entry titled 'Священник с приёма'; find it with journal_search");
        assertThat(tools.journalSearch("Ashfall", "Себастьен", null).entries()).hasSize(1);
        assertThat(tools.journalSearch("Ashfall", "Священник", null).entries()).isEmpty();
    }

    @Test
    void renamingAMissingEntryFails() {   // a mistyped title must not quietly create an entry
        var tools = open().journalTools;
        assertThatThrownBy(() -> tools.journalWrite("Ashfall", npc, " Священник ", "x", null, "Отец Себастьен"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no npc 'Священник' to rename; leave out new_title to record a new entry");
        assertThat(tools.journalSearch("Ashfall", "Себастьен", null).entries()).isEmpty();
        assertThat(tools.journalSearch("Ashfall", "Священник", null).entries()).isEmpty();
    }

    @Test
    void renameToATakenTitleFails() {
        var tools = open().journalTools;
        var varo = tools.journalWrite("Ashfall", npc, "Varo", "A drover.", null, null);
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null);
        assertThatThrownBy(() -> tools.journalWrite("Ashfall", npc, "Varo", "Runs the inn.", null, "innkeeper varo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("npc 'Innkeeper Varo' already exists; write to it, or pick another new_title");
        var unchanged = tools.journalGet("Ashfall", varo.entryId().toString(), null).entry();
        assertThat(unchanged.title()).isEqualTo("Varo");
        assertThat(unchanged.body()).isEqualTo("A drover.");
        tools.journalWrite("Ashfall", location, "Таверна", "A tavern.", null, null);
        var renamed = tools.journalWrite("Ashfall", npc, "Varo", "Runs the tavern.", null, "Таверна");   // another kind
        assertThat(renamed.entryId()).isEqualTo(varo.entryId());
        assertThat(renamed.replaced()).isTrue();
    }

    @Test
    void renameCanFixTheCase() {
        var tools = open().journalTools;
        var first = tools.journalWrite("Ashfall", npc, "innkeeper varo", "Runs the inn.", null, null);
        var renamed = tools.journalWrite("Ashfall", npc, "innkeeper varo", "Runs the inn.", null, "Innkeeper Varo");
        assertThat(renamed.entryId()).isEqualTo(first.entryId());
        assertThat(renamed.summary()).startsWith("Renamed npc 'innkeeper varo' to 'Innkeeper Varo'; ");
        assertThat(tools.journalGet("Ashfall", first.entryId().toString(), null).entry().title())
            .isEqualTo("Innkeeper Varo");
    }

    @Test
    void renameTrimsTheNewTitleAndTakesNewTags() {
        var tools = open().journalTools;
        var first = tools.journalWrite("Ashfall", npc, "Lucia", "A stylist.", List.of("salon"), null);
        var renamed = tools.journalWrite("Ashfall", npc, "Lucia", "A stylist.", List.of("road"), "  Lucia Berni ");
        assertThat(renamed.summary()).startsWith("Renamed npc 'Lucia' to 'Lucia Berni'; ");
        var entry = tools.journalGet("Ashfall", first.entryId().toString(), null).entry();
        assertThat(entry.title()).isEqualTo("Lucia Berni");
        assertThat(entry.tags()).containsExactly("road");
    }

    @Test
    void aBlankNewTitleIsAPlainWrite() {
        var tools = open().journalTools;
        var written = tools.journalWrite("Ashfall", npc, "Lucia", "A stylist.", null, " ");
        assertThat(written.replaced()).isFalse();
        assertThat(written.summary()).isEqualTo("Recorded npc 'Lucia' (scene 0).");
        var replaced = tools.journalWrite("Ashfall", npc, "lucia", "A barber.", null, "");
        assertThat(replaced.replaced()).isTrue();
        assertThat(replaced.summary())
            .isEqualTo("Replaced npc 'Lucia'; check previous_body for anything the new text dropped.");
        assertThat(tools.journalGet("Ashfall", null, "Lucia").entry().title()).isEqualTo("Lucia");
    }

    @Test
    void journalToolsWriteNoRollLogRows() {
        var oracle = open();
        var id = oracle.journalTools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null).entryId();
        oracle.journalTools.journalGet("Ashfall", id.toString(), null);
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void journalToolsNeedAKnownCampaign() {
        var oracle = open();
        var tools = oracle.journalTools;
        assertThatThrownBy(() -> tools.journalWrite(null, npc, "Innkeeper Varo", "Runs the inn.", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("campaign must not be blank; campaigns: Ashfall");
        assertThatThrownBy(() -> tools.journalWrite("Ashfal", npc, "Innkeeper Varo", "Runs the inn.", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no campaign 'Ashfal'; campaigns: Ashfall");
        assertThatThrownBy(() -> tools.journalGet("Ashfal", null, "Innkeeper Varo"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no campaign 'Ashfal'; campaigns: Ashfall");
        assertThat(oracle.journal.titles(oracle.campaignId("Ashfall"), npc)).isEmpty();
    }
}
