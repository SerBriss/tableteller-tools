package dev.tableteller.table;

import dev.tableteller.lists.ListName;

/** Which list a random event focus picks its target from. Constants are the {@code target} values in packs. */
public enum FocusTarget {
    character(ListName.characters), thread(ListName.threads);

    private final ListName list;

    FocusTarget(ListName list) {
        this.list = list;
    }

    public ListName list() {
        return list;
    }
}
