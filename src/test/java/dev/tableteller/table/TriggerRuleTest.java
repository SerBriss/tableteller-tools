package dev.tableteller.table;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TriggerRuleTest {
    @ParameterizedTest
    @CsvSource({"11,1,true", "22,1,false", "22,2,true", "55,5,true", "66,5,false",
                "99,9,true", "100,9,false", "54,9,false", "1,9,false"})
    void doublesAtOrBelowChaos(int roll, int chaos, boolean expected) {
        assertThat(TriggerRule.doubles_at_or_below_chaos.triggers(roll, chaos)).isEqualTo(expected);
    }
}
