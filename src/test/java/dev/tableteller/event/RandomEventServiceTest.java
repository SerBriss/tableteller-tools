package dev.tableteller.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.lists.ListName;
import dev.tableteller.lists.ListRepository;
import dev.tableteller.lists.ListStatus;
import dev.tableteller.meaning.MeaningFixtures;
import dev.tableteller.meaning.MeaningService;
import dev.tableteller.random.ScriptedRandomSource;
import dev.tableteller.support.TestDatabase;
import dev.tableteller.table.FocusFixtures;
import dev.tableteller.table.FocusTable;
import dev.tableteller.table.MeaningTable;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RandomEventServiceTest {
    ListRepository lists = new ListRepository(TestDatabase.jdbc());
    FocusTable focus = FocusFixtures.focus();
    MeaningTable actions = MeaningFixtures.actions();
    UUID campaign;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        campaign = new CampaignRepository(TestDatabase.jdbc()).create("Ashfall", null, null, null, null).id();
    }

    @Test
    void focusWithoutTargetRollsOnlyFocusAndMeaning() {
        assertThat(service(1, 1, 2).roll(campaign, focus, actions))
            .isEqualTo(new RolledEvent(new RandomEvent("Distant event", null, List.of("seek", "ally")), List.of(1, 1, 2)));
    }

    @Test
    void weightedPickCountsWeightTwoTwice() {   // Varo weight 1, Ilsa weight 2: pick 1 is Varo, 2 and 3 are Ilsa
        lists.add(campaign, ListName.characters, "Varo", 1, ListStatus.active);
        lists.add(campaign, ListName.characters, "Ilsa", 2, ListStatus.active);
        assertThat(service(2, 1, 1, 1).roll(campaign, focus, actions).event().target())
            .isEqualTo(EventTarget.existing(ListName.characters, "Varo"));
        assertThat(service(2, 2, 1, 1).roll(campaign, focus, actions).event().target())
            .isEqualTo(EventTarget.existing(ListName.characters, "Ilsa"));
        assertThat(service(2, 3, 1, 1).roll(campaign, focus, actions).event().target())
            .isEqualTo(EventTarget.existing(ListName.characters, "Ilsa"));
    }

    @Test
    void pickRollIsInTheDice() {
        lists.add(campaign, ListName.characters, "Varo", 1, ListStatus.active);
        assertThat(service(2, 1, 3, 4).roll(campaign, focus, actions).dice()).containsExactly(2, 1, 3, 4);
    }

    @Test
    void emptyListAsksForANewEntryWithoutAPickRoll() {
        var rolled = service(3, 1, 2).roll(campaign, focus, actions);
        assertThat(rolled.event().target()).isEqualTo(EventTarget.fresh(ListName.threads));
        assertThat(rolled.dice()).containsExactly(3, 1, 2);
    }

    @Test
    void closedEntriesAreNeverPicked() {
        lists.add(campaign, ListName.threads, "Find the heir", 3, ListStatus.closed);
        assertThat(service(3, 1, 2).roll(campaign, focus, actions).event().target())
            .isEqualTo(EventTarget.fresh(ListName.threads));
    }

    @Test
    void describeNamesTheTarget() {
        assertThat(new RandomEvent("A character acts", EventTarget.existing(ListName.characters, "Innkeeper Varo"),
            List.of("seek", "ally")).describe()).isEqualTo("A character acts → Innkeeper Varo (seek / ally)");
        assertThat(new RandomEvent("A character acts", EventTarget.fresh(ListName.characters), List.of("seek", "ally"))
            .describe()).isEqualTo("A character acts → a new character (seek / ally)");
        assertThat(new RandomEvent("Distant event", null, List.of("seek", "ally")).describe())
            .isEqualTo("Distant event (seek / ally)");
    }

    private RandomEventService service(int... rolls) {
        var random = new ScriptedRandomSource(rolls);
        return new RandomEventService(random, new MeaningService(random), lists);
    }
}
