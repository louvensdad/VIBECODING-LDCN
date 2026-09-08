package com.vibecode.context.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * V9, run the two ways it will actually be run.
 *
 * <p>A fresh database has to reach the current schema, and a developer's database already at V8 has
 * to reach it without losing the rows in it. The second is the one worth a test: V9 adds a foreign
 * key onto {@code projects}, and a migration that fails halfway on a populated database is
 * discovered in production or not at all.
 *
 * <p>Every database here is an in-memory H2 named after a fresh UUID, so nothing this test does can
 * reach a real one.
 */
class ContextPackMigrationTest {

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
    SimpleDriverDataSource dataSource = database("context-migration-empty-" + UUID.randomUUID());

    flyway(dataSource, null).migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(tableExists(jdbc, "context_packs")).isTrue();
    assertThat(tableExists(jdbc, "context_pack_items")).isTrue();

    // No seeded pack. A migration that invented one would be inventing context nobody selected.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM context_packs", Integer.class)).isZero();
  }

  @Test
  @DisplayName("A database already at V8 migrates to the current schema and keeps its rows")
  void existingDatabaseIsPreserved() {
    SimpleDriverDataSource dataSource = database("context-migration-v8-" + UUID.randomUUID());
    flyway(dataSource, "8").migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID userId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, email, password_hash, display_name, status, role, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        userId,
        "context-owner@example.com",
        "not-a-real-hash",
        "Context Owner",
        "ACTIVE",
        "USER",
        Instant.now(),
        Instant.now());
    UUID projectId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO projects (id, name, description, original_idea, status, created_at, "
            + "updated_at, owner_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        projectId,
        "Existing project",
        "Created before the context engine existed",
        "An idea recorded at V8",
        "ACTIVE",
        Instant.now(),
        Instant.now(),
        userId);

    flyway(dataSource, null).migrate();

    // The rows survived, and the new tables arrived alongside them rather than instead of them.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE id = ?", Integer.class, projectId))
        .isEqualTo(1);
    assertThat(tableExists(jdbc, "context_packs")).isTrue();
    assertThat(tableExists(jdbc, "context_pack_items")).isTrue();

    // And a project that predates the engine starts with no packs, rather than a placeholder one.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM context_packs WHERE project_id = ?", Integer.class, projectId))
        .isZero();
  }

  @Test
  @DisplayName("No constraint or index makes the content fingerprint an identity")
  void theFingerprintIsNotAKey() {
    SimpleDriverDataSource dataSource = database("context-migration-keys-" + UUID.randomUUID());
    flyway(dataSource, null).migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // Asked of the schema rather than of the SQL text: a UNIQUE constraint, a primary key or a
    // foreign key added later would show up here whatever it was called.
    Integer constraints =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.key_column_usage "
                + "WHERE LOWER(column_name) LIKE '%fingerprint%' AND LOWER(table_name) LIKE 'context%'",
            Integer.class);
    assertThat(constraints).isZero();

    // An index would not be a uniqueness constraint, but it would be someone preparing to look a
    // pack up by its fingerprint, which is the thing the digest must never be used for.
    Integer indexes =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.index_columns "
                + "WHERE LOWER(column_name) LIKE '%fingerprint%' AND LOWER(table_name) LIKE 'context%'",
            Integer.class);
    assertThat(indexes).isZero();
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
