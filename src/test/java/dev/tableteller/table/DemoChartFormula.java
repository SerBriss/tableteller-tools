package dev.tableteller.table;

import static dev.tableteller.table.OddsLevel.certain;
import static dev.tableteller.table.OddsLevel.fifty_fifty;
import static dev.tableteller.table.OddsLevel.impossible;
import static dev.tableteller.table.OddsLevel.likely;
import static dev.tableteller.table.OddsLevel.nearly_certain;
import static dev.tableteller.table.OddsLevel.nearly_impossible;
import static dev.tableteller.table.OddsLevel.unlikely;
import static dev.tableteller.table.OddsLevel.very_likely;
import static dev.tableteller.table.OddsLevel.very_unlikely;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The demo fate chart formula from docs/SPEC.md, used to check the demo pack and as a test chart. */
public final class DemoChartFormula {   // public: tests in fate, pack and tools use it
    private static final Map<OddsLevel, Integer> BASE = Map.of(
        impossible, 5, nearly_impossible, 10, very_unlikely, 20, unlikely, 35, fifty_fifty, 50,
        likely, 65, very_likely, 80, nearly_certain, 90, certain, 95);

    private DemoChartFormula() {
    }

    public static ChartCell cell(OddsLevel odds, int chaos) {
        int yes = Math.clamp(BASE.get(odds) + 5 * (chaos - 5), 1, 99);
        return new ChartCell(yes / 5, yes, 101 - (100 - yes) / 5);
    }

    public static FateChart chart() {
        var cells = new EnumMap<OddsLevel, List<ChartCell>>(OddsLevel.class);
        for (var odds : OddsLevel.values()) {
            var column = new ArrayList<ChartCell>();
            for (int chaos = 1; chaos <= 9; chaos++) column.add(cell(odds, chaos));
            cells.put(odds, column);
        }
        return new FateChart("demo", "default", TriggerRule.doubles_at_or_below_chaos, cells);
    }
}
