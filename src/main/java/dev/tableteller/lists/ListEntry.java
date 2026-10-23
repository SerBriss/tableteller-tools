package dev.tableteller.lists;

/** One list line; weight 1-3 is how many list slots it takes. */
public record ListEntry(String title, int weight, ListStatus status) {
}
