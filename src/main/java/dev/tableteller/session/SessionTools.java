package dev.tableteller.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.campaign.CampaignResolver;
import dev.tableteller.lists.ListEntry;
import dev.tableteller.lists.ListName;
import dev.tableteller.lists.ListRepository;
import dev.tableteller.scene.Scene;
import dev.tableteller.scene.SceneRepository;
import dev.tableteller.state.OracleStateRepository;
import dev.tableteller.tracker.Tracker;
import dev.tableteller.tracker.TrackerRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Play sessions: session_start gives the recap a new session starts from, session_end records what the DM wrote.
 * Journal-side tools: they write no roll-log rows. Each runs in one transaction that locks the campaign row.
 */
@Component
public class SessionTools {

    private static final int RECAP_SCENES = 3;

    private final SessionRepository sessions;
    private final SceneRepository scenes;
    private final ListRepository lists;
    private final TrackerRepository trackers;
    private final CampaignRepository campaigns;
    private final CampaignResolver resolver;
    private final OracleStateRepository state;
    private final TransactionTemplate transactions;

    public SessionTools(SessionRepository sessions, SceneRepository scenes, ListRepository lists,
            TrackerRepository trackers, CampaignRepository campaigns, CampaignResolver resolver,
            OracleStateRepository state, TransactionTemplate transactions) {
        this.sessions = sessions;
        this.scenes = scenes;
        this.lists = lists;
        this.trackers = trackers;
        this.campaigns = campaigns;
        this.resolver = resolver;
        this.state = state;
        this.transactions = transactions;
    }

    /**
     * What a session starts from. {@code lastSession} is the session just before this one (null for the first);
     * {@code scenes} are the last three, oldest first; the lists hold open threads and active characters;
     * {@code pcSkills} is the campaign's text as the DM wrote it; the trackers come in the order added.
     */
    public record Recap(@JsonProperty("session_id") UUID sessionId, String campaign,
            @JsonProperty("last_session") SessionLog lastSession, @JsonProperty("chaos_factor") int chaosFactor,
            @JsonProperty("scene_number") int sceneNumber, List<Scene> scenes, List<ListEntry> threads,
            List<ListEntry> characters, @JsonProperty("pc_skills") String pcSkills, List<Tracker> trackers,
            String summary) {
    }

    public record SessionEnded(@JsonProperty("session_id") UUID sessionId, String summary) {
    }

    @McpTool(name = "session_start", description = "Start a play session and get the recap: the last session's "
        + "summary and open questions, chaos, both lists, the last 3 scenes, the PC's skills and the trackers. Call "
        + "it first every session, right after campaign_open.")
    public Recap sessionStart(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign) {
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            var current = state.lock(campaignId);
            sessions.findOpen(campaignId).ifPresent(leftOpen -> sessions.abandon(leftOpen.sessionId()));
            var last = sessions.findLatest(campaignId).orElse(null);
            var session = sessions.start(campaignId);
            var campaignRow = campaigns.findById(campaignId).orElseThrow();
            var name = campaignRow.name();
            var lastPart = last == null ? "first session" : switch (last.status()) {
                case ended -> "last session ended";
                case abandoned -> "last session was never ended and is now abandoned, so recap from the scenes";
                case open -> throw new IllegalStateException("session " + last.sessionId() + " is still open");
            };
            var progress = Scene.progress(scenes.findOpen(campaignId).orElse(null), current.sceneNumber());
            var summary = "Session started for '%s': %s; chaos %d; %s."
                .formatted(name, lastPart, current.chaosFactor(), progress);
            return new Recap(session.sessionId(), name, last, current.chaosFactor(), current.sceneNumber(),
                scenes.findLast(campaignId, RECAP_SCENES), lists.live(campaignId, ListName.threads),
                lists.live(campaignId, ListName.characters), campaignRow.pcSkills(), trackers.findAll(campaignId),
                summary);
        });
    }

    // Parameter names are the tool's wire names, so open_questions stays snake_case.
    @McpTool(name = "session_end", description = "End the play session with a summary of what happened and the "
        + "questions the story left open; the next session_start shows them. A scene may stay open.")
    public SessionEnded sessionEnd(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "What happened this session, in a few sentences")
            String summary,
            @McpToolParam(required = true, description = "The questions the story has left open; \"none\" if there "
                + "are none") String open_questions) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        if (open_questions == null || open_questions.isBlank()) {
            throw new IllegalArgumentException("open_questions must not be blank; write \"none\" if nothing is open");
        }
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            state.lock(campaignId);
            var session = sessions.findOpen(campaignId).orElseThrow(() ->
                new IllegalArgumentException("no session is open; call session_start first, then session_end"));
            sessions.end(session.sessionId(), summary, open_questions);
            var name = campaigns.findById(campaignId).orElseThrow().name();
            return new SessionEnded(session.sessionId(), scenes.findOpen(campaignId)
                .map(open -> "Session ended for '%s'; scene %d is still open and carries over."
                    .formatted(name, open.number()))
                .orElse("Session ended for '%s'.".formatted(name)));
        });
    }
}
