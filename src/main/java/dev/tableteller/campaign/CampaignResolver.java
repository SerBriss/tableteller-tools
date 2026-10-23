package dev.tableteller.campaign;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a tool's {@code campaign} argument, a name or an id, into the campaign's id. The server keeps no active
 * campaign: every call names its own, so several chats can share one server and each play a different campaign.
 */
@Component
public class CampaignResolver {

    /** The description of every tool's {@code campaign} parameter. */
    public static final String DESCRIPTION = "This chat's campaign: its name (ignoring case) or id";

    private final CampaignRepository campaigns;

    public CampaignResolver(CampaignRepository campaigns) {
        this.campaigns = campaigns;
    }

    /** The id of the campaign with this id or, failing that, this name; both trimmed, the name ignoring case. */
    public UUID resolve(String campaign) {
        if (campaign == null || campaign.isBlank()) {
            throw new IllegalArgumentException("campaign must not be blank; " + choices());
        }
        return byId(campaign).or(() -> campaigns.findByName(campaign)).map(Campaign::id)
            .orElseThrow(() -> new IllegalArgumentException("no campaign '" + campaign.trim() + "'; " + choices()));
    }

    private Optional<Campaign> byId(String campaign) {
        try {
            return campaigns.findById(UUID.fromString(campaign.trim()));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private String choices() {
        var names = campaigns.names();
        return names.isEmpty() ? "none exist yet, create one with campaign_create"
            : "campaigns: " + String.join(", ", names);
    }
}
