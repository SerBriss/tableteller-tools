package dev.tableteller.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.tableteller.lists.ListEntry;
import dev.tableteller.lists.ListName;
import dev.tableteller.lists.ListStatus;
import dev.tableteller.scene.Scene;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import dev.tableteller.tracker.Tracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionToolsTest {
    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    private static TestOracle open(int... rolls) {
        var oracle = TestOracle.withRolls(rolls);
        oracle.createCampaign("Ashfall");
        return oracle;
    }

    /** Plays one scene that runs as expected (scripted 8) and ends it. */
    private static void playScene(TestOracle oracle, String setup, boolean pcInControl) {
        oracle.sceneTools.sceneCheck("Ashfall", setup);
        oracle.sceneTools.endScene("Ashfall", setup + " is over.", pcInControl, null);
    }

    @Test
    void recapShowsThePcSkills() {
        var oracle = TestOracle.withRolls();
        oracle.campaignTools.campaignCreate("Ashfall", null, null, null, "Stealth: exceptional, +2");
        oracle.createCampaign("Iron Coast");
        assertThat(oracle.sessionTools.sessionStart("Ashfall").pcSkills()).isEqualTo("Stealth: exceptional, +2");
        assertThat(oracle.sessionTools.sessionStart("Iron Coast").pcSkills()).isNull();
    }

    @Test
    void recapShowsTheTrackers() {
        var oracle = TestOracle.withRolls();
        var hunger = new Tracker("Голод", 1, 0, 5, "Rouse: d10");
        oracle.trackers.add(oracle.createCampaign("Ashfall"), hunger);
        oracle.createCampaign("Iron Coast");
        assertThat(oracle.sessionTools.sessionStart("Ashfall").trackers()).containsExactly(hunger);
        assertThat(oracle.sessionTools.sessionStart("Iron Coast").trackers()).isEmpty();
    }

    @Test
    void firstSessionHasNoLastSession() {
        var recap = open().sessionTools.sessionStart("Ashfall");
        assertThat(recap.campaign()).isEqualTo("Ashfall");
        assertThat(recap.lastSession()).isNull();
        assertThat(recap.scenes()).isEmpty();
        assertThat(recap.chaosFactor()).isEqualTo(5);
        assertThat(recap.sceneNumber()).isZero();
        assertThat(recap.summary()).isEqualTo("Session started for 'Ashfall': first session; chaos 5; no scenes yet.");
    }

    @Test
    void lastSessionEndedSummary() {
        var oracle = open(8, 8, 8);
        oracle.sessionTools.sessionStart("Ashfall");
        playScene(oracle, "The gate", true);      // chaos 4
        playScene(oracle, "The archive", false);  // chaos 5
        playScene(oracle, "The steward", false);  // chaos 6
        oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", "Who hired the guard?");
        assertThat(oracle.sessionTools.sessionStart("Ashfall").summary())
            .isEqualTo("Session started for 'Ashfall': last session ended; chaos 6; scene 3 closed.");
    }

    @Test
    void recapShowsTheEndedSessionAndTheLastThreeScenes() {
        var oracle = open(8, 8, 8, 8);
        oracle.listTools.listAdd("Ashfall", ListName.threads, "Find the heir", null, null);
        oracle.listTools.listAdd("Ashfall", ListName.threads, "Old feud", null, ListStatus.closed);
        oracle.listTools.listAdd("Ashfall", ListName.characters, "Varo", 2, null);
        oracle.sessionTools.sessionStart("Ashfall");
        for (var setup : new String[] {"The gate", "The archive", "The steward", "The docks"}) {
            playScene(oracle, setup, true);
        }
        oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", "Who hired the guard?");

        var recap = oracle.sessionTools.sessionStart("Ashfall");
        assertThat(recap.lastSession().status()).isEqualTo(SessionStatus.ended);
        assertThat(recap.lastSession().summary()).isEqualTo("Mara reached the city.");
        assertThat(recap.lastSession().openQuestions()).isEqualTo("Who hired the guard?");
        assertThat(recap.lastSession().endedAt()).isNotNull();
        assertThat(recap.scenes()).extracting(Scene::number).containsExactly(2, 3, 4);
        assertThat(recap.scenes().getLast().summary()).isEqualTo("The docks is over.");
        assertThat(recap.threads()).containsExactly(new ListEntry("Find the heir", 1, ListStatus.open));
        assertThat(recap.characters()).containsExactly(new ListEntry("Varo", 2, ListStatus.active));
        assertThat(recap.chaosFactor()).isEqualTo(1);
        assertThat(recap.sceneNumber()).isEqualTo(4);
        assertThat(recap.sessionId()).isNotEqualTo(recap.lastSession().sessionId());
    }

    @Test
    void abandonedSession() {   // spec lifecycle test
        var oracle = open(8);
        var first = oracle.sessionTools.sessionStart("Ashfall");
        oracle.sceneTools.sceneCheck("Ashfall", "The gate");
        var recap = oracle.sessionTools.sessionStart("Ashfall");
        assertThat(recap.lastSession().sessionId()).isEqualTo(first.sessionId());
        assertThat(recap.lastSession().status()).isEqualTo(SessionStatus.abandoned);
        assertThat(recap.lastSession().summary()).isNull();
        assertThat(recap.lastSession().openQuestions()).isNull();
        assertThat(recap.lastSession().endedAt()).isNull();
        assertThat(recap.scenes()).extracting(Scene::number, Scene::open).containsExactly(tuple(1, true));
        assertThat(recap.summary()).isEqualTo("Session started for 'Ashfall': last session was never ended and is now "
            + "abandoned, so recap from the scenes; chaos 5; scene 1 open.");
        assertThat(oracle.sessions.findAll(oracle.campaignId("Ashfall"))).extracting(SessionLog::status)
            .containsExactly(SessionStatus.abandoned, SessionStatus.open);
    }

    @Test
    void startingTwiceAbandonsTheFirstSession() {
        var oracle = open(8);
        oracle.listTools.listAdd("Ashfall", ListName.threads, "Find the heir", null, null);
        oracle.sessionTools.sessionStart("Ashfall");
        playScene(oracle, "The gate", true);
        oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", "none");
        var once = oracle.sessionTools.sessionStart("Ashfall");
        var twice = oracle.sessionTools.sessionStart("Ashfall");
        assertThat(twice.lastSession().sessionId()).isEqualTo(once.sessionId());
        assertThat(twice.lastSession().status()).isEqualTo(SessionStatus.abandoned);
        assertThat(twice.scenes()).extracting(Scene::number).containsExactly(1);
        assertThat(twice.threads()).extracting(ListEntry::title).containsExactly("Find the heir");
    }

    @Test
    void openSceneCarriesAcrossSessions() {
        var oracle = open(8);
        oracle.sessionTools.sessionStart("Ashfall");
        oracle.sceneTools.sceneCheck("Ashfall", "The gate");
        assertThat(oracle.sessionTools.sessionEnd("Ashfall", "Mara waits at the gate.", "Will the guard let her in?").summary())
            .isEqualTo("Session ended for 'Ashfall'; scene 1 is still open and carries over.");
        var recap = oracle.sessionTools.sessionStart("Ashfall");
        assertThat(recap.scenes().getLast().open()).isTrue();
        assertThat(recap.summary()).isEqualTo("Session started for 'Ashfall': last session ended; chaos 5; scene 1 open.");
    }

    @Test
    void endSummary() {
        var oracle = open();
        var started = oracle.sessionTools.sessionStart("Ashfall");
        var ended = oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", "Who hired the guard?");
        assertThat(ended.sessionId()).isEqualTo(started.sessionId());
        assertThat(ended.summary()).isEqualTo("Session ended for 'Ashfall'.");
    }

    @Test
    void summaryMustNotBeBlank() {
        var oracle = open();
        oracle.sessionTools.sessionStart("Ashfall");
        assertThatThrownBy(() -> oracle.sessionTools.sessionEnd("Ashfall", " ", "none"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("summary must not be blank");
    }

    @Test
    void openQuestionsMustNotBeBlank() {
        var oracle = open();
        oracle.sessionTools.sessionStart("Ashfall");
        assertThatThrownBy(() -> oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("open_questions must not be blank; write \"none\" if nothing is open");
    }

    @Test
    void noOpenSession() {   // session_end before session_start
        var oracle = open();
        assertThatThrownBy(() -> oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", "none"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no session is open; call session_start first, then session_end");
        oracle.sessionTools.sessionStart("Ashfall");
        oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", "none");
        assertThatThrownBy(() -> oracle.sessionTools.sessionEnd("Ashfall", "Again.", "none"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no session is open; call session_start first, then session_end");
    }

    @Test
    void sessionsBelongToTheirCampaign() {
        var oracle = open();
        var ashfall = oracle.campaignId("Ashfall");
        oracle.sessionTools.sessionStart("Ashfall");
        oracle.createCampaign("Iron Coast");
        assertThat(oracle.sessionTools.sessionStart("Iron Coast").lastSession()).isNull();
        oracle.sessionTools.sessionEnd("Iron Coast", "Ilsa set sail.", "none");
        assertThat(oracle.sessions.findOpen(ashfall)).isPresent();
    }

    @Test
    void sessionToolsWriteNoRollLogRows() {
        var oracle = open();
        oracle.sessionTools.sessionStart("Ashfall");
        oracle.sessionTools.sessionEnd("Ashfall", "Mara reached the city.", "none");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void sessionToolsNeedAKnownCampaign() {
        var oracle = open();
        var tools = oracle.sessionTools;
        var message = "no campaign 'Ashfal'; campaigns: Ashfall";
        assertThatThrownBy(() -> tools.sessionStart("Ashfal")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
        assertThatThrownBy(() -> tools.sessionEnd("Ashfal", "Mara reached the city.", "none"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThat(oracle.sessions.findAll(oracle.campaignId("Ashfall"))).isEmpty();
    }
}
