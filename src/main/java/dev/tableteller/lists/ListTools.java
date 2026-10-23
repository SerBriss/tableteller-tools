package dev.tableteller.lists;

import dev.tableteller.campaign.CampaignResolver;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** The threads and characters lists. Journal-side tools: they write no roll-log rows. */
@Component
public class ListTools {

    private final ListRepository lists;
    private final CampaignResolver resolver;

    public ListTools(ListRepository lists, CampaignResolver resolver) {
        this.lists = lists;
        this.resolver = resolver;
    }

    public record ListResult(ListName list, List<ListEntry> entries, String summary) {
    }

    @McpTool(name = "list_add", description = "Add a thread or character to a list. Weight 1-3 is how often random "
        + "events pick it.")
    public ListResult listAdd(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "threads or characters") ListName list,
            @McpToolParam(required = true, description = "The thread or character, e.g. Find the heir") String title,
            @McpToolParam(required = false, description = "1-3; default 1") Integer weight,
            @McpToolParam(required = false, description = "Default open (threads) or active (characters)")
            ListStatus status) {
        requireList(list);
        requireTitle(title);
        int newWeight = weight == null ? 1 : checkedWeight(weight);
        var newStatus = status == null ? list.liveStatus() : checkedStatus(list, status);
        var campaignId = resolver.resolve(campaign);
        lists.find(campaignId, list, title).ifPresent(existing -> {
            throw new IllegalArgumentException(
                "'" + existing.title() + "' is already on " + list + "; change it with list_update");
        });
        lists.add(campaignId, list, title, newWeight, newStatus);
        return new ListResult(list, lists.findAll(campaignId, list),
            "Added '%s' to %s (weight %d).".formatted(title.trim(), list, newWeight));
    }

    // Parameter names are the tool's wire names, so new_title stays snake_case.
    @McpTool(name = "list_update", description = "Rename a list entry, change its weight, or close a thread / remove "
        + "a character.")
    public ListResult listUpdate(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "threads or characters") ListName list,
            @McpToolParam(required = true, description = "The entry's title, matched ignoring case") String title,
            @McpToolParam(required = false, description = "A new title for the entry, e.g. once an NPC's name is "
                + "known; omit to keep it") String new_title,
            @McpToolParam(required = false, description = "New weight, 1-3") Integer weight,
            @McpToolParam(required = false, description = "open/closed for threads, active/removed for characters")
            ListStatus status) {
        requireList(list);
        requireTitle(title);
        boolean rename = new_title != null && !new_title.isBlank();
        if (!rename && weight == null && status == null) {
            throw new IllegalArgumentException("list_update needs new_title, weight or status");
        }
        if (weight != null) checkedWeight(weight);
        if (status != null) checkedStatus(list, status);
        var campaignId = resolver.resolve(campaign);
        var existing = lists.find(campaignId, list, title).orElseThrow(() -> unknownTitle(campaignId, list, title));
        if (rename) requireFreeTitle(campaignId, list, new_title, existing);
        int newWeight = weight == null ? existing.weight() : weight;
        var newStatus = status == null ? existing.status() : status;
        var storedTitle = rename ? new_title.trim() : existing.title();
        lists.update(campaignId, list, title, storedTitle, newWeight, newStatus);
        var summary = rename
            ? "Renamed '%s' to '%s' on %s: weight %d, %s.".formatted(existing.title(), storedTitle, list, newWeight,
                newStatus)
            : "Updated '%s' on %s: weight %d, %s.".formatted(existing.title(), list, newWeight, newStatus);
        return new ListResult(list, lists.findAll(campaignId, list), summary);
    }

    @McpTool(name = "list_get", description = "Show a list with weights and status.")
    public ListResult listGet(
            @McpToolParam(required = true, description = CampaignResolver.DESCRIPTION) String campaign,
            @McpToolParam(required = true, description = "threads or characters") ListName list) {
        requireList(list);
        var entries = lists.findAll(resolver.resolve(campaign), list);
        if (entries.isEmpty()) return new ListResult(list, entries, list + ": empty.");
        long live = entries.stream().filter(entry -> entry.status() == list.liveStatus()).count();
        return new ListResult(list, entries, "%s: %d %s, %d %s.".formatted(
            list, live, list.liveStatus(), entries.size() - live, list.endedStatus()));
    }

    private static void requireList(ListName list) {
        if (list == null) throw new IllegalArgumentException("list is required; one of threads, characters");
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
    }

    private static int checkedWeight(int weight) {
        if (weight < 1 || weight > 3) throw new IllegalArgumentException("weight must be 1-3; got " + weight);
        return weight;
    }

    private static ListStatus checkedStatus(ListName list, ListStatus status) {
        if (!list.allows(status)) {
            throw new IllegalArgumentException("status for %s must be %s or %s; got %s".formatted(
                list, list.liveStatus(), list.endedStatus(), status));
        }
        return status;
    }

    /**
     * A new title may match only the entry being renamed (a change of case). Entries have no id, so that entry is
     * the one stored under the same spelling; titles are unique ignoring case, so no other entry shares it.
     */
    private void requireFreeTitle(UUID campaignId, ListName list, String newTitle, ListEntry renamed) {
        lists.find(campaignId, list, newTitle)
            .filter(taken -> !taken.title().equals(renamed.title()))
            .ifPresent(taken -> {
                throw new IllegalArgumentException(
                    "'" + taken.title() + "' is already on " + list + "; pick another new_title");
            });
    }

    private IllegalArgumentException unknownTitle(UUID campaignId, ListName list, String title) {
        var entries = lists.findAll(campaignId, list);
        var onTheList = entries.isEmpty() ? list + " is empty"
            : "on the list: " + entries.stream().map(ListEntry::title).collect(Collectors.joining(", "));
        return new IllegalArgumentException("no '" + title.trim() + "' on " + list + "; " + onTheList);
    }
}
