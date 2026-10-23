package dev.tableteller.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class MigrationTest {
    @BeforeEach
    void reset() {
        TestDatabase.reset();
    }

    @Test
    void createsTheTables() {
        assertThat(tables()).contains("campaign", "scene", "list_entry", "roll_log", "journal_entry", "session_log",
            "tracker");
    }

    @Test
    void chaosFactorIsCheckedByTheDatabase() {
        assertThatThrownBy(() -> insertCampaign("Ashfall", 10)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void campaignNamesAreUniqueIgnoringCase() {
        insertCampaign("Ashfall", 5);
        assertThatThrownBy(() -> insertCampaign("ASHFALL", 5)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void listTitlesAreUniquePerListIgnoringCase() {
        var campaign = insertCampaign("Ashfall", 5);
        insertEntry(campaign, "thread", "Find the heir");
        insertEntry(campaign, "character", "Find the heir");   // another list: allowed
        assertThatThrownBy(() -> insertEntry(campaign, "thread", "find the HEIR"))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void journalTitlesAreUniquePerKindIgnoringCase() {
        var campaign = insertCampaign("Ashfall", 5);
        insertJournal(campaign, "npc", "Таверна");
        insertJournal(campaign, "location", "Таверна");   // another kind: allowed
        assertThatThrownBy(() -> insertJournal(campaign, "npc", "таверна")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void journalKindIsCheckedByTheDatabase() {
        var campaign = insertCampaign("Ashfall", 5);
        assertThatThrownBy(() -> insertJournal(campaign, "monster", "Wyrm"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void atMostOneOpenSessionPerCampaign() {
        var campaign = insertCampaign("Ashfall", 5);
        var other = insertCampaign("Iron Coast", 5);
        insertSession(campaign, "ended");
        insertSession(campaign, "open");
        insertSession(other, "open");   // another campaign: allowed
        assertThatThrownBy(() -> insertSession(campaign, "open")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void trackerNamesAreUniquePerCampaignIgnoringCase() {
        var campaign = insertCampaign("Ashfall", 5);
        var other = insertCampaign("Iron Coast", 5);
        insertTracker(campaign, "Голод", 1, null, null);
        insertTracker(other, "Голод", 1, null, null);   // another campaign: allowed
        assertThatThrownBy(() -> insertTracker(campaign, "голод", 1, null, null))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void trackerLimitsAreCheckedByTheDatabase() {
        var campaign = insertCampaign("Ashfall", 5);
        assertThatThrownBy(() -> insertTracker(campaign, "Голод", 6, 0, 5))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTracker(campaign, "Сила воли", 5, 5, 5))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static List<String> tables() {
        return TestDatabase.jdbc()
            .sql("select table_name from information_schema.tables where table_schema = 'public'")
            .query(String.class).list();
    }

    private static void insertJournal(UUID campaign, String kind, String title) {
        TestDatabase.jdbc()
            .sql("insert into journal_entry (id, campaign_id, kind, title, body, scene_number) values (?, ?, ?, ?, '', 0)")
            .params(UUID.randomUUID(), campaign, kind, title)
            .update();
    }

    private static void insertSession(UUID campaign, String status) {
        TestDatabase.jdbc().sql("insert into session_log (id, campaign_id, status) values (?, ?, ?)")
            .params(UUID.randomUUID(), campaign, status).update();
    }

    private static void insertTracker(UUID campaign, String name, int value, Integer min, Integer max) {
        TestDatabase.jdbc()
            .sql("insert into tracker (id, campaign_id, name, value, min_value, max_value) values (?, ?, ?, ?, ?, ?)")
            .params(UUID.randomUUID(), campaign, name, value, min, max)
            .update();
    }

    private static UUID insertCampaign(String name, int chaos) {
        var id = UUID.randomUUID();
        TestDatabase.jdbc().sql("insert into campaign (id, name, chaos_factor) values (?, ?, ?)")
            .params(id, name, chaos).update();
        return id;
    }

    private static void insertEntry(UUID campaign, String list, String title) {
        TestDatabase.jdbc()
            .sql("insert into list_entry (id, campaign_id, list, title, status) values (?, ?, ?, ?, ?)")
            .params(UUID.randomUUID(), campaign, list, title, list.equals("thread") ? "open" : "active")
            .update();
    }
}
