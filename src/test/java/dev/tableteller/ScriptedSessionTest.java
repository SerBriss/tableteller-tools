package dev.tableteller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.tableteller.campaign.Campaign;
import dev.tableteller.journal.JournalEntry;
import dev.tableteller.journal.JournalKind;
import dev.tableteller.lists.ListEntry;
import dev.tableteller.lists.ListName;
import dev.tableteller.scene.Scene;
import dev.tableteller.scene.SceneResult;
import dev.tableteller.session.SessionLog;
import dev.tableteller.session.SessionStatus;
import dev.tableteller.state.RollRecord;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import dev.tableteller.table.OddsLevel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The spec's integration test: open → start → three scenes → end, checked row by row, then the next recap. */
class ScriptedSessionTest {
    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void threeScenesProduceTheExpectedRows() {
        // scripted, in call order:
        // scene 1 check 8 (expected); fate 30 (fifty_fifty: yes, no event); end in control          → chaos 4
        // scene 2 check 2 (interrupted at chaos 4); event focus 1, meaning 1, 2; end not in control → chaos 5
        // scene 3 check 3 (altered); end not in control with actual_setup                          → chaos 6
        var oracle = TestOracle.withRolls(8, 30, 2, 1, 1, 2, 3);
        oracle.createCampaign("Ashfall");
        var first = oracle.sessionTools.sessionStart("Ashfall");
        oracle.listTools.listAdd("Ashfall", ListName.threads, "Find the heir", null, null);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara reaches the gate");
        oracle.journalTools.journalWrite("Ashfall", JournalKind.location, "North gate", "Iron-bound, watched at night.",
            List.of("ashfall"), null);
        oracle.tools.fateCheck("Ashfall", "Is the gate guarded?", OddsLevel.fifty_fifty, null, null);
        oracle.sceneTools.endScene("Ashfall", "Mara slipped past.", true, null);
        oracle.sceneTools.sceneCheck("Ashfall", "Mara searches the archive");
        oracle.sceneTools.endScene("Ashfall", "A fire broke out.", false, "The archive burns");
        oracle.sceneTools.sceneCheck("Ashfall", "Mara questions the steward");
        oracle.sceneTools.endScene("Ashfall", "The steward lied.", false, "The steward is gone; his aide waits");
        oracle.sessionTools.sessionEnd("Ashfall", "Mara got into the city and lost the steward.", "Who set the fire?");
        var second = oracle.sessionTools.sessionStart("Ashfall");

        var campaignId = oracle.campaignId("Ashfall");
        assertThat(oracle.journal.find(campaignId, JournalKind.location, "North gate")).get()
            .extracting(JournalEntry::sceneNumber).isEqualTo(1);
        assertThat(second.lastSession())
            .extracting(SessionLog::sessionId, SessionLog::status, SessionLog::summary, SessionLog::openQuestions)
            .containsExactly(first.sessionId(), SessionStatus.ended, "Mara got into the city and lost the steward.",
                "Who set the fire?");
        assertThat(second.scenes()).extracting(Scene::number).containsExactly(1, 2, 3);
        assertThat(second.threads()).extracting(ListEntry::title).containsExactly("Find the heir");
        assertThat(oracle.sessions.findAll(campaignId)).extracting(SessionLog::sessionId, SessionLog::status)
            .containsExactly(tuple(first.sessionId(), SessionStatus.ended), tuple(second.sessionId(), SessionStatus.open));

        // journal and session tools add no roll-log rows
        assertThat(oracle.rows("Ashfall")).extracting(RollRecord::tool).containsExactly(
            "scene_check", "fate_check", "end_scene", "scene_check", "end_scene", "scene_check", "end_scene");
        assertThat(oracle.rows("Ashfall")).extracting(RollRecord::sceneNumber).containsExactly(1, 1, 1, 2, 2, 3, 3);
        assertThat(oracle.scenes.findAll(oracle.campaignId("Ashfall")))
            .extracting(Scene::number, Scene::checkResult, Scene::actualSetup, Scene::open)
            .containsExactly(
                tuple(1, SceneResult.expected, "Mara reaches the gate", false),
                tuple(2, SceneResult.interrupted, "The archive burns", false),
                tuple(3, SceneResult.altered, "The steward is gone; his aide waits", false));
        assertThat(oracle.campaigns.findByName("Ashfall")).get()
            .extracting(Campaign::chaosFactor, Campaign::sceneNumber)
            .containsExactly(6, 3);
    }
}
