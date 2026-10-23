package dev.tableteller.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.campaign.CampaignResolver;
import dev.tableteller.event.EventTarget;
import dev.tableteller.event.RandomEvent;
import dev.tableteller.event.RandomEventService;
import dev.tableteller.fate.FateService;
import dev.tableteller.meaning.MeaningService;
import dev.tableteller.pack.PackRegistry;
import dev.tableteller.random.RandomSource;
import dev.tableteller.state.NewRoll;
import dev.tableteller.state.OracleStateRepository;
import dev.tableteller.state.RollLogRepository;
import dev.tableteller.table.FateAnswer;
import dev.tableteller.table.OddsLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The oracle tools. Each validates its input, then in one transaction locks the row of the campaign it names, does
 * its work and appends exactly one roll-log row there. Rejected input, including an unknown campaign, throws
 * {@link IllegalArgumentException}, which the client sees as an error result, and writes nothing.
 */
@Component
public class OracleTools {

    private static final int ROLL_LIMIT = 1_000_000;
    private static final int POOL_LIMIT = 10;
    private static final int SHIFT_LIMIT = 8;

    private final RandomSource random;
    private final FateService fate;
    private final MeaningService meaning;
    private final RandomEventService events;
    private final PackRegistry packs;
    private final CampaignResolver resolver;
    private final OracleStateRepository state;
    private final RollLogRepository rolls;
    private final TransactionTemplate transactions;

    public OracleTools(RandomSource random, FateService fate, MeaningService meaning, RandomEventService events,
            PackRegistry packs, CampaignResolver resolver, OracleStateRepository state, RollLogRepository rolls,
            TransactionTemplate transactions) {
        this.random = random;
        this.fate = fate;
        this.meaning = meaning;
        this.events = events;
        this.packs = packs;
        this.resolver = resolver;
        this.state = state;
        this.rolls = rolls;
        this.transactions = transactions;
    }

    /** One of {@code value} (a single roll) and {@code values} (a count of 2-10, in the order rolled) is present. */
    public record RollResult(@JsonProperty("roll_id") String rollId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer value,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Integer> values, String summary) {
    }

    public record SetChaosResult(@JsonProperty("roll_id") String rollId,
            @JsonProperty("chaos_factor") int chaosFactor, String summary) {
    }

    public record FateCheckResult(@JsonProperty("roll_id") String rollId, FateAnswer answer, List<Integer> dice,
            @JsonProperty("chaos_factor") int chaosFactor,
            @JsonProperty("random_event_triggered") boolean randomEventTriggered,
            @JsonProperty("random_event") @JsonInclude(JsonInclude.Include.NON_NULL) RandomEvent randomEvent,
            @JsonProperty("shift_effect") @JsonInclude(JsonInclude.Include.NON_NULL) ShiftEffect shiftEffect,
            String summary) {
    }

    /** Only when a shift changed the answer: the odds rolled on, and what the odds asked would have answered. */
    public record ShiftEffect(OddsLevel odds, @JsonProperty("without_shift") FateAnswer withoutShift) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RandomEventResult(@JsonProperty("roll_id") String rollId, String focus, EventTarget target,
            List<String> meaning, List<Integer> dice, String summary) {
    }

    public record MeaningResult(@JsonProperty("roll_id") String rollId, String table, List<List<String>> pairs,
            List<Integer> dice, String summary) {
    }

