package com.wealth.portfolio.repair;

import com.wealth.portfolio.TestContainerImages;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One shared Postgres container, one database per test so an aborting migration
 * cannot poison later cases.
 */
public final class PostgresRepairHarness {

    public static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    private PostgresRepairHarness() {}

    public static Session newSession() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        String dbName = "r" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = POSTGRES.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + dbName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create test database " + dbName, e);
        }
        String jdbcUrl =
                "jdbc:postgresql://"
                        + POSTGRES.getHost()
                        + ":"
                        + POSTGRES.getMappedPort(5432)
                        + "/"
                        + dbName;
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return new Session(dataSource);
    }

    public static final class Session {
        private final DataSource dataSource;
        private final JdbcTemplate jdbc;

        Session(DataSource dataSource) {
            this.dataSource = dataSource;
            this.jdbc = new JdbcTemplate(dataSource);
        }

        public JdbcTemplate jdbc() {
            return jdbc;
        }

        public void migrateTo(String version) {
            flyway(version).migrate();
        }

        public void migrateRemaining() {
            flyway(null).migrate();
        }

        public void remigrateRepairVersions() {
            jdbc.update("DELETE FROM flyway_schema_history WHERE version IN ('17', '18', '19')");
            migrateRemaining();
        }

        private Flyway flyway(String target) {
            var config =
                    Flyway.configure()
                            .dataSource(dataSource)
                            .locations("classpath:db/migration");
            if (target != null) {
                config.target(target);
            }
            return config.load();
        }
    }
}
