package dev.tableteller.table;

/** One table row: the rolls {@code from}..{@code to} (inclusive) give {@code value}. */
public record RangeEntry(int from, int to, String value) {
}
