package dev.tableteller.scene;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.random.ScriptedRandomSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SceneServiceTest {
    @ParameterizedTest
    @CsvSource({"5,5,altered", "4,5,interrupted", "6,5,expected", "10,9,expected", "9,9,altered", "1,1,altered",
                "2,1,expected"})
    void d10AtOrBelowChaosAltersOnOddAndInterruptsOnEven(int roll, int chaos, SceneResult expected) {
        assertThat(new SceneService(new ScriptedRandomSource(roll)).check(chaos))
            .isEqualTo(new SceneService.SceneCheck(roll, expected));
    }
}
