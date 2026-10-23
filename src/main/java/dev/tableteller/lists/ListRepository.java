package dev.tableteller.lists;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Threads and characters. Titles match trimmed and ignoring case; the stored spelling is the one the entry was added
 * or last renamed with.
 */
@Repository
public class ListRepository {

    private final JdbcClient jdbc;

    public ListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ListEntry> find(UUID campaignId, ListName list, String title) {
        return jdbc.sql("""
                select title, weight, status from list_entry
                where campaign_id = ? and list = ? and lower(title) = lower(?)""")
            .params(campaignId, list.dbValue(), title.trim())
            .query(ListRepository::entry)
            .optional();
    }

    public void add(UUID campaignId, ListName list, String title, int weight, ListStatus status) {
        jdbc.sql("insert into list_entry (id, campaign_id, list, title, weight, status) values (?, ?, ?, ?, ?, ?)")
            .params(UUID.randomUUID(), campaignId, list.dbValue(), title.trim(), weight, status.name())
            .update();
    }

    /** Sets the title (pass the entry's own title to keep it), weight and status of the entry {@code title} matches. */
    public void update(UUID campaignId, ListName list, String title, String newTitle, int weight, ListStatus status) {
        jdbc.sql("""
                update list_entry set title = ?, weight = ?, status = ?
                where campaign_id = ? and list = ? and lower(title) = lower(?)""")
            .params(newTitle.trim(), weight, status.name(), campaignId, list.dbValue(), title.trim())
            .update();
    }

    /** Live entries first, then ended ones, each in the order added. */
    public List<ListEntry> findAll(UUID campaignId, ListName list) {
        return jdbc.sql("""
                select title, weight, status from list_entry where campaign_id = ? and list = ?
                order by (status = ?) desc, created_at, title""")
            .params(campaignId, list.dbValue(), list.liveStatus().name())
            .query(ListRepository::entry)
            .list();
    }

    /** The entries random events pick from: open threads or active characters, in the order added. */
    public List<ListEntry> live(UUID campaignId, ListName list) {
        return jdbc.sql("""
                select title, weight, status from list_entry where campaign_id = ? and list = ? and status = ?
                order by created_at, title""")
            .params(campaignId, list.dbValue(), list.liveStatus().name())
            .query(ListRepository::entry)
            .list();
    }

    private static ListEntry entry(ResultSet rs, int row) throws SQLException {
        return new ListEntry(rs.getString("title"), rs.getInt("weight"), ListStatus.valueOf(rs.getString("status")));
    }
}
