package dev.tableteller.pack;

import dev.tableteller.table.DemoChartFormula;
import dev.tableteller.table.OddsLevel;

/** Builds pack YAML for tests. */
final class PackYaml {

    private PackYaml() {
    }

    /** A complete fate chart whose cells come from {@link DemoChartFormula}, one line per cell. */
    static String fateChart(String pack, String name) {
        var yaml = new StringBuilder("""
            pack: %s
            type: fate_chart
            name: %s
            die: d100
            random_event: { rule: doubles_at_or_below_chaos }
            cells:
            """.formatted(pack, name));
        for (var odds : OddsLevel.values()) {
            yaml.append("  ").append(odds).append(":\n");
            for (int chaos = 1; chaos <= 9; chaos++) {
                var cell = DemoChartFormula.cell(odds, chaos);
                yaml.append("    %d: { exceptional_yes: %d, yes: %d, exceptional_no: %d }\n"
                    .formatted(chaos, cell.exceptionalYes(), cell.yes(), cell.exceptionalNo()));
            }
        }
        return yaml.toString();
    }
}
