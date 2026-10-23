package dev.tableteller.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The local database setup the README promises: start once, survives reboots, reachable only from this machine. */
class ComposeFileTest {
    @Test
    void databaseComesBackAfterARebootAndListensOnlyLocally() throws Exception {
        var compose = YAMLMapper.builder().build().readTree(Files.readString(Path.of("compose.yaml")));
        var postgres = compose.at("/services/postgres");
        assertThat(postgres.path("restart").asString()).isEqualTo("unless-stopped");
        assertThat(postgres.at("/ports/0").asString()).startsWith("127.0.0.1:5432:");
    }
}
