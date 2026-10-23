package dev.tableteller.table;

import java.util.List;

/** One column of a range table; the pack reader guarantees it covers its die with no overlaps. */
public record RangeColumn(List<RangeEntry> entries) {

    public RangeColumn {
        entries = List.copyOf(entries);
    }

    public String valueFor(int roll) {
        for (var entry : entries) {
            if (roll >= entry.from() && roll <= entry.to()) return entry.value();
        }
        throw new IllegalArgumentException("no entry covers " + roll);
    }
}
