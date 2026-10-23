package dev.tableteller.table;

/** Named random event trigger rules a fate chart can use; constants are the names written in pack files. */
public enum TriggerRule {
    /** 11, 22 … 99 where the repeated digit is at or below the chaos factor; 100 never triggers. */
    doubles_at_or_below_chaos {
        @Override
        public boolean triggers(int roll, int chaosFactor) {
            return roll < 100 && roll % 11 == 0 && roll / 11 <= chaosFactor;
        }
    };

    public abstract boolean triggers(int roll, int chaosFactor);
}
