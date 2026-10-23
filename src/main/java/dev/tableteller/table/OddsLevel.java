package dev.tableteller.table;

/**
 * The nine odds levels. Constants are lowercase because Spring AI writes {@code Enum.name()}
 * into the tool schema, and pack files use the same names as chart keys.
 */
public enum OddsLevel {
    impossible, nearly_impossible, very_unlikely, unlikely, fifty_fifty, likely, very_likely, nearly_certain, certain;

    /** These odds moved {@code levels} steps, stopping at either end; impossible and certain never move. */
    public OddsLevel shifted(int levels) {
        if (this == impossible || this == certain) return this;
        var all = values();
        return all[Math.clamp((long) ordinal() + levels, 0, all.length - 1)];
    }
}
