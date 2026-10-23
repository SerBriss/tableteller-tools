package dev.tableteller.meaning;

/** One word pair and the two rolls that chose it. */
public record MeaningPair(int firstRoll, String first, int secondRoll, String second) {
}
