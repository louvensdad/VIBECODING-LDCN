package com.vibecode.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The migration, run the two ways it will actually be run.
 *
 * <p>A fresh database has to reach the current schema, and an existing one at V7 has to reach it
 * without losing the rows already in it. The second is the one worth a test: V8 introduces a
 * foreign key in each direction between the two vault tables, and a migration that fails halfway
 * on a populated database is discovered in production or not at all.
 */
class VaultMigrationTest {

  private SimpleDriverDataSource database(String name) {
    SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
    dataSource.setDriverClass(org.h2.Driver.class);
    dataSource.setUrl(
        "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private Flyway flyway(SimpleDriverDataSource dataSource, String target) {
    org.flywaydb.core.api.configuration.FluentConfiguration configuration =
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    if (target != null) {
      configuration.target(org.flywaydb.core.api.MigrationVersion.fromVersion(target));
    }
    return configuration.load();
  }

  @Test
  @DisplayName("An empty database migrates from V1 to the current schema")
  void emptyDatabaseReachesLatest() {
    SimpleDriverDataSource dataSource = database("migration-empty-" + UUID.randomUUID());

    flyway(dataSource, null).migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(tableExists(jdbc, "vault_secrets")).isTrue();
    assertThat(tableExists(jdbc, "vault_secret_versions")).isTrue();
    assertThat(tableExists(jdbc, "provider_accounts")).isTrue();

    // The vault starts empty. A migration that seeded a secret would be seeding a key.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_secrets", Integer.class)).isZero();
  }

  @Test
  @DisplayName("A database already at V7 migrates to the current schema and keeps its rows")
  void existingDatabaseIsPreserved() {
    SimpleDriverDataSource dataSource = database("migration-v7-" + UUID.randomUUID());
    flyway(dataSource, "7").migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID userId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, email, password_hash, display_name, status, role, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        userId,
        "existing-user@example.com",
        "not-a-real-hash",
        "Existing User",
        "ACTIVE",
        "USER",
        Instant.now(),
        Instant.now());

    flyway(dataSource, null).migrate();

    // The user survived, and the new tables arrived alongside them rather than instead of them.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId))
        .isEqualTo(1);
    assertThat(tableExists(jdbc, "vault_secrets")).isTrue();
    assertThat(tableExists(jdbc, "provider_accounts")).isTrue();

    // And every existing user starts with no provider connections, rather than a placeholder one.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_accounts", Integer.class))
        .isZero();
  }

  private boolean tableExists(JdbcTemplate jdbc, String table) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?",
            Integer.class,
            table);
    return count != null && count > 0;
  }
}
