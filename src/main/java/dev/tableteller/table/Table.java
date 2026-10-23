package dev.tableteller.table;

/** A loaded table, identified by its type and name. */
public interface Table {

    TableType type();

    String pack();

    String name();
}
