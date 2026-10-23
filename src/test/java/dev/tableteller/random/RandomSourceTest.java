package dev.tableteller.random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class RandomSourceTest {
    @Test
    void generatorSourceCoversInclusiveRange() {
        var source = new GeneratorRandomSource(new SplittableRandom(42));
        var seen = new TreeSet<Integer>();
        for (int i = 0; i < 1_000; i++) seen.add(source.between(1, 6));
        assertThat(seen).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void generatorSourceHandlesIntegerMaxValue() {
        var source = new GeneratorRandomSource(new SplittableRandom(42));
        for (int i = 0; i < 100; i++)
            assertThat(source.between(Integer.MAX_VALUE - 1, Integer.MAX_VALUE)).isGreaterThanOrEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void generatorSourceRejectsMinAboveMax() {
        var source = new GeneratorRandomSource(new SplittableRandom(42));
        assertThatThrownBy(() -> source.between(6, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scriptedSourceReturnsValuesInOrder() {
        var source = new ScriptedRandomSource(44, 11);
        assertThat(source.between(1, 100)).isEqualTo(44);
        assertThat(source.between(1, 100)).isEqualTo(11);
    }

    @Test
    void scriptedSourceRejectsValueOutsideRequestedRange() {
        assertThatThrownBy(() -> new ScriptedRandomSource(7).between(1, 6))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("7").hasMessageContaining("1-6");
    }

    @Test
    void scriptedSourceFailsWhenExhausted() {
        var source = new ScriptedRandomSource(3);
        source.between(1, 6);
        assertThatThrownBy(() -> source.between(1, 6)).isInstanceOf(IllegalStateException.class);
    }
}
