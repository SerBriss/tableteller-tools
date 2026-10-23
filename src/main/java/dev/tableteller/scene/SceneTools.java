package dev.tableteller.scene;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.campaign.CampaignResolver;
import dev.tableteller.event.RandomEvent;
import dev.tableteller.event.RandomEventService;
import dev.tableteller.pack.PackRegistry;
import dev.tableteller.state.NewRoll;
import dev.tableteller.state.OracleStateRepository;
import dev.tableteller.state.RollLogRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scene tools. Oracle tools like the ones in OracleTools: each validates its input, then in one transaction
 * locks the row of the campaign it names, changes the scene and state, and appends exactly one roll-log row.
 */
@Component
public class SceneTools {

    private final SceneService sceneService;
    private final RandomEventService events;
    private final PackRegistry packs;
    private final CampaignResolver resolver;
    private final OracleStateRepository state;
    private final SceneRepository scenes;
    private final RollLogRepository rolls;
    private final TransactionTemplate transactions;

    public SceneTools(SceneService sceneService, RandomEventService events, PackRegistry packs,
            CampaignResolver resolver, OracleStateRepository state, SceneRepository scenes, RollLogRepository rolls,
            TransactionTemplate transactions) {
        this.sceneService = sceneService;
        this.events = events;
        this.packs = packs;
        this.resolver = resolver;
        this.state = state;
        this.scenes = scenes;
        this.rolls = rolls;
        this.transactions = transactions;
    }

    public record SceneCheckResult(@JsonProperty("roll_id") String rollId, @JsonProperty("scene_number") int sceneNumber,
            SceneResult result, List<Integer> dice, @JsonProperty("chaos_factor") int chaosFactor,
            @JsonProperty("random_event") @JsonInclude(JsonInclude.Include.NON_NULL) RandomEvent randomEvent,
            String summary) {
    }

    public record EndSceneResult(@JsonProperty("roll_id") String rollId, @JsonProperty("scene_number") int sceneNumber,
            @JsonProperty("chaos_factor") int chaosFactor, String summary) {
    }

    // Parameter names are the tools' wire names, so they stay snake_case.
    @McpTool(name = "scene_check", description = "Start the next scene: state the expected setup; the server rolls d10 "
        + "against chaos and says whether it runs as expected, altered or interrupted.")
    public SceneCheckResult sceneCheck(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "What you expect the scene to be") String expected_setup) {
        if (expected_setup == null || expected_setup.isBlank()) {
            throw new IllegalArgumentException("expected_setup must not be blank");
        }
        var setup = expected_setup.trim();
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            scenes.findOpen(campaignId).ifPresent(open -> {
                throw new IllegalArgumentException("scene %d is still open (\"%s\"); end it with end_scene first"
                    .formatted(open.number(), open.setup()));
            });
            int number = current.sceneNumber() + 1;
            var check = sceneService.check(current.chaosFactor());
            var dice = new ArrayList<>(List.of(check.roll()));
            var result = new LinkedHashMap<String, Object>();
            result.put("scene_number", number);
            result.put("result", check.result().name());
            result.put("chaos_factor", current.chaosFactor());
            var summary = switch (check.result()) {
                case expected -> "Scene %d starts as expected (%d vs chaos %d).";
                case altered -> "Scene %d is altered (%d vs chaos %d): change the expected setup, "
                    + "then pass actual_setup to end_scene.";
                case interrupted -> "Scene %d is interrupted (%d vs chaos %d).";
            };
            summary = summary.formatted(number, check.roll(), current.chaosFactor());
            RandomEvent event = null;
            if (check.result() == SceneResult.interrupted) {
                var rolled = events.roll(campaignId, packs.focus(), packs.meaning("actions"));
                event = rolled.event();
                dice.addAll(rolled.dice());
                result.put("random_event", event.toLogValue());
                summary += " Random event: " + event.describe() + ".";
            }
            scenes.open(campaignId, number, setup, check.result(), check.result() == SceneResult.expected ? setup : null);
            state.saveSceneNumber(campaignId, number);
            var inputs = new LinkedHashMap<String, Object>();
            inputs.put("expected_setup", setup);
            var row = rolls.append(campaignId, new NewRoll("scene_check", inputs, dice, result, number));
            return new SceneCheckResult(row.rollId(), number, check.result(), List.copyOf(dice), current.chaosFactor(),
                event, summary);
        });
    }

    @McpTool(name = "end_scene", description = "End the open scene: summarize it and say honestly whether the PC was in "
        + "control; chaos goes down 1 if so, up 1 if not.")
    public EndSceneResult endScene(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "What happened in the scene") String summary,
            @McpToolParam(required = true, description = "Was the player character in control of the scene?")
            Boolean pc_in_control,
            @McpToolParam(required = false, description = "What the scene really was; required after an altered or "
                + "interrupted scene check") String actual_setup) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        if (pc_in_control == null) {
            throw new IllegalArgumentException("pc_in_control is required (true or false)");
        }
        boolean actualGiven = actual_setup != null && !actual_setup.isBlank();
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            var open = scenes.findOpen(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("no scene is open; start one with scene_check"));
            if (open.checkResult() != SceneResult.expected && !actualGiven) {
                throw new IllegalArgumentException("scene %d was %s; pass actual_setup with what the scene really was"
                    .formatted(open.number(), open.checkResult()));
            }
            int chaos = Math.clamp(current.chaosFactor() + (pc_in_control ? -1 : 1), 1, 9);
            scenes.close(campaignId, open.number(), actualGiven ? actual_setup.trim() : open.actualSetup(), summary);
            state.saveChaos(campaignId, chaos);
            var inputs = new LinkedHashMap<String, Object>();
            inputs.put("summary", summary);
            inputs.put("pc_in_control", pc_in_control);
            if (actualGiven) inputs.put("actual_setup", actual_setup);
            var result = new LinkedHashMap<String, Object>();
            result.put("scene_number", open.number());
            result.put("chaos_factor", chaos);
            result.put("previous_chaos_factor", current.chaosFactor());
            var row = rolls.append(campaignId, new NewRoll("end_scene", inputs, List.of(), result, open.number()));
            return new EndSceneResult(row.rollId(), open.number(), chaos, "Scene %d closed. Chaos %d → %d (PC %s)."
                .formatted(open.number(), current.chaosFactor(), chaos, pc_in_control ? "in control" : "not in control"));
        });
    }
}
