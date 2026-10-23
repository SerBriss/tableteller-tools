package dev.tableteller.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * One play session. An ended session has its summary and open questions; an abandoned one was never ended, so it
 * has neither, and no {@code endedAt}.
 */
public record SessionLog(@JsonProperty("session_id") UUID sessionId, SessionStatus status,
        @JsonProperty("started_at") Instant startedAt, @JsonProperty("ended_at") Instant endedAt, String summary,
        @JsonProperty("open_questions") String openQuestions) {
}
