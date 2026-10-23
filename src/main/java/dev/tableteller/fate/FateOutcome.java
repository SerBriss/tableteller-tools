package dev.tableteller.fate;

import dev.tableteller.table.FateAnswer;
import dev.tableteller.table.OddsLevel;

/** One fate-check roll: {@code answer} is read at {@code shiftedOdds}, {@code withoutShift} at the odds asked. */
public record FateOutcome(int roll, FateAnswer answer, boolean randomEventTriggered, OddsLevel shiftedOdds,
        FateAnswer withoutShift) {

    public boolean shiftChangedTheAnswer() {
        return answer != withoutShift;
    }
}
