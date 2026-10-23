package dev.tableteller.scene;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SceneRepository {

    private static final String COLUMNS = "number, expected_setup, actual_setup, check_result, summary, status";

    private final JdbcClient jdbc;

    public SceneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Scene> findOpen(UUID campaignId) {
        return jdbc.sql("select " + COLUMNS + " from scene where campaign_id = ? and status = 'open'")
            .param(campaignId).query(SceneRepository::scene).optional();
    }

    public void open(UUID campaignId, int number, String expectedSetup, SceneResult result, String actualSetup) {
        jdbc.sql("""
                insert into scene (campaign_id, number, expected_setup, actual_setup, check_result, status)
                values (?, ?, ?, ?, ?, 'open')""")
            .params(campaignId, number, expectedSetup, actualSetup, result.name())
            .update();
    }

    public void close(UUID campaignId, int number, String actualSetup, String summary) {
        jdbc.sql("update scene set actual_setup = ?, summary = ?, status = 'closed' where campaign_id = ? and number = ?")
            .params(actualSetup, summary, campaignId, number)
            .update();
    }

    public List<Scene> findAll(UUID campaignId) {
        return jdbc.sql("select " + COLUMNS + " from scene where campaign_id = ? order by number")
            .param(campaignId).query(SceneRepository::scene).list();
    }

    /** The last {@code count} scenes, oldest first. */
    public List<Scene> findLast(UUID campaignId, int count) {
        return jdbc.sql("select * from (select " + COLUMNS
                + " from scene where campaign_id = ? order by number desc limit ?) latest order by number")
            .params(campaignId, count).query(SceneRepository::scene).list();
    }

    private static Scene scene(ResultSet rs, int row) throws SQLException {
        return new Scene(rs.getInt("number"), rs.getString("expected_setup"), rs.getString("actual_setup"),
            SceneResult.valueOf(rs.getString("check_result")), rs.getString("summary"),
            rs.getString("status").equals("open"));
    }
}
