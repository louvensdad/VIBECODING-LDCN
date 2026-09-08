package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.context.application.source.ContextReadWindow;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What the compiler does with input a caller should not have sent.
 *
 * <p>Three failure modes are being ruled out, and they are different from each other. A raw crash
 * (an unhandled runtime exception escaping as a 500) tells an attacker the shape of the internals.
 * A silent truncation stores something the caller did not ask for and never says so, which is worse
 * than a refusal because the resulting pack looks correct. Running out of memory on a large input
 * takes the process down for everyone else. Every case below is checked for a controlled,
 * argument-shaped refusal, and for nothing having been written.
 *
 * <p>Redaction amplification lives here too, because it arrives as a boundary failure: a caller
 * sends a reference comfortably inside the cap, redaction replaces a short secret with a longer
 * marker, and the value that reaches the domain is over. The engine must refuse it in one piece —
 * not truncate it, and not let a database constraint be the thing that notices.
 */
class ContextBoundaryInputTest extends ContextProbeFixture {

  private Planted planted;

  @BeforeEach
  void plant() {
    planted = plantTheProbeEverywhere("boundary-owner");
  }

  @Test
  @DisplayName("Absent arguments are refused as arguments, not as internal failures")
  void nothingMissingBecomesAnInternalFailure() {
    assertThatThrownBy(() -> assembler.assemble(null, "CTX-09", GENEROUS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("project");

    assertThatThrownBy(() -> assembler.assemble(planted.projectId(), "CTX-09", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("budget");

    assertThatThrownBy(() -> assembler.assemble(planted.projectId(), null, GENEROUS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("task");

    assertThatThrownBy(() -> assembler.assemble(planted.projectId(), "   ", GENEROUS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("task");

    assertThatThrownBy(() -> new ContextReadWindow(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContextBudget(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);

    assertThat(packRowsFor(planted.projectId())).isZero();
  }

  @Test
  @DisplayName("A project id that belongs to nobody is not found, and is not a 500")
  void anUnknownProjectIsNotFound() {
    assertThatThrownBy(() -> assembler.assemble(UUID.randomUUID(), "CTX-09 nowhere", GENEROUS))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("An over-long task reference is refused whole, never cut down to fit")
  void anOverLongReferenceIsRefusedRatherThanTrimmed() {
    String tooLong = "CTX-09 ".repeat(120); // 840 characters
    assertThat(tooLong.length()).isGreaterThan(ContextPack.MAX_TASK_REFERENCE_LENGTH);

    assertThatThrownBy(() -> assembler.assemble(planted.projectId(), tooLong, GENEROUS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(String.valueOf(ContextPack.MAX_TASK_REFERENCE_LENGTH))
        .as("the domain refuses it, not the INSERT")
        .isNotInstanceOf(DataIntegrityViolationException.class);

    assertThat(packRowsFor(planted.projectId()))
        .as("a refused compilation writes nothing at all")
        .isZero();
  }

  @Test
  @DisplayName("Redaction lengthens a reference inside the cap past it, and the pack is refused")
  void redactionAmplificationOnTheTaskReferenceIsAControlledRefusal() {
    // Under the cap on the way in. "TOKEN=" + the probe is 41 characters and becomes
    // "TOKEN=[REDACTED]" — 16 — so this one shrinks; to grow it, the value has to be shorter than
    // the marker. A four-character value is replaced by ten.
    // The leading space matters: the redactor's key pattern is anchored on a word boundary, so
    // "RRRRTOKEN=abcd" is not a match and "RRRR TOKEN=abcd" is.
    String shortSecret = " TOKEN=abcd"; // 11 chars in, " TOKEN=[REDACTED]" — 17 — out
    String padding = "R".repeat(ContextPack.MAX_TASK_REFERENCE_LENGTH - shortSecret.length());
    String reference = padding + shortSecret;
    assertThat(reference.length()).isEqualTo(ContextPack.MAX_TASK_REFERENCE_LENGTH);

    Throwable refusal =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> assembler.assemble(planted.projectId(), reference, GENEROUS));

    assertThat(refusal)
        .as("a value that grew past the cap during redaction is an argument problem, not a crash")
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(refusal).hasMessageContaining(String.valueOf(ContextPack.MAX_TASK_REFERENCE_LENGTH));

    for (Throwable link = refusal; link != null; link = link.getCause()) {
      assertThat(link)
          .as("no database constraint may be the thing that notices")
          .isNotInstanceOf(DataIntegrityViolationException.class)
          .isNotInstanceOf(java.sql.SQLException.class);
    }

    assertThat(packRowsFor(planted.projectId())).isZero();
  }

  @Test
  @DisplayName("No collector can emit a label near the cap, so amplification cannot reach one")
  void labelAmplificationIsUnreachableFromEveryCollector() {
    // Stated as an observation about today's sources, not as a guarantee. Every column a label is
    // read from is 200 wide or narrower — brain_entries.title, tasks.title — and the rest are
    // literals in the collectors. Redaction can roughly double a short value, so 200 cannot cross
    // 500. That is why ContextItem's cap has no end-to-end route to it: the route is closed
    // upstream, not by the cap.
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 labels", GENEROUS);
    assertThat(compiled.admittedItems()).isNotEmpty();
    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      assertThat(admitted.item().label().length())
          .as("label of %s", admitted.id())
          .isLessThanOrEqualTo(ContextItem.MAX_LABEL_LENGTH);
      assertThat(admitted.item().label().length())
          .as(
              "label of %s is under 250; if this ever fails, redaction amplification has become"
                  + " reachable from a collector and needs its own end-to-end test",
              admitted.id())
          .isLessThan(250);
    }
  }

  @Test
  @DisplayName("A record far larger than the budget is skipped whole, and nothing runs out of memory")
  void anEnormousRecordIsSkippedRatherThanTruncated() {
    String enormous = "E".repeat(2_000_000);
    brain.add(
        planted.projectId(),
        BrainEntryType.DECISION,
        "An entry nobody should have written",
        enormous,
        "test");

    ContextBudget modest = new ContextBudget(50, 50_000L, 100_000L);
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 enormous", modest);

    for (AdmittedContextItem admitted : compiled.admittedItems()) {
      assertThat(admitted.item().content())
          .as("item %s must be whole or absent, never shortened", admitted.id())
          .doesNotStartWith("EEEEEEEEEE");
      assertThat(admitted.item().content().length()).isLessThanOrEqualTo(50_000);
    }
    assertThat(compiled.usage().characters()).isLessThanOrEqualTo(50_000L);
    assertThat(compiled.admittedItems())
        .as("the rest of the project still compiled around the oversized record")
        .isNotEmpty();
    assertThat(packRowsFor(planted.projectId())).isEqualTo(1);
  }

  @Test
  @DisplayName("A budget nothing fits under yields an empty pack that still persists and reloads")
  void anEmptyPackIsARealPack() {
    ContextBudget tiny = new ContextBudget(1, 1L, 1L);
    CompiledContextPack compiled = assembler.assemble(planted.projectId(), "CTX-09 tiny", tiny);

    assertThat(compiled.admittedItems()).isEmpty();
    assertThat(packRowsFor(planted.projectId())).isEqualTo(1);

    Integer items =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items WHERE pack_id = ?",
            Integer.class,
            compiled.packId());
    assertThat(items).isZero();
    assertThat(compiled.packDigest()).hasSize(64);
  }

  private Integer packRowsFor(UUID projectId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM context_packs WHERE project_id = ?", Integer.class, projectId);
  }
}
