package dev.tableteller.meaning;

import static dev.tableteller.meaning.MeaningFixtures.actions;
import static dev.tableteller.meaning.MeaningFixtures.single;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.random.ScriptedRandomSource;
import org.junit.jupiter.api.Test;

class MeaningServiceTest {
    @Test
    void oneColumnTableRollsBothWordsOnIt() {
        assertThat(service(1, 4).roll(single(), 1)).containsExactly(new MeaningPair(1, "fire", 4, "air"));
    }

    @Test
    void twoColumnTableRollsTheSecondWordOnTheSecondColumn() {
        assertThat(service(2, 3).roll(actions(), 1)).containsExactly(new MeaningPair(2, "hide", 3, "debt"));
    }

    @Test
    void countTwoRollsTwoPairsInOrder() {
        assertThat(service(1, 2, 3, 4).roll(actions(), 2)).containsExactly(
            new MeaningPair(1, "seek", 2, "ally"), new MeaningPair(3, "break", 4, "border"));
    }

    private MeaningService service(int... rolls) {
        return new MeaningService(new ScriptedRandomSource(rolls));
    }
}
