package dev.tableteller.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.tableteller.event.RandomEvent;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SceneToolsTest {
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
    void firstSceneIsNumberOne() {
        var oracle = open(8);
        assertThat(oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate"))
            .isEqualTo(new SceneTools.SceneCheckResult(
                "r_0001", 1, SceneResult.expected, List.of(8), 5, null, "Scene 1 starts as expected (8 vs chaos 5)."));
        assertThat(oracle.campaigns.findById(oracle.campaignId("Ashfall")).orElseThrow().sceneNumber()).isEqualTo(1);
    }

    @Test
    void expectedSceneCopiesTheSetup() {
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThat(oracle.scenes.findOpen(oracle.campaignId("Ashfall"))).contains(
            new Scene(1, "Mara reaches the gate", "Mara reaches the gate", SceneResult.expected, null, true));
    }

    @Test
    void alteredSceneLeavesActualSetupToTheDm() {
        var oracle = open(3);
        assertThat(oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate").summary()).isEqualTo(
            "Scene 1 is altered (3 vs chaos 5): change the expected setup, then pass actual_setup to end_scene.");
        assertThat(oracle.scenes.findOpen(oracle.campaignId("Ashfall")).orElseThrow().actualSetup()).isNull();
    }

    @Test
    void interruptedSceneCarriesTheEvent() {
        var oracle = open(2, 1, 1, 2);
        var result = oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThat(result.result()).isEqualTo(SceneResult.interrupted);
        assertThat(result.randomEvent()).isEqualTo(new RandomEvent("Distant event", null, List.of("seek", "ally")));
        assertThat(result.dice()).containsExactly(2, 1, 1, 2);
        assertThat(result.summary())
            .isEqualTo("Scene 1 is interrupted (2 vs chaos 5). Random event: Distant event (seek / ally).");
        assertThat(oracle.rows("Ashfall").getFirst().result().get("random_event"))
            .isEqualTo(Map.of("focus", "Distant event", "meaning", List.of("seek", "ally")));
    }

    @Test
    void expectedSetupMustNotBeBlank() {
        var oracle = open();
        assertThatThrownBy(() -> oracle.sceneTools.sceneCheck("Ashfall", " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("expected_setup must not be blank");
    }

    @Test
    void sceneAlreadyOpen() {   // spec lifecycle test
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "The tavern brawl");
        assertThatThrownBy(() -> oracle.sceneTools.sceneCheck("Ashfall", "The road north"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("scene 1 is still open (\"The tavern brawl\"); end it with end_scene first");
        assertThat(oracle.rows("Ashfall")).hasSize(1);
    }

    @Test
    void noOpenScene() {   // spec lifecycle test
        var oracle = open();
        assertThatThrownBy(() -> oracle.sceneTools.endScene("Ashfall", "Nothing happened.", true, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no scene is open; start one with scene_check");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void endSceneNeedsASummaryAndPcInControl() {
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThatThrownBy(() -> oracle.sceneTools.endScene("Ashfall", " ", true, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("summary must not be blank");
        assertThatThrownBy(() -> oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("pc_in_control is required (true or false)");
    }

    @Test
    void alteredSceneNeedsActualSetupToEnd() {
        var oracle = open(3);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThatThrownBy(() -> oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("scene 1 was altered; pass actual_setup with what the scene really was");
    }

    @Test
    void endSceneRaisesChaosWhenThePcWasNotInControl() {
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThat(oracle.sceneTools.endScene("Ashfall", "The guards caught her.", false, null))
            .isEqualTo(new SceneTools.EndSceneResult("r_0002", 1, 6, "Scene 1 closed. Chaos 5 → 6 (PC not in control)."));
        assertThat(oracle.chaos("Ashfall")).isEqualTo(6);
    }

    @Test
    void endSceneLowersChaosWhenThePcWasInControl() {
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThat(oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, null).summary())
            .isEqualTo("Scene 1 closed. Chaos 5 → 4 (PC in control).");
    }

    @Test
    void endSceneClampsChaosAtNine() {
        var oracle = open(10);
        oracle.tools.setChaos("Ashfall", 9);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThat(oracle.sceneTools.endScene("Ashfall", "It went badly.", false, null).chaosFactor()).isEqualTo(9);
    }

    @Test
    void endSceneClampsChaosAtOne() {
        var oracle = open(8);
        oracle.tools.setChaos("Ashfall", 1);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThat(oracle.sceneTools.endScene("Ashfall", "It went well.", true, null).chaosFactor()).isEqualTo(1);
    }

    @Test
    void expectedSceneMayRefineActualSetup() {
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, "Mara reaches the gate at dusk");
        assertThat(oracle.scenes.findAll(oracle.campaignId("Ashfall"))).containsExactly(new Scene(1,
            "Mara reaches the gate", "Mara reaches the gate at dusk", SceneResult.expected, "Mara slipped past.", false));
    }

    @Test
    void nextSceneIsNumberedAfterTheLast() {
        var oracle = open(8, 8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, null);
        assertThat(oracle.sceneTools.sceneCheck("Ashfall", "Mara searches the archive").sceneNumber()).isEqualTo(2);
    }

    @Test
    void sceneToolsLogOneRowEach() {
        var oracle = open(8);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", false, null);
        var rows = oracle.rows("Ashfall");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).tool()).isEqualTo("scene_check");
        assertThat(rows.get(0).inputs()).isEqualTo(Map.of("expected_setup", "Mara reaches the gate"));
        assertThat(rows.get(0).dice()).containsExactly(8);
        assertThat(rows.get(0).result()).isEqualTo(Map.of("scene_number", 1, "result", "expected", "chaos_factor", 5));
        assertThat(rows.get(0).sceneNumber()).isEqualTo(1);
        assertThat(rows.get(1).tool()).isEqualTo("end_scene");
        assertThat(rows.get(1).inputs()).isEqualTo(Map.of("summary", "Mara slipped past.", "pc_in_control", false));
        assertThat(rows.get(1).dice()).isEmpty();
        assertThat(rows.get(1).result()).isEqualTo(Map.of("scene_number", 1, "chaos_factor", 6, "previous_chaos_factor", 5));
    }

    @Test
    void scenesBelongToTheCampaignTheyName() {   // the first playtest's mix-up, one level down
        var oracle = open(8, 8);
        oracle.createCampaign("Iron Coast");
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        oracle.sceneTools.sceneCheck("Iron Coast", "Ilsa boards the ship");
        oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, null);
        assertThat(oracle.scenes.findAll(oracle.campaignId("Ashfall"))).extracting(Scene::setup, Scene::open)
            .containsExactly(tuple("Mara reaches the gate", false));
        assertThat(oracle.scenes.findAll(oracle.campaignId("Iron Coast"))).extracting(Scene::setup, Scene::open)
            .containsExactly(tuple("Ilsa boards the ship", true));
        assertThat(oracle.chaos("Ashfall")).isEqualTo(4);
        assertThat(oracle.chaos("Iron Coast")).isEqualTo(5);
    }

    @Test
    void sceneToolsNeedAKnownCampaign() {
        var oracle = open(8);
        var message = "no campaign 'Ashfal'; campaigns: Ashfall";
        assertThatThrownBy(() -> oracle.sceneTools.sceneCheck("Ashfal", "Mara reaches the gate"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> oracle.sceneTools.endScene("Ashfal", "Mara slipped past.", true, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }
}
