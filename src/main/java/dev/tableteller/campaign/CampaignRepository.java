package dev.tableteller.campaign;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CampaignRepository {

    private static final String COLUMNS =
        "id, name, setting, tone, pc_summary, pc_skills, chaos_factor, scene_number, created_at";

    private final JdbcClient jdbc;

    public CampaignRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new campaign at chaos 5 with no scenes. */
    public Campaign create(String name, String setting, String tone, String pcSummary, String pcSkills) {
        var id = UUID.randomUUID();
        jdbc.sql("insert into campaign (id, name, setting, tone, pc_summary, pc_skills) values (?, ?, ?, ?, ?, ?)")
            .params(id, name, setting, tone, pcSummary, pcSkills)
            .update();
        return findById(id).orElseThrow();
    }

    /** Sets the fields that are not null and keeps the others. */
    public void update(UUID id, String setting, String tone, String pcSummary, String pcSkills) {
        jdbc.sql("update campaign set setting = coalesce(?, setting), tone = coalesce(?, tone), "
                + "pc_summary = coalesce(?, pc_summary), pc_skills = coalesce(?, pc_skills) where id = ?")
            .params(setting, tone, pcSummary, pcSkills, id)
            .update();
    }

    public Optional<Campaign> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from campaign where id = ?").param(id)
            .query(CampaignRepository::campaign).optional();
    }

    /** Matches the trimmed name, ignoring case. */
    public Optional<Campaign> findByName(String name) {
        return jdbc.sql("select " + COLUMNS + " from campaign where lower(name) = lower(?)").param(name.trim())
            .query(CampaignRepository::campaign).optional();
    }

    /** Every campaign name, sorted ignoring case. */
    public List<String> names() {
        return jdbc.sql("select name from campaign order by lower(name)").query(String.class).list();
    }

    private static Campaign campaign(ResultSet rs, int row) throws SQLException {
        return new Campaign(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("setting"),
            rs.getString("tone"), rs.getString("pc_summary"), rs.getString("pc_skills"), rs.getInt("chaos_factor"),
            rs.getInt("scene_number"), rs.getTimestamp("created_at").toInstant());
    }
}
