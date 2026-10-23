package dev.tableteller.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.table.DemoChartFormula;
import dev.tableteller.table.FateChart;
import dev.tableteller.table.FocusTable;
import dev.tableteller.table.FocusTarget;
import dev.tableteller.table.MeaningTable;
import dev.tableteller.table.OddsLevel;
import dev.tableteller.table.Table;
import dev.tableteller.table.TriggerRule;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PackReaderTest {
    PackReader reader = new PackReader();

    static final String COLORS = """
        pack: test
        type: meaning
        name: colors
        die: d4
        entries:
          - { range: [1, 2], value: "red" }
          - { range: [3, 3], value: "green" }
          - { range: [4, 4], value: "blue" }
        """;

    static final String CHART = PackYaml.fateChart("test", "default");

    @Test
    void readsOneColumnMeaningTable() {
        var table = (MeaningTable) single(reader.read("test.yaml", COLORS));
        assertThat(table.pack()).isEqualTo("test");
        assertThat(table.name()).isEqualTo("colors");
        assertThat(table.dieSize()).isEqualTo(4);
        assertThat(table.first().valueFor(2)).isEqualTo("red");
        assertThat(table.second().valueFor(4)).isEqualTo("blue");
    }

    @Test
    void readsTwoColumnMeaningTable() {
        var table = (MeaningTable) single(reader.read("test.yaml", COLORS + """
            entries_2:
              - { range: [1, 4], value: "bright" }
            """));
        assertThat(table.first().valueFor(1)).isEqualTo("red");
        assertThat(table.second().valueFor(1)).isEqualTo("bright");
    }

    @Test
    void readsFocusTable() {
        assertThat(single(reader.read("test.yaml", COLORS.replace("type: meaning", "type: focus"))))
            .isInstanceOf(FocusTable.class);
    }

    @Test
    void readsFocusTargets() {
        var focus = (FocusTable) single(reader.read("focus.yaml", """
            pack: test
            type: focus
            name: default
            die: d4
            entries:
              - { range: [1, 1], value: "Distant event" }
              - { range: [2, 2], value: "A character acts", target: character }
              - { range: [3, 4], value: "A thread moves forward", target: thread }
            """));
        assertThat(focus.targetFor("A character acts")).contains(FocusTarget.character);
        assertThat(focus.targetFor("A thread moves forward")).contains(FocusTarget.thread);
        assertThat(focus.targetFor("Distant event")).isEmpty();
    }

    @Test
    void readsFateChart() {
        var chart = (FateChart) single(reader.read("chart.yaml", CHART));
        assertThat(chart.trigger()).isEqualTo(TriggerRule.doubles_at_or_below_chaos);
        for (var odds : OddsLevel.values())
            for (int chaos = 1; chaos <= 9; chaos++)
                assertThat(chart.cell(odds, chaos)).isEqualTo(DemoChartFormula.cell(odds, chaos));
    }

    @Test
    void readsJson() {
        var json = """
            {"pack": "test", "type": "meaning", "name": "colors", "die": "d4",
             "entries": [{"range": [1, 2], "value": "red"}, {"range": [3, 3], "value": "green"},
                         {"range": [4, 4], "value": "blue"}]}
            """;
        assertThat(reader.read("test.json", json)).isEqualTo(reader.read("test.yaml", COLORS));
    }

    @Test
    void readsEveryYamlDocumentInAFile() {
        var tables = reader.read("both.yaml", COLORS + "---\n" + COLORS.replace("name: colors", "name: shades"));
        assertThat(tables).extracting(Table::name).containsExactly("colors", "shades");
    }

    @ParameterizedTest(name = "{index}: {1}")
    @MethodSource("invalidPacks")
    void rejectsInvalidPack(String yaml, String expected) {
        assertThatThrownBy(() -> reader.read("test.yaml", yaml))
            .isInstanceOf(PackException.class)
            .hasMessageStartingWith("test.yaml: ")
            .hasMessageContaining(expected);
    }

    static Stream<Arguments> invalidPacks() {
        return Stream.of(
            Arguments.of(COLORS.replace("type: meaning", "type: oracle"),
                "unknown type 'oracle'; expected meaning, focus or fate_chart"),
            Arguments.of(COLORS.replace("type: meaning", "type: fate_check"),
                "type fate_check (2d10 fate check) is not supported yet"),
            Arguments.of(COLORS.replace("name: colors\n", ""), "missing 'name'"),
            Arguments.of(COLORS.replace("die: d4", "die: 2d6"), "die must look like d100; got '2d6'"),
            Arguments.of(COLORS.replace("  - { range: [4, 4], value: \"blue\" }\n", ""), "entries: 4 is not covered"),
            Arguments.of(COLORS.replace("[3, 3]", "[2, 3]"), "entries: [2, 3] overlaps [1, 2]"),
            Arguments.of(COLORS.replace("[4, 4]", "[4, 5]"), "entries: [4, 5] is outside d4"),
            Arguments.of(COLORS.replace("[3, 3]", "[3, 2]"), "entries: [3, 2] runs backwards"),
            Arguments.of(COLORS.replace("value: \"red\"", "value: \"\""), "entries: [1, 2] has no value"),
            Arguments.of(COLORS.replace("type: meaning", "type: focus") + "entries_2:\n  - { range: [1, 4], value: \"x\" }\n",
                "entries_2 is not allowed for focus"),
            Arguments.of(COLORS + "random_event: { rule: doubles_at_or_below_chaos }\n",
                "random_event is not allowed for meaning"),
            Arguments.of(COLORS.replace("entries:", "entires:"), "entires"),
            Arguments.of(CHART.replace("die: d100", "die: d10"), "fate_chart die must be d100"),
            Arguments.of(CHART.replace("rule: doubles_at_or_below_chaos", "rule: sevens"),
                "unknown random_event rule 'sevens'; expected doubles_at_or_below_chaos"),
            Arguments.of(CHART.replaceAll("(?m)^  very_likely:\\n(?:    .*\\n){9}", ""), "cells: missing very_likely"),
            Arguments.of(CHART + "  probable:\n    1: { exceptional_yes: 0, yes: 50, exceptional_no: 101 }\n",
                "cells: unknown odds level 'probable'"),
            Arguments.of(CHART.replaceAll("(?m)(^  likely:\\n(?:    .*\\n){6})    7: .*\\n", "$1"),
                "cells.likely: missing chaos 7"),
            Arguments.of(CHART.replace("  likely:\n", "  likely:\n    10: { exceptional_yes: 1, yes: 50, exceptional_no: 101 }\n"),
                "cells.likely: chaos 10 is outside 1-9"),
            Arguments.of(CHART.replace("5: { exceptional_yes: 13, yes: 65, exceptional_no: 94 }",
                    "5: { exceptional_yes: 14, yes: 13, exceptional_no: 94 }"),
                "cells.likely.5: thresholds must satisfy"),
            Arguments.of(CHART + "entries:\n  - { range: [1, 100], value: \"x\" }\n",
                "entries is not allowed for fate_chart"),
            Arguments.of(COLORS + "entries:\n  - { range: [1, 4], value: \"x\" }\n",
                "Duplicate Object property \"entries\""),
            Arguments.of(CHART.replace("  likely:\n", "  likely:\n    5: { exceptional_yes: 1, yes: 50, exceptional_no: 101 }\n"),
                "Duplicate Object property \"5\""),
            Arguments.of(COLORS.replace("type: meaning", "type: focus")
                    .replace("value: \"green\"", "value: \"green\", target: npc"),
                "entries: [3, 3] has unknown target 'npc'; expected character or thread"),
            Arguments.of(COLORS.replace("value: \"green\"", "value: \"green\", target: character"),
                "target is only allowed in focus tables"));
    }

    private static Table single(List<Table> tables) {
        assertThat(tables).hasSize(1);
        return tables.getFirst();
    }
}
