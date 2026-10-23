package dev.tableteller.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.event.EventTarget;
import dev.tableteller.event.RandomEvent;
import dev.tableteller.lists.ListName;
import dev.tableteller.state.RollRecord;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import dev.tableteller.table.FateAnswer;
import dev.tableteller.table.OddsLevel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OracleToolsTest {
    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    /** A fixture with these scripted rolls and an open campaign. */
    private static TestOracle open(int... rolls) {
        var oracle = TestOracle.withRolls(rolls);
        oracle.createCampaign("Ashfall");
        return oracle;
    }

    @Test
    void rollReturnsValueAndLogsIt() {
        var oracle = open(4);
        assertThat(oracle.tools.roll("Ashfall", 1, 6, null, null))
            .isEqualTo(new OracleTools.RollResult("r_0001", 4, null, "Rolled 4 (1 to 6)."));
        var row = oracle.rows("Ashfall").getFirst();
        assertThat(row.tool()).isEqualTo("roll");
        assertThat(row.inputs()).isEqualTo(Map.of("min", 1, "max", 6));
        assertThat(row.dice()).containsExactly(4);
        assertThat(row.result()).isEqualTo(Map.of("value", 4));
        assertThat(row.sceneNumber()).isZero();
    }

    @Test
    void stateAndRollIdsPersistAcrossToolInstances() {
        var first = TestOracle.withRolls();
        first.createCampaign("Ashfall");
        first.tools.setChaos("Ashfall", 7);
        var second = TestOracle.withRolls(4, 50);
        assertThat(second.tools.roll("Ashfall", 1, 6, null, null).rollId()).isEqualTo("r_0002");
        assertThat(second.tools.fateCheck("Ashfall", "Is it?", OddsLevel.likely, null, null).chaosFactor())
            .isEqualTo(7);
    }

    @Test
    void rollAcceptsTheWidestRange() {
        assertThat(open(0).tools.roll("Ashfall", -1_000_000, 1_000_000, null, null).value()).isZero();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"5,5", "6,1", "-1000001,0", "0,1000001", "null,6", "1,null"})
    void rollRejectsBadRangesWithoutLogging(Integer min, Integer max) {
        var oracle = open();
        assertThatThrownBy(() -> oracle.tools.roll("Ashfall", min, max, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min < max");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void rollLogsItsQuestion() {
        var oracle = open(2);
        assertThat(oracle.tools.roll("Ashfall", 1, 4, null, "Which city? 1 Rome, 2 Milan, 3 Venice, 4 Naples"))
            .isEqualTo(new OracleTools.RollResult("r_0001", 2, null, "Rolled 2 (1 to 4)."));
        assertThat(oracle.rows("Ashfall").getFirst().inputs()).isEqualTo(
            Map.of("question", "Which city? 1 Rome, 2 Milan, 3 Venice, 4 Naples", "min", 1, "max", 4));
    }

    @Test
    void rollWithACountReturnsEachValue() {
        var oracle = open(7, 1, 10);
        assertThat(oracle.tools.roll("Ashfall", 1, 10, 3, "Hunger dice")).isEqualTo(
            new OracleTools.RollResult("r_0001", null, List.of(7, 1, 10), "Rolled 7, 1, 10 (3 rolls of 1 to 10)."));
        assertThat(oracle.rows("Ashfall")).hasSize(1);
        var row = oracle.rows("Ashfall").getFirst();
        assertThat(row.dice()).containsExactly(7, 1, 10);
        assertThat(row.inputs()).isEqualTo(Map.of("question", "Hunger dice", "min", 1, "max", 10, "count", 3));
        assertThat(row.result()).isEqualTo(Map.of("values", List.of(7, 1, 10)));
    }

    @Test
    void aCountOfOneIsASingleRoll() {
        var oracle = open(4);
        assertThat(oracle.tools.roll("Ashfall", 1, 6, 1, null))
            .isEqualTo(new OracleTools.RollResult("r_0001", 4, null, "Rolled 4 (1 to 6)."));
        assertThat(oracle.rows("Ashfall").getFirst().inputs()).isEqualTo(Map.of("min", 1, "max", 6));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 11})
    void rollRejectsACountOutsideOneToTen(int count) {
        var oracle = open();
        assertThatThrownBy(() -> oracle.tools.roll("Ashfall", 1, 6, count, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("count must be 1-10; got " + count);
        assertThat(oracle.rows("Ashfall")).isEmpty();
        assertThatThrownBy(() -> oracle.tools.roll("Ashfal", 1, 6, 11, null))   // checked before the campaign
            .isInstanceOf(IllegalArgumentException.class).hasMessage("count must be 1-10; got 11");
    }

    @Test
    void aBlankQuestionIsLeftOut() {
        var oracle = open(4, 2, 3);
        oracle.tools.roll("Ashfall", 1, 6, null, "  ");
        oracle.tools.meaning("Ashfall", "actions", null, "");
        assertThat(oracle.rows("Ashfall")).extracting(RollRecord::inputs).containsExactly(
            Map.of("min", 1, "max", 6), Map.of("table", "actions", "count", 1));
    }

    @Test
    void setChaosUpdatesStateAndLogsWithoutDice() {
        var oracle = open();
        assertThat(oracle.tools.setChaos("Ashfall", 7))
            .isEqualTo(new OracleTools.SetChaosResult("r_0001", 7, "Chaos factor is now 7 (was 5)."));
        assertThat(oracle.chaos("Ashfall")).isEqualTo(7);
        assertThat(oracle.rows("Ashfall").getFirst().dice()).isEmpty();
        assertThat(oracle.rows("Ashfall").getFirst().result())
            .isEqualTo(Map.of("chaos_factor", 7, "previous_chaos_factor", 5));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"0", "10", "null"})
    void setChaosRejectsValuesOutsideOneToNine(Integer value) {
        var oracle = open();
        assertThatThrownBy(() -> oracle.tools.setChaos("Ashfall", value))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chaos factor must be 1-9");
        assertThat(oracle.rows("Ashfall")).isEmpty();
        assertThat(oracle.chaos("Ashfall")).isEqualTo(5);
    }

    @Test
    void fateCheckMatchesTheSpecExample() {        // likely at chaos 6: 14 / 70 / 95; 44 is a double at or below 6
        var oracle = open(44, 1, 1, 2);
        oracle.tools.setChaos("Ashfall", 6);
        assertThat(oracle.tools.fateCheck("Ashfall", "Is the innkeeper hiding something?", OddsLevel.likely, null,
                null))
            .isEqualTo(new OracleTools.FateCheckResult("r_0002", FateAnswer.yes, List.of(44, 1, 1, 2), 6, true,
                new RandomEvent("Distant event", null, List.of("seek", "ally")), null,
                "Yes (44 on likely, chaos 6). Random event: Distant event (seek / ally)."));
        var row = oracle.rows("Ashfall").get(1);
        assertThat(row.tool()).isEqualTo("fate_check");
        assertThat(row.inputs()).isEqualTo(Map.of("question", "Is the innkeeper hiding something?", "odds", "likely"));
        assertThat(row.dice()).containsExactly(44, 1, 1, 2);
        assertThat(row.result()).isEqualTo(Map.of("answer", "yes", "chaos_factor", 6, "random_event_triggered", true,
            "random_event", Map.of("focus", "Distant event", "meaning", List.of("seek", "ally"))));
    }

    @Test
    void fateCheckIncludesTheRandomEvent() {     // focus 2 needs a character; Varo is the only one
        var oracle = open(44, 2, 1, 1, 2);
        oracle.listTools.listAdd("Ashfall", ListName.characters, "Innkeeper Varo", null, null);
        oracle.tools.setChaos("Ashfall", 6);
        var result = oracle.tools.fateCheck("Ashfall", "Is the innkeeper hiding something?", OddsLevel.likely, null,
            null);
        assertThat(result.randomEvent()).isEqualTo(new RandomEvent("A character acts",
            EventTarget.existing(ListName.characters, "Innkeeper Varo"), List.of("seek", "ally")));
        assertThat(result.dice()).containsExactly(44, 2, 1, 1, 2);
        assertThat(result.summary())
            .isEqualTo("Yes (44 on likely, chaos 6). Random event: A character acts → Innkeeper Varo (seek / ally).");
        assertThat(oracle.rows("Ashfall").get(1).result().get("random_event")).isEqualTo(Map.of("focus", "A character acts",
            "target", Map.of("list", "characters", "title", "Innkeeper Varo"), "meaning", List.of("seek", "ally")));
    }

    @Test
    void fateCheckWithoutEventHasNoRandomEventField() {
        assertThat(open(97).tools.fateCheck("Ashfall", "Is the bridge intact?", OddsLevel.likely, null, null)
            .randomEvent()).isNull();
    }

    @Test
    void randomEventLogsContextAndTable() {
        var oracle = open(1, 1, 2);
        var result = oracle.tools.randomEvent("Ashfall", "the bridge", null);
        assertThat(result).isEqualTo(new OracleTools.RandomEventResult("r_0001", "Distant event", null,
            List.of("seek", "ally"), List.of(1, 1, 2), "Random event: Distant event (seek / ally)."));
        var row = oracle.rows("Ashfall").getFirst();
        assertThat(row.tool()).isEqualTo("random_event");
        assertThat(row.inputs()).isEqualTo(Map.of("table", "actions", "context", "the bridge"));
        assertThat(row.dice()).containsExactly(1, 1, 2);
        assertThat(row.result()).isEqualTo(Map.of("focus", "Distant event", "meaning", List.of("seek", "ally")));
    }

    @Test
    void randomEventUsesTheNamedMeaningTable() {   // single: fire, water, earth, air
        assertThat(open(1, 1, 4).tools.randomEvent("Ashfall", null, "single").meaning()).containsExactly("fire", "air");
    }

    @Test
    void randomEventRejectsUnknownTable() {
        var oracle = open();
        assertThatThrownBy(() -> oracle.tools.randomEvent("Ashfall", null, "Actions"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("unknown meaning table 'Actions'; loaded: actions, single");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void fateCheckExceptionalNoWithoutEvent() {   // likely at chaos 5: exceptional no from 94
        var result = open(97).tools.fateCheck("Ashfall", "Is the bridge intact?", OddsLevel.likely, null, null);
        assertThat(result.answer()).isEqualTo(FateAnswer.exceptional_no);
        assertThat(result.randomEventTriggered()).isFalse();
        assertThat(result.summary()).isEqualTo("Exceptional no (97 on likely, chaos 5).");
    }

    @Test
    void fateCheckRejectsBlankQuestionAndMissingOdds() {
        var oracle = open();
        assertThatThrownBy(() -> oracle.tools.fateCheck("Ashfall", "  ", OddsLevel.likely, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("question must not be blank");
        assertThatThrownBy(() -> oracle.tools.fateCheck("Ashfall", "Is it?", null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("odds is required; one of impossible, nearly_impossible, very_unlikely, unlikely, "
                + "fifty_fifty, likely, very_likely, nearly_certain, certain");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void meaningDefaultsToOnePairAndLogsIt() {
        var oracle = open(2, 3);
        assertThat(oracle.tools.meaning("Ashfall", "actions", null, null)).isEqualTo(new OracleTools.MeaningResult(
            "r_0001", "actions", List.of(List.of("hide", "debt")), List.of(2, 3), "actions: hide / debt"));
        var row = oracle.rows("Ashfall").getFirst();
        assertThat(row.inputs()).isEqualTo(Map.of("table", "actions", "count", 1));
        assertThat(row.dice()).containsExactly(2, 3);
        assertThat(row.result()).isEqualTo(Map.of("pairs", List.of(List.of("hide", "debt"))));
    }

    @Test
    void meaningTwoPairsSummary() {
        assertThat(open(1, 2, 3, 4).tools.meaning("Ashfall", "actions", 2, null).summary())
            .isEqualTo("actions: seek / ally; break / border");
    }

    @Test
    void meaningRejectsUnknownTable() {
        var oracle = open();
        assertThatThrownBy(() -> oracle.tools.meaning("Ashfall", "Actions", 1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("unknown meaning table 'Actions'; loaded: actions, single");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void meaningRejectsCountOutsideOneToTwo(int count) {
        var oracle = open();
        assertThatThrownBy(() -> oracle.tools.meaning("Ashfall", "actions", count, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("count must be 1 or 2; got " + count);
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }

    @Test
    void meaningLogsItsQuestion() {
        var oracle = open(2, 3);
        assertThat(oracle.tools.meaning("Ashfall", "actions", null, "Who is watching Jacques?").summary())
            .isEqualTo("actions: hide / debt");
        assertThat(oracle.rows("Ashfall").getFirst().inputs()).isEqualTo(
            Map.of("question", "Who is watching Jacques?", "table", "actions", "count", 1));
    }

    @Test
    void eachCallActsOnTheCampaignItNames() {
        var oracle = TestOracle.withRolls(4, 50);
        oracle.createCampaign("Ashfall");
        oracle.createCampaign("Iron Coast");
        oracle.tools.setChaos("Iron Coast", 7);
        oracle.tools.roll("Ashfall", 1, 6, null, null);
        assertThat(oracle.rows("Ashfall")).extracting(RollRecord::tool).containsExactly("roll");
        assertThat(oracle.rows("Iron Coast")).extracting(RollRecord::tool).containsExactly("set_chaos");
        assertThat(oracle.chaos("Ashfall")).isEqualTo(5);
        assertThat(oracle.tools.fateCheck("Iron Coast", "Is it?", OddsLevel.likely, null, null).chaosFactor())
            .isEqualTo(7);
    }

    @Test
    void oracleToolsNeedAKnownCampaign() {   // spec lifecycle test: a blank or unknown campaign
        var oracle = TestOracle.withRolls(4);
        assertThatThrownBy(() -> oracle.tools.roll(null, 1, 6, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("campaign must not be blank; none exist yet, create one with campaign_create");
        oracle.createCampaign("iron Coast");
        oracle.createCampaign("Ashfall");
        for (var campaign : List.of("Ashfal", " ")) {
            var message = campaign.isBlank() ? "campaign must not be blank; campaigns: Ashfall, iron Coast"
                : "no campaign 'Ashfal'; campaigns: Ashfall, iron Coast";
            for (ThrowingCallable call : List.<ThrowingCallable>of(
                    () -> oracle.tools.roll(campaign, 1, 6, null, null),
                    () -> oracle.tools.fateCheck(campaign, "Is it?", OddsLevel.likely, null, null),
                    () -> oracle.tools.meaning(campaign, "actions", 1, null),
                    () -> oracle.tools.setChaos(campaign, 6),
                    () -> oracle.tools.randomEvent(campaign, null, null))) {
                assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
            }
        }
        assertThat(oracle.rows("Ashfall")).isEmpty();
        assertThat(oracle.rows("iron Coast")).isEmpty();
    }

    @Test
    void aShiftThatTurnsTheAnswerMatchesTheSpecExample() {   // chaos 4: likely yes up to 60, very_likely up to 75
        var oracle = open(62);
        oracle.tools.setChaos("Ashfall", 4);
        assertThat(oracle.tools.fateCheck("Ashfall", "Do I break free before the guards turn?", OddsLevel.likely, 1,
                "Athletics +4"))
            .isEqualTo(new OracleTools.FateCheckResult("r_0002", FateAnswer.yes, List.of(62), 4, false, null,
                new OracleTools.ShiftEffect(OddsLevel.very_likely, FateAnswer.no),
                "Yes (62 on likely +1 = very_likely, chaos 4). Athletics +4 turned a no into a yes."));
        var row = oracle.rows("Ashfall").get(1);
        assertThat(row.inputs()).isEqualTo(Map.of("question", "Do I break free before the guards turn?",
            "odds", "likely", "shift", 1, "reason", "Athletics +4"));
        assertThat(row.result()).isEqualTo(Map.of("answer", "yes", "shifted_odds", "very_likely",
            "without_shift", "no", "chaos_factor", 4, "random_event_triggered", false));
    }

    @Test
    void aShiftThatChangesNothingStillNamesTheOdds() {   // chaos 5: 30 is a yes on likely and on very_likely
        var oracle = open(30);
        assertThat(oracle.tools.fateCheck("Ashfall", "Do I climb the wall?", OddsLevel.likely, 1, " Athletics +4 "))
            .isEqualTo(new OracleTools.FateCheckResult("r_0001", FateAnswer.yes, List.of(30), 5, false, null, null,
                "Yes (30 on likely +1 = very_likely, chaos 5)."));
        var row = oracle.rows("Ashfall").getFirst();
        assertThat(row.inputs()).isEqualTo(Map.of("question", "Do I climb the wall?", "odds", "likely", "shift", 1,
            "reason", "Athletics +4"));
        assertThat(row.result()).isEqualTo(Map.of("answer", "yes", "shifted_odds", "very_likely",
            "without_shift", "yes", "chaos_factor", 5, "random_event_triggered", false));
    }

    @Test
    void exceptionalBandsCount() {   // chaos 5: exceptional yes up to 13 on likely, up to 16 on very_likely
        var result = open(15).tools.fateCheck("Ashfall", "Do I slip past unseen?", OddsLevel.likely, 1, "Stealth +7");
        assertThat(result.answer()).isEqualTo(FateAnswer.exceptional_yes);
        assertThat(result.shiftEffect()).isEqualTo(new OracleTools.ShiftEffect(OddsLevel.very_likely, FateAnswer.yes));
        assertThat(result.summary()).isEqualTo("Exceptional yes (15 on likely +1 = very_likely, chaos 5). "
            + "Stealth +7 turned a yes into an exceptional yes.");
    }

    @Test
    void thePlaytestShiftNamesLikely() {   // chaos 5: unlikely exceptional yes up to 7, likely up to 13
        var result = open(3).tools.fateCheck("Ashfall", "Does he hand over his phone?", OddsLevel.unlikely, 2,
            "Благоговение +2");
        assertThat(result.answer()).isEqualTo(FateAnswer.exceptional_yes);
        assertThat(result.shiftEffect()).isNull();
        assertThat(result.summary()).isEqualTo("Exceptional yes (3 on unlikely +2 = likely, chaos 5).");
    }

    @Test
    void theShiftSentenceComesBeforeTheRandomEvent() {   // chaos 6: likely yes up to 70, unlikely up to 40
        var oracle = open(44, 1, 1, 2);
        oracle.tools.setChaos("Ashfall", 6);
        var result = oracle.tools.fateCheck("Ashfall", "Do I outrun the patrol?", OddsLevel.likely, -2, "Bad knee");
        assertThat(result.answer()).isEqualTo(FateAnswer.no);
        assertThat(result.dice()).containsExactly(44, 1, 1, 2);
        assertThat(result.shiftEffect()).isEqualTo(new OracleTools.ShiftEffect(OddsLevel.unlikely, FateAnswer.yes));
        assertThat(result.summary()).isEqualTo("No (44 on likely -2 = unlikely, chaos 6). "
            + "Bad knee turned a yes into a no. Random event: Distant event (seek / ally).");
    }

    @Test
    void shiftsStopAtTheEnds() {   // chaos 5: nearly_certain yes up to 90, certain up to 95
        var oracle = open(93, 50);
        var past = oracle.tools.fateCheck("Ashfall", "Does the lock give?", OddsLevel.nearly_certain, 3,
            "Lockpicking +11");
        assertThat(past.shiftEffect()).isEqualTo(new OracleTools.ShiftEffect(OddsLevel.certain, FateAnswer.no));
        assertThat(past.summary())
            .isEqualTo("Yes (93 on nearly_certain +3 = certain, chaos 5). Lockpicking +11 turned a no into a yes.");
        var fixed = oracle.tools.fateCheck("Ashfall", "Is the sun up?", OddsLevel.certain, -8, "Awful luck");
        assertThat(fixed.shiftEffect()).isNull();
        assertThat(fixed.summary()).isEqualTo("Yes (50 on certain -8 = certain, chaos 5).");
        assertThat(oracle.rows("Ashfall").get(1).result().get("shifted_odds")).isEqualTo("certain");
    }

    @Test
    void aReasonWithoutAShiftChangesNothing() {
        var oracle = open(30, 30);
        for (var shift : Arrays.asList(0, null)) {
            var result = oracle.tools.fateCheck("Ashfall", "Is it?", OddsLevel.likely, shift, "Stealth +7");
            assertThat(result.shiftEffect()).isNull();
            assertThat(result.summary()).isEqualTo("Yes (30 on likely, chaos 5).");
        }
        assertThat(oracle.rows("Ashfall")).extracting(RollRecord::inputs).containsExactly(
            Map.of("question", "Is it?", "odds", "likely"), Map.of("question", "Is it?", "odds", "likely"));
    }

    @Test
    void shiftNeedsARangeAndAReason() {
        var oracle = open(30);
        for (int shift : new int[] {9, -9}) {
            assertThatThrownBy(() -> oracle.tools.fateCheck("Ashfall", "Is it?", OddsLevel.likely, shift, "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("shift must be -8 to 8; got " + shift);
        }
        for (var reason : Arrays.asList(null, "  ")) {
            assertThatThrownBy(() -> oracle.tools.fateCheck("Ashfall", "Is it?", OddsLevel.likely, 1, reason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason must not be blank when shift is not 0; say what the shift comes from, for example "
                    + "\"Athletics +4\"");
        }
        assertThatThrownBy(() -> oracle.tools.fateCheck("Ashfal", "Is it?", OddsLevel.likely, 9, "x"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("shift must be -8 to 8; got 9");
        assertThat(oracle.rows("Ashfall")).isEmpty();
    }
}
