package dev.tableteller.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Play sessions. At most one per campaign is open; the database enforces it. */
@Repository
public class SessionRepository {

    private static final String COLUMNS = "id, status, started_at, ended_at, summary, open_questions";

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SessionLog> findOpen(UUID campaignId) {
        return jdbc.sql("select " + COLUMNS + " from session_log where campaign_id = ? and status = 'open'")
            .param(campaignId).query(SessionRepository::session).optional();
    }

    /** The session started most recently, whatever its status. */
    public Optional<SessionLog> findLatest(UUID campaignId) {
        return jdbc.sql("select " + COLUMNS
                + " from session_log where campaign_id = ? order by started_at desc limit 1")
            .param(campaignId).query(SessionRepository::session).optional();
    }

    /** A new open session, started now. */
    public SessionLog start(UUID campaignId) {
        return jdbc.sql("insert into session_log (id, campaign_id) values (?, ?) returning " + COLUMNS)
            .params(UUID.randomUUID(), campaignId).query(SessionRepository::session).single();
    }

    /** Marks a session that was never ended; its end time stays unknown. */
    public void abandon(UUID sessionId) {
        jdbc.sql("update session_log set status = 'abandoned' where id = ?").param(sessionId).update();
    }

    public void end(UUID sessionId, String summary, String openQuestions) {
        jdbc.sql("""
                update session_log set status = 'ended', ended_at = now(), summary = ?, open_questions = ?
                where id = ?""")
            .params(summary, openQuestions, sessionId)
            .update();
    }

    public List<SessionLog> findAll(UUID campaignId) {
        return jdbc.sql("select " + COLUMNS + " from session_log where campaign_id = ? order by started_at")
            .param(campaignId).query(SessionRepository::session).list();
    }

    private static SessionLog session(ResultSet rs, int row) throws SQLException {
        Timestamp endedAt = rs.getTimestamp("ended_at");
        return new SessionLog(rs.getObject("id", UUID.class), SessionStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("started_at").toInstant(), endedAt == null ? null : endedAt.toInstant(),
            rs.getString("summary"), rs.getString("open_questions"));
    }
}
