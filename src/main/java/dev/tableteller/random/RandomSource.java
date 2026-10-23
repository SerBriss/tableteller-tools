package dev.tableteller.random;

/** The only source of random numbers in the server. */
public interface RandomSource {

    /** Returns a whole number from {@code min} to {@code max}, both inclusive; {@code min > max} is an error. */
    int between(int min, int max);
}
