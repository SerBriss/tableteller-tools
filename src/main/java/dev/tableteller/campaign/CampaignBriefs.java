package dev.tableteller.campaign;

import dev.tableteller.lists.ListName;
import dev.tableteller.lists.ListRepository;
import dev.tableteller.scene.Scene;
import dev.tableteller.scene.SceneRepository;
import dev.tableteller.tracker.TrackerRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds the campaign brief that campaign_open returns and the campaign://{campaign}/brief resource shows. */
@Component
public class CampaignBriefs {

    private final CampaignRepository campaigns;
    private final ListRepository lists;
    private final SceneRepository scenes;
    private final TrackerRepository trackers;

    public CampaignBriefs(CampaignRepository campaigns, ListRepository lists, SceneRepository scenes,
            TrackerRepository trackers) {
        this.campaigns = campaigns;
        this.lists = lists;
        this.scenes = scenes;
        this.trackers = trackers;
    }

    /** The brief; its summary starts with {@code lead}, e.g. {@code Opened 'Ashfall': chaos 6; scene 4 open; …}. */
    public CampaignBrief brief(UUID campaignId, String lead) {
        var campaign = campaigns.findById(campaignId).orElseThrow();
        var openScene = scenes.findOpen(campaignId).orElse(null);
        var threads = lists.live(campaignId, ListName.threads);
        var characters = lists.live(campaignId, ListName.characters);
        return new CampaignBrief(campaign.id(), campaign.name(), campaign.setting(), campaign.tone(),
            campaign.pcSummary(), campaign.pcSkills(), campaign.chaosFactor(), campaign.sceneNumber(), openScene,
            threads, characters, trackers.findAll(campaignId),
            "%s '%s': chaos %d; %s; threads %d, characters %d.".formatted(lead, campaign.name(),
                campaign.chaosFactor(), Scene.progress(openScene, campaign.sceneNumber()), threads.size(),
                characters.size()));
    }
}
