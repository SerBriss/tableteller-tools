package dev.tableteller.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tableteller.lists.ListName;

/**
 * Who a random event is about: an entry picked from a list, or, when that list has no live entries,
 * {@code new: true}, telling the DM to introduce one and list_add it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventTarget(ListName list, String title, @JsonProperty("new") Boolean newEntry) {

    public static EventTarget existing(ListName list, String title) {
        return new EventTarget(list, title, null);
    }

    public static EventTarget fresh(ListName list) {
        return new EventTarget(list, null, true);
    }

    String describe() {
        return title != null ? title : "a new " + list.singular();
    }
}
