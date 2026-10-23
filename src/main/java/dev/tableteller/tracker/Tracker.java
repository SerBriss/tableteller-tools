package dev.tableteller.tracker;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A number the story keeps, such as Hunger or ammunition. The server never reads what it means; {@code min},
 * {@code max} and {@code note} are left out of the JSON when not set.
 */
public record Tracker(String name, int value, @JsonInclude(JsonInclude.Include.NON_NULL) Integer min,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer max,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note) {

    /** The limits as a suffix for a summary: {@code " (0 to 5)"}, {@code " (min 0)"}, {@code " (max 5)"} or "". */
    public String limits() {
        if (min != null && max != null) {
            return " (%d to %d)".formatted(min, max);
        }
        if (min != null) {
            return " (min %d)".formatted(min);
        }
        return max != null ? " (max %d)".formatted(max) : "";
    }
}
