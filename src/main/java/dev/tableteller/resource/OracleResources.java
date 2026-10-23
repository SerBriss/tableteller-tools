package dev.tableteller.resource;

import dev.tableteller.campaign.CampaignBriefs;
import dev.tableteller.campaign.CampaignResolver;
import dev.tableteller.journal.JournalKind;
import dev.tableteller.journal.JournalRepository;
import dev.tableteller.pack.PackRegistry;
import dev.tableteller.table.TableType;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only context a client can attach without a tool call. Content is JSON. The campaign resources are URI
 * templates whose {@code {campaign}} segment is the campaign's name or id, like the tools' {@code campaign}.
 */
@Component
public class OracleResources {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TABLES_URI = "oracle://tables";
    private static final String KINDS =
        Arrays.stream(JournalKind.values()).map(Enum::name).collect(Collectors.joining(", "));

    private final CampaignResolver resolver;
    private final CampaignBriefs briefs;
    private final JournalRepository journal;
    private final PackRegistry packs;

    public OracleResources(CampaignResolver resolver, CampaignBriefs briefs, JournalRepository journal,
            PackRegistry packs) {
        this.resolver = resolver;
        this.briefs = briefs;
        this.journal = journal;
        this.packs = packs;
    }

    public record JournalTitles(JournalKind kind, List<String> titles) {
    }

    public record LoadedTable(TableType type, String name, String pack) {
    }

    // Spring AI binds template variables to parameters by position, as they arrive in the URI: still encoded.
    @McpResource(uri = "campaign://{campaign}/brief", name = "campaign_brief", mimeType = "application/json",
        description = "A campaign's setting, tone, PC, PC skills, chaos factor, current scene, both lists and "
            + "trackers. {campaign} is its name or id.")
    public ReadResourceResult brief(String campaign) {
        var campaignId = resolver.resolve(decoded(campaign));
        return json("campaign://" + campaign + "/brief", briefs.brief(campaignId, "Campaign"));
    }

    @McpResource(uri = "campaign://{campaign}/journal/{kind}", name = "journal_titles", mimeType = "application/json",
        description = "Titles of a campaign's journal entries of one kind: npc, location, item, fact or note. "
            + "{campaign} is its name or id.")
    public ReadResourceResult journalTitles(String campaign, String kind) {
        var journalKind = Arrays.stream(JournalKind.values()).filter(value -> value.name().equals(kind)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown journal kind '" + kind + "'; one of " + KINDS));
        var campaignId = resolver.resolve(decoded(campaign));
        return json("campaign://" + campaign + "/journal/" + kind,
            new JournalTitles(journalKind, journal.titles(campaignId, journalKind)));
    }

    @McpResource(uri = TABLES_URI, name = "tables", mimeType = "application/json",
        description = "Every loaded table with its type and pack. Meaning tables are the ones the meaning tool "
            + "accepts.")
    public ReadResourceResult tables() {
        return json(TABLES_URI, packs.tables().stream()
            .map(table -> new LoadedTable(table.type(), table.name(), table.pack()))
            .toList());
    }

    /** A URI segment, percent-decoded with {@code +} kept as a plus; a segment that does not decode is used as is. */
    private static String decoded(String segment) {
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            return segment;
        }
    }

    private static ReadResourceResult json(String uri, Object value) {
        return new ReadResourceResult(List.of(
            new TextResourceContents(uri, "application/json", JSON.writeValueAsString(value))));
    }
}
