package dev.tableteller.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tableteller.support.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CampaignResolverTest {
    final CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    final CampaignResolver resolver = new CampaignResolver(campaigns);

    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void resolvesANameIgnoringCaseAndSpaces() {
        var id = campaigns.create("Пепел", null, null, null, null).id();
        assertThat(resolver.resolve("  пепел ")).isEqualTo(id);
    }

    @Test
    void resolvesAnIdWithSpaces() {
        var id = campaigns.create("Ashfall", null, null, null, null).id();
        assertThat(resolver.resolve(" " + id + " ")).isEqualTo(id);
    }

    @Test
    void blankListsTheCampaigns() {
        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("campaign must not be blank; none exist yet, create one with campaign_create");
        campaigns.create("iron Coast", null, null, null, null);
        campaigns.create("Ashfall", null, null, null, null);
        assertThatThrownBy(() -> resolver.resolve(" ")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("campaign must not be blank; campaigns: Ashfall, iron Coast");
    }

    @Test
    void unknownListsTheCampaigns() {
        assertThatThrownBy(() -> resolver.resolve("Ashfal")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign 'Ashfal'; none exist yet, create one with campaign_create");
        campaigns.create("iron Coast", null, null, null, null);
        campaigns.create("Ashfall", null, null, null, null);
        assertThatThrownBy(() -> resolver.resolve(" Ashfal ")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign 'Ashfal'; campaigns: Ashfall, iron Coast");
    }

    @Test
    void anIdOfNoCampaignIsUnknown() {
        campaigns.create("Ashfall", null, null, null, null);
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> resolver.resolve(id.toString())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("no campaign '" + id + "'; campaigns: Ashfall");
    }
}
