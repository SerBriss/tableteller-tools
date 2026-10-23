package dev.tableteller.lists;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.support.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ListRepositoryTest {
    ListRepository lists = new ListRepository(TestDatabase.jdbc());
    UUID campaign;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        campaign = new CampaignRepository(TestDatabase.jdbc()).create("Ashfall", null, null, null, null).id();
    }

    @Test
    void findAllPutsLiveEntriesFirstInAddedOrder() {
        lists.add(campaign, ListName.threads, "A", 1, ListStatus.open);
        lists.add(campaign, ListName.threads, "B", 2, ListStatus.open);
        lists.add(campaign, ListName.threads, "C", 3, ListStatus.open);
        lists.update(campaign, ListName.threads, "a", "A", 1, ListStatus.closed);
        assertThat(lists.findAll(campaign, ListName.threads)).containsExactly(
            new ListEntry("B", 2, ListStatus.open), new ListEntry("C", 3, ListStatus.open),
            new ListEntry("A", 1, ListStatus.closed));
    }

    @Test
    void updateRetitlesTheEntryTrimmed() {
        lists.add(campaign, ListName.characters, "Священник", 1, ListStatus.active);
        lists.update(campaign, ListName.characters, "священник", "  Отец Себастьен ", 2, ListStatus.active);
        assertThat(lists.findAll(campaign, ListName.characters))
            .containsExactly(new ListEntry("Отец Себастьен", 2, ListStatus.active));
    }

    @Test
    void liveSkipsEndedEntries() {
        lists.add(campaign, ListName.characters, "Varo", 1, ListStatus.active);
        lists.add(campaign, ListName.characters, "Ilsa", 2, ListStatus.removed);
        assertThat(lists.live(campaign, ListName.characters)).containsExactly(new ListEntry("Varo", 1, ListStatus.active));
    }

    @Test
    void listsAreSeparate() {
        lists.add(campaign, ListName.threads, "Varo", 1, ListStatus.open);
        assertThat(lists.findAll(campaign, ListName.characters)).isEmpty();
    }

    @Test
    void titlesMatchIgnoringCaseIncludingCyrillic() {
        lists.add(campaign, ListName.threads, "Тайна", 1, ListStatus.open);
        assertThat(lists.find(campaign, ListName.threads, "  тайна ")).get().extracting(ListEntry::title).isEqualTo("Тайна");
    }
}
