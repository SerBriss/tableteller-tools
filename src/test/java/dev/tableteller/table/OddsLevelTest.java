package dev.tableteller.table;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OddsLevelTest {

    @ParameterizedTest
    @CsvSource({"likely,1,very_likely", "fifty_fifty,2,very_likely", "very_likely,2,certain", "nearly_certain,3,certain",
        "fifty_fifty,-2,very_unlikely", "unlikely,-8,impossible", "likely,0,likely"})
    void shiftedMovesAndStopsAtTheEnds(OddsLevel odds, int levels, OddsLevel shifted) {
        assertThat(odds.shifted(levels)).isEqualTo(shifted);
    }

    @Test
    void impossibleAndCertainNeverMove() {
        assertThat(OddsLevel.impossible.shifted(8)).isEqualTo(OddsLevel.impossible);
        assertThat(OddsLevel.certain.shifted(-8)).isEqualTo(OddsLevel.certain);
    }
}
