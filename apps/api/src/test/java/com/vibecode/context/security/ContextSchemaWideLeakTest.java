package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.context.domain.CompiledContextPack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * After a compilation, the probe is looked for in <em>every</em> table in the schema, not in the two
 * the pack is written to.
 *
 * <p>The existing leak test searches {@code context_packs} and {@code context_pack_items}, which
 * answers "did the pack keep it" and nothing else. A pack is compiled by reading eleven services,
 * each of which touches its own rows, and a value copied into a working table on the way past would
 * be invisible to that search. So this sweeps the whole schema and then partitions it: the tables
 * that are the user's own records are allowed to hold what the user typed, and every other table in
 * the database — present or added later — must hold none of it.
 *
 * <p>Auto-partitioning is the point. A table introduced next month is in the "must be clean" half
 * by default, so a future write path that spills context into it fails here without anyone
 * remembering to update a list.
 *
 * <p><b>One column type the sweep cannot read, named so "every table" is not read as "every
 * byte".</b> {@link #occurrencesIn(String, String)} stringifies each value with {@code
 * String.valueOf}, which renders a {@code byte[]} as {@code [B@1f2c3d4} — a binary column is
 * therefore swept and always comes back clean, whatever is in it. Three columns in the schema are
 * binary today, all of them in the vault: {@code vault_secret_versions.ciphertext}, {@code .nonce}
 * and {@code .wrapped_data_key}. They are an implausible destination for a leak in both
 * directions — the context engine cannot reach the vault to write there, and ciphertext is what
 * that table is <em>for</em> — so this is a stated gap rather than an unmeasured one. Textual
 * columns are read correctly, {@code TEXT}/CLOB included: turning off content redaction makes this
 * sweep go red, which is what establishes that {@code String.valueOf} is not being defeated by
 * H2's CLOB mapping.
 */
class ContextSchemaWideLeakTest extends ContextProbeFixture {

  /**
   * The tables that are the project's own records. These hold what the user wrote, including the
   * probe, and always did — the engine reads them, it does not author them. Everything else in the
   * schema must be clean.
   */
  private static final Set<String> USER_RECORD_TABLES =
      Set.of(
          "projects",
          "brain_entries",
          "roadmaps",
          "roadmap_phases",
          "tasks",
          "task_acceptance_criteria",
          "task_evidence",
          "output_analysis_records",
          "memory_update_proposals",
          "security_findings");

  private Planted planted;

  @BeforeEach
  void plant() {
    planted = plantTheProbeEverywhere("schema-sweep-owner");
  }

  @Test
  @DisplayName("The probe is in the source tables and in no other table in the schema")
  void theProbeReachesNoTableItDidNotStartIn() {
    CompiledContextPack compiled = assembler.assemble(planted.projectId(), "CTX-09 sweep", GENEROUS);
    assertThat(compiled.admittedItems())
        .as("a compilation that admitted nothing would make every count below trivially zero")
        .isNotEmpty();

    Map<String, Integer> occurrences = occurrencesPerTable(PROBE);

    // Non-vacuous control. If the probe is not still sitting in the records it was written to,
    // every zero below means only that the fixture failed to plant anything.
    assertThat(occurrences.get("brain_entries"))
        .as("the probe must still be in the brain entries it was written to")
        .isPositive();
    assertThat(occurrences.get("tasks"))
        .as("the probe must still be in the task objective it was written to")
        .isPositive();
    // task_evidence is deliberately NOT a control here, and the reason is worth stating:
    // EvidenceService redacts raw_content before it saves the row, so the probe never reaches that
    // table in the first place. Asserting it there would fail, and asserting its absence there
    // would credit the context engine with a scrub that happened upstream of it.
    assertThat(occurrences.get("task_evidence"))
        .as("evidence is redacted by EvidenceService on the way in, before context ever reads it")
        .isZero();
    assertThat(occurrences.get("projects"))
        .as("the probe must still be in the project's own text")
        .isPositive();
    assertThat(occurrences.get("task_acceptance_criteria"))
        .as("the probe must still be in the acceptance criterion it was written to")
        .isPositive();

    Map<String, Integer> leaked = new TreeMap<>();
    occurrences.forEach(
        (table, count) -> {
          if (count > 0 && !USER_RECORD_TABLES.contains(table)) {
            leaked.put(table, count);
          }
        });

    assertThat(leaked)
        .as(
            "a table outside the project's own records is holding the probe; the context engine"
                + " reads those records, it does not get to copy them anywhere else")
        .isEmpty();
  }

  @Test
  @DisplayName("The two context tables hold the marker instead, and no fragment of the probe")
  void theContextTablesHoldTheMarkerAndNothingOfTheProbe() {
    CompiledContextPack compiled =
        assembler.assemble(planted.projectId(), "CTX-09 marker " + KEYED_PROBE, GENEROUS);

    assertThat(occurrencesIn("context_packs", PROBE)).isZero();
    assertThat(occurrencesIn("context_pack_items", PROBE)).isZero();

    // Not a fragment either. "zqxw_928472" cannot be produced by a UUID — it is not hex — so this
    // cannot collide with a random id the way a four-character discriminator would.
    assertThat(occurrencesIn("context_packs", "zqxw_928472")).isZero();
    assertThat(occurrencesIn("context_pack_items", "zqxw_928472")).isZero();

    assertThat(occurrencesIn("context_pack_items", "[REDACTED]"))
        .as("the value was replaced with a marker, not the item quietly dropped")
        .isPositive();

    String storedReference =
        jdbc.queryForObject(
            "SELECT task_reference FROM context_packs WHERE id = ?",
            String.class,
            compiled.packId());
    assertThat(storedReference).doesNotContain(PROBE).contains("[REDACTED]");
    assertThat(compiled.canonicalPayload().value()).doesNotContain(PROBE);
  }

  @Test
  @DisplayName("A refused item's content is nowhere in the schema outside the record it came from")
  void refusedContentIsNotCopiedAnywhere() {
    assembler.assemble(planted.projectId(), "CTX-09 refusal", GENEROUS);

    Map<String, Integer> occurrences = occurrencesPerTable(DENIED_MARKER);

    assertThat(occurrences.get("brain_entries"))
        .as("the refused entries must still exist, or their absence proves nothing")
        .isPositive();

    Map<String, Integer> elsewhere = new TreeMap<>();
    occurrences.forEach(
        (table, count) -> {
          if (count > 0 && !"brain_entries".equals(table)) {
            elsewhere.put(table, count);
          }
        });
    assertThat(elsewhere)
        .as(
            "the marker is not secret-shaped, so redaction never touches it: anywhere it appears,"
                + " the refused item itself got there")
        .isEmpty();
  }

  /** Every table in the schema, mapped to how many of its column values contain the needle. */
  private Map<String, Integer> occurrencesPerTable(String needle) {
    List<String> tables =
        jdbc.queryForList(
            "SELECT LOWER(table_name) FROM information_schema.tables"
                + " WHERE UPPER(table_schema) = 'PUBLIC' AND UPPER(table_type) = 'BASE TABLE'"
                + " ORDER BY 1",
            String.class);
    assertThat(tables)
        .as("the sweep must actually see the schema")
        .contains("context_packs", "context_pack_items", "audit_events", "brain_entries");

    Map<String, Integer> perTable = new LinkedHashMap<>();
    for (String table : tables) {
      perTable.put(table, occurrencesIn(table, needle));
    }
    return perTable;
  }

  /**
   * Every value of every column of one table, stringified, counted for the needle.
   *
   * <p>Textual columns read correctly, {@code TEXT}/CLOB included. A {@code byte[]} column does
   * not: {@code String.valueOf} renders it as {@code [B@...} and the needle can never be found in
   * it. See the class javadoc for which columns that is and why it is accepted.
   */
  private int occurrencesIn(String table, String needle) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM \"" + table + "\"");
    int occurrences = 0;
    for (Map<String, Object> row : rows) {
      for (Object value : row.values()) {
        if (value != null && String.valueOf(value).contains(needle)) {
          occurrences++;
        }
      }
    }
    return occurrences;
  }
}
