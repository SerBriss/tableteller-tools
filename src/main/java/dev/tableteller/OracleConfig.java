package dev.tableteller;

import dev.tableteller.pack.PackLoader;
import dev.tableteller.pack.PackRegistry;
import dev.tableteller.random.GeneratorRandomSource;
import dev.tableteller.random.RandomSource;
import java.security.SecureRandom;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OracleConfig {

    @Bean
    RandomSource randomSource() {
        return new GeneratorRandomSource(new SecureRandom());
    }

    /**
     * Runs each MCP request to completion on the thread that reads stdin. By default tool calls run in parallel on
     * worker threads, and the stdio transport in MCP SDK 2.0.0 fails ("Failed to enqueue message") when two replies
     * are sent at once, which drops replies and can end the session.
     */
    @Bean
    McpSyncServerCustomizer oneRequestAtATime() {
        return spec -> spec.immediateExecution(true);
    }

    @Bean
    PackRegistry packRegistry(@Value("${oracle.packs.dir:}") String packsDir) {
        return PackLoader.forConfiguredDir(packsDir).load();
    }
}
