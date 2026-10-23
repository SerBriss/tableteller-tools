package dev.tableteller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.journal.JournalKind;
import dev.tableteller.journal.JournalRepository;
import dev.tableteller.lists.ListEntry;
import dev.tableteller.lists.ListName;
import dev.tableteller.lists.ListRepository;
import dev.tableteller.scene.Scene;
import dev.tableteller.scene.SceneRepository;
import dev.tableteller.session.SessionLog;
import dev.tableteller.session.SessionRepository;
import dev.tableteller.session.SessionStatus;
import dev.tableteller.state.RollLogRepository;
import dev.tableteller.state.RollRecord;
import dev.tableteller.support.McpStdioServer;
import dev.tableteller.support.TestDatabase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The first playtest's bug: Claude Desktop runs one server for all its chats, so two chats playing two campaigns
 * share one stdio connection. Each chat names its own campaign in every call, and its rows must stay there.
 */
class TwoChatsEndToEndTest {
    @TempDir Path dir;
    final CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    final RollLogRepository rolls = new RollLogRepository(TestDatabase.jdbc());
    final SceneRepository scenes = new SceneRepository(TestDatabase.jdbc());
    final ListRepository lists = new ListRepository(TestDatabase.jdbc());
    final JournalRepository journal = new JournalRepository(TestDatabase.jdbc());
    final SessionRepository sessions = new SessionRepository(TestDatabase.jdbc());

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    /** One chat: every call it makes names its own campaign, and none may fail. */
    record Chat(McpStdioServer server, String campaign) {
        JsonNode call(String tool, Object... namesAndValues) {
            var arguments = new LinkedHashMap<String, Object>();
            arguments.put("campaign", campaign);
            for (int i = 0; i < namesAndValues.length; i += 2) {
                arguments.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
            var result = server.callTool(tool, arguments);
            assertThat(result.path("isError").asBoolean(false))
                .as("%s in %s: %s", tool, campaign, result.at("/content/0/text").asString())
                .isFalse();
            return McpStdioServer.body(result);
        }
    }

    @Test
    void twoChatsOnOneServerKeepTheirCampaignsApart() {
        try (var server = McpStdioServer.start(dir)) {
            server.callTool("campaign_create", Map.of("name", "Ashfall"));
            var first = new Chat(server, "Ashfall");
            first.call("session_start");
            first.call("scene_check", "expected_setup", "Mara reaches the gate");

            // the second chat creates its campaign while the first has a scene open, as in the playtest
            var created = McpStdioServer.body(server.callTool("campaign_create", Map.of("name", "Iron Coast")));
            var second = new Chat(server, created.get("campaign_id").asString());
            second.call("session_start");

            first.call("fate_check", "question", "Is the gate guarded?", "odds", "likely");
            second.call("scene_check", "expected_setup", "Ilsa boards the ship");
            first.call("journal_write", "kind", "location", "title", "North gate", "body", "Iron-bound.");
            second.call("journal_write", "kind", "npc", "title", "Captain Orr", "body", "Owns the ship.");
            first.call("list_add", "list", "threads", "title", "Find the heir");
            second.call("set_chaos", "value", 7);
            // the server's rolls are real here, so the scene may have been altered: actual_setup is always allowed
            first.call("end_scene", "summary", "Mara slipped past.", "pc_in_control", true,
                "actual_setup", "Mara slips through the gate");
            second.call("roll", "min", 1, "max", 6);
            first.call("session_end", "summary", "Mara got in.", "open_questions", "none");
        }

        var ashfall = campaigns.findByName("Ashfall").orElseThrow();
        var ironCoast = campaigns.findByName("Iron Coast").orElseThrow();
        assertThat(rolls.findAll(ashfall.id())).extracting(RollRecord::tool)
            .containsExactly("scene_check", "fate_check", "end_scene");
        assertThat(rolls.findAll(ironCoast.id())).extracting(RollRecord::tool)
            .containsExactly("scene_check", "set_chaos", "roll");
        assertThat(scenes.findAll(ashfall.id()))
            .extracting(Scene::number, Scene::actualSetup, Scene::summary, Scene::open)
            .containsExactly(tuple(1, "Mara slips through the gate", "Mara slipped past.", false));
        assertThat(scenes.findAll(ironCoast.id())).extracting(Scene::number, Scene::expectedSetup, Scene::open)
            .containsExactly(tuple(1, "Ilsa boards the ship", true));
        assertThat(ashfall.chaosFactor()).isEqualTo(4);
        assertThat(ironCoast.chaosFactor()).isEqualTo(7);
        assertThat(lists.live(ashfall.id(), ListName.threads)).extracting(ListEntry::title)
            .containsExactly("Find the heir");
        assertThat(lists.live(ironCoast.id(), ListName.threads)).isEmpty();
        assertThat(journal.titles(ashfall.id(), JournalKind.location)).containsExactly("North gate");
        assertThat(journal.titles(ashfall.id(), JournalKind.npc)).isEmpty();
        assertThat(journal.titles(ironCoast.id(), JournalKind.npc)).containsExactly("Captain Orr");
        assertThat(sessions.findAll(ashfall.id())).extracting(SessionLog::status).containsExactly(SessionStatus.ended);
        assertThat(sessions.findAll(ironCoast.id())).extracting(SessionLog::status).containsExactly(SessionStatus.open);
    }

    @Test
    void parallelCallsOnTwoCampaignsLandInTheirOwn() {   // each call locks only its own campaign's row
        try (var server = McpStdioServer.start(dir)) {
            server.callTool("campaign_create", Map.of("name", "Ashfall"));
            server.callTool("campaign_create", Map.of("name", "Iron Coast"));
            var calls = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 200; i++) {
                calls.add(Map.of("campaign", i % 2 == 0 ? "Ashfall" : "Iron Coast", "min", 1, "max", 6));
            }
            assertThat(server.callToolsAtOnce("roll", calls)).hasSize(200)
                .allSatisfy(result -> assertThat(result.path("isError").asBoolean(false)).isFalse());
        }
        assertThat(rolls.findAll(id("Ashfall"))).hasSize(100);
        assertThat(rolls.findAll(id("Iron Coast"))).hasSize(100);
    }

    private UUID id(String name) {
        return campaigns.findByName(name).orElseThrow().id();
    }
}
