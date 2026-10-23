package dev.tableteller.state;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.campaign.CampaignResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Reads the roll log back, for audits. A journal-side tool: it writes no roll-log row itself. */
@Component
public class RollHistoryTools {

    private static final int DEFAULT_LAST = 20;
    private static final int MAX_LAST = 100;

    private final RollLogRepository rolls;
    private final CampaignResolver resolver;

    public RollHistoryTools(RollLogRepository rolls, CampaignResolver resolver) {
        this.rolls = rolls;
        this.resolver = resolver;
    }

    public record RollRow(@JsonProperty("roll_id") String rollId, String tool, Map<String, Object> inputs,
            List<Integer> dice, Map<String, Object> result, @JsonProperty("scene_number") int sceneNumber,
            @JsonProperty("created_at") Instant createdAt) {

        public static RollRow of(RollRecord record) {
            return new RollRow(record.rollId(), record.tool(), record.inputs(), record.dice(), record.result(),
                record.sceneNumber(), record.createdAt());
        }
    }

    public record HistoryResult(List<RollRow> rolls, String summary) {
    }

    // Parameter names are the tool's wire names, so last_n stays snake_case.
    @McpTool(name = "roll_history", description = "Show logged rolls, oldest first: the last N (default 20, at most "
        + "100) or every roll in one scene. Use it to audit results or answer 'what did we roll?'.")
    public HistoryResult rollHistory(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = false, description = "How many of the latest rolls, 1-100; default 20")
            Integer last_n,
            @McpToolParam(required = false, description = "A scene number, for every roll in that scene; 0 is before "
                + "the first scene") Integer scene) {
        if (last_n != null && scene != null) {
            throw new IllegalArgumentException("pass last_n or scene, not both");
        }
        if (last_n != null && (last_n < 1 || last_n > MAX_LAST)) {
            throw new IllegalArgumentException("last_n must be 1-" + MAX_LAST + "; got " + last_n);
        }
        if (scene != null && scene < 0) {
            throw new IllegalArgumentException("scene must be 0 or more; got " + scene);
        }
        var campaignId = resolver.resolve(campaign);
        if (scene != null) {
            var rows = rows(rolls.findByScene(campaignId, scene));
            return new HistoryResult(rows, "Scene " + scene + ": " + switch (rows.size()) {
                case 0 -> "no rolls.";
                case 1 -> "1 roll, " + rows.getFirst().rollId() + ".";
                default -> rows.size() + " rolls, " + span(rows) + ".";
            });
        }
        var rows = rows(rolls.findLast(campaignId, last_n == null ? DEFAULT_LAST : last_n));
        return new HistoryResult(rows, switch (rows.size()) {
            case 0 -> "No rolls yet.";
            case 1 -> "The last roll: " + rows.getFirst().rollId() + ".";
            default -> "The last " + rows.size() + " rolls: " + span(rows) + ".";
        });
    }

    private static List<RollRow> rows(List<RollRecord> records) {
        return records.stream().map(RollRow::of).toList();
    }

    private static String span(List<RollRow> rows) {
        return rows.getFirst().rollId() + " to " + rows.getLast().rollId();
    }
}
