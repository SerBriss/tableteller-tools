package dev.tableteller.table;

/**
 * One fate chart cell. A d100 roll at or below {@code exceptionalYes} is an exceptional yes, at or below
 * {@code yes} a yes, at or above {@code exceptionalNo} an exceptional no, and anything else a no.
 */
public record ChartCell(int exceptionalYes, int yes, int exceptionalNo) {

    public ChartCell {
        if (exceptionalYes < 0 || exceptionalYes > yes || yes > 100 || yes >= exceptionalNo || exceptionalNo > 101) {
            throw new IllegalArgumentException(
                "thresholds must satisfy 0 <= exceptional_yes <= yes <= 100 and yes < exceptional_no <= 101");
        }
    }

    public FateAnswer answer(int roll) {
        if (roll <= exceptionalYes) return FateAnswer.exceptional_yes;
        if (roll <= yes) return FateAnswer.yes;
        if (roll >= exceptionalNo) return FateAnswer.exceptional_no;
        return FateAnswer.no;
    }
}
