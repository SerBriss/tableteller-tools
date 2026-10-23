package dev.tableteller.lists;

import static dev.tableteller.lists.ListName.characters;
import static dev.tableteller.lists.ListName.threads;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ListToolsTest {
    TestOracle oracle;
    ListTools tools;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        tools = oracle.listTools;
    }

    @Test
    void addDefaultsToWeightOneAndTheLiveStatus() {
        var result = tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        assertThat(result.list()).isEqualTo(threads);
        assertThat(result.entries()).containsExactly(new ListEntry("Find the heir", 1, ListStatus.open));
        assertThat(result.summary()).isEqualTo("Added 'Find the heir' to threads (weight 1).");
    }

    @Test
    void addTrimsTheTitle() {
        assertThat(tools.listAdd("Ashfall", characters, "  Varo ", 2, null).entries())
            .containsExactly(new ListEntry("Varo", 2, ListStatus.active));
    }

    @Test
    void listIsRequired() {
        assertThatThrownBy(() -> tools.listAdd("Ashfall", null, "Find the heir", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list is required; one of threads, characters");
        assertThatThrownBy(() -> tools.listGet("Ashfall", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("list is required; one of threads, characters");
    }

    @Test
    void titleMustNotBeBlank() {
        assertThatThrownBy(() -> tools.listAdd("Ashfall", threads, " ", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("title must not be blank");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4})
    void weightMustBeOneToThree(int weight) {
        assertThatThrownBy(() -> tools.listAdd("Ashfall", threads, "Find the heir", weight, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("weight must be 1-3; got " + weight);
    }

    @Test
    void statusMustSuitTheList() {
        assertThatThrownBy(() -> tools.listAdd("Ashfall", threads, "Find the heir", null, ListStatus.active))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("status for threads must be open or closed; got active");
        assertThatThrownBy(() -> tools.listAdd("Ashfall", characters, "Varo", null, ListStatus.open))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("status for characters must be active or removed; got open");
    }

    @Test
    void addRejectsATitleThatDiffersOnlyInCaseOrSpaces() {
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        assertThatThrownBy(() -> tools.listAdd("Ashfall", threads, "  find THE heir ", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'Find the heir' is already on threads; change it with list_update");
    }

    @Test
    void updateChangesWeightAndStatus() {
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        var result = tools.listUpdate("Ashfall", threads, "Find the heir", null, 2, ListStatus.closed);
        assertThat(result.entries()).containsExactly(new ListEntry("Find the heir", 2, ListStatus.closed));
        assertThat(result.summary()).isEqualTo("Updated 'Find the heir' on threads: weight 2, closed.");
    }

    @Test
    void updateKeepsTheStoredSpelling() {
        tools.listAdd("Ashfall", characters, "Innkeeper Varo", null, null);
        assertThat(tools.listUpdate("Ashfall", characters, "innkeeper varo", null, 3, null).entries())
            .containsExactly(new ListEntry("Innkeeper Varo", 3, ListStatus.active));
    }

    @Test
    void updateUnknownTitleNamesWhatIsOnTheList() {
        assertThatThrownBy(() -> tools.listUpdate("Ashfall", threads, "Lost crown", null, 2, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no 'Lost crown' on threads; threads is empty");
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        tools.listAdd("Ashfall", threads, "Clear the pass", null, null);
        assertThatThrownBy(() -> tools.listUpdate("Ashfall", threads, "Lost crown", null, 2, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no 'Lost crown' on threads; on the list: Find the heir, Clear the pass");
    }

    @Test
    void updateNeedsANewTitleWeightOrStatus() {
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        assertThatThrownBy(() -> tools.listUpdate("Ashfall", threads, "Find the heir", null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("list_update needs new_title, weight or status");
        assertThatThrownBy(() -> tools.listUpdate("Ashfall", threads, "Find the heir", "  ", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("list_update needs new_title, weight or status");
    }

    @Test
    void renameKeepsWeightAndStatus() {   // the playtests are in Russian: the match must ignore Cyrillic case
        tools.listAdd("Ashfall", characters, "Священник с приёма", 2, null);
        var renamed = tools.listUpdate("Ashfall", characters, "священник с приёма", "Отец Себастьен", null, null);
        assertThat(renamed.entries()).containsExactly(new ListEntry("Отец Себастьен", 2, ListStatus.active));
        assertThat(renamed.summary())
            .isEqualTo("Renamed 'Священник с приёма' to 'Отец Себастьен' on characters: weight 2, active.");
        assertThat(tools.listUpdate("Ashfall", characters, "отец себастьен", null, 3, null).entries())
            .containsExactly(new ListEntry("Отец Себастьен", 3, ListStatus.active));
    }

    @Test
    void renameTogetherWithAStatusChange() {
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        assertThat(tools.listUpdate("Ashfall", threads, "Find the heir", "Crown the heir", null, ListStatus.closed)
            .entries()).containsExactly(new ListEntry("Crown the heir", 1, ListStatus.closed));
    }

    @Test
    void renameToATakenTitleFails() {
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        tools.listAdd("Ashfall", threads, "Clear the pass", null, null);
        assertThatThrownBy(() -> tools.listUpdate("Ashfall", threads, "Find the heir", " clear the PASS ", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'Clear the pass' is already on threads; pick another new_title");
        assertThat(tools.listGet("Ashfall", threads).entries()).containsExactly(
            new ListEntry("Find the heir", 1, ListStatus.open), new ListEntry("Clear the pass", 1, ListStatus.open));
    }

    @Test
    void renameCanFixTheCase() {
        tools.listAdd("Ashfall", characters, "innkeeper varo", null, null);
        assertThat(tools.listUpdate("Ashfall", characters, "innkeeper varo", "Innkeeper Varo", null, null).entries())
            .containsExactly(new ListEntry("Innkeeper Varo", 1, ListStatus.active));
    }

    @Test
    void aBlankNewTitleIsNotARename() {   // a plain update never rewrites the stored spelling
        tools.listAdd("Ashfall", characters, "Innkeeper Varo", null, null);
        var result = tools.listUpdate("Ashfall", characters, "INNKEEPER VARO", " ", 3, null);
        assertThat(result.entries()).containsExactly(new ListEntry("Innkeeper Varo", 3, ListStatus.active));
        assertThat(result.summary()).isEqualTo("Updated 'Innkeeper Varo' on characters: weight 3, active.");
    }

    @Test
    void getSummarizesTheList() {
        assertThat(tools.listGet("Ashfall", threads).summary()).isEqualTo("threads: empty.");
        tools.listAdd("Ashfall", threads, "A", null, null);
        tools.listAdd("Ashfall", threads, "B", null, null);
        tools.listAdd("Ashfall", threads, "C", null, ListStatus.closed);
        assertThat(tools.listGet("Ashfall", threads).summary()).isEqualTo("threads: 2 open, 1 closed.");
        tools.listAdd("Ashfall", characters, "Varo", null, null);
        assertThat(tools.listGet("Ashfall", characters).summary()).isEqualTo("characters: 1 active, 0 removed.");
    }

    @Test
    void listToolsWriteNoRollLogRows() {
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        tools.listUpdate("Ashfall", threads, "Find the heir", null, 2, null);
        tools.listGet("Ashfall", threads);
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void listsBelongToTheCampaignTheyName() {
        oracle.createCampaign("Iron Coast");
        tools.listAdd("Ashfall", threads, "Find the heir", null, null);
        tools.listAdd("Iron Coast", threads, "Find the heir", 3, null);   // no "already on threads" error
        assertThat(tools.listGet("Ashfall", threads).entries())
            .containsExactly(new ListEntry("Find the heir", 1, ListStatus.open));
        assertThat(tools.listGet("Iron Coast", threads).entries())
            .containsExactly(new ListEntry("Find the heir", 3, ListStatus.open));
    }

    @Test
    void listToolsNeedAKnownCampaign() {
        var message = "no campaign 'Ashfal'; campaigns: Ashfall";
        assertThatThrownBy(() -> tools.listAdd("Ashfal", threads, "Find the heir", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> tools.listUpdate("Ashfal", threads, "Find the heir", null, 2, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> tools.listGet("Ashfal", threads))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThat(tools.listGet("Ashfall", threads).entries()).isEmpty();
    }
}
