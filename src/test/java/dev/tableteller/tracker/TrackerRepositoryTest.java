package dev.tableteller.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.support.TestDatabase;
import dev.tableteller.support.TestOracle;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrackerRepositoryTest {
    TestOracle oracle;
    TrackerRepository trackers;
    UUID campaign;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        oracle = TestOracle.withRolls();
        trackers = oracle.trackers;
        campaign = oracle.createCampaign("Ashfall");
    }

    @Test
    void addAndFindIgnoringCase() {
        trackers.add(campaign, new Tracker(" Голод ", 1, 0, 5, "Rouse: d10"));
        assertThat(trackers.find(campaign, "голод")).contains(new Tracker("Голод", 1, 0, 5, "Rouse: d10"));
    }

    @Test
    void findAllInTheOrderAdded() {
        trackers.add(campaign, new Tracker("Willpower", 4, null, null, null));
        trackers.add(campaign, new Tracker("Hunger", 1, null, null, null));
        assertThat(trackers.findAll(campaign)).extracting(Tracker::name).containsExactly("Willpower", "Hunger");
    }

    @Test
    void setValueAndRemove() {
        trackers.add(campaign, new Tracker("Hunger", 1, 0, 5, null));
        trackers.setValue(campaign, "HUNGER", 3);
        assertThat(trackers.find(campaign, "Hunger")).get().extracting(Tracker::value).isEqualTo(3);
        trackers.remove(campaign, "hunger");
        assertThat(trackers.find(campaign, "Hunger")).isEmpty();
        assertThat(trackers.findAll(campaign)).isEmpty();
    }

    @Test
    void trackersBelongToTheirCampaign() {
        var ironCoast = oracle.createCampaign("Iron Coast");
        trackers.add(campaign, new Tracker("Hunger", 1, null, null, null));
        trackers.add(ironCoast, new Tracker("Hunger", 4, null, null, null));
        assertThat(trackers.findAll(campaign)).containsExactly(new Tracker("Hunger", 1, null, null, null));
        assertThat(trackers.findAll(ironCoast)).containsExactly(new Tracker("Hunger", 4, null, null, null));
    }
}
