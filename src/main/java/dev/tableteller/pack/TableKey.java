package dev.tableteller.pack;

import dev.tableteller.table.Table;
import dev.tableteller.table.TableType;

/** Tables are identified by type and name; the pack name is informational. */
record TableKey(TableType type, String name) {

    static TableKey of(Table table) {
        return new TableKey(table.type(), table.name());
    }

    @Override
    public String toString() {
        return type + " table '" + name + "'";
    }
}
