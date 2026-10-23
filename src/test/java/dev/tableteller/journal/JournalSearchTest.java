package dev.tableteller.journal;

import static dev.tableteller.journal.JournalKind.location;
import static dev.tableteller.journal.JournalKind.note;
import static dev.tableteller.journal.JournalKind.npc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JournalSearchTest {
    TestOracle oracle;
    JournalTools tools;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        tools = oracle.journalTools;
    }

    private List<String> titles(String query) {
        return titles(query, null);
    }

    private List<String> titles(String query, JournalKind kind) {
        return tools.journalSearch("Ashfall", query, kind).entries().stream().map(SearchHit::title).toList();
    }

    @Test
    void matchesEveryWordAsAPrefixOfTitleOrBody() {
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the Drowned Rat.", null, null);
        tools.journalWrite("Ashfall", location, "Drowned Rat", "A tavern by the docks.", null, null);
        assertThat(titles("inn varo")).containsExactly("Innkeeper Varo");
        assertThat(titles("drown")).containsExactlyInAnyOrder("Innkeeper Varo", "Drowned Rat");
        assertThat(titles("varo tavern")).isEmpty();
    }

    @Test
    void prefixMatchesInflectedCyrillicNames() {
        tools.journalWrite("Ashfall", npc, "Варо", "Хозяин таверны.", null, null);
        tools.journalWrite("Ashfall", note, "Слух", "Говорят, с Варом что-то не так.", null, null);
        assertThat(titles("вар")).containsExactlyInAnyOrder("Варо", "Слух");
        assertThat(titles("варом")).containsExactly("Слух");
    }

    @Test
    void kindFiltersResults() {
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the Drowned Rat.", null, null);
        tools.journalWrite("Ashfall", location, "Drowned Rat", "A tavern by the docks.", null, null);
        assertThat(titles("drowned", location)).containsExactly("Drowned Rat");
    }

    @Test
    void bestMatchFirst() {   // Rumours is newer, so only the rank puts Varo first
        tools.journalWrite("Ashfall", npc, "Varo", "Varo runs the inn; Varo owes money.", null, null);
        tools.journalWrite("Ashfall", note, "Rumours", "Someone saw Varo.", null, null);
        assertThat(titles("varo")).containsExactly("Varo", "Rumours");
    }

    @Test
    void hitCarriesIdKindExcerptTagsAndScene() {
        var id = tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", List.of("inn"), null).entryId();
        assertThat(tools.journalSearch("Ashfall", "varo", null).entries())
            .containsExactly(new SearchHit(id, npc, "Innkeeper Varo", "Runs the inn.", List.of("inn"), 0));
    }

    @Test
    void queryPunctuationNeverReachesPostgresAsSyntax() {
        tools.journalWrite("Ashfall", npc, "O'Brien", "A smuggler.", null, null);
        assertThat(titles("O'Brien")).containsExactly("O'Brien");
        for (var query : List.of("\"O'Brien\"", "o'brien:*", "back\\slash", "a & | ! ( )")) {
            assertThatCode(() -> tools.journalSearch("Ashfall", query, null)).as(query).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> tools.journalSearch("Ashfall", "!!!", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("query needs at least one letter or digit; got '!!!'");
    }

    @Test
    void excerptIsTheFirst300Characters() {
        assertThat(SearchHit.excerpt("a".repeat(300))).isEqualTo("a".repeat(300));
        assertThat(SearchHit.excerpt("a".repeat(301))).isEqualTo("a".repeat(300) + "…");
        assertThat(SearchHit.excerpt("a".repeat(299) + "😀" + "b")).isEqualTo("a".repeat(299) + "😀…");
        var body = "rat ".repeat(100);
        tools.journalWrite("Ashfall", note, "Long", body, null, null);
        assertThat(tools.journalSearch("Ashfall", "rat", null).entries().getFirst().excerpt())
            .isEqualTo(body.substring(0, 300) + "…");
    }

    @Test
    void atMostTwentyEntries() {
        for (int i = 1; i <= 21; i++) {
            tools.journalWrite("Ashfall", note, "Rat " + i, "A rat.", null, null);
        }
        var result = tools.journalSearch("Ashfall", "rat", null);
        assertThat(result.entries()).hasSize(20);
        assertThat(result.summary()).isEqualTo(
            "More than 20 entries match 'rat'; showing the best 20. Add words or pass kind to narrow it.");
    }

    @Test
    void searchStaysInTheNamedCampaign() {
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null);
        oracle.createCampaign("Iron Coast");
        assertThat(tools.journalSearch("Iron Coast", "varo", null).entries()).isEmpty();
        assertThat(titles("varo")).containsExactly("Innkeeper Varo");
    }

    @Test
    void searchNeedsAKnownCampaign() {
        assertThatThrownBy(() -> tools.journalSearch("Ashfal", "varo", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no campaign 'Ashfal'; campaigns: Ashfall");
    }

    @Test
    void queryMustNotBeBlank() {
        assertThatThrownBy(() -> tools.journalSearch("Ashfall", " ", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("query must not be blank");
        assertThatThrownBy(() -> tools.journalSearch("Ashfall", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("query must not be blank");
    }

    @Test
    void noEntriesSuggestsJournalWrite() {
        var result = tools.journalSearch("Ashfall", "  varo ", null);
        assertThat(result.query()).isEqualTo("varo");
        assertThat(result.entries()).isEmpty();
        assertThat(result.summary()).isEqualTo("No entries match 'varo'; if it is new, record it with journal_write.");
    }

    @Test
    void oneEntrySummary() {
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null);
        assertThat(tools.journalSearch("Ashfall", "varo", null).summary()).isEqualTo("1 entry matches 'varo'.");
    }

    @Test
    void severalEntriesSummary() {
        tools.journalWrite("Ashfall", npc, "Innkeeper Varo", "Runs the inn.", null, null);
        tools.journalWrite("Ashfall", note, "Rumours", "Someone saw Varo.", null, null);
        assertThat(tools.journalSearch("Ashfall", "varo", null).summary()).isEqualTo("2 entries match 'varo'.");
    }

    @Test
    void searchWritesNoRollLogRows() {
        tools.journalSearch("Ashfall", "varo", null);
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }
}
