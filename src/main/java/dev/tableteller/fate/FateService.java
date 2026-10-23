package dev.tableteller.fate;

import dev.tableteller.random.RandomSource;
import dev.tableteller.table.FateChart;
import dev.tableteller.table.OddsLevel;
import org.springframework.stereotype.Service;

/** Rolls one d100 fate check against a chart. */
@Service
public class FateService {

    private final RandomSource random;

    public FateService(RandomSource random) {
        this.random = random;
    }

    public FateOutcome check(FateChart chart, OddsLevel odds, int chaosFactor) {
        return check(chart, odds, 0, chaosFactor);
    }

    /** One roll, read at the shifted odds for the answer and at the odds asked for the answer without the shift. */
    public FateOutcome check(FateChart chart, OddsLevel odds, int shift, int chaosFactor) {
        int roll = random.between(1, 100);
        var shifted = odds.shifted(shift);
        return new FateOutcome(roll, chart.cell(shifted, chaosFactor).answer(roll),
            chart.trigger().triggers(roll, chaosFactor), shifted, chart.cell(odds, chaosFactor).answer(roll));
    }
}
