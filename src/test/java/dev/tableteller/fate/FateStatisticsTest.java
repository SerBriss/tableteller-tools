package dev.tableteller.fate;

import static dev.tableteller.table.FateAnswer.exceptional_yes;
import static dev.tableteller.table.FateAnswer.yes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.tableteller.random.GeneratorRandomSource;
import dev.tableteller.table.DemoChartFormula;
import dev.tableteller.table.OddsLevel;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FateStatisticsTest {
    @ParameterizedTest
    @EnumSource(OddsLevel.class)
    void yesRateMatchesTheChartWithinOnePoint(OddsLevel odds) {
        var service = new FateService(new GeneratorRandomSource(new SplittableRandom(20261017L)));
        var chart = DemoChartFormula.chart();
        int yesCount = 0;
        for (int i = 0; i < 100_000; i++) {
            var answer = service.check(chart, odds, 5).answer();
            if (answer == yes || answer == exceptional_yes) yesCount++;
        }
        assertThat(yesCount / 100_000.0).isCloseTo(chart.cell(odds, 5).yes() / 100.0, within(0.01));
    }
}
