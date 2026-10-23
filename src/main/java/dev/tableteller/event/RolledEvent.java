package dev.tableteller.event;

import java.util.List;

/** A random event and the rolls that made it: focus, then the target pick if any, then the meaning pair. */
public record RolledEvent(RandomEvent event, List<Integer> dice) {
}
