package dev.tableteller.support;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One migrated Postgres container for the whole test run. Tests that touch it call {@link #reset()} first. */
public final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final HikariDataSource DATA_SOURCE;
    private static final JdbcClient JDBC;
    private static final TransactionTemplate TRANSACTIONS;

    static {
        POSTGRES.start();
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setMaximumPoolSize(4);
        Flyway.configure().dataSource(DATA_SOURCE).load().migrate();
        JDBC = JdbcClient.create(DATA_SOURCE);
        TRANSACTIONS = new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
    }

    private TestDatabase() {
    }

    public static DataSource dataSource() {
        return DATA_SOURCE;
    }

    public static JdbcClient jdbc() {
        return JDBC;
    }

    public static TransactionTemplate transactions() {
        return TRANSACTIONS;
    }

    public static String url() {
        return POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    /** Empties every table and restarts id sequences, so roll ids start at r_0001 again. */
    public static void reset() {
        JDBC.sql("truncate campaign, scene, list_entry, roll_log, journal_entry, session_log, tracker "
            + "restart identity cascade").update();
    }
}
