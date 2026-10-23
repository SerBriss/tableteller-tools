package dev.tableteller.random;

import java.util.random.RandomGenerator;

public final class GeneratorRandomSource implements RandomSource {

    private final RandomGenerator generator;

    public GeneratorRandomSource(RandomGenerator generator) {
        this.generator = generator;
    }

    @Override
    public int between(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max; got min=" + min + ", max=" + max);
        }
        // long bound so that max = Integer.MAX_VALUE cannot overflow
        return (int) generator.nextLong(min, (long) max + 1);
    }
}
