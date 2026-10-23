package dev.tableteller.tracker;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.campaign.CampaignResolver;
import dev.tableteller.state.NewRoll;
import dev.tableteller.state.OracleStateRepository;
import dev.tableteller.state.RollLogRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tracker tools: named whole numbers a campaign keeps, such as Hunger or ammunition. Each validates its input,
 * then in one transaction locks the row of the campaign it names, changes the tracker and appends exactly one
 * roll-log row there, like {@code set_chaos}. They roll nothing, so the row has no dice. Rejected input, including an
 * unknown campaign or tracker, throws {@link IllegalArgumentException}, which the client sees as an error result, and
 * writes nothing.
 */
@Component
public class TrackerTools {

    private static final int LIMIT = 1_000_000;

    private final TrackerRepository trackers;
    private final CampaignResolver resolver;
    private final OracleStateRepository state;
    private final RollLogRepository rolls;
    private final TransactionTemplate transactions;

    public TrackerTools(TrackerRepository trackers, CampaignResolver resolver, OracleStateRepository state,
            RollLogRepository rolls, TransactionTemplate transactions) {
        this.trackers = trackers;
        this.resolver = resolver;
        this.state = state;
        this.rolls = rolls;
        this.transactions = transactions;
    }

    /** The tracker as it is after an add, or as it was when removed. */
    public record TrackerResult(@JsonProperty("roll_id") String rollId, Tracker tracker, String summary) {
    }

    public record AdjustResult(@JsonProperty("roll_id") String rollId, Tracker tracker,
            @JsonProperty("previous_value") int previousValue, boolean clamped, String summary) {
    }