    @McpTool(name = "roll", description = "Roll an evenly distributed whole number from min to max, inclusive; with "
        + "count, several such numbers, read one by one and never added. Use it whenever the story needs a number, "
        + "instead of choosing one yourself.")
    public RollResult roll(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "Lowest possible value, inclusive") Integer min,
            @McpToolParam(required = true, description = "Highest possible value, inclusive") Integer max,
            @McpToolParam(required = false, description = "How many numbers to roll in the same range, 1-10; "
                + "default 1. They are never added together.") Integer count,
            @McpToolParam(required = false, description = "What the roll is for, e.g. Which city? 1 Rome, 2 Milan. "
                + "Stored, not interpreted") String question) {
        if (min == null || max == null || min >= max || min < -ROLL_LIMIT || max > ROLL_LIMIT) {
            throw new IllegalArgumentException(
                "roll needs whole numbers min < max, both within ±1,000,000; got min=" + min + ", max=" + max);
        }
        int numbers = count == null ? 1 : count;
        if (numbers < 1 || numbers > POOL_LIMIT) {
            throw new IllegalArgumentException("count must be 1-" + POOL_LIMIT + "; got " + count);
        }
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            var values = new ArrayList<Integer>();
            for (int i = 0; i < numbers; i++) values.add(random.between(min, max));
            var inputs = ordered();
            if (question != null && !question.isBlank()) inputs.put("question", question);
            inputs.put("min", min);
            inputs.put("max", max);
            if (numbers == 1) {
                int value = values.getFirst();
                var row = rolls.append(campaignId, new NewRoll("roll", inputs, values, ordered("value", value),
                    current.sceneNumber()));
                return new RollResult(row.rollId(), value, null, "Rolled %d (%d to %d).".formatted(value, min, max));
            }
            inputs.put("count", numbers);
            var row = rolls.append(campaignId, new NewRoll("roll", inputs, values, ordered("values", values),
                current.sceneNumber()));
            var listed = values.stream().map(String::valueOf).collect(Collectors.joining(", "));
            return new RollResult(row.rollId(), null, List.copyOf(values),
                "Rolled %s (%d rolls of %d to %d).".formatted(listed, numbers, min, max));
        });
    }

    @McpTool(name = "set_chaos", description = "Set the chaos factor (1-9) directly, to correct a misjudged scene.")
    public SetChaosResult setChaos(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "New chaos factor, 1-9") Integer value) {
        if (value == null || value < 1 || value > 9) {
            throw new IllegalArgumentException("chaos factor must be 1-9; got " + value);
        }
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            state.saveChaos(campaignId, value);
            var row = rolls.append(campaignId, new NewRoll("set_chaos", ordered("value", value), List.of(),
                ordered("chaos_factor", value, "previous_chaos_factor", current.chaosFactor()), current.sceneNumber()));
            return new SetChaosResult(row.rollId(), value,
                "Chaos factor is now %d (was %d).".formatted(value, current.chaosFactor()));
        });
    }

    @McpTool(name = "fate_check", description = "Ask the oracle a yes/no question about the story. Pick honest odds; "
        + "for the player character's own action, pick them for an ordinary capable person and pass the shift from "
        + "the pc_skills entry that fits, naming it in reason, or no shift if none fits. The server rolls d100 on the "
        + "fate chart at the current chaos factor and reports whether a random event triggered. Narrate the answer "
        + "faithfully and never reroll.")
    public FateCheckResult fateCheck(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "The yes/no question, stored as asked") String question,
            @McpToolParam(required = true, description = "How likely a yes is") OddsLevel odds,
            @McpToolParam(required = false, description = "Levels to move the odds for the player character's own "
                + "action, from the campaign's pc_skills: -8 to 8; omit for none") Integer shift,
            @McpToolParam(required = false, description = "What the shift comes from, for example Athletics +4; "
                + "required when shift is not 0") String reason) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (odds == null) {
            throw new IllegalArgumentException("odds is required; one of "
                + Arrays.stream(OddsLevel.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
        int levels = shift == null ? 0 : shift;
        if (levels < -SHIFT_LIMIT || levels > SHIFT_LIMIT) {
            throw new IllegalArgumentException("shift must be -8 to 8; got " + shift);
        }
        if (levels != 0 && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason must not be blank when shift is not 0; say what the shift "
                + "comes from, for example \"Athletics +4\"");
        }
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            var outcome = fate.check(packs.fateChart(), odds, levels, current.chaosFactor());
            var dice = new ArrayList<>(List.of(outcome.roll()));
            var inputs = ordered("question", question, "odds", odds.name());
            var result = ordered("answer", outcome.answer().name());
            if (levels != 0) {   // logged whether or not the shift changed the answer
                inputs.put("shift", levels);
                inputs.put("reason", reason.trim());
                result.put("shifted_odds", outcome.shiftedOdds().name());
                result.put("without_shift", outcome.withoutShift().name());
            }
            result.put("chaos_factor", current.chaosFactor());
            result.put("random_event_triggered", outcome.randomEventTriggered());
            ShiftEffect effect = null;
            var summary = levels == 0
                ? "%s (%d on %s, chaos %d).".formatted(
                    outcome.answer().label(), outcome.roll(), odds.name(), current.chaosFactor())
                : "%s (%d on %s %+d = %s, chaos %d).".formatted(outcome.answer().label(), outcome.roll(),
                    odds.name(), levels, outcome.shiftedOdds().name(), current.chaosFactor());
            if (outcome.shiftChangedTheAnswer()) {   // otherwise the reply is what it would be without a shift
                effect = new ShiftEffect(outcome.shiftedOdds(), outcome.withoutShift());
                summary += " %s turned %s into %s.".formatted(reason.trim(),
                    outcome.withoutShift().withArticle(), outcome.answer().withArticle());
            }
            RandomEvent event = null;
            if (outcome.randomEventTriggered()) {
                var rolled = events.roll(campaignId, packs.focus(), packs.meaning("actions"));
                event = rolled.event();
                dice.addAll(rolled.dice());
                result.put("random_event", event.toLogValue());
                summary += " Random event: " + event.describe() + ".";
            }
            var row = rolls.append(campaignId, new NewRoll("fate_check", inputs, dice, result, current.sceneNumber()));
            return new FateCheckResult(row.rollId(), outcome.answer(), List.copyOf(dice), current.chaosFactor(),
                outcome.randomEventTriggered(), event, effect, summary);
        });
    }

    @McpTool(name = "random_event", description = "Roll a random event: a focus, a target from the threads or "
        + "characters list when the focus needs one, and a meaning pair.")
    public RandomEventResult randomEvent(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = false, description = "What prompted the event; stored, not interpreted")
            String context,
            @McpToolParam(required = false, description = "Meaning table for the word pair; default actions")
            String table) {
        var tableName = table == null || table.isBlank() ? "actions" : table;
        var meaningTable = packs.meaning(tableName);
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            var rolled = events.roll(campaignId, packs.focus(), meaningTable);
            var event = rolled.event();
            var inputs = ordered("table", tableName);
            if (context != null && !context.isBlank()) inputs.put("context", context);
            var row = rolls.append(campaignId, new NewRoll("random_event", inputs, rolled.dice(), event.toLogValue(),
                current.sceneNumber()));
            return new RandomEventResult(row.rollId(), event.focus(), event.target(), event.meaning(), rolled.dice(),
                "Random event: " + event.describe() + ".");
        });
    }


    @McpTool(name = "meaning", description = "Roll a word pair on a meaning table, such as actions or descriptions, "
        + "to interpret an event. Tell the player which words you got and how you read them.")
    public MeaningResult meaning(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "Meaning table name, for example actions or descriptions")
            String table,
            @McpToolParam(required = false, description = "Number of word pairs, 1 or 2; default 1") Integer count,
            @McpToolParam(required = false, description = "What the words are for, e.g. Who is watching Jacques? "
                + "Stored, not interpreted") String question) {
        int pairCount = count == null ? 1 : count;
        if (pairCount < 1 || pairCount > 2) {
            throw new IllegalArgumentException("count must be 1 or 2; got " + count);
        }
        var meaningTable = packs.meaning(table);
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            var pairs = meaning.roll(meaningTable, pairCount);
            var words = pairs.stream().map(pair -> List.of(pair.first(), pair.second())).toList();
            var dice = pairs.stream().flatMap(pair -> Stream.of(pair.firstRoll(), pair.secondRoll())).toList();
            var inputs = ordered();
            if (question != null && !question.isBlank()) inputs.put("question", question);
            inputs.put("table", table);
            inputs.put("count", pairCount);
            var row = rolls.append(campaignId, new NewRoll("meaning", inputs, dice, ordered("pairs", words),
                current.sceneNumber()));
            var summary = table + ": " + words.stream()
                .map(pair -> pair.get(0) + " / " + pair.get(1))
                .collect(Collectors.joining("; "));
            return new MeaningResult(row.rollId(), table, words, dice, summary);
        });
    }

    /** A map that keeps its keys in the order given, so stored rows read the way the tool's input reads. */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
