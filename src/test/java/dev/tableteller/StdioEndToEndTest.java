package dev.tableteller;

import static dev.tableteller.support.McpStdioServer.body;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.support.McpStdioServer;
import dev.tableteller.support.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One server shared by these tests, with an open campaign and a Cyrillic personal pack. */
class StdioEndToEndTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z]+(?:_[a-z]+)+\\b");
    @TempDir static Path dir;
    static McpStdioServer server;

    @BeforeAll
    static void startServer() throws Exception {
        var packs = Files.createDirectories(dir.resolve("packs"));
        Files.writeString(packs.resolve("slova.yaml"), """
            pack: ru
            type: meaning
            name: slova
            die: d2
            entries:
              - { range: [1, 1], value: "тайна" }
              - { range: [2, 2], value: "предательство" }
            """, StandardCharsets.UTF_8);
        TestDatabase.reset();
        server = McpStdioServer.start(dir);
        assertThat(server.callTool("campaign_create", Map.of("name", "E2E")).path("isError").asBoolean(false)).isFalse();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.close();
    }

    @Test
    void listsTheOracleToolsWithLowercaseOdds() {
        var tools = server.request("tools/list", Map.of()).at("/result/tools");
        assertThat(tools.valueStream().map(t -> t.get("name").asString()))
            .containsExactlyInAnyOrder("roll", "fate_check", "meaning", "set_chaos", "campaign_create", "campaign_update",
                "list_add", "list_update", "list_get", "random_event", "scene_check", "end_scene",
                "campaign_open", "journal_write", "journal_get", "journal_search", "roll_history",
                "session_start", "session_end", "tracker_add", "tracker_adjust", "tracker_remove");
        var fate = tools.valueStream().filter(t -> t.get("name").asString().equals("fate_check")).findFirst().orElseThrow();
        assertThat(fate.get("description").asString()).startsWith("Ask the oracle a yes/no question about the story. "
            + "Pick honest odds; for the player character's own action,").contains("or no shift if none fits");
        assertThat(fate.at("/inputSchema/properties/odds/enum").valueStream().map(JsonNode::asString)).containsExactly(
            "impossible", "nearly_impossible", "very_unlikely", "unlikely", "fifty_fifty",
            "likely", "very_likely", "nearly_certain", "certain");
    }

    @Test
    void everyToolButCampaignCreateRequiresCampaign() {
        var tools = server.request("tools/list", Map.of()).at("/result/tools");
        assertThat(tools.valueStream()).hasSize(22).allSatisfy(tool -> {
            var schema = tool.get("inputSchema");
            var required = schema.path("required").valueStream().map(JsonNode::asString).toList();
            if (tool.get("name").asString().equals("campaign_create")) {
                assertThat(schema.at("/properties/campaign").isMissingNode()).isTrue();
            } else {
                assertThat(required).as(tool.get("name").asString()).contains("campaign");
            }
        });
    }

    @Test
    void fateCheckReturnsRollIdAnswerAndDice() {   // no e2e test changes chaos
        var result = server.callTool("fate_check",
            Map.of("campaign", "E2E", "question", "Is the door locked?", "odds", "likely"));
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        var body = body(result);
        assertThat(body.get("roll_id").asString()).matches("r_\\d{4,}");
        assertThat(body.get("answer").asString()).isIn("yes", "no", "exceptional_yes", "exceptional_no");
        assertThat(body.get("dice").get(0).asInt()).isBetween(1, 100);
        assertThat(body.get("chaos_factor").asInt()).isEqualTo(5);
        assertThat(body.has("random_event_triggered")).isTrue();
    }

    @Test
    void fateCheckShiftsOverStdio() {   // the playtests are in Russian: the reason must survive the reply and the log
        var result = server.callTool("fate_check", Map.of("campaign", "E2E", "question", "Успею ли я?",
            "odds", "likely", "shift", 2, "reason", "Ловкость +3"));
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        var body = body(result);
        assertThat(body.get("summary").asString()).contains("on likely +2 = nearly_certain");
        if (body.has("shift_effect")) {
            assertThat(body.get("summary").asString()).contains("Ловкость +3 turned");
        } else {
            assertThat(body.get("summary").asString()).doesNotContain("turned");
        }
        var history = body(server.callTool("roll_history", Map.of("campaign", "E2E", "last_n", 1)));
        assertThat(history.at("/rolls/0/inputs/reason").asString()).isEqualTo("Ловкость +3");
        assertThat(history.at("/rolls/0/inputs/shift").asInt()).isEqualTo(2);
    }

    @Test
    void parallelToolCallsEachGetAResponse() {   // a dropped reply would be a roll the DM never saw
        var results = server.callToolsAtOnce("list_get",
            Collections.nCopies(200, Map.of("campaign", "E2E", "list", "threads")));
        assertThat(results).hasSize(200)
            .allSatisfy(result -> assertThat(result.path("isError").asBoolean(false)).isFalse());
    }

    @Test
    void offFormatOddsAreRejectedWithTheValidValues() {
        var result = server.callTool("fate_check", Map.of("campaign", "E2E", "question", "Is it?", "odds", "Likely"));
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asString()).contains("fifty_fifty");
    }

    @Test
    void badRollRangeIsAnErrorResult() {
        var result = server.callTool("roll", Map.of("campaign", "E2E", "min", 5, "max", 5));
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asString()).contains("min < max");
    }

    @Test
    void rollTakesACountAndAQuestionOverStdio() {   // the question is Russian: it must survive the reply and the log
        var result = server.callTool("roll", Map.of("campaign", "E2E", "min", 1, "max", 10, "count", 3,
            "question", "Кубы Голода"));
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        var body = body(result);
        assertThat(body.has("value")).isFalse();
        assertThat(body.get("values").valueStream().map(JsonNode::asInt)).hasSize(3).allSatisfy(
            value -> assertThat(value).isBetween(1, 10));
        var history = body(server.callTool("roll_history", Map.of("campaign", "E2E", "last_n", 1)));
        assertThat(history.at("/rolls/0/inputs/question").asString()).isEqualTo("Кубы Голода");
        assertThat(history.at("/rolls/0/inputs/count").asInt()).isEqualTo(3);
    }

    @Test
    void cyrillicWordsSurviveStdio() {
        var pair = body(server.callTool("meaning", Map.of("campaign", "E2E", "table", "slova"))).at("/pairs/0");
        assertThat(pair.valueStream().map(JsonNode::asString))
            .hasSize(2)
            .allMatch(word -> Set.of("тайна", "предательство").contains(word));
    }

    @Test
    void journalWriteTakesTagsOverStdio() {   // checks the List<String> schema and the timestamp format
        server.callTool("journal_write", Map.of("campaign", "E2E", "kind", "npc", "title", "Stdio Varo",
            "body", "Runs the inn.", "tags", List.of("inn")));
        var entry = body(server.callTool("journal_get", Map.of("campaign", "E2E", "title", "stdio varo"))).get("entry");
        assertThat(entry.get("tags").get(0).asString()).isEqualTo("inn");
        assertThat(entry.get("created_at").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    @Test
    void renamesOverStdio() {   // new_title must be the wire name, or the rename is dropped; the titles are Russian
        server.callTool("list_add", Map.of("campaign", "E2E", "list", "characters", "title", "Священник с приёма"));
        var listed = server.callTool("list_update",
            Map.of("campaign", "E2E", "list", "characters", "title", "Священник с приёма", "new_title", "Отец Себастьен"));
        assertThat(listed.path("isError").asBoolean(false)).isFalse();
        assertThat(body(listed).get("entries").valueStream().map(entry -> entry.get("title").asString()))
            .contains("Отец Себастьен").doesNotContain("Священник с приёма");

        server.callTool("journal_write", Map.of("campaign", "E2E", "kind", "npc", "title", "Священник с приёма",
            "body", "A priest."));
        var written = server.callTool("journal_write", Map.of("campaign", "E2E", "kind", "npc",
            "title", "Священник с приёма", "body", "Father Sébastien.", "new_title", "Отец Себастьен"));
        assertThat(written.path("isError").asBoolean(false)).isFalse();
        assertThat(body(written).get("summary").asString()).startsWith("Renamed npc 'Священник с приёма' to ");
        var entry = body(server.callTool("journal_get", Map.of("campaign", "E2E", "title", "Отец Себастьен")))
            .get("entry");
        assertThat(entry.get("body").asString()).isEqualTo("Father Sébastien.");
    }

    @Test
    void trackersOverStdio() {   // the name and the reason are Russian: they must survive the reply, the brief and the log
        var added = server.callTool("tracker_add",
            Map.of("campaign", "E2E", "name", "Голод", "value", 1, "min", 0, "max", 5));
        assertThat(added.path("isError").asBoolean(false)).isFalse();
        assertThat(body(added).get("summary").asString()).isEqualTo("Tracking Голод: 1 (0 to 5).");

        var adjusted = server.callTool("tracker_adjust",
            Map.of("campaign", "E2E", "name", "голод", "delta", 1, "reason", "Проверка Пробуждения: 4"));
        assertThat(adjusted.path("isError").asBoolean(false)).isFalse();
        var body = body(adjusted);
        assertThat(body.get("summary").asString()).isEqualTo("Голод: 1 → 2 (+1, Проверка Пробуждения: 4).");
        assertThat(body.get("previous_value").asInt()).isEqualTo(1);
        assertThat(body.get("clamped").asBoolean()).isFalse();

        var tracker = body(server.callTool("campaign_open", Map.of("campaign", "E2E"))).at("/trackers/0");
        assertThat(tracker.get("value").asInt()).isEqualTo(2);
        assertThat(tracker.has("note")).isFalse();

        var history = body(server.callTool("roll_history", Map.of("campaign", "E2E", "last_n", 1)));
        assertThat(history.at("/rolls/0/tool").asString()).isEqualTo("tracker_adjust");
        assertThat(history.at("/rolls/0/inputs/reason").asString()).isEqualTo("Проверка Пробуждения: 4");
    }

    @Test
    void listsTheResourcesAndTheJournalTemplate() {
        var resources = server.request("resources/list", Map.of()).at("/result/resources");
        assertThat(resources.valueStream().map(resource -> resource.get("uri").asString()))
            .containsExactly("oracle://tables");
        var templates = server.request("resources/templates/list", Map.of()).at("/result/resourceTemplates");
        assertThat(templates.valueStream().map(template -> template.get("uriTemplate").asString()))
            .containsExactlyInAnyOrder("campaign://{campaign}/brief", "campaign://{campaign}/journal/{kind}");
    }

    @Test
    void readsABriefByAPercentEncodedName() {
        server.callTool("campaign_create", Map.of("name", "Iron Coast"));
        var brief = server.request("resources/read", Map.of("uri", "campaign://Iron%20Coast/brief"))
            .at("/result/contents/0");
        assertThat(brief.get("uri").asString()).isEqualTo("campaign://Iron%20Coast/brief");
        assertThat(JSON.readTree(brief.get("text").asString()).get("name").asString()).isEqualTo("Iron Coast");
    }

    @Test
    void readsTheBriefAndTablesOverStdio() {
        var brief = server.request("resources/read", Map.of("uri", "campaign://E2E/brief")).at("/result/contents/0");
        assertThat(brief.get("mimeType").asString()).isEqualTo("application/json");
        assertThat(JSON.readTree(brief.get("text").asString()).get("name").asString()).isEqualTo("E2E");
        var tables = server.request("resources/read", Map.of("uri", "oracle://tables")).at("/result/contents/0/text");
        assertThat(JSON.readTree(tables.asString()).valueStream()).anySatisfy(table -> {
            assertThat(table.get("type").asString()).isEqualTo("meaning");
            assertThat(table.get("name").asString()).isEqualTo("slova");
            assertThat(table.get("pack").asString()).isEqualTo("ru");
        });
    }

    @Test
    void readsJournalTitlesThroughTheTemplate() {
        server.callTool("journal_write",
            Map.of("campaign", "E2E", "kind", "npc", "title", "Template Varo", "body", "Runs the inn."));
        var titles = server.request("resources/read", Map.of("uri", "campaign://E2E/journal/npc"))
            .at("/result/contents/0/text");
        assertThat(JSON.readTree(titles.asString()).get("titles").valueStream().map(JsonNode::asString))
            .contains("Template Varo");
    }

    @Test
    void listsThePrompts() {
        var prompts = server.request("prompts/list", Map.of()).at("/result/prompts");
        assertThat(prompts.valueStream().map(prompt -> prompt.get("name").asString()))
            .containsExactlyInAnyOrder("start_session", "end_session", "new_campaign", "game_master",
                "character_sheet");
        var start = prompts.valueStream().filter(prompt -> prompt.get("name").asString().equals("start_session"))
            .findFirst().orElseThrow();
        assertThat(start.get("arguments").size()).isEqualTo(1);
        assertThat(start.at("/arguments/0/name").asString()).isEqualTo("campaign");
        assertThat(start.at("/arguments/0/required").asBoolean()).isTrue();
    }

    @Test
    void systemPromptNamesOnlyWhatTheToolsHave() {   // a renamed tool, parameter or value must not leave it stale
        var known = new HashSet<String>();
        server.request("tools/list", Map.of()).at("/result/tools").valueStream().forEach(tool -> {
            known.add(tool.get("name").asString());
            tool.at("/inputSchema/properties").properties().forEach(property -> {
                known.add(property.getKey());
                property.getValue().path("enum").valueStream().forEach(value -> known.add(value.asString()));
            });
        });
        var prompt = server.request("prompts/get", Map.of("name", "game_master"))
            .at("/result/messages/0/content/text").asString();
        var names = SNAKE_CASE.matcher(prompt).results().map(MatchResult::group).distinct().toList();
        assertThat(names).contains("fate_check", "scene_check", "end_scene", "session_start", "campaign_open",
            "pc_skills", "campaign_update", "new_title", "list_update", "tracker_add", "tracker_adjust",
            "tracker_remove");
        assertThat(names).allSatisfy(name -> assertThat(known).as("tools, parameters and values").contains(name));
    }

    @Test
    void getsAPromptOverStdio() {
        var message = server.request("prompts/get", Map.of("name", "start_session", "arguments",
            Map.of("campaign", "E2E"))).at("/result/messages/0");
        assertThat(message.get("role").asString()).isEqualTo("user");
        assertThat(message.at("/content/text").asString()).contains("Call campaign_open with campaign \"E2E\"",
            "pass campaign \"E2E\" to every tool");
    }

    @Test
    void logsGoToTheLogFile() {
        // logged while the tool beans are built, so before the server can answer initialize
        assertThat(dir.resolve("oracle.log")).content(StandardCharsets.UTF_8)
            .contains("Loaded fate_chart table 'default'");
    }
}
