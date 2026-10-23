package dev.tableteller.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.lists.ListEntry;
import dev.tableteller.lists.ListName;
import dev.tableteller.lists.ListStatus;
import dev.tableteller.scene.Scene;
import dev.tableteller.scene.SceneResult;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import dev.tableteller.tracker.Tracker;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CampaignToolsTest {
    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void createReturnsTheIdAndNamesTheCampaign() {
        var oracle = TestOracle.withRolls();
        var created = oracle.campaignTools.campaignCreate("Ashfall", "a burnt valley", "grim", "Mara, a scout", null);
        assertThat(created.summary())
            .isEqualTo("Campaign 'Ashfall' created. Chaos 5, no scenes yet; pass campaign 'Ashfall' to every other tool.");
        assertThat(oracle.campaignId("Ashfall")).isEqualTo(created.campaignId());
    }

    @Test
    void createRejectsANameThatDiffersOnlyInCaseOrSpaces() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        assertThatThrownBy(() -> oracle.campaignTools.campaignCreate("  ashfall ", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a campaign named 'Ashfall' already exists; open it with campaign_open");
    }

    @Test
    void createRejectsABlankName() {
        assertThatThrownBy(() -> TestOracle.withRolls().campaignTools.campaignCreate(" ", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name must not be blank");
    }

    @Test
    void openReturnsTheBrief() {
        var oracle = TestOracle.withRolls(8);
        var id = oracle.campaignTools.campaignCreate("Ashfall", "a burnt valley", "grim", "Mara, a scout",
            "Stealth: exceptional, +2").campaignId();
        oracle.listTools.listAdd("Ashfall", ListName.threads, "Find the heir", 2, null);
        oracle.listTools.listAdd("Ashfall", ListName.threads, "Old feud", null, ListStatus.closed);
        oracle.listTools.listAdd("Ashfall", ListName.characters, "Varo", null, null);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        assertThat(oracle.campaignTools.campaignOpen("Ashfall")).isEqualTo(new CampaignBrief(id, "Ashfall",
            "a burnt valley", "grim", "Mara, a scout", "Stealth: exceptional, +2", 5, 1,
            new Scene(1, "Mara reaches the gate", "Mara reaches the gate", SceneResult.expected, null, true),
            List.of(new ListEntry("Find the heir", 2, ListStatus.open)),
            List.of(new ListEntry("Varo", 1, ListStatus.active)), List.of(),
            "Opened 'Ashfall': chaos 5; scene 1 open; threads 1, characters 1."));
    }

    @Test
    void briefListsTheTrackersInTheOrderAdded() {
        var oracle = TestOracle.withRolls();
        var id = oracle.createCampaign("Ashfall");
        var hunger = new Tracker("Голод", 1, 0, 5, "Rouse: d10");
        var willpower = new Tracker("Сила воли", 4, null, null, null);
        oracle.trackers.add(id, hunger);
        oracle.trackers.add(id, willpower);
        assertThat(oracle.campaignTools.campaignOpen("Ashfall").trackers()).containsExactly(hunger, willpower);
    }

    @Test
    void briefSaysWhenThereAreNoScenesOrTheLastIsClosed() {
        var oracle = TestOracle.withRolls(8);
        oracle.createCampaign("Ashfall");
        var fresh = oracle.campaignTools.campaignOpen("Ashfall");
        assertThat(fresh.openScene()).isNull();
        assertThat(fresh.summary()).isEqualTo("Opened 'Ashfall': chaos 5; no scenes yet; threads 0, characters 0.");
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, null);
        assertThat(oracle.campaignTools.campaignOpen("Ashfall").summary())
            .isEqualTo("Opened 'Ashfall': chaos 4; scene 1 closed; threads 0, characters 0.");
    }

    @Test
    void opensByNameIgnoringCaseAndSpaces() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Пепел");
        assertThat(oracle.campaignTools.campaignOpen("  пепел ").name()).isEqualTo("Пепел");
    }

    @Test
    void opensById() {
        var oracle = TestOracle.withRolls();
        var id = oracle.createCampaign("Ashfall");
        assertThat(oracle.campaignTools.campaignOpen(id.toString()).campaignId()).isEqualTo(id);
    }

    @Test
    void openUnknownListsCampaigns() {
        var oracle = TestOracle.withRolls();
        assertThatThrownBy(() -> oracle.campaignTools.campaignOpen("Ashfal"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign 'Ashfal'; none exist yet, create one with campaign_create");
        oracle.createCampaign("Iron Coast");
        oracle.createCampaign("Ashfall");
        assertThatThrownBy(() -> oracle.campaignTools.campaignOpen("Ashfal"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign 'Ashfal'; campaigns: Ashfall, Iron Coast");
    }

    @Test
    void openRejectsABlankName() {
        assertThatThrownBy(() -> TestOracle.withRolls().campaignTools.campaignOpen(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("campaign must not be blank; none exist yet, create one with campaign_create");
    }

    @Test
    void openWritesNoRollLogRow() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        oracle.campaignTools.campaignOpen("Ashfall");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void updateReplacesOnlyTheFieldsGiven() {
        var oracle = TestOracle.withRolls();
        oracle.campaignTools.campaignCreate("Ashfall", null, null, "Mara, a scout", null);
        var skills = oracle.campaignTools.campaignUpdate("ashfall", null, null, null, "Stealth +7: +3");
        assertThat(skills.pcSummary()).isEqualTo("Mara, a scout");
        assertThat(skills.pcSkills()).isEqualTo("Stealth +7: +3");
        assertThat(skills.summary()).isEqualTo("Updated 'Ashfall': chaos 5; no scenes yet; threads 0, characters 0.");
        var summary = oracle.campaignTools.campaignUpdate("Ashfall", null, null, "Mara, a veteran scout", null);
        assertThat(summary.pcSummary()).isEqualTo("Mara, a veteran scout");
        assertThat(summary.pcSkills()).isEqualTo("Stealth +7: +3");
    }

    @Test
    void blankFieldsAreLeftAsTheyAre() {   // a DM may send "" for the field it means to keep
        var oracle = TestOracle.withRolls();
        oracle.campaignTools.campaignCreate("Ashfall", null, null, "Mara, a scout", "Stealth +7: +3");
        for (var blank : List.of("", "  ")) {
            var brief = oracle.campaignTools.campaignUpdate("Ashfall", null, null, blank, "Stealth +7: +4");
            assertThat(brief.pcSummary()).isEqualTo("Mara, a scout");
            assertThat(brief.pcSkills()).isEqualTo("Stealth +7: +4");
        }
    }

    @Test
    void updateNeedsAField() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        for (var fields : List.of(new String[] {null, null, null, null}, new String[] {"", " ", "", "  "})) {
            assertThatThrownBy(() -> oracle.campaignTools.campaignUpdate("Ashfall", fields[0], fields[1], fields[2],
                fields[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign_update needs setting, tone, pc_summary or pc_skills");
        }
        assertThatThrownBy(() -> oracle.campaignTools.campaignUpdate("Ashfal", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("campaign_update needs setting, tone, pc_summary or pc_skills");
    }

    @Test
    void updateReplacesTheSettingAndTone() {
        var oracle = TestOracle.withRolls();
        oracle.campaignTools.campaignCreate("Ashfall", "Modern Italy; the city is picked in the first scene",
            "Spy novel", "Jacques", null);
        var setting = oracle.campaignTools.campaignUpdate("Ashfall", "Modern Milan", null, null, null);
        assertThat(setting.setting()).isEqualTo("Modern Milan");
        assertThat(setting.tone()).isEqualTo("Spy novel");
        assertThat(setting.pcSummary()).isEqualTo("Jacques");
        var tone = oracle.campaignTools.campaignUpdate("Ashfall", " ", "Spy novel with irony", null, null);
        assertThat(tone.setting()).isEqualTo("Modern Milan");
        assertThat(tone.tone()).isEqualTo("Spy novel with irony");
        assertThat(tone.pcSummary()).isEqualTo("Jacques");
    }

    @Test
    void updateNeedsAKnownCampaign() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        assertThatThrownBy(() -> oracle.campaignTools.campaignUpdate("Ashfal", null, null, null, "x"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no campaign 'Ashfal'; campaigns: Ashfall");
    }

    @Test
    void updateWritesNoRollLogRow() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        oracle.campaignTools.campaignUpdate("Ashfall", null, null, null, "Stealth +7: +3");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void aLongSheetSurvivesTheBriefAndTheRecap() {   // a converted character sheet: many lines, any language
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        var sheet = new StringBuilder("System: D&D 5e; skill modifiers: floor -1, average +2, ceiling +11");
        for (int i = 1; i <= 40; i++) {
            sheet.append("\nНавык ").append(i).append(": +1");
        }
        var skills = sheet.toString();
        oracle.campaignTools.campaignUpdate("Ashfall", null, null, null, skills);
        assertThat(oracle.campaignTools.campaignOpen("Ashfall").pcSkills()).isEqualTo(skills);
        var resource = (TextResourceContents) oracle.resources.brief("Ashfall").contents().getFirst();
        assertThat(JsonMapper.builder().build().readTree(resource.text()).get("pc_skills").asString())
            .isEqualTo(skills);
        assertThat(oracle.sessionTools.sessionStart("Ashfall").pcSkills()).isEqualTo(skills);
    }
}
