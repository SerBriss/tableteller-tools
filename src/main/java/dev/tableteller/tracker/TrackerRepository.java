package dev.tableteller.tracker;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Trackers. Names match trimmed and ignoring case; the stored spelling is the one the tracker was added with.
 */
@Repository
public class TrackerRepository {

    private final JdbcClient jdbc;

    public TrackerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tracker> find(UUID campaignId, String name) {
        return jdbc.sql("""
                select name, value, min_value, max_value, note from tracker
                where campaign_id = ? and lower(name) = lower(?)""")
            .params(campaignId, name.trim())
            .query(TrackerRepository::tracker)
            .optional();
    }

    /** Every tracker of the campaign, in the order added. */
    public List<Tracker> findAll(UUID campaignId) {
        return jdbc.sql("""
                select name, value, min_value, max_value, note from tracker where campaign_id = ?
                order by created_at, name""")
            .params(campaignId)
            .query(TrackerRepository::tracker)
            .list();
    }

    public void add(UUID campaignId, Tracker tracker) {
        jdbc.sql("""
                insert into tracker (id, campaign_id, name, value, min_value, max_value, note)
                values (?, ?, ?, ?, ?, ?, ?)""")
            .params(UUID.randomUUID(), campaignId, tracker.name().trim(), tracker.value(), tracker.min(),
                tracker.max(), tracker.note())
            .update();
    }

    public void setValue(UUID campaignId, String name, int value) {
        jdbc.sql("""
                update tracker set value = ?, updated_at = now()
                where campaign_id = ? and lower(name) = lower(?)""")
            .params(value, campaignId, name.trim())
            .update();
    }

    public void remove(UUID campaignId, String name) {
        jdbc.sql("delete from tracker where campaign_id = ? and lower(name) = lower(?)")
            .params(campaignId, name.trim())
            .update();
    }

    private static Tracker tracker(ResultSet rs, int row) throws SQLException {
        return new Tracker(rs.getString("name"), rs.getInt("value"), nullableInt(rs, "min_value"),
            nullableInt(rs, "max_value"), rs.getString("note"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
