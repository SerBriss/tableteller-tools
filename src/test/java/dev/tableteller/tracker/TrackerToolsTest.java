package dev.tableteller.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.state.RollRecord;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import dev.tableteller.tracker.TrackerTools.AdjustResult;
import dev.tableteller.tracker.TrackerTools.TrackerResult;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrackerToolsTest {
    private static final String RANGE = "must be within ±1,000,000; got ";
    private static final String NO_REASON = "reason must not be blank; say what changed the number, for example "
        + "\"Rouse check 4\"";

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    /** A fixture with an open campaign. */
    private static TestOracle open() {
        var oracle = TestOracle.withRolls();
        oracle.createCampaign("Ashfall");
        return oracle;
    }

    /** Ashfall with Голод at this value, 0 to 5, and its house rule as the note. */
    private static TestOracle hungry(int value) {
        var oracle = open();
        oracle.trackerTools.trackerAdd("Ashfall", "Голод", value, 0, 5, "Rouse: d10");
        return oracle;
    }

    /** The call is rejected with this message, and Ashfall keeps its roll-log rows and its trackers. */
    private static void assertRejected(TestOracle oracle, ThrowingCallable call, String message) {
        var campaign = oracle.campaignId("Ashfall");
        var rows = oracle.rows("Ashfall");
        var trackers = oracle.trackers.findAll(campaign);
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThat(oracle.rows("Ashfall")).isEqualTo(rows);
        assertThat(oracle.trackers.findAll(campaign)).isEqualTo(trackers);
    }

    private static void assertAddRejected(TestOracle oracle, String campaign, String name, Integer value, Integer min,
            Integer max, String message) {
        assertRejected(oracle, () -> oracle.trackerTools.trackerAdd(campaign, name, value, min, max, null), message);
    }

    private static void assertAdjustRejected(TestOracle oracle, String campaign, String name, Integer delta,
            String reason, String message) {
        assertRejected(oracle, () -> oracle.trackerTools.trackerAdjust(campaign, name, delta, reason), message);
    }

    @Test
    void addStartsTrackingAndLogsIt() {
        var oracle = open();
        assertThat(oracle.trackerTools.trackerAdd("Ashfall", "Голод", 1, 0, 5, "Rouse: d10")).isEqualTo(
            new TrackerResult("r_0001", new Tracker("Голод", 1, 0, 5, "Rouse: d10"), "Tracking Голод: 1 (0 to 5)."));
        var row = oracle.rows("Ashfall").getFirst();
        assertThat(row.tool()).isEqualTo("tracker_add");
        assertThat(row.dice()).isEmpty();
        assertThat(row.sceneNumber()).isZero();
        var stored = Map.of("name", "Голод", "value", 1, "min", 0, "max", 5, "note", "Rouse: d10");
        assertThat(row.inputs()).isEqualTo(stored);
        assertThat(row.result()).isEqualTo(stored);
        assertThat(oracle.trackers.findAll(oracle.campaignId("Ashfall")))
            .containsExactly(new Tracker("Голод", 1, 0, 5, "Rouse: d10"));
    }

    @Test
    void addWithoutLimitsOrNote() {
        var oracle = open();
        var result = oracle.trackerTools.trackerAdd("Ashfall", "Сила воли", 4, null, null, " ");
        assertThat(result.tracker()).isEqualTo(new Tracker("Сила воли", 4, null, null, null));
        assertThat(result.summary()).isEqualTo("Tracking Сила воли: 4.");
        var row = oracle.rows("Ashfall").getFirst();
        assertThat(row.inputs()).isEqualTo(Map.of("name", "Сила воли", "value", 4));
        assertThat(row.result()).isEqualTo(Map.of("name", "Сила воли", "value", 4));
        assertThat(oracle.trackers.find(oracle.campaignId("Ashfall"), "сила воли").orElseThrow().note()).isNull();
    }

    @Test
    void oneSidedLimits() {
        var oracle = open();
        assertThat(oracle.trackerTools.trackerAdd("Ashfall", "Arrows", 12, 0, null, null).summary())
            .isEqualTo("Tracking Arrows: 12 (min 0).");
        assertThat(oracle.trackerTools.trackerAdd("Ashfall", "Countdown", 7, null, 7, null).summary())
            .isEqualTo("Tracking Countdown: 7 (max 7).");
    }

    @Test
    void addRejectsBadInput() {
        var oracle = open();
        oracle.trackerTools.trackerAdd("Ashfall", "Hunger", 1, 0, 5, null);
        assertAddRejected(oracle, "Ashfall", null, 1, null, null, "name must not be blank");
        assertAddRejected(oracle, "Ashfall", "  ", 1, null, null, "name must not be blank");
        assertAddRejected(oracle, "Ashfal", " ", 1, null, null, "name must not be blank");   // before the campaign
        assertAddRejected(oracle, "Ashfal", "", null, null, null, "name must not be blank");   // before the value
        assertAddRejected(oracle, "Ashfal", "Gold", null, null, null, "value is required");
        assertAddRejected(oracle, "Ashfal", "Gold", null, 1_000_001, null, "value is required");   // before the limits
        assertAddRejected(oracle, "Ashfal", "Gold", 1_000_001, null, null, "value " + RANGE + "1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", -1_000_001, null, null, "value " + RANGE + "-1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", 1_000_001, 1_000_001, 1_000_001, "value " + RANGE + "1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", 0, 1_000_001, null, "min " + RANGE + "1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", 0, -1_000_001, null, "min " + RANGE + "-1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", 0, 1_000_001, 1_000_001, "min " + RANGE + "1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", 0, null, 1_000_001, "max " + RANGE + "1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", 0, null, -1_000_001, "max " + RANGE + "-1000001");
        assertAddRejected(oracle, "Ashfal", "Gold", 5, 5, 5, "min must be less than max; got min=5, max=5");
        assertAddRejected(oracle, "Ashfal", "Gold", 5, 6, 5, "min must be less than max; got min=6, max=5");
        assertAddRejected(oracle, "Ashfal", "Gold", 9, 5, 5, "min must be less than max; got min=5, max=5");
        assertAddRejected(oracle, "Ashfal", "Gold", 7, 0, 5, "value 7 is outside 0 to 5");
        assertAddRejected(oracle, "Ashfal", "Gold", -1, 0, 5, "value -1 is outside 0 to 5");
        assertAddRejected(oracle, "Ashfal", "Gold", -1, 0, null, "value -1 is below the min, 0");
        assertAddRejected(oracle, "Ashfal", "Gold", 6, null, 5, "value 6 is above the max, 5");
    }

    @Test
    void addRejectsANameAlreadyTracked() {
        var oracle = hungry(1);
        assertAddRejected(oracle, "Ashfall", " голод ", 2, null, null,
            "'Голод' is already tracked (1, 0 to 5); change it with tracker_adjust, or tracker_remove it to start over");
        assertThat(oracle.rows("Ashfall")).hasSize(1);
    }

    @Test
    void theNameTakenMessageHoldsTheValueAndWhateverLimitsThereAre() {
        var oracle = open();
        oracle.trackerTools.trackerAdd("Ashfall", "Arrows", 12, 0, null, null);
        oracle.trackerTools.trackerAdd("Ashfall", "Countdown", 7, null, 7, null);
        oracle.trackerTools.trackerAdd("Ashfall", "Willpower", 4, null, null, null);
        var tail = "; change it with tracker_adjust, or tracker_remove it to start over";
        assertAddRejected(oracle, "Ashfall", "ARROWS", 1, null, null, "'Arrows' is already tracked (12, min 0)" + tail);
        assertAddRejected(oracle, "Ashfall", "countdown", 1, null, null,
            "'Countdown' is already tracked (7, max 7)" + tail);
        assertAddRejected(oracle, "Ashfall", "willpower", 1, null, null, "'Willpower' is already tracked (4)" + tail);
    }

    @Test
    void adjustAnnouncesTheChange() {
        var oracle = hungry(1);
        assertThat(oracle.trackerTools.trackerAdjust("Ashfall", "голод", 1, " Rouse check 4 ")).isEqualTo(
            new AdjustResult("r_0002", new Tracker("Голод", 2, 0, 5, "Rouse: d10"), 1, false,
                "Голод: 1 → 2 (+1, Rouse check 4)."));
        var row = oracle.rows("Ashfall").get(1);
        assertThat(row.tool()).isEqualTo("tracker_adjust");
        assertThat(row.inputs()).isEqualTo(Map.of("name", "голод", "delta", 1, "reason", "Rouse check 4"));
        assertThat(row.result())
            .isEqualTo(Map.of("name", "Голод", "value", 2, "previous_value", 1, "clamped", false));
        assertThat(row.dice()).isEmpty();
        assertThat(oracle.trackers.find(oracle.campaignId("Ashfall"), "Голод").orElseThrow().value()).isEqualTo(2);
    }

    @Test
    void adjustStopsAtTheLimitsAndSaysSo() {
        var oracle = hungry(5);
        var atMax = oracle.trackerTools.trackerAdjust("Ashfall", "Голод", 1, "Rouse check 3");
        assertThat(atMax.tracker().value()).isEqualTo(5);
        assertThat(atMax.previousValue()).isEqualTo(5);
        assertThat(atMax.clamped()).isTrue();
        assertThat(atMax.summary()).isEqualTo("Голод: 5 → 5 (+1, Rouse check 3). Stopped at the max, 5.");
        assertThat(oracle.rows("Ashfall").get(1).result())
            .isEqualTo(Map.of("name", "Голод", "value", 5, "previous_value", 5, "clamped", true));

        var atMin = oracle.trackerTools.trackerAdjust("Ashfall", "Голод", -9, "fed on Lucia");
        assertThat(atMin.tracker().value()).isZero();
        assertThat(atMin.clamped()).isTrue();
        assertThat(atMin.summary()).isEqualTo("Голод: 5 → 0 (-9, fed on Lucia). Stopped at the min, 0.");

        var between = oracle.trackerTools.trackerAdjust("Ashfall", "Голод", 2, "Rouse checks 1 and 3");
        assertThat(between.tracker().value()).isEqualTo(2);
        assertThat(between.clamped()).isFalse();
        assertThat(between.summary()).isEqualTo("Голод: 0 → 2 (+2, Rouse checks 1 and 3).");
        assertThat(oracle.trackers.find(oracle.campaignId("Ashfall"), "Голод").orElseThrow().value()).isEqualTo(2);
    }

    @Test
    void adjustingToALimitExactlyDoesNotClamp() {
        var oracle = hungry(4);
        var result = oracle.trackerTools.trackerAdjust("Ashfall", "Голод", 1, "Rouse check 5");
        assertThat(result.tracker().value()).isEqualTo(5);
        assertThat(result.clamped()).isFalse();
        assertThat(result.summary()).isEqualTo("Голод: 4 → 5 (+1, Rouse check 5).");
    }

    @Test
    void adjustPastAMillionWithoutALimitIsAnError() {
        var oracle = open();
        oracle.trackerTools.trackerAdd("Ashfall", "Gold", 999_999, null, null, null);
        oracle.trackerTools.trackerAdd("Ashfall", "Debt", -999_999, null, null, null);
        oracle.trackerTools.trackerAdd("Ashfall", "Arrows", 999_999, 0, null, null);
        assertAdjustRejected(oracle, "Ashfall", "Gold", 2, "loot", "that would make Gold 1000001, past ±1,000,000");
        assertAdjustRejected(oracle, "Ashfall", "Debt", -2, "loan", "that would make Debt -1000001, past ±1,000,000");
        assertAdjustRejected(oracle, "Ashfall", "Arrows", 2, "quiver", "that would make Arrows 1000001, past ±1,000,000");
        var campaign = oracle.campaignId("Ashfall");
        assertThat(oracle.trackers.find(campaign, "Gold").orElseThrow().value()).isEqualTo(999_999);
        assertThat(oracle.rows("Ashfall")).hasSize(3);
        assertThat(oracle.trackerTools.trackerAdjust("Ashfall", "Gold", 1, "loot").tracker().value())
            .isEqualTo(1_000_000);
    }

    @Test
    void adjustRejectsBadInput() {
        var oracle = open();
        assertAdjustRejected(oracle, "Ashfal", null, 1, "Rouse check 4", "name must not be blank");
        assertAdjustRejected(oracle, "Ashfal", "  ", 0, null, "name must not be blank");   // before the delta
        assertAdjustRejected(oracle, "Ashfal", "Голод", null, "Rouse check 4",
            "delta must be a whole number other than 0; got null");
        assertAdjustRejected(oracle, "Ashfal", "Голод", 0, "Rouse check 4",
            "delta must be a whole number other than 0; got 0");
        assertAdjustRejected(oracle, "Ashfal", "Голод", 0, " ", "delta must be a whole number other than 0; got 0");
        assertAdjustRejected(oracle, "Ashfal", "Голод", 1_000_001, "Rouse check 4", "delta " + RANGE + "1000001");
        assertAdjustRejected(oracle, "Ashfal", "Голод", -1_000_001, null, "delta " + RANGE + "-1000001");   // before the reason
        assertAdjustRejected(oracle, "Ashfal", "Голод", 1, null, NO_REASON);
        assertAdjustRejected(oracle, "Ashfal", "Голод", 1, "  ", NO_REASON);
        assertAdjustRejected(oracle, "Ashfall", "Hunger", 1, "Rouse check 4",
            "nothing is tracked yet; start with tracker_add");
        oracle.trackerTools.trackerAdd("Ashfall", "Hunger", 1, 0, 5, null);
        oracle.trackerTools.trackerAdd("Ashfall", "Willpower", 4, null, null, null);
        assertAdjustRejected(oracle, "Ashfall", " Hungr ", 1, "Rouse check 4",
            "no tracker 'Hungr'; tracked: Hunger, Willpower");
    }

    @Test
    void removeStopsTrackingAndKeepsTheHistory() {
        var oracle = hungry(1);
        oracle.trackerTools.trackerAdjust("Ashfall", "Голод", 1, "Rouse check 4");
        assertThat(oracle.trackerTools.trackerRemove("Ashfall", " ГОЛОД ")).isEqualTo(
            new TrackerResult("r_0003", new Tracker("Голод", 2, 0, 5, "Rouse: d10"), "Stopped tracking Голод at 2."));
        var row = oracle.rows("Ashfall").get(2);
        assertThat(row.tool()).isEqualTo("tracker_remove");
        assertThat(row.inputs()).isEqualTo(Map.of("name", "ГОЛОД"));
        assertThat(row.result()).isEqualTo(Map.of("name", "Голод", "value", 2));
        assertThat(row.dice()).isEmpty();
        assertThat(oracle.campaignTools.campaignOpen("Ashfall").trackers()).isEmpty();
        assertThat(oracle.rows("Ashfall")).extracting(RollRecord::tool)
            .containsExactly("tracker_add", "tracker_adjust", "tracker_remove");
        assertThat(oracle.trackerTools.trackerAdd("Ashfall", "Голод", 0, 0, 5, null).summary())
            .isEqualTo("Tracking Голод: 0 (0 to 5).");
    }

    @Test
    void removeRejectsBadInput() {
        var oracle = open();
        assertRejected(oracle, () -> oracle.trackerTools.trackerRemove("Ashfal", null), "name must not be blank");
        assertRejected(oracle, () -> oracle.trackerTools.trackerRemove("Ashfal", " "), "name must not be blank");
        assertRejected(oracle, () -> oracle.trackerTools.trackerRemove("Ashfall", "Hunger"),
            "nothing is tracked yet; start with tracker_add");
        oracle.trackerTools.trackerAdd("Ashfall", "Hunger", 1, 0, 5, null);
        oracle.trackerTools.trackerAdd("Ashfall", "Willpower", 4, null, null, null);
        assertRejected(oracle, () -> oracle.trackerTools.trackerRemove("Ashfall", " Hungr "),
            "no tracker 'Hungr'; tracked: Hunger, Willpower");
    }

    @Test
    void trackersStayWithTheirCampaign() {
        var oracle = open();
        var ironCoast = oracle.createCampaign("Iron Coast");
        oracle.trackerTools.trackerAdd("Ashfall", "Hunger", 1, null, null, null);
        oracle.trackerTools.trackerAdd("Iron Coast", "Hunger", 3, null, null, null);
        oracle.trackerTools.trackerAdjust("Ashfall", "Hunger", 1, "Rouse check 4");
        assertThat(oracle.trackers.find(oracle.campaignId("Ashfall"), "Hunger").orElseThrow().value()).isEqualTo(2);
        assertThat(oracle.trackers.find(ironCoast, "Hunger").orElseThrow().value()).isEqualTo(3);
        assertThat(oracle.rows("Ashfall")).extracting(RollRecord::tool).containsExactly("tracker_add", "tracker_adjust");
        assertThat(oracle.rows("Iron Coast")).extracting(RollRecord::tool).containsExactly("tracker_add");
    }

    @Test
    void trackerToolsNeedAKnownCampaign() {
        var oracle = hungry(1);
        var message = "no campaign 'Ashfal'; campaigns: Ashfall";
        assertAddRejected(oracle, "Ashfal", "Willpower", 4, null, null, message);
        assertAdjustRejected(oracle, "Ashfal", "Голод", 1, "Rouse check 4", message);
        assertRejected(oracle, () -> oracle.trackerTools.trackerRemove("Ashfal", "Голод"), message);
        assertThat(oracle.rows("Ashfall")).hasSize(1);
    }
}
