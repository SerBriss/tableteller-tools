package dev.tableteller.scene;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SceneRepositoryTest {
    CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    SceneRepository scenes = new SceneRepository(TestDatabase.jdbc());

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void findLastReturnsTheLastScenesOldestFirst() {
        var campaign = campaigns.create("Ashfall", null, null, null, null).id();
        for (int number = 1; number <= 5; number++) {
            scenes.open(campaign, number, "Scene " + number, SceneResult.expected, "Scene " + number);
        }
        assertThat(scenes.findLast(campaign, 3)).extracting(Scene::number).containsExactly(3, 4, 5);
        assertThat(scenes.findLast(campaign, 10)).extracting(Scene::number).containsExactly(1, 2, 3, 4, 5);
    }
}
