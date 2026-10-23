package dev.tableteller.campaign;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.lists.ListEntry;
import dev.tableteller.scene.Scene;
import dev.tableteller.tracker.Tracker;
import java.util.List;
import java.util.UUID;

/**
 * What campaign_open returns: the campaign, its oracle state, the open scene (null when none), the live
 * entries of both lists and the trackers in the order added.
 */
public record CampaignBrief(@JsonProperty("campaign_id") UUID campaignId, String name, String setting, String tone,
        @JsonProperty("pc_summary") String pcSummary, @JsonProperty("pc_skills") String pcSkills,
        @JsonProperty("chaos_factor") int chaosFactor,
        @JsonProperty("scene_number") int sceneNumber, @JsonProperty("open_scene") Scene openScene,
        List<ListEntry> threads, List<ListEntry> characters, List<Tracker> trackers, String summary) {
}
