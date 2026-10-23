package dev.tableteller.table;

/** A meaning table; a one-column table rolls both words of a pair on {@code first}, so {@code second == first}. */
public record MeaningTable(String pack, String name, int dieSize, RangeColumn first, RangeColumn second)
        implements Table {

    @Override
    public TableType type() {
        return TableType.meaning;
    }
}
