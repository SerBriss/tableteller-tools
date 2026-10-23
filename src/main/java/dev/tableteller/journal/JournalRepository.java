package dev.tableteller.journal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Journal entries, one per campaign, kind and title. Titles match trimmed and ignoring case; the stored spelling
 * is the one the entry was written or last renamed with.
 */
@Repository
public class JournalRepository {

    private static final String COLUMNS = "id, kind, title, body, tags, scene_number, created_at, updated_at";
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private final JdbcClient jdbc;

    public JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<JournalEntry> find(UUID campaignId, JournalKind kind, String title) {
        return jdbc.sql("select " + COLUMNS
                + " from journal_entry where campaign_id = ? and kind = ? and lower(title) = lower(?)")
            .params(campaignId, kind.name(), title.trim())
            .query(JournalRepository::entry)
            .optional();
    }

    public Optional<JournalEntry> findById(UUID campaignId, UUID entryId) {
        return jdbc.sql("select " + COLUMNS + " from journal_entry where campaign_id = ? and id = ?")
            .params(campaignId, entryId)
            .query(JournalRepository::entry)
            .optional();
    }

    /** Entries of every kind with this title, ordered by kind. */
    public List<JournalEntry> findByTitle(UUID campaignId, String title) {
        return jdbc.sql("select " + COLUMNS
                + " from journal_entry where campaign_id = ? and lower(title) = lower(?) order by kind")
            .params(campaignId, title.trim())
            .query(JournalRepository::entry)
            .list();
    }

    public JournalEntry insert(UUID campaignId, JournalKind kind, String title, String body, List<String> tags,
            int sceneNumber) {
        return jdbc.sql("insert into journal_entry (id, campaign_id, kind, title, body, tags, scene_number) "
                + "values (?, ?, ?, ?, ?, ?, ?) returning " + COLUMNS)
            .params(UUID.randomUUID(), campaignId, kind.name(), title.trim(), body, tags.toArray(String[]::new),
                sceneNumber)
            .query(JournalRepository::entry)
            .single();
    }

    /** Sets the title (pass the entry's own title to keep it), body, tags and scene; the search column follows. */
    public void update(UUID entryId, String title, String body, List<String> tags, int sceneNumber) {
        jdbc.sql("update journal_entry set title = ?, body = ?, tags = ?, scene_number = ?, updated_at = now()"
                + " where id = ?")
            .params(title.trim(), body, tags.toArray(String[]::new), sceneNumber, entryId)
            .update();
    }

    /** Titles of one kind, sorted ignoring case. */
    public List<String> titles(UUID campaignId, JournalKind kind) {
        return jdbc.sql("select title from journal_entry where campaign_id = ? and kind = ?"
                + " order by lower(title), title")
            .params(campaignId, kind.name())
            .query(String.class)
            .list();
    }

    /** The runs of letters and digits in a search query: the only part of it that reaches Postgres. */
    public static List<String> words(String query) {
        return WORD.matcher(query).results().map(MatchResult::group).toList();
    }

    /**
     * Entries in which every word is the start of a word in the title or body, best match first. Each word becomes
     * a prefix term ({@code varo:*}); words hold only letters and digits, so they cannot break the tsquery syntax.
     * A null {@code kind} searches every kind.
     */
    public List<SearchHit> search(UUID campaignId, List<String> words, JournalKind kind, int limit) {
        var query = words.stream().map(word -> word + ":*").collect(Collectors.joining(" & "));
        var params = new ArrayList<Object>(List.of(campaignId, query));
        if (kind != null) params.add(kind.name());
        params.add(query);
        params.add(limit);
        return jdbc.sql("select id, kind, title, body, tags, scene_number from journal_entry"
                + " where campaign_id = ? and search @@ to_tsquery('simple', ?)" + (kind == null ? "" : " and kind = ?")
                + " order by ts_rank(search, to_tsquery('simple', ?)) desc, updated_at desc, title limit ?")
            .params(params)
            .query((rs, row) -> new SearchHit(rs.getObject("id", UUID.class), JournalKind.valueOf(rs.getString("kind")),
                rs.getString("title"), SearchHit.excerpt(rs.getString("body")), tags(rs), rs.getInt("scene_number")))
            .list();
    }

    private static List<String> tags(ResultSet rs) throws SQLException {
        return List.of((String[]) rs.getArray("tags").getArray());
    }

    private static JournalEntry entry(ResultSet rs, int row) throws SQLException {
        return new JournalEntry(rs.getObject("id", UUID.class), JournalKind.valueOf(rs.getString("kind")),
            rs.getString("title"), rs.getString("body"), tags(rs), rs.getInt("scene_number"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
    }
}
