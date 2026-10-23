package dev.tableteller.state;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Append-only roll log: rows are added, never updated or deleted. Inputs, dice and result are jsonb. */
@Repository
public class RollLogRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Integer>> DICE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    public RollLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RollRecord append(UUID campaignId, NewRoll roll) {
        return jdbc.sql("""
                insert into roll_log (campaign_id, tool, inputs, dice, result, scene_number)
                values (?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
                returning id, campaign_id, tool, inputs, dice, result, scene_number, created_at""")
            .params(campaignId, roll.tool(), JSON.writeValueAsString(roll.inputs()),
                JSON.writeValueAsString(roll.dice()), JSON.writeValueAsString(roll.result()), roll.sceneNumber())
            .query(RollLogRepository::record)
            .single();
    }

    public List<RollRecord> findAll(UUID campaignId) {
        return jdbc.sql("""
                select id, campaign_id, tool, inputs, dice, result, scene_number, created_at
                from roll_log where campaign_id = ? order by id""")
            .param(campaignId)
            .query(RollLogRepository::record)
            .list();
    }

    /** The newest {@code count} rows, oldest first. */
    public List<RollRecord> findLast(UUID campaignId, int count) {
        return jdbc.sql("""
                select * from (
                  select id, campaign_id, tool, inputs, dice, result, scene_number, created_at
                  from roll_log where campaign_id = ? order by id desc limit ?
                ) newest order by id""")
            .params(campaignId, count)
            .query(RollLogRepository::record)
            .list();
    }

    public List<RollRecord> findByScene(UUID campaignId, int sceneNumber) {
        return jdbc.sql("""
                select id, campaign_id, tool, inputs, dice, result, scene_number, created_at
                from roll_log where campaign_id = ? and scene_number = ? order by id""")
            .params(campaignId, sceneNumber)
            .query(RollLogRepository::record)
            .list();
    }

    private static RollRecord record(ResultSet rs, int row) throws SQLException {
        return new RollRecord(rs.getLong("id"), rs.getObject("campaign_id", UUID.class), rs.getString("tool"),
            JSON.readValue(rs.getString("inputs"), MAP), JSON.readValue(rs.getString("dice"), DICE),
            JSON.readValue(rs.getString("result"), MAP), rs.getInt("scene_number"),
            rs.getTimestamp("created_at").toInstant());
    }
}
