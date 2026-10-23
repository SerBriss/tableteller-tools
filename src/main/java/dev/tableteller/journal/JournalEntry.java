package dev.tableteller.journal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A journal entry in full; {@code sceneNumber} is the scene of its latest write. */
public record JournalEntry(@JsonProperty("entry_id") UUID entryId, JournalKind kind, String title, String body,
        List<String> tags, @JsonProperty("scene_number") int sceneNumber, @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {
}
