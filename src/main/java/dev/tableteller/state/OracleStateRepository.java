package dev.tableteller.state;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chaos factor and scene number, stored on the campaign row. */
@Repository
public class OracleStateRepository {

    private final JdbcClient jdbc;

    public OracleStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reads the state and locks the campaign row until the surrounding transaction ends, so overlapping oracle
     * calls on one campaign run one after another. Call it inside a transaction.
     */
    public OracleState lock(UUID campaignId) {
        return jdbc.sql("select chaos_factor, scene_number from campaign where id = ? for update")
            .param(campaignId)
            .query((rs, row) -> new OracleState(rs.getInt("chaos_factor"), rs.getInt("scene_number")))
            .optional()
            .orElseThrow(() -> new IllegalStateException("no campaign " + campaignId));
    }

    public void saveChaos(UUID campaignId, int chaosFactor) {
        jdbc.sql("update campaign set chaos_factor = ? where id = ?").params(chaosFactor, campaignId).update();
    }

    public void saveSceneNumber(UUID campaignId, int sceneNumber) {
        jdbc.sql("update campaign set scene_number = ? where id = ?").params(sceneNumber, campaignId).update();
    }
}
