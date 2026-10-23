package dev.tableteller.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the server as a subprocess, the way an MCP client does, and talks JSON-RPC over its stdin/stdout. Every
 * stdout line must be a JSON-RPC 2.0 message. No -D encoding flags: the tests see what a client sees.
 */
public final class McpStdioServer implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Process process;
    private final Path workDir;
    private final BufferedWriter stdin;
    private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
    private int nextId = 1;

    private McpStdioServer(Process process, Path workDir) {
        this.process = process;
        this.workDir = workDir;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) stdout.add(line);
            } catch (IOException e) {
                // the server exited; nextMessage reports the missing response
            }
        });
    }

    /** Starts the server against the test database and initializes the MCP connection. */
    public static McpStdioServer start(Path workDir, String... extraArgs) {
        var server = new McpStdioServer(launch(workDir, extraArgs), workDir);
        var init = server.request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
            "clientInfo", Map.of("name", "e2e", "version", "1")));
        assertThat(init.at("/result/serverInfo/name").asString()).isEqualTo("tableteller-tools");
        server.notify("notifications/initialized");
        return server;
    }

    /**
     * Starts the process without talking to it. Packs come from {@code workDir/packs} (created if missing), the log
     * goes to {@code workDir/oracle.log}, and the database is the test database unless {@code extraArgs} override
     * a setting ({@code --name=value}; a repeated name would reach Spring as a list).
     */
    public static Process launch(Path workDir, String... extraArgs) {
        try {
            var settings = new LinkedHashMap<String, String>();
            settings.put("oracle.packs.dir", Files.createDirectories(workDir.resolve("packs")).toString());
            settings.put("logging.file.name", workDir.resolve("oracle.log").toString());
            settings.put("spring.datasource.url", TestDatabase.url());
            settings.put("spring.datasource.username", TestDatabase.username());
            settings.put("spring.datasource.password", TestDatabase.password());
            for (var arg : extraArgs) {
                var nameAndValue = arg.substring(2).split("=", 2);
                settings.put(nameAndValue[0], nameAndValue[1]);
            }
            var command = new ArrayList<String>();
            command.add(ProcessHandle.current().info().command().orElseThrow());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("dev.tableteller.TabletellerToolsApplication");
            settings.forEach((name, value) -> command.add("--" + name + "=" + value));
            return new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.appendTo(workDir.resolve("stderr.txt").toFile()))
                .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sends a request and returns the response with the same id; other messages are checked and skipped. */
    public synchronized JsonNode request(String method, Object params) {
        int id = nextId++;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        while (true) {
            var message = nextMessage();
            if (message.path("id").asInt(-1) == id) return message;
        }
    }

    /**
     * Sends every call before reading any response, as a client making parallel tool calls does, and returns the
     * results in call order. A dropped response fails after 30 s of silence.
     */
    public synchronized List<JsonNode> callToolsAtOnce(String name, List<Map<String, Object>> argumentsList) {
        var ids = new ArrayList<Integer>();
        for (var arguments : argumentsList) {
            int id = nextId++;
            ids.add(id);
            send(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)));
        }
        var results = new HashMap<Integer, JsonNode>();
        while (results.size() < ids.size()) {
            var message = nextMessage();
            int id = message.path("id").asInt(-1);
            if (ids.contains(id)) results.put(id, message.get("result"));
        }
        return ids.stream().map(results::get).toList();
    }

    public void notify(String method) {
        send(Map.of("jsonrpc", "2.0", "method", method));
    }

    /** The {@code result} of a tools/call: content, and isError when the tool failed. */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", name, "arguments", arguments)).get("result");
    }

    /** The JSON a successful tool returned, parsed from its text content. */
    public static JsonNode body(JsonNode toolResult) {
        return JSON.readTree(toolResult.at("/content/0/text").asString());
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException e) {
            // already gone
        }
        try {
            // A client ends the session by closing stdin; the server then shuts down and closes its log itself.
            if (process.waitFor(10, TimeUnit.SECONDS)) return;
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(Object message) {
        try {
            stdin.write(JSON.writeValueAsString(message));
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The next stdout line, which must parse as a JSON-RPC 2.0 message; waits at most 30 s. */
    private JsonNode nextMessage() {
        String line;
        try {
            line = stdout.poll(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        if (line == null) return fail("no message from the server within 30 s; stderr: " + stderr());
        JsonNode message;
        try {
            message = JSON.readTree(line);
        } catch (JacksonException e) {
            return fail("stdout line is not JSON: " + line);
        }
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asString())) {
            return fail("stdout line is not JSON-RPC 2.0: " + line);
        }
        return message;
    }

    private String stderr() {
        try {
            return Files.readString(workDir.resolve("stderr.txt"));
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
