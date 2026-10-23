package dev.tableteller.pack;

import dev.tableteller.table.FateChart;
import dev.tableteller.table.FocusTable;
import dev.tableteller.table.MeaningTable;
import dev.tableteller.table.Table;
import dev.tableteller.table.TableType;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The tables in play after packs are combined, looked up by type and name. */
public class PackRegistry {

    private final Map<TableKey, Table> tables = new HashMap<>();
    private final FateChart fateChart;
    private final FocusTable focus;

    public PackRegistry(Collection<? extends Table> tables) {
        tables.forEach(table -> this.tables.put(TableKey.of(table), table));
        if (!(this.tables.get(new TableKey(TableType.fate_chart, "default")) instanceof FateChart chart)) {
            throw new IllegalStateException("no fate_chart named 'default'");
        }
        this.fateChart = chart;
        if (!(this.tables.get(new TableKey(TableType.focus, "default")) instanceof FocusTable focusTable)) {
            throw new IllegalStateException("no focus table named 'default'");
        }
        this.focus = focusTable;
    }

    /** The table every random event rolls its focus on: the {@code focus} table named {@code default}. */
    public FocusTable focus() {
        return focus;
    }

    /** The chart every fate check uses: the {@code fate_chart} named {@code default}. */
    public FateChart fateChart() {
        return fateChart;
    }

    public MeaningTable meaning(String name) {
        if (tables.get(new TableKey(TableType.meaning, name)) instanceof MeaningTable table) {
            return table;
        }
        throw new IllegalArgumentException(
            "unknown meaning table '" + name + "'; loaded: " + String.join(", ", meaningTableNames()));
    }

    /** Every table in play, sorted by type name, then table name. */
    public List<Table> tables() {
        return tables.values().stream()
            .sorted(Comparator.comparing((Table table) -> table.type().name()).thenComparing(Table::name))
            .toList();
    }

    public List<String> meaningTableNames() {
        return tables.keySet().stream()
            .filter(key -> key.type() == TableType.meaning)
            .map(TableKey::name)
            .sorted()
            .toList();
    }
}
