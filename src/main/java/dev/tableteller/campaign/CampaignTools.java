package dev.tableteller.campaign;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Creating campaigns, reading their briefs and updating them. Journal-side tools: they write no roll-log rows. None
 * of them opens anything; every other tool names its campaign.
 */
@Component
public class CampaignTools {

    private final CampaignRepository campaigns;
    private final CampaignResolver resolver;
    private final CampaignBriefs briefs;

    public CampaignTools(CampaignRepository campaigns, CampaignResolver resolver, CampaignBriefs briefs) {
        this.campaigns = campaigns;
        this.resolver = resolver;
        this.briefs = briefs;
    }

    @McpTool(name = "campaign_open", description = "Read a campaign's brief by name or id: setting, tone, PC, the PC's "
        + "skills, chaos factor, the current scene, both lists and the trackers. Call it at the start of every "
        + "session. It changes nothing; every other tool names the campaign itself.")
    public CampaignBrief campaignOpen(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign) {
        return briefs.brief(resolver.resolve(campaign), "Opened");
    }

    public record CampaignCreated(@JsonProperty("campaign_id") UUID campaignId, String name, String summary) {
    }

    // Parameter names are the tool's wire names, so pc_summary and pc_skills stay snake_case.
    @McpTool(name = "campaign_create", description = "Create a campaign. Chaos starts at 5 with no scenes. Every other "
        + "tool takes its name as campaign.")
    public CampaignCreated campaignCreate(
            @McpToolParam(required = true, description = "Campaign name, unique ignoring case") String name,
            @McpToolParam(required = false, description = "The world and situation") String setting,
            @McpToolParam(required = false, description = "The mood, e.g. grim, hopeful, pulpy") String tone,
            @McpToolParam(required = false, description = "Who the player character is") String pc_summary,
            @McpToolParam(required = false, description = "The PC's skills, one per line with the odds shift each "
                + "gives, e.g. Swordplay: exceptional, +2") String pc_skills) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        campaigns.findByName(name).ifPresent(existing -> {
            throw new IllegalArgumentException(
                "a campaign named '" + existing.name() + "' already exists; open it with campaign_open");
        });
        var created = campaigns.create(name.trim(), setting, tone, pc_summary, pc_skills);
        return new CampaignCreated(created.id(), created.name(),
            "Campaign '%1$s' created. Chaos 5, no scenes yet; pass campaign '%1$s' to every other tool."
                .formatted(created.name()));
    }

    @McpTool(name = "campaign_update", description = "Change the campaign's setting or tone, or the PC's summary or "
        + "skills: for a setting the story has filled in, a PC who has changed, or a character sheet converted again. "
        + "Replaces each field it is given, keeps the others, and returns the brief. Writes no roll.")
    public CampaignBrief campaignUpdate(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = false, description = "The world and situation now; omit to keep it")
            String setting,
            @McpToolParam(required = false, description = "The mood now; omit to keep it") String tone,
            @McpToolParam(required = false, description = "Who the player character is now; omit to keep it")
            String pc_summary,
            @McpToolParam(required = false, description = "The PC's skills, one per line with the odds shift each "
                + "gives; omit to keep them") String pc_skills) {
        var newSetting = given(setting);
        var newTone = given(tone);
        var summary = given(pc_summary);
        var skills = given(pc_skills);
        if (newSetting == null && newTone == null && summary == null && skills == null) {
            throw new IllegalArgumentException("campaign_update needs setting, tone, pc_summary or pc_skills");
        }
        var id = resolver.resolve(campaign);
        campaigns.update(id, newSetting, newTone, summary, skills);
        return briefs.brief(id, "Updated");
    }

    /** The field as given, or null when it is missing or blank: a DM may send "" for a field it means to keep. */
    private static String given(String field) {
        return field == null || field.isBlank() ? null : field;
    }
}
