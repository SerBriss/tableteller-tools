package dev.tableteller.event;

import dev.tableteller.lists.ListName;
import dev.tableteller.lists.ListRepository;
import dev.tableteller.meaning.MeaningService;
import dev.tableteller.random.RandomSource;
import dev.tableteller.table.FocusTable;
import dev.tableteller.table.MeaningTable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Rolls a random event: a focus, a weighted target when the focus needs one, and one meaning pair. */
@Service
public class RandomEventService {

    private final RandomSource random;
    private final MeaningService meaning;
    private final ListRepository lists;

    public RandomEventService(RandomSource random, MeaningService meaning, ListRepository lists) {
        this.random = random;
        this.meaning = meaning;
        this.lists = lists;
    }

    public RolledEvent roll(UUID campaignId, FocusTable focus, MeaningTable meaningTable) {
        var dice = new ArrayList<Integer>();
        int focusRoll = random.between(1, focus.dieSize());
        dice.add(focusRoll);
        var focusValue = focus.entries().valueFor(focusRoll);
        var target = focus.targetFor(focusValue).map(kind -> pick(campaignId, kind.list(), dice)).orElse(null);
        var pair = meaning.roll(meaningTable, 1).getFirst();
        dice.add(pair.firstRoll());
        dice.add(pair.secondRoll());
        return new RolledEvent(new RandomEvent(focusValue, target, List.of(pair.first(), pair.second())),
            List.copyOf(dice));
    }

    /** Picks a live entry, each counted as many times as its weight; an empty list asks for a new one. */
    private EventTarget pick(UUID campaignId, ListName list, List<Integer> dice) {
        var live = lists.live(campaignId, list);
        if (live.isEmpty()) return EventTarget.fresh(list);
        int pickRoll = random.between(1, live.stream().mapToInt(entry -> entry.weight()).sum());
        dice.add(pickRoll);
        int remaining = pickRoll;
        for (var entry : live) {
            remaining -= entry.weight();
            if (remaining <= 0) return EventTarget.existing(list, entry.title());
        }
        throw new IllegalStateException("pick " + pickRoll + " is beyond the list's total weight");
    }
}
