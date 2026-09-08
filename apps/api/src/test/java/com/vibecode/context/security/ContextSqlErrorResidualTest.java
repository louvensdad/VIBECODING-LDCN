package com.vibecode.context.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * The known limitation, stated rather than papered over.
 *
 * <p>A {@link java.sql.SQLException} raised by a driver can carry the offending value inside itself.
 * H2 does exactly that for a value-too-long error: the message quotes the value it refused. That
 * exception object exists, in memory, on the failing thread. <b>This test does not claim it is
 * clean, because it is not.</b> It asserts the opposite where the opposite is true, and then asserts
 * the guarantee that is actually made: the detail does not cross a boundary. Not into a log, not
 * into the audit trail, not into a row.
 *
 * <p>Writing this the other way round — asserting the in-memory exception carries nothing — would
 * be a false statement that happened to pass on some driver versions and would quietly stop being
 * checked the day one of them changed its message.
 *
 * <p>The failure is provoked through a real asymmetry in the schema rather than a mock; see {@link
 * ContextPersistenceFailure} for which columns have a domain cap behind them and which do not.
 */
class ContextSqlErrorResidualTest extends ContextProbeFixture {

  @Autowired ContextPackRepository packs;

  private Planted planted;

  @BeforeEach
  void plant() {
    planted = plantTheProbeEverywhere("sql-residual-owner");
  }

  @Test
  @DisplayName("The write is refused as a data-access failure, not as an unexplained crash")
  void theFailureIsControlled() {
    Throwable failure =
        catchThrowable(() -> ContextPersistenceFailure.provoke(packs, planted.projectId()));

    assertThat(failure)
        .as("a 240-character item id against a 200-wide column has to fail somewhere")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("The driver's detail is real: it is inside the exception, which is why the rest matters")
  void theResidualIsAcknowledgedWhereItActuallyLives() {
    Throwable failure =
        catchThrowable(() -> ContextPersistenceFailure.provoke(packs, planted.projectId()));

    boolean detailIsInTheChain = false;
    for (Throwable link = failure; link != null; link = link.getCause()) {
      String message = String.valueOf(link.getMessage());
      if (message.contains(ContextPersistenceFailure.OVERSIZED_IDENTIFIER_PROBE)
          || message.toUpperCase(java.util.Locale.ROOT).contains("ITEM_ID")) {
        detailIsInTheChain = true;
      }
      if (link.getCause() == link) {
        break;
      }
    }
    assertThat(detailIsInTheChain)
        .as(
            "if the driver ever stops describing what it refused, the boundary assertions in this"
                + " file and in ContextCompilationLogLeakTest stop testing anything and must be"
                + " reworked rather than left passing")
        .isTrue();
  }

  @Test
  @DisplayName("Nothing of the failed write reaches a row or the audit trail")
  void theFailedWriteLeavesNothingBehind() {
    Instant before = Instant.now().minusSeconds(1);
    catchThrowable(() -> ContextPersistenceFailure.provoke(packs, planted.projectId()));

    Integer items =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM context_pack_items WHERE item_id LIKE ?",
            Integer.class,
            "%" + ContextPersistenceFailure.OVERSIZED_IDENTIFIER_PROBE + "%");
    assertThat(items).isZero();

    List<Map<String, Object>> events =
        jdbc.queryForList("SELECT * FROM audit_events WHERE created_at >= ?", before);
    for (Map<String, Object> event : events) {
      StringBuilder row = new StringBuilder();
      event.values().forEach(value -> row.append(String.valueOf(value)).append(" | "));
      assertThat(row.toString())
          .doesNotContain(ContextPersistenceFailure.OVERSIZED_IDENTIFIER_PROBE)
          .doesNotContain("ITEM_ID")
          .doesNotContain("item_id");
    }
  }
}
