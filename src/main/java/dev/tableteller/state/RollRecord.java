package dev.tableteller.state;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A stored roll-log row. */
public record RollRecord(long id, UUID campaignId, String tool, Map<String, Object> inputs, List<Integer> dice,
        Map<String, Object> result, int sceneNumber, Instant createdAt) {

    /** The id tools return, for example {@code r_0142}. */
    public String rollId() {
        return "r_%04d".formatted(id);
    }
}
