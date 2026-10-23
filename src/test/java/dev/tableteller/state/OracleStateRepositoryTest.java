package dev.tableteller.state;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OracleStateRepositoryTest {
    CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    OracleStateRepository state = new OracleStateRepository(TestDatabase.jdbc());

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void newCampaignIsAtChaosFiveSceneZero() {
        var id = campaigns.create("Ashfall", null, null, null, null).id();
        OracleState locked = TestDatabase.transactions().execute(tx -> state.lock(id));
        assertThat(locked).isEqualTo(new OracleState(5, 0));
    }

    @Test
    void savesChaosAndSceneNumber() {
        var id = campaigns.create("Ashfall", null, null, null, null).id();
        state.saveChaos(id, 7);
        state.saveSceneNumber(id, 3);
        OracleState locked = TestDatabase.transactions().execute(tx -> state.lock(id));
        assertThat(locked).isEqualTo(new OracleState(7, 3));
    }
}
