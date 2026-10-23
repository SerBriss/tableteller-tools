package dev.tableteller.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A random event as tools return it: focus, optional target and a meaning pair. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RandomEvent(String focus, EventTarget target, List<String> meaning) {

    /** One line for summaries, e.g. {@code A character acts → Innkeeper Varo (seek / ally)}. */
    public String describe() {
        return focus + (target == null ? "" : " → " + target.describe())
            + " (" + meaning.get(0) + " / " + meaning.get(1) + ")";
    }

    /** The event as a roll-log value: focus, target if any, meaning. */
    public Map<String, Object> toLogValue() {
        var map = new LinkedHashMap<String, Object>();
        map.put("focus", focus);
        if (target != null) map.put("target", target);
        map.put("meaning", meaning);
        return map;
    }
}
