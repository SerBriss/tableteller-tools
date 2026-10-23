package dev.tableteller.support;

import dev.tableteller.campaign.CampaignBriefs;
import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.campaign.CampaignResolver;
import dev.tableteller.campaign.CampaignTools;
import dev.tableteller.event.RandomEventService;
import dev.tableteller.fate.FateService;
import dev.tableteller.journal.JournalRepository;
import dev.tableteller.journal.JournalTools;
import dev.tableteller.lists.ListRepository;
import dev.tableteller.lists.ListTools;
import dev.tableteller.meaning.MeaningFixtures;
import dev.tableteller.meaning.MeaningService;
import dev.tableteller.pack.PackRegistry;
import dev.tableteller.random.ScriptedRandomSource;
import dev.tableteller.resource.OracleResources;
import dev.tableteller.scene.SceneRepository;
import dev.tableteller.scene.SceneService;
import dev.tableteller.scene.SceneTools;
import dev.tableteller.session.SessionRepository;
import dev.tableteller.session.SessionTools;
import dev.tableteller.state.OracleStateRepository;
import dev.tableteller.state.RollHistoryTools;
import dev.tableteller.state.RollLogRepository;
import dev.tableteller.state.RollRecord;
import dev.tableteller.table.DemoChartFormula;
import dev.tableteller.table.FocusFixtures;
import dev.tableteller.tools.OracleTools;
import dev.tableteller.tracker.TrackerRepository;
import dev.tableteller.tracker.TrackerTools;
import java.util.List;
import java.util.UUID;

/** Every component wired over {@link TestDatabase}, with scripted rolls. Call {@code TestDatabase.reset()} first. */
public final class TestOracle {

    public final ScriptedRandomSource random;
    public final CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    public final CampaignResolver resolver = new CampaignResolver(campaigns);
    public final OracleStateRepository state = new OracleStateRepository(TestDatabase.jdbc());
    public final RollLogRepository rolls = new RollLogRepository(TestDatabase.jdbc());
    public final ListRepository lists = new ListRepository(TestDatabase.jdbc());
    public final SceneRepository scenes = new SceneRepository(TestDatabase.jdbc());
    public final JournalRepository journal = new JournalRepository(TestDatabase.jdbc());
    public final SessionRepository sessions = new SessionRepository(TestDatabase.jdbc());
    public final PackRegistry packs = new PackRegistry(
        List.of(DemoChartFormula.chart(), FocusFixtures.focus(), MeaningFixtures.single(), MeaningFixtures.actions()));
    public final TrackerRepository trackers = new TrackerRepository(TestDatabase.jdbc());
    public final CampaignBriefs briefs = new CampaignBriefs(campaigns, lists, scenes, trackers);
    public final OracleResources resources = new OracleResources(resolver, briefs, journal, packs);
    public final RandomEventService events;
    public final OracleTools tools;
    public final CampaignTools campaignTools;
    public final ListTools listTools;
    public final SceneTools sceneTools;
    public final JournalTools journalTools;
    public final RollHistoryTools rollHistoryTools;
    public final SessionTools sessionTools;
    public final TrackerTools trackerTools;

    private TestOracle(int... rolls) {
        random = new ScriptedRandomSource(rolls);
        var meaning = new MeaningService(random);
        events = new RandomEventService(random, meaning, lists);
        tools = new OracleTools(random, new FateService(random), meaning, events, packs, resolver,
            state, this.rolls, TestDatabase.transactions());
        campaignTools = new CampaignTools(campaigns, resolver, briefs);
        listTools = new ListTools(lists, resolver);
        sceneTools = new SceneTools(new SceneService(random), events, packs, resolver, state, scenes, this.rolls,
            TestDatabase.transactions());
        journalTools = new JournalTools(journal, resolver, state, TestDatabase.transactions());
        rollHistoryTools = new RollHistoryTools(this.rolls, resolver);
        sessionTools = new SessionTools(sessions, scenes, lists, trackers, campaigns, resolver, state,
            TestDatabase.transactions());
        trackerTools = new TrackerTools(trackers, resolver, state, this.rolls, TestDatabase.transactions());
    }

    public static TestOracle withRolls(int... rolls) {
        return new TestOracle(rolls);
    }

    /** Creates a campaign with only a name. */
    public UUID createCampaign(String name) {
        return campaignTools.campaignCreate(name, null, null, null, null).campaignId();
    }

    /** The id of the campaign with this name or id. */
    public UUID campaignId(String campaign) {
        return resolver.resolve(campaign);
    }

    /** The named campaign's chaos factor. */
    public int chaos(String campaign) {
        return campaigns.findById(campaignId(campaign)).orElseThrow().chaosFactor();
    }

    /** The named campaign's roll-log rows. */
    public List<RollRecord> rows(String campaign) {
        return rolls.findAll(campaignId(campaign));
    }
}
