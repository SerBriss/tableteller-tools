package dev.tableteller.meaning;

import dev.tableteller.random.RandomSource;
import dev.tableteller.table.MeaningTable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Rolls word pairs on a meaning table: the first word on its first column, the second on its second. */
@Service
public class MeaningService {

    private final RandomSource random;

    public MeaningService(RandomSource random) {
        this.random = random;
    }

    public List<MeaningPair> roll(MeaningTable table, int count) {
        var pairs = new ArrayList<MeaningPair>();
        for (int i = 0; i < count; i++) {
            int firstRoll = random.between(1, table.dieSize());
            int secondRoll = random.between(1, table.dieSize());
            pairs.add(new MeaningPair(firstRoll, table.first().valueFor(firstRoll),
                secondRoll, table.second().valueFor(secondRoll)));
        }
        return pairs;
    }
}
