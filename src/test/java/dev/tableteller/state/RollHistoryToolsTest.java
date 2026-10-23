package dev.tableteller.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RollHistoryToolsTest {
    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    private static TestOracle open(int... rolls) {
        var oracle = TestOracle.withRolls(rolls);
        oracle.createCampaign("Ashfall");
        return oracle;
    }

    /** Appends roll-log rows with the given scene numbers, so a test controls which scene each row is in. */
    private static void rowsInScenes(TestOracle oracle, int... sceneNumbers) {
        for (int scene : sceneNumbers) {
            oracle.rolls.append(oracle.campaignId("Ashfall"),
                new NewRoll("roll", Map.of("min", 1, "max", 6), List.of(4), Map.of("value", 4), scene));
        }
    }

    @Test
    void lastNAndSceneTogetherAreRejected() {
        assertThatThrownBy(() -> open().rollHistoryTools.rollHistory("Ashfall", 5, 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("pass last_n or scene, not both");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void lastNMustBeOneToHundred(int lastN) {
        assertThatThrownBy(() -> open().rollHistoryTools.rollHistory("Ashfall", lastN, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("last_n must be 1-100; got " + lastN);
    }

    @Test
    void sceneMustNotBeNegative() {
        assertThatThrownBy(() -> open().rollHistoryTools.rollHistory("Ashfall", null, -1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("scene must be 0 or more; got -1");
    }

    @Test
    void lastSeveralRolls() {
        var oracle = open();
        rowsInScenes(oracle, 0, 0, 0);
        assertThat(oracle.rollHistoryTools.rollHistory("Ashfall", null, null).summary())
            .isEqualTo("The last 3 rolls: r_0001 to r_0003.");
    }

    @Test
    void lastOneRoll() {
        var oracle = open();
        rowsInScenes(oracle, 0, 0, 0, 0);
        var history = oracle.rollHistoryTools.rollHistory("Ashfall", 1, null);
        assertThat(history.rolls()).extracting(RollHistoryTools.RollRow::rollId).containsExactly("r_0004");
        assertThat(history.summary()).isEqualTo("The last roll: r_0004.");
    }

    @Test
    void noRollsYet() {
        var history = open().rollHistoryTools.rollHistory("Ashfall", null, null);
        assertThat(history.rolls()).isEmpty();
        assertThat(history.summary()).isEqualTo("No rolls yet.");
    }

    @Test
    void severalRollsInAScene() {
        var oracle = open();
        rowsInScenes(oracle, 0, 0, 0, 2, 2, 2);
        assertThat(oracle.rollHistoryTools.rollHistory("Ashfall", null, 2).summary())
            .isEqualTo("Scene 2: 3 rolls, r_0004 to r_0006.");
    }

    @Test
    void oneRollInAScene() {
        var oracle = open();
        rowsInScenes(oracle, 0, 0, 0, 2);
        assertThat(oracle.rollHistoryTools.rollHistory("Ashfall", null, 2).summary()).isEqualTo("Scene 2: 1 roll, r_0004.");
    }

    @Test
    void noRollsInAScene() {
        var oracle = open();
        rowsInScenes(oracle, 0, 1);
        var history = oracle.rollHistoryTools.rollHistory("Ashfall", null, 2);
        assertThat(history.rolls()).isEmpty();
        assertThat(history.summary()).isEqualTo("Scene 2: no rolls.");
    }

    @Test
    void defaultsToTheLast20() {
        var oracle = open(Collections.nCopies(25, 1).stream().mapToInt(Integer::intValue).toArray());
        for (int i = 0; i < 25; i++) {
            oracle.tools.roll("Ashfall", 1, 6, null, null);
        }
        var history = oracle.rollHistoryTools.rollHistory("Ashfall", null, null);
        assertThat(history.rolls()).hasSize(20);
        assertThat(history.rolls().getFirst().rollId()).isEqualTo("r_0006");
        assertThat(history.rolls().getLast().rollId()).isEqualTo("r_0025");
        assertThat(history.summary()).isEqualTo("The last 20 rolls: r_0006 to r_0025.");
    }

    @Test
    void sceneRows() {
        var oracle = open(3, 8, 4);
        oracle.tools.roll("Ashfall", 1, 6, null, null);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        oracle.tools.roll("Ashfall", 1, 6, null, null);
        oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, null);
        assertThat(oracle.rollHistoryTools.rollHistory("Ashfall", null, 1).rolls()).extracting(RollHistoryTools.RollRow::tool)
            .containsExactly("scene_check", "roll", "end_scene");
        assertThat(oracle.rollHistoryTools.rollHistory("Ashfall", null, 0).rolls()).extracting(RollHistoryTools.RollRow::tool)
            .containsExactly("roll");
    }

    @Test
    void rowsCarryInputsDiceAndResult() {
        var oracle = open(4);
        oracle.tools.roll("Ashfall", 1, 6, null, null);
        var row = oracle.rollHistoryTools.rollHistory("Ashfall", null, null).rolls().getFirst();
        assertThat(row).isEqualTo(RollHistoryTools.RollRow.of(oracle.rows("Ashfall").getFirst()));
        assertThat(row.rollId()).isEqualTo("r_0001");
        assertThat(row.inputs()).isEqualTo(Map.of("min", 1, "max", 6));
        assertThat(row.dice()).containsExactly(4);
        assertThat(row.result()).isEqualTo(Map.of("value", 4));
        assertThat(row.createdAt()).isNotNull();
    }

    @Test
    void onlyTheNamedCampaignsRolls() {
        var oracle = open(4);
        oracle.tools.roll("Ashfall", 1, 6, null, null);
        oracle.createCampaign("Iron Coast");
        assertThat(oracle.rollHistoryTools.rollHistory("Iron Coast", null, null).summary()).isEqualTo("No rolls yet.");
        assertThat(oracle.rollHistoryTools.rollHistory("Ashfall", null, null).summary())
            .isEqualTo("The last roll: r_0001.");
    }

    @Test
    void rollHistoryWritesNoRow() {
        var oracle = open(4);
        oracle.tools.roll("Ashfall", 1, 6, null, null);
        oracle.rollHistoryTools.rollHistory("Ashfall", null, null);
        assertThat(oracle.rows("Ashfall")).hasSize(1);
    }

    @Test
    void rollHistoryNeedsAKnownCampaign() {
        assertThatThrownBy(() -> TestOracle.withRolls().rollHistoryTools.rollHistory("Ashfall", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign 'Ashfall'; none exist yet, create one with campaign_create");
        assertThatThrownBy(() -> open().rollHistoryTools.rollHistory(" ", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("campaign must not be blank; campaigns: Ashfall");
    }
}
