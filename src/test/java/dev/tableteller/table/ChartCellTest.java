package dev.tableteller.table;

import static dev.tableteller.table.FateAnswer.exceptional_no;
import static dev.tableteller.table.FateAnswer.exceptional_yes;
import static dev.tableteller.table.FateAnswer.no;
import static dev.tableteller.table.FateAnswer.yes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChartCellTest {
    @Test
    void likelyAtChaosFiveBands() {               // 13 / 65 / 94
        var cell = new ChartCell(13, 65, 94);
        assertThat(cell.answer(1)).isEqualTo(exceptional_yes);
        assertThat(cell.answer(13)).isEqualTo(exceptional_yes);
        assertThat(cell.answer(14)).isEqualTo(yes);
        assertThat(cell.answer(65)).isEqualTo(yes);
        assertThat(cell.answer(66)).isEqualTo(no);
        assertThat(cell.answer(93)).isEqualTo(no);
        assertThat(cell.answer(94)).isEqualTo(exceptional_no);
        assertThat(cell.answer(100)).isEqualTo(exceptional_no);
    }

    @Test
    void zeroAndHundredOneMeanTheExceptionalResultCannotOccur() {
        var cell = new ChartCell(0, 1, 101);
        assertThat(cell.answer(1)).isEqualTo(yes);
        assertThat(cell.answer(100)).isEqualTo(no);
    }

    @ParameterizedTest
    @CsvSource({"14,13,94", "-1,5,90", "0,101,101", "0,65,65", "0,65,102"})
    void rejectsUnorderedThresholds(int exceptionalYes, int yes, int exceptionalNo) {
        assertThatThrownBy(() -> new ChartCell(exceptionalYes, yes, exceptionalNo))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thresholds must satisfy");
    }
}
