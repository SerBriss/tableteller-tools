package dev.tableteller.state;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.support.TestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RollLogRepositoryTest {
    CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    RollLogRepository rolls = new RollLogRepository(TestDatabase.jdbc());
    UUID campaign;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        campaign = campaigns.create("Ashfall", null, null, null, null).id();
    }

    @Test
    void appendAssignsIdsFromOneAfterReset() {
        var first = rolls.append(campaign, roll());
        assertThat(first.rollId()).isEqualTo("r_0001");
        assertThat(first.createdAt()).isNotNull();
        assertThat(rolls.append(campaign, roll()).rollId()).isEqualTo("r_0002");
    }

    @Test
    void jsonRoundTripsNestedValuesAndCyrillic() {
        var result = Map.<String, Object>of("pairs", List.of(List.of("тайна", "debt")), "random_event_triggered", true,
            "target", Map.of("list", "characters", "new", true));
        rolls.append(campaign, new NewRoll("meaning", Map.of("table", "actions", "count", 1), List.of(2, 3), result, 4));
        var row = rolls.findAll(campaign).getFirst();
        assertThat(row.tool()).isEqualTo("meaning");
        assertThat(row.inputs()).isEqualTo(Map.of("table", "actions", "count", 1));
        assertThat(row.dice()).containsExactly(2, 3);
        assertThat(row.result()).isEqualTo(result);
        assertThat(row.sceneNumber()).isEqualTo(4);
    }

    @Test
    void findAllReturnsOnlyThisCampaignInOrder() {
        var other = campaigns.create("Iron Coast", null, null, null, null).id();
        rolls.append(campaign, roll());
        rolls.append(other, roll());
        rolls.append(campaign, roll());
        assertThat(rolls.findAll(campaign)).extracting(RollRecord::id).containsExactly(1L, 3L);
    }

    @Test
    void findLastReturnsTheNewestRowsOldestFirst() {
        var other = campaigns.create("Iron Coast", null, null, null, null).id();
        rolls.append(campaign, roll());
        rolls.append(campaign, roll());
        rolls.append(other, roll());
        rolls.append(campaign, roll());
        assertThat(rolls.findLast(campaign, 2)).extracting(RollRecord::id).containsExactly(2L, 4L);
        assertThat(rolls.findLast(campaign, 10)).extracting(RollRecord::id).containsExactly(1L, 2L, 4L);
    }

    @Test
    void findBySceneReturnsThatScenesRows() {
        rolls.append(campaign, rollInScene(0));
        rolls.append(campaign, rollInScene(1));
        rolls.append(campaign, rollInScene(1));
        rolls.append(campaign, rollInScene(2));
        assertThat(rolls.findByScene(campaign, 1)).extracting(RollRecord::id).containsExactly(2L, 3L);
    }

    @Test
    void rollIdPadsToFourDigitsAndGrowsPastThem() {
        assertThat(record(142).rollId()).isEqualTo("r_0142");
        assertThat(record(12345).rollId()).isEqualTo("r_12345");
    }

    private static NewRoll roll() {
        return rollInScene(0);
    }

    private static NewRoll rollInScene(int sceneNumber) {
        return new NewRoll("roll", Map.of("min", 1, "max", 6), List.of(4), Map.of("value", 4), sceneNumber);
    }

    private RollRecord record(long id) {
        return new RollRecord(id, campaign, "roll", Map.of(), List.of(), Map.of(), 0, Instant.now());
    }
}
