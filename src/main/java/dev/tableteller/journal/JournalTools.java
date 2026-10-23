package dev.tableteller.journal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.campaign.CampaignResolver;
import dev.tableteller.state.OracleStateRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The campaign journal: canon the DM must not contradict. Journal-side tools: they write no roll-log rows. Writes
 * lock the campaign row, so they read the current scene number and never interleave with oracle tools.
 */
@Component
public class JournalTools {

    private static final String KINDS =
        Arrays.stream(JournalKind.values()).map(Enum::name).collect(Collectors.joining(", "));
    private static final int SEARCH_LIMIT = 20;

    private final JournalRepository journal;
    private final CampaignResolver resolver;
    private final OracleStateRepository state;
    private final TransactionTemplate transactions;

    public JournalTools(JournalRepository journal, CampaignResolver resolver, OracleStateRepository state,
            TransactionTemplate transactions) {
        this.journal = journal;
        this.resolver = resolver;
        this.state = state;
        this.transactions = transactions;
    }

    public record WriteResult(@JsonProperty("entry_id") UUID entryId, boolean replaced,
            @JsonProperty("previous_body") @JsonInclude(JsonInclude.Include.NON_NULL) String previousBody,
            String summary) {
    }

    public record EntryResult(JournalEntry entry, String summary) {
    }

    public record SearchResult(String query, List<SearchHit> entries, String summary) {
    }

    // Parameter names are the tool's wire names, so new_title stays snake_case.
    @McpTool(name = "journal_write", description = "Record something the story has established: an npc, location, "
        + "item, fact or note. Writing a kind and title that already exist replaces that entry and returns its "
        + "previous body; new_title also renames it.")
    public WriteResult journalWrite(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "npc, location, item, fact or note") JournalKind kind,
            @McpToolParam(required = true, description = "The entry's name, e.g. Innkeeper Varo; one entry per kind "
                + "and title, ignoring case") String title,
            @McpToolParam(required = true, description = "Everything that is true about it; replaces the old body")
            String body,
            @McpToolParam(required = false, description = "Labels for the entry; omit them to keep the entry's tags")
            List<String> tags,
            @McpToolParam(required = false, description = "A new title for an entry that exists, e.g. once an NPC's "
                + "name is known; omit to keep the title") String new_title) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required; one of " + KINDS);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        var newTags = tags == null ? null : cleaned(tags);
        boolean rename = new_title != null && !new_title.isBlank();
        return transactions.execute(tx -> {
            var campaignId = resolver.resolve(campaign);
            int scene = state.lock(campaignId).sceneNumber();
            var existing = journal.find(campaignId, kind, title);
            if (rename) {
                var old = existing.orElseThrow(() -> new IllegalArgumentException(
                    "no %s '%s' to rename; leave out new_title to record a new entry".formatted(kind, title.trim())));
                journal.find(campaignId, kind, new_title)
                    .filter(taken -> !taken.entryId().equals(old.entryId()))
                    .ifPresent(taken -> {
                        throw new IllegalArgumentException(("%s '%s' already exists; write to it, or pick another "
                            + "new_title").formatted(kind, taken.title()));
                    });
                journal.update(old.entryId(), new_title, body, newTags == null ? old.tags() : newTags, scene);
                return new WriteResult(old.entryId(), true, old.body(),
                    "Renamed %s '%s' to '%s'; check previous_body for anything the new text dropped."
                        .formatted(kind, old.title(), new_title.trim()));
            }
            if (existing.isPresent()) {
                var old = existing.get();
                journal.update(old.entryId(), old.title(), body, newTags == null ? old.tags() : newTags, scene);
                return new WriteResult(old.entryId(), true, old.body(),
                    "Replaced %s '%s'; check previous_body for anything the new text dropped."
                        .formatted(kind, old.title()));
            }
            var entry = journal.insert(campaignId, kind, title, body, newTags == null ? List.of() : newTags, scene);
            return new WriteResult(entry.entryId(), false, null,
                "Recorded %s '%s' (scene %d).".formatted(kind, entry.title(), scene));
        });
    }

    // Parameter names are the tool's wire names, so entry_id stays snake_case.
    @McpTool(name = "journal_get", description = "Read one journal entry in full, by entry_id or by title.")
    public EntryResult journalGet(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = false, description = "An entry_id from journal_write or journal_search")
            String entry_id,
            @McpToolParam(required = false, description = "The entry's title, matched ignoring case") String title) {
        boolean byId = entry_id != null && !entry_id.isBlank();
        boolean byTitle = title != null && !title.isBlank();
        if (!byId && !byTitle) {
            throw new IllegalArgumentException("journal_get needs entry_id or title");
        }
        if (byId && byTitle) {
            throw new IllegalArgumentException("pass entry_id or title, not both");
        }
        var id = byId ? entryId(entry_id) : null;
        var campaignId = resolver.resolve(campaign);
        var entry = byId
            ? journal.findById(campaignId, id)
                .orElseThrow(() -> new IllegalArgumentException("no journal entry with id " + id))
            : onlyEntryTitled(campaignId, title.trim());
        return new EntryResult(entry, "%s '%s', last written in scene %d."
            .formatted(entry.kind(), entry.title(), entry.sceneNumber()));
    }

    @McpTool(name = "journal_search", description = "Search the journal before naming or describing anything that may "
        + "already exist. Every word must match the start of a word in an entry's title or body.")
    public SearchResult journalSearch(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "Words to look for, e.g. a name") String query,
            @McpToolParam(required = false, description = "Only entries of this kind: npc, location, item, fact or "
                + "note") JournalKind kind) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        var text = query.trim();
        var words = JournalRepository.words(text);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("query needs at least one letter or digit; got '" + text + "'");
        }
        var hits = journal.search(resolver.resolve(campaign), words, kind, SEARCH_LIMIT + 1);
        if (hits.size() > SEARCH_LIMIT) {
            return new SearchResult(text, hits.subList(0, SEARCH_LIMIT), ("More than %d entries match '%s'; showing "
                + "the best %d. Add words or pass kind to narrow it.").formatted(SEARCH_LIMIT, text, SEARCH_LIMIT));
        }
        var summary = switch (hits.size()) {
            case 0 -> "No entries match '%s'; if it is new, record it with journal_write.".formatted(text);
            case 1 -> "1 entry matches '%s'.".formatted(text);
            default -> "%d entries match '%s'.".formatted(hits.size(), text);
        };
        return new SearchResult(text, hits, summary);
    }

    private JournalEntry onlyEntryTitled(UUID campaignId, String title) {
        var entries = journal.findByTitle(campaignId, title);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("no journal entry titled '" + title + "'; find it with journal_search");
        }
        if (entries.size() > 1) {
            throw new IllegalArgumentException("'%s' is the title of %d entries: %s; pass entry_id".formatted(
                title, entries.size(), entries.stream()
                    .map(entry -> entry.kind() + " " + entry.entryId())
                    .collect(Collectors.joining(", "))));
        }
        return entries.getFirst();
    }

    private static UUID entryId(String entryId) {
        try {
            return UUID.fromString(entryId.trim());
        } catch (IllegalArgumentException notAnId) {
            throw new IllegalArgumentException("entry_id '" + entryId.trim()
                + "' is not an id; use an entry_id from journal_write or journal_search");
        }
    }

    /** Trimmed, without blanks or repeats, in the order given. */
    private static List<String> cleaned(List<String> tags) {
        return tags.stream().filter(Objects::nonNull).map(String::trim).filter(tag -> !tag.isEmpty()).distinct()
            .toList();
    }
}
