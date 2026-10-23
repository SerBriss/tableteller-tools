package dev.tableteller.journal;

import static dev.tableteller.journal.JournalKind.npc;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.support.TestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JournalRepositoryTest {
    CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    JournalRepository journal = new JournalRepository(TestDatabase.jdbc());
    UUID campaign;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        campaign = campaigns.create("Ashfall", null, null, null, null).id();
    }

    @Test
    void insertStoresTagsAndScene() {
        var inserted = journal.insert(campaign, npc, "Innkeeper Varo", "Runs the inn.", List.of("inn", "docks"), 2);
        var found = journal.find(campaign, npc, "Innkeeper Varo").orElseThrow();
        assertThat(found).isEqualTo(inserted);
        assertThat(found.tags()).containsExactly("inn", "docks");
        assertThat(found.sceneNumber()).isEqualTo(2);
        assertThat(found.createdAt()).isEqualTo(found.updatedAt());
    }

    @Test
    void updateReplacesBodyTagsAndScene() {
        var inserted = journal.insert(campaign, npc, "Innkeeper Varo", "Runs the inn.", List.of("inn"), 1);
        journal.update(inserted.entryId(), inserted.title(), "Sold the inn.", List.of("docks"), 3);
        var updated = journal.findById(campaign, inserted.entryId()).orElseThrow();
        assertThat(updated.title()).isEqualTo("Innkeeper Varo");
        assertThat(updated.body()).isEqualTo("Sold the inn.");
        assertThat(updated.tags()).containsExactly("docks");
        assertThat(updated.sceneNumber()).isEqualTo(3);
        assertThat(updated.createdAt()).isEqualTo(inserted.createdAt());
        assertThat(updated.updatedAt()).isAfter(inserted.updatedAt());
    }

    @Test
    void updateRetitlesTheEntryTrimmedAndSearchFollowsTheTitle() {
        var inserted = journal.insert(campaign, npc, "Священник", "A priest.", List.of(), 1);
        journal.update(inserted.entryId(), "  Отец Себастьен ", "A priest.", List.of(), 2);
        assertThat(journal.findById(campaign, inserted.entryId())).get().extracting(JournalEntry::title)
            .isEqualTo("Отец Себастьен");
        assertThat(journal.search(campaign, List.of("Себастьен"), null, 10)).extracting(SearchHit::entryId)
            .containsExactly(inserted.entryId());
        assertThat(journal.search(campaign, List.of("Священник"), null, 10)).isEmpty();
    }

    @Test
    void findMatchesTitleIgnoringCaseAndSpaces() {
        journal.insert(campaign, npc, "Innkeeper Varo", "Runs the inn.", List.of(), 0);
        assertThat(journal.find(campaign, npc, "  innkeeper VARO ")).isPresent();
    }
}
