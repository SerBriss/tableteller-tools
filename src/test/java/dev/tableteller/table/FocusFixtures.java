package dev.tableteller.table;

import java.util.List;
import java.util.Map;

/** A small d4 focus table named default, for tests. */
public final class FocusFixtures {

    private FocusFixtures() {
    }

    /** 1 Distant event, 2 A character acts (character), 3 A thread moves forward (thread), 4 Trouble for the hero. */
    public static FocusTable focus() {
        var entries = new RangeColumn(List.of(
            new RangeEntry(1, 1, "Distant event"),
            new RangeEntry(2, 2, "A character acts"),
            new RangeEntry(3, 3, "A thread moves forward"),
            new RangeEntry(4, 4, "Trouble for the hero")));
        return new FocusTable("test", "default", 4, entries,
            Map.of("A character acts", FocusTarget.character, "A thread moves forward", FocusTarget.thread));
    }
}
