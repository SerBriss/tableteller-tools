package dev.tableteller.table;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A d100 fate chart: one cell per odds level and chaos factor 1–9 (list index = chaos − 1). */
public record FateChart(String pack, String name, TriggerRule trigger, Map<OddsLevel, List<ChartCell>> cells)
        implements Table {

    public FateChart {
        var copy = new EnumMap<OddsLevel, List<ChartCell>>(OddsLevel.class);
        for (var odds : OddsLevel.values()) {
            var column = cells.get(odds);
            if (column == null || column.size() != 9) {
                throw new IllegalArgumentException("fate chart needs nine cells for " + odds);
            }
            copy.put(odds, List.copyOf(column));
        }
        cells = Collections.unmodifiableMap(copy);
    }

    public ChartCell cell(OddsLevel odds, int chaosFactor) {
        return cells.get(odds).get(chaosFactor - 1);
    }

    @Override
    public TableType type() {
        return TableType.fate_chart;
    }
}
