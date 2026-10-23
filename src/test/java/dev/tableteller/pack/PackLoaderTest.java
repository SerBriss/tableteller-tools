package dev.tableteller.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tableteller.table.DemoChartFormula;
import dev.tableteller.table.FocusTarget;
import dev.tableteller.table.OddsLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackLoaderTest {
    @TempDir Path packs;

    @Test
    void demoPackLoadsWhenPacksDirIsMissing() {
        var registry = new PackLoader(packs.resolve("missing")).load();
        assertThat(registry.fateChart().pack()).isEqualTo("demo");
        assertThat(registry.fateChart().name()).isEqualTo("default");
        assertThat(registry.meaningTableNames()).containsExactly("actions", "descriptions");
    }

    @Test
    void demoChartMatchesTheFormulaInAll81Cells() {
        var chart = new PackLoader(packs).load().fateChart();
        for (var odds : OddsLevel.values())
            for (int chaos = 1; chaos <= 9; chaos++)
                assertThat(chart.cell(odds, chaos)).isEqualTo(DemoChartFormula.cell(odds, chaos));
    }

    @Test
    void demoMeaningTablesHaveTheirWordLists() {
        var registry = new PackLoader(packs).load();
        assertThat(registry.meaning("actions").first().valueFor(1)).isEqualTo("seek");
        assertThat(registry.meaning("actions").second().valueFor(100)).isEqualTo("shrine");
        assertThat(registry.meaning("descriptions").first().valueFor(50)).isEqualTo("oddly");
        assertThat(registry.meaning("descriptions").second().valueFor(96)).isEqualTo("sweet");
        for (var name : List.of("actions", "descriptions"))
            for (int n = 1; n <= 100; n++) {
                assertThat(registry.meaning(name).first().valueFor(n)).isNotBlank();
                assertThat(registry.meaning(name).second().valueFor(n)).isNotBlank();
            }
    }

    @Test
    void demoFocusTableHasItsTargets() {
        var focus = new PackLoader(packs).load().focus();
        assertThat(focus.entries().valueFor(25)).isEqualTo("A character acts");
        assertThat(focus.targetFor("A character acts")).contains(FocusTarget.character);
        assertThat(focus.entries().valueFor(59)).isEqualTo("A thread moves forward");
        assertThat(focus.targetFor("A thread moves forward")).contains(FocusTarget.thread);
        assertThat(focus.entries().valueFor(100)).isEqualTo("The current scene shifts");
        assertThat(focus.targetFor("The current scene shifts")).isEmpty();
    }

    @Test
    void personalTableReplacesDemoTableWithSameTypeAndName() throws IOException {
        Files.writeString(packs.resolve("my-actions.yaml"), meaningYaml("mine", "actions"));
        var registry = new PackLoader(packs).load();
        assertThat(registry.meaning("actions").pack()).isEqualTo("mine");
        assertThat(registry.meaning("descriptions").pack()).isEqualTo("demo");
        assertThat(registry.fateChart().pack()).isEqualTo("demo");
    }

    @Test
    void duplicatePersonalTablesStopLoading() throws IOException {
        Files.writeString(packs.resolve("a.yaml"), meaningYaml("one", "actions"));
        Files.createDirectories(packs.resolve("sub"));
        Files.writeString(packs.resolve("sub/b.yaml"), meaningYaml("two", "actions"));
        assertThatThrownBy(() -> new PackLoader(packs).load())
            .isInstanceOf(PackException.class)
            .hasMessageContaining("duplicate meaning table 'actions'")
            .hasMessageContaining("a.yaml").hasMessageContaining("b.yaml");
    }

    @Test
    void invalidPersonalPackStopsLoadingAndNamesTheFile() throws IOException {
        Files.writeString(packs.resolve("broken.yaml"), meaningYaml("mine", "broken").replace("die: d2", "die: 2d6"));
        assertThatThrownBy(() -> new PackLoader(packs).load())
            .isInstanceOf(PackException.class)
            .hasMessageContaining("broken.yaml").hasMessageContaining("die must look like d100");
    }

    @Test
    void ignoresNonPackFilesAndReadsNestedJson() throws IOException {
        Files.writeString(packs.resolve("README.md"), "# my notes");
        Files.writeString(packs.resolve("notes.txt"), "not a pack");
        Files.createDirectories(packs.resolve("nested"));
        Files.writeString(packs.resolve("nested/extra.json"), """
            {"pack": "mine", "type": "meaning", "name": "extra", "die": "d2",
             "entries": [{"range": [1, 2], "value": "x"}]}
            """);
        assertThat(new PackLoader(packs).load().meaningTableNames()).containsExactly("actions", "descriptions", "extra");
    }

    @Test
    void readsUtf8WithByteOrderMarkAndCyrillic() throws IOException {
        var yaml = """
            pack: ru
            type: meaning
            name: slova
            die: d2
            entries:
              - { range: [1, 1], value: "тайна" }
              - { range: [2, 2], value: "предательство" }
            """.getBytes(StandardCharsets.UTF_8);
        var withBom = new byte[yaml.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(yaml, 0, withBom, 3, yaml.length);
        Files.write(packs.resolve("slova.yaml"), withBom);
        assertThat(new PackLoader(packs).load().meaning("slova").first().valueFor(1)).isEqualTo("тайна");
    }

    @Test
    void nonUtf8PackStopsLoadingAndNamesTheFile() throws IOException {
        Files.write(packs.resolve("utf16.yaml"), meaningYaml("mine", "wide").getBytes(StandardCharsets.UTF_16));
        assertThatThrownBy(() -> new PackLoader(packs).load())
            .isInstanceOf(PackException.class)
            .hasMessageContaining("utf16.yaml").hasMessageContaining("not UTF-8 text");
    }

    @Test
    void followsASymlinkedPacksDirectory() throws IOException {
        var real = Files.createDirectories(packs.resolve("real"));
        Files.writeString(real.resolve("extra.yaml"), meaningYaml("mine", "extra"));
        var link = packs.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "creating symlinks needs admin rights or Developer Mode here: " + e.getMessage());
        }
        assertThat(new PackLoader(link).load().meaningTableNames()).contains("extra");
    }

    @Test
    void configuredPacksDirThatDoesNotExistStopsLoading() {
        var typo = packs.resolve("typo").toString();
        assertThatThrownBy(() -> PackLoader.forConfiguredDir(typo).load())
            .isInstanceOf(PackException.class)
            .hasMessageContaining(typo).hasMessageContaining("does not exist");
    }

    @Test
    void unsetPacksDirMeansTheDefaultUnderHome() {
        assertThat(PackLoader.forConfiguredDir("").packsDir())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".oracle", "packs"));
    }

    @Test
    void expandsLeadingTildeToHome() {
        var home = Path.of(System.getProperty("user.home"));
        assertThat(PackLoader.expandHome("~")).isEqualTo(home);
        assertThat(PackLoader.expandHome("~/packs")).isEqualTo(home.resolve("packs"));
        assertThat(PackLoader.expandHome("~\\packs")).isEqualTo(home.resolve("packs"));
        assertThat(PackLoader.expandHome(packs.toString())).isEqualTo(packs);
    }

    /** A valid one-column d2 meaning table. */
    private static String meaningYaml(String pack, String name) {
        return """
            pack: %s
            type: meaning
            name: %s
            die: d2
            entries:
              - { range: [1, 1], value: "a" }
              - { range: [2, 2], value: "b" }
            """.formatted(pack, name);
    }
}
