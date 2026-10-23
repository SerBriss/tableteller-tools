package dev.tableteller.random;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** Returns the given numbers in order, so a test can roll exactly what it needs. */
public final class ScriptedRandomSource implements RandomSource {

    private final Deque<Integer> values = new ArrayDeque<>();

    public ScriptedRandomSource(int... values) {
        Arrays.stream(values).forEach(this.values::add);
    }

    @Override
    public int between(int min, int max) {
        if (values.isEmpty()) {
            throw new IllegalStateException("no scripted values left for a roll of " + min + "-" + max);
        }
        int value = values.removeFirst();
        if (value < min || value > max) {
            throw new IllegalStateException("scripted value " + value + " is outside the requested range " + min + "-" + max);
        }
        return value;
    }
}
