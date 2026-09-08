package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the audit trail is allowed to know about a compilation, and what it is not.
 *
 * <p>Audit may record that a thing happened to a project: ids, an action, a result, a count. It may
 * not record what the thing said. A pack's content in {@code audit_events.metadata} would be the
 * same leak as a pack's content in a log line, with the added property that the audit trail is
 * deliberately retained after the project it describes is deleted.
 *
 * <p>The control that makes this non-vacuous is a real denial: Bob asks for Alice's project, the
 * access gate refuses him and writes an audit row. So the assertions below run against a table that
 * demonstrably received a row during the very flow under test, rather than against an empty table
 * that would satisfy any assertion at all.
 */
class ContextAuditLeakTest extends ContextProbeFixture {

  private Planted planted;

  @BeforeEach
  void plant() {
    planted = plantTheProbeEverywhere("audit-alice");
  }

  @Test
  @DisplayName("Compiling writes no audit row carrying pack content, a probe, or evidence")
  void auditKeepsNoneOfWhatThePackSays() {
    Instant before = Instant.now().minusSeconds(1);
    CompiledContextPack compiled = assembler.assemble(planted.projectId(), "CTX-09 audit", GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();

    List<Map<String, Object>> events =
        jdbc.queryForList("SELECT * FROM audit_events WHERE created_at >= ?", before);

    for (Map<String, Object> event : events) {
      String row = stringify(event);
      assertThat(row).doesNotContain(PROBE);
      assertThat(row).doesNotContain(DENIED_MARKER);
      assertThat(row).doesNotContain("zqxw_928472");
    }

    // Nothing the pack said, either — not just nothing secret. An admitted item's content is
    // project prose the audit trail has no business retaining.
    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      String content = admitted.item().content();
      for (Map<String, Object> event : events) {
        assertThat(stringify(event))
            .as("audit row repeating the content of item %s", admitted.id())
            .doesNotContain(content);
      }
    }
  }

  @Test
  @DisplayName("Bob compiling Alice's project is refused as not found, and audited without content")
  void theDenialIsAuditedAsMetadataAndReportedAsNotFound() {
    Instant before = Instant.now().minusSeconds(1);
    identity.clear();
    identity.createAndAuthenticate("audit-bob");

    assertThatThrownBy(() -> assembler.assemble(planted.projectId(), "CTX-09 bob", GENEROUS))
        .as("another user's project is not found, never forbidden — a 403 would confirm it exists")
        .isInstanceOf(ResourceNotFoundException.class);

    List<Map<String, Object>> denials =
        jdbc.queryForList(
            "SELECT * FROM audit_events WHERE created_at >= ? AND event_type = ?",
            before,
            "CROSS_USER_ACCESS_DENIED");

    // The non-vacuous control. Without a row here every assertion below is about nothing.
    assertThat(denials)
        .as("the refusal must have been audited, or the emptiness below proves nothing")
        .isNotEmpty();

    for (Map<String, Object> denial : denials) {
      String row = stringify(denial);
      assertThat(row).doesNotContain(PROBE).doesNotContain(DENIED_MARKER);
      assertThat(row)
          .as("a denial names the project, it does not describe it")
          .doesNotContain("Probe project");
      assertThat(String.valueOf(denial.get("target_id")))
          .as("the id is the whole of what a denial needs to say")
          .contains(planted.projectId().toString());
    }

    // And nothing of Alice's was compiled for Bob on the way to the refusal.
    Integer packsForBob =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_packs WHERE task_reference = ?",
            Integer.class,
            "CTX-09 bob");
    assertThat(packsForBob).isZero();
  }

  private static String stringify(Map<String, Object> row) {
    StringBuilder text = new StringBuilder();
    for (Object value : row.values()) {
      text.append(String.valueOf(value)).append(" | ");
    }
    return text.toString();
  }
}
