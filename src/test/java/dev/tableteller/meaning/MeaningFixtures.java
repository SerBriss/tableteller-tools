package dev.tableteller.meaning;

import dev.tableteller.table.MeaningTable;
import dev.tableteller.table.RangeColumn;
import dev.tableteller.table.RangeEntry;
import java.util.ArrayList;

/** Small d4 meaning tables for tests. */
public final class MeaningFixtures {

    private MeaningFixtures() {
    }

    /** One column: fire, water, earth, air. */
    public static MeaningTable single() {
        var column = column("fire", "water", "earth", "air");
        return new MeaningTable("test", "single", 4, column, column);
    }

    /** Two columns: seek, hide, break, guard / secret, ally, debt, border. */
    public static MeaningTable actions() {
        return new MeaningTable("test", "actions", 4,
            column("seek", "hide", "break", "guard"), column("secret", "ally", "debt", "border"));
    }

    private static RangeColumn column(String... words) {
        var entries = new ArrayList<RangeEntry>();
        for (int i = 0; i < words.length; i++) entries.add(new RangeEntry(i + 1, i + 1, words[i]));
        return new RangeColumn(entries);
    }
}
