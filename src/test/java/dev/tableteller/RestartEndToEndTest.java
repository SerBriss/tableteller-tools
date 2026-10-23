package dev.tableteller;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.support.McpStdioServer;
import dev.tableteller.support.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Restarts and a missing database, as a client would meet them. */
class RestartEndToEndTest {
    @TempDir Path dir;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void stateSurvivesARestart() {
        try (var first = McpStdioServer.start(dir)) {
            first.callTool("campaign_create", Map.of("name", "Persisted"));
            first.callTool("set_chaos", Map.of("campaign", "Persisted", "value", 7));
            first.callTool("list_add", Map.of("campaign", "Persisted", "list", "threads", "title", "Find the heir"));
        }
        try (var second = McpStdioServer.start(dir)) {
            var missing = second.callTool("roll", Map.of("min", 1, "max", 6));   // fails the tool's input schema
            assertThat(missing.get("isError").asBoolean()).isTrue();
            assertThat(missing.at("/content/0/text").asString()).contains("required property 'campaign'");
            var misspelled = second.callTool("roll", Map.of("campaign", "Persistd", "min", 1, "max", 6));
            assertThat(misspelled.get("isError").asBoolean()).isTrue();
            assertThat(misspelled.at("/content/0/text").asString())
                .contains("no campaign 'Persistd'; campaigns: Persisted");
            var rolled = second.callTool("roll", Map.of("campaign", "persisted", "min", 1, "max", 6));
            assertThat(McpStdioServer.body(rolled).get("roll_id").asString()).isEqualTo("r_0002");
            var brief = McpStdioServer.body(second.callTool("campaign_open", Map.of("campaign", "persisted")));
            assertThat(brief.get("chaos_factor").asInt()).isEqualTo(7);
            assertThat(brief.at("/threads/0/title").asString()).isEqualTo("Find the heir");
        }
    }

    @Test
    void openSessionSurvivesARestart() {
        try (var first = McpStdioServer.start(dir)) {
            first.callTool("campaign_create", Map.of("name", "Persisted"));
            first.callTool("session_start", Map.of("campaign", "Persisted"));
        }
        try (var second = McpStdioServer.start(dir)) {
            second.callTool("campaign_open", Map.of("campaign", "Persisted"));
            var ended = second.callTool("session_end",
                Map.of("campaign", "Persisted", "summary", "Mara reached the city.", "open_questions", "none"));
            assertThat(ended.path("isError").asBoolean(false)).isFalse();
            var recap = McpStdioServer.body(second.callTool("session_start", Map.of("campaign", "Persisted")));
            assertThat(recap.at("/last_session/status").asString()).isEqualTo("ended");
            assertThat(recap.at("/last_session/summary").asString()).isEqualTo("Mara reached the city.");
        }
    }

    @Test
    void serverExitsWhenTheDatabaseIsDown() throws Exception {
        var process = McpStdioServer.launch(dir, "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/oracle");
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(process.getInputStream().readAllBytes()).isEmpty();
        assertThat(dir.resolve("oracle.log")).content(StandardCharsets.UTF_8).containsIgnoringCase("connection");
    }
}
