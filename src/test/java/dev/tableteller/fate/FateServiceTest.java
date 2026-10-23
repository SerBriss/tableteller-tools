package dev.tableteller.fate;

import static dev.tableteller.table.FateAnswer.exceptional_no;
import static dev.tableteller.table.FateAnswer.no;
import static dev.tableteller.table.FateAnswer.yes;
import static dev.tableteller.table.OddsLevel.certain;
import static dev.tableteller.table.OddsLevel.likely;
import static dev.tableteller.table.OddsLevel.very_likely;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.random.ScriptedRandomSource;
import dev.tableteller.table.DemoChartFormula;
import dev.tableteller.table.FateChart;
import org.junit.jupiter.api.Test;

class FateServiceTest {
    FateChart chart = DemoChartFormula.chart();

    @Test
    void readsTheCellForOddsAndChaos() {           // likely, chaos 6: 14 / 70 / 95
        assertThat(service(70).check(chart, likely, 6).answer()).isEqualTo(yes);
        assertThat(service(71).check(chart, likely, 6).answer()).isEqualTo(no);
        assertThat(service(95).check(chart, likely, 6).answer()).isEqualTo(exceptional_no);
    }

    @Test
    void reportsRandomEventFromTheChartRule() {
        assertThat(service(44).check(chart, likely, 6)).isEqualTo(new FateOutcome(44, yes, true, likely, yes));
        assertThat(service(44).check(chart, likely, 3)).isEqualTo(new FateOutcome(44, yes, false, likely, yes));
    }

    @Test
    void aShiftReadsBothCellsWithOneRoll() {       // chaos 4: likely yes up to 60, very_likely up to 75
        assertThat(service(62).check(chart, likely, 1, 4)).isEqualTo(new FateOutcome(62, yes, false, very_likely, no));
    }

    @Test
    void certainCanStillBeNoAtHundred() {          // certain, chaos 9: yes is clamped to 99
        assertThat(service(100).check(chart, certain, 9).answer()).isEqualTo(no);
    }

    private FateService service(int... rolls) {
        return new FateService(new ScriptedRandomSource(rolls));
    }
}
