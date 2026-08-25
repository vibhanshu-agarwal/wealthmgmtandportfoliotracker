package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migration tests for V14-V16 (.kiro/specs/new-user-signup-profile, Requirement 8.3-8.7).
 * Run via: ./gradlew :portfolio-service:integrationTest
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class AuthSchemaMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer(TestContainerImages.POSTGRES)
          .withDatabaseName("portfolio_db")
          .withUsername("wealth_user")
          .withPassword("wealth_pass");

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void v20IsHighestAppliedVersionAndBetterAuthTablesAreAbsent() {
    String maxVersion = jdbcTemplate.queryForObject(
        "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
        String.class);
    assertThat(maxVersion).isEqualTo("20");

    List<String> baTables = jdbcTemplate.queryForList(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
            + "AND table_name IN ('ba_user','ba_session','ba_account','ba_verification')",
        String.class);
    assertThat(baTables).isEmpty();
  }

  @Test
  void demoDevAndE2eUsersEachResolveToExactlyOneRowPair() {
    List<String> ids = List.of(
        "00000000-0000-0000-0000-0000000d3110",
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000e2e");
    for (String id : ids) {
      Integer userCount = jdbcTemplate.queryForObject(
          "SELECT count(*) FROM users WHERE id = ?::uuid", Integer.class, id);
      Integer credCount = jdbcTemplate.queryForObject(
          "SELECT count(*) FROM user_credentials WHERE user_id = ?::uuid", Integer.class, id);
      assertThat(userCount).as("users row for %s", id).isEqualTo(1);
      assertThat(credCount).as("user_credentials row for %s", id).isEqualTo(1);
    }
  }

  @Test
  void demoAccountIsReadOnlyAndOwnsTheShowcasePortfolioNonEmpty() {
    Boolean readOnly = jdbcTemplate.queryForObject(
        "SELECT read_only FROM users WHERE id = '00000000-0000-0000-0000-0000000d3110'::uuid",
        Boolean.class);
    assertThat(readOnly).isTrue();

    Integer devOwnedCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM portfolios WHERE user_id = '00000000-0000-0000-0000-000000000001'",
        Integer.class);
    assertThat(devOwnedCount)
        .as("V20 backfill must provision the dev user's empty primary portfolio")
        .isEqualTo(1);

    Integer demoHoldingCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id "
            + "WHERE p.user_id = '00000000-0000-0000-0000-0000000d3110'",
        Integer.class);
    assertThat(demoHoldingCount).as("demo account's showcase portfolio must have holdings").isGreaterThan(0);
  }

  @Test
  void reRunningMigrateIsIdempotent() {
    // Flyway already ran once via Spring Boot's auto-migrate on context startup.
    // Re-invoking migrate() directly must be a no-op (no duplicate rows, no error).
    org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load();
    flyway.migrate(); // no-op: already at V20

    Integer demoCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM users WHERE id = '00000000-0000-0000-0000-0000000d3110'::uuid",
        Integer.class);
    assertThat(demoCount).isEqualTo(1);
  }
}
