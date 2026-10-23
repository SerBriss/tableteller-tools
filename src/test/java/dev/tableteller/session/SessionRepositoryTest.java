package dev.tableteller.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tableteller.campaign.CampaignRepository;
import dev.tableteller.support.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionRepositoryTest {
    CampaignRepository campaigns = new CampaignRepository(TestDatabase.jdbc());
    SessionRepository sessions = new SessionRepository(TestDatabase.jdbc());
    UUID campaign;

    @BeforeEach
    void reset() {
        TestDatabase.reset();
        campaign = campaigns.create("Ashfall", null, null, null, null).id();
    }

    @Test
    void startAbandonAndEndSetStatusAndTimes() {
        var first = sessions.start(campaign);
        assertThat(first.status()).isEqualTo(SessionStatus.open);
        assertThat(first.startedAt()).isNotNull();
        assertThat(first.endedAt()).isNull();
        sessions.abandon(first.sessionId());
        var second = sessions.start(campaign);
        sessions.end(second.sessionId(), "Mara reached the city.", "Who hired the guard?");

        var all = sessions.findAll(campaign);
        assertThat(all.getFirst())
            .isEqualTo(new SessionLog(first.sessionId(), SessionStatus.abandoned, first.startedAt(), null, null, null));
        var ended = all.getLast();
        assertThat(ended.sessionId()).isEqualTo(second.sessionId());
        assertThat(ended.status()).isEqualTo(SessionStatus.ended);
        assertThat(ended.endedAt()).isAfter(ended.startedAt());
        assertThat(ended.summary()).isEqualTo("Mara reached the city.");
        assertThat(ended.openQuestions()).isEqualTo("Who hired the guard?");
    }

    @Test
    void findLatestIsTheNewestStart() {
        var first = sessions.start(campaign);
        sessions.end(first.sessionId(), "Mara reached the city.", "none");
        var second = sessions.start(campaign);
        assertThat(sessions.findLatest(campaign)).get().extracting(SessionLog::sessionId).isEqualTo(second.sessionId());
        assertThat(sessions.findOpen(campaign)).get().extracting(SessionLog::sessionId).isEqualTo(second.sessionId());
    }
}
