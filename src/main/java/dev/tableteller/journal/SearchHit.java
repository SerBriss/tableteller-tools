package dev.tableteller.journal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** A journal_search result: the entry with an excerpt of its body instead of the whole body. */
public record SearchHit(@JsonProperty("entry_id") UUID entryId, JournalKind kind, String title, String excerpt,
        List<String> tags, @JsonProperty("scene_number") int sceneNumber) {

    private static final int EXCERPT_LENGTH = 300;

    /** The first 300 characters of the body, ending in … when the body is longer; never splits a surrogate pair. */
    public static String excerpt(String body) {
        if (body.codePointCount(0, body.length()) <= EXCERPT_LENGTH) return body;
        return body.substring(0, body.offsetByCodePoints(0, EXCERPT_LENGTH)) + "…";
    }
}
