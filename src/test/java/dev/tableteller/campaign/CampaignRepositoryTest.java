package dev.tableteller.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CampaignRepositoryTest {
    CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void createStartsAtChaosFiveSceneZero() {
        var created = campaigns.create("Ashfall", "a burnt valley", "grim", "Mara, a scout", null);
        assertThat(campaigns.findById(created.id())).get()
            .extracting(Campaign::name, Campaign::chaosFactor, Campaign::sceneNumber, Campaign::pcSummary)
            .containsExactly("Ashfall", 5, 0, "Mara, a scout");
    }

    @Test
    void createStoresThePcSkills() {
        var skills = "Stealth: exceptional, +2\nCharm: awful, -2";
        var created = campaigns.create("Ashfall", null, null, "Mara", skills);
        assertThat(campaigns.findById(created.id())).get().extracting(Campaign::pcSkills).isEqualTo(skills);
        var without = campaigns.create("Iron Coast", null, null, null, null);
        assertThat(campaigns.findById(without.id())).get().extracting(Campaign::pcSkills).isNull();
    }

    @Test
    void findsByNameIgnoringCaseAndSpaces() {
        campaigns.create("Ashfall", null, null, null, null);
        assertThat(campaigns.findByName("  ASHFALL ")).get().extracting(Campaign::name).isEqualTo("Ashfall");
    }

    @Test
    void namesAreSortedIgnoringCase() {
        campaigns.create("iron Coast", null, null, null, null);
        campaigns.create("Ashfall", null, null, null, null);
        assertThat(campaigns.names()).containsExactly("Ashfall", "iron Coast");
    }
}
