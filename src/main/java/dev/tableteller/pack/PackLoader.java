package dev.tableteller.pack;

import dev.tableteller.table.Table;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads the demo pack from the classpath, then personal packs from a directory. A personal table replaces
 * a demo table with the same type and name; two personal tables with the same type and name are an error.
 */
public class PackLoader {

    private static final Logger log = LoggerFactory.getLogger(PackLoader.class);
    private static final String DEMO_PACK = "classpath:packs/demo/*.yaml";

    private final Path packsDir;
    private final boolean mustExist;
    private final PackReader reader = new PackReader();

    /** Loads from {@code packsDir}; a missing directory means the demo pack alone. */
    public PackLoader(Path packsDir) {
        this(packsDir, false);
    }

    private PackLoader(Path packsDir, boolean mustExist) {
        this.packsDir = packsDir;
        this.mustExist = mustExist;
    }

    /**
     * The loader for the {@code oracle.packs.dir} setting. Unset or blank means {@code ~/.oracle/packs}, which
     * may be missing on a fresh install. A directory someone set must exist: a typo there would otherwise put
     * them on the demo chart without knowing.
     */
    public static PackLoader forConfiguredDir(String configured) {
        if (configured == null || configured.isBlank()) {
            return new PackLoader(Path.of(System.getProperty("user.home"), ".oracle", "packs"), false);
        }
        return new PackLoader(expandHome(configured), true);
    }

    public Path packsDir() {
        return packsDir;
    }

    /** Resolves a leading {@code ~} to the user's home directory; Java does not expand it. */
    public static Path expandHome(String raw) {
        var home = System.getProperty("user.home");
        if (raw.equals("~")) return Path.of(home);
        if (raw.startsWith("~/") || raw.startsWith("~\\")) return Path.of(home, raw.substring(2));
        return Path.of(raw);
    }

    public PackRegistry load() {
        var tables = new LinkedHashMap<TableKey, Loaded>();
        demoFiles().forEach(file -> addUnique(tables, file));

        var personal = new LinkedHashMap<TableKey, Loaded>();
        personalFiles().forEach(file -> addUnique(personal, file));
        personal.forEach((key, loaded) -> {
            var replaced = tables.put(key, loaded);
            if (replaced != null) {
                log.info("{} from {} replaces the one from {}", key, loaded.source(), replaced.source());
            }
        });

        tables.values().forEach(loaded ->
            log.info("Loaded {} (pack {}) from {}", TableKey.of(loaded.table()), loaded.table().pack(), loaded.source()));
        return new PackRegistry(tables.values().stream().map(Loaded::table).toList());
    }

    private void addUnique(Map<TableKey, Loaded> tables, SourceFile file) {
        for (var table : reader.read(file.source(), file.content())) {
            var key = TableKey.of(table);
            var previous = tables.putIfAbsent(key, new Loaded(table, file.source()));
            if (previous != null) {
                throw new PackException("packs", "duplicate " + key + " in " + previous.source() + " and " + file.source());
            }
        }
    }

    private List<SourceFile> demoFiles() {
        try {
            var resources = new PathMatchingResourcePatternResolver().getResources(DEMO_PACK);
            return Arrays.stream(resources)
                .sorted(Comparator.comparing(Resource::getFilename))
                .map(resource -> new SourceFile("classpath:packs/demo/" + resource.getFilename(), demoContent(resource)))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String demoContent(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<SourceFile> personalFiles() {
        if (!Files.isDirectory(packsDir)) {
            if (mustExist) {
                throw new PackException(packsDir.toString(), "the configured packs directory does not exist");
            }
            log.info("No packs directory at {}; using the demo pack only", packsDir);
            return List.of();
        }
        // FOLLOW_LINKS: a symlinked packs directory would otherwise be skipped as a single non-regular entry
        try (var paths = Files.walk(packsDir, FileVisitOption.FOLLOW_LINKS)) {
            return paths.filter(Files::isRegularFile)
                .filter(PackLoader::isPackFile)
                .sorted()
                .map(PackLoader::personalFile)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isPackFile(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    private static SourceFile personalFile(Path path) {
        var source = path.toAbsolutePath().toString();
        try {
            var content = Files.readString(path, StandardCharsets.UTF_8);
            // Windows editors often save UTF-8 with a byte-order mark
            return new SourceFile(source, content.startsWith("﻿") ? content.substring(1) : content);
        } catch (CharacterCodingException e) {
            throw new PackException(source, "not UTF-8 text; save the file as UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record SourceFile(String source, String content) {
    }

    private record Loaded(Table table, String source) {
    }
}
