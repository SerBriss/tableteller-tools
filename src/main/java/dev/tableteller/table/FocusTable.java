package dev.tableteller.table;

import java.util.Map;
import java.util.Optional;

/** A random event focus table; {@code targets} maps a focus value to the list its target comes from. */
public record FocusTable(String pack, String name, int dieSize, RangeColumn entries, Map<String, FocusTarget> targets)
        implements Table {

    public FocusTable {
        targets = Map.copyOf(targets);
    }

    public Optional<FocusTarget> targetFor(String focus) {
        return Optional.ofNullable(targets.get(focus));
    }

    @Override
    public TableType type() {
        return TableType.focus;
    }
}