    @McpTool(name = "tracker_add", description = "Start tracking a number the story keeps, such as Hunger, "
        + "ammunition or a countdown, once the player agrees. Give it limits if it has them, and put the rule for "
        + "how it changes in note.")
    public TrackerResult trackerAdd(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "What is tracked, e.g. Hunger; one tracker per name in the "
                + "campaign, ignoring case") String name,
            @McpToolParam(required = true, description = "Its value now, a whole number") Integer value,
            @McpToolParam(required = false, description = "The lowest it can go, if it has a lowest") Integer min,
            @McpToolParam(required = false, description = "The highest it can go, if it has a highest") Integer max,
            @McpToolParam(required = false, description = "How it changes, e.g. the house rule; shown in the brief "
                + "and the recap, never read by the server") String note) {
        var trimmed = checkedName(name);
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        checkWithinLimit("value", value);
        if (min != null) checkWithinLimit("min", min);
        if (max != null) checkWithinLimit("max", max);
        if (min != null && max != null && min >= max) {
            throw new IllegalArgumentException("min must be less than max; got min=" + min + ", max=" + max);
        }
        if (min != null && max != null && (value < min || value > max)) {
            throw new IllegalArgumentException("value %d is outside %d to %d".formatted(value, min, max));
        }
        if (min != null && value < min) {
            throw new IllegalArgumentException("value %d is below the min, %d".formatted(value, min));
        }
        if (max != null && value > max) {
            throw new IllegalArgumentException("value %d is above the max, %d".formatted(value, max));
        }
        var kept = note == null || note.isBlank() ? null : note;
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            trackers.find(campaignId, trimmed).ifPresent(taken -> {
                throw new IllegalArgumentException(("'%s' is already tracked (%s); change it with tracker_adjust, or "
                    + "tracker_remove it to start over").formatted(taken.name(), held(taken)));
            });
            var tracker = new Tracker(trimmed, value, min, max, kept);
            trackers.add(campaignId, tracker);
            var inputs = ordered("name", trimmed, "value", value);
            if (min != null) inputs.put("min", min);
            if (max != null) inputs.put("max", max);
            if (kept != null) inputs.put("note", kept);
            var row = rolls.append(campaignId, new NewRoll("tracker_add", inputs, List.of(), new LinkedHashMap<>(inputs),
                current.sceneNumber()));
            return new TrackerResult(row.rollId(), tracker,
                "Tracking %s: %d%s.".formatted(trimmed, value, tracker.limits()));
        });
    }

    @McpTool(name = "tracker_adjust", description = "Change a tracked number by delta and say what changed it; "
        + "announce the change to the player. It stops at the tracker's limits and says so.")
    public AdjustResult trackerAdjust(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "The tracker's name, matched ignoring case") String name,
            @McpToolParam(required = true, description = "How much to add; negative to take away; not 0")
            Integer delta,
            @McpToolParam(required = true, description = "What changed it, e.g. Rouse check 4") String reason) {
        var trimmed = checkedName(name);
        if (delta == null || delta == 0) {
            throw new IllegalArgumentException("delta must be a whole number other than 0; got " + delta);
        }
        checkWithinLimit("delta", delta);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "reason must not be blank; say what changed the number, for example \"Rouse check 4\"");
        }
        var why = reason.trim();
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            var tracker = tracked(campaignId, trimmed);
            int before = tracker.value();
            int wanted = before + delta;   // both are within ±1,000,000, so this cannot overflow
            int after = wanted;
            String stoppedAt = null;
            if (tracker.max() != null && wanted > tracker.max()) {
                after = tracker.max();
                stoppedAt = "max";
            } else if (tracker.min() != null && wanted < tracker.min()) {
                after = tracker.min();
                stoppedAt = "min";
            } else if (Math.abs(wanted) > LIMIT) {   // only a side without a limit gets here
                throw new IllegalArgumentException(
                    "that would make %s %d, past ±1,000,000".formatted(tracker.name(), wanted));
            }
            trackers.setValue(campaignId, trimmed, after);
            boolean clamped = stoppedAt != null;
            var row = rolls.append(campaignId, new NewRoll("tracker_adjust",
                ordered("name", trimmed, "delta", delta, "reason", why), List.of(),
                ordered("name", tracker.name(), "value", after, "previous_value", before, "clamped", clamped),
                current.sceneNumber()));
            var summary = "%s: %d → %d (%+d, %s).".formatted(tracker.name(), before, after, delta, why);
            if (clamped) summary += " Stopped at the %s, %d.".formatted(stoppedAt, after);
            return new AdjustResult(row.rollId(),
                new Tracker(tracker.name(), after, tracker.min(), tracker.max(), tracker.note()), before, clamped,
                summary);
        });
    }

    @McpTool(name = "tracker_remove", description = "Stop tracking a number the story no longer needs. Its history "
        + "stays in the roll log.")
    public TrackerResult trackerRemove(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "The tracker's name, matched ignoring case") String name) {
        var trimmed = checkedName(name);
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            var tracker = tracked(campaignId, trimmed);
            trackers.remove(campaignId, trimmed);
            var row = rolls.append(campaignId, new NewRoll("tracker_remove", ordered("name", trimmed), List.of(),
                ordered("name", tracker.name(), "value", tracker.value()), current.sceneNumber()));
            return new TrackerResult(row.rollId(), tracker,
                "Stopped tracking %s at %d.".formatted(tracker.name(), tracker.value()));
        });
    }

    private static String checkedName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim();
    }

    private static void checkWithinLimit(String field, int number) {
        if (number < -LIMIT || number > LIMIT) {
            throw new IllegalArgumentException(field + " must be within ±1,000,000; got " + number);
        }
    }

    /** The campaign's tracker with this name, or an error that lists the names that are tracked. */
    private Tracker tracked(UUID campaignId, String name) {
        return trackers.find(campaignId, name).orElseThrow(() -> {
            var tracked = trackers.findAll(campaignId);
            return new IllegalArgumentException(tracked.isEmpty()
                ? "nothing is tracked yet; start with tracker_add"
                : "no tracker '%s'; tracked: %s".formatted(name,
                    tracked.stream().map(Tracker::name).collect(Collectors.joining(", "))));
        });
    }

    /** What a message shows of a tracker: {@code 1, 0 to 5}, {@code 12, min 0}, {@code 7, max 7} or just {@code 4}. */
    private static String held(Tracker tracker) {
        var limits = tracker.limits();   // " (0 to 5)", or "" without limits
        return limits.isEmpty() ? String.valueOf(tracker.value())
            : tracker.value() + ", " + limits.substring(2, limits.length() - 1);
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
