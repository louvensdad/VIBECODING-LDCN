package com.vibecode.usage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.model.domain.ProviderId;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CreditSnapshotTest {

  @Test
  void anUnknownBalanceCarriesNoNumber() {
    CreditSnapshot snapshot = CreditSnapshot.unknown(ProviderId.ANTHROPIC, "personal");

    assertThat(snapshot.amount()).isNull();
    assertThat(snapshot.confidence()).isEqualTo(CreditConfidence.UNKNOWN);
    assertThat(snapshot.isFact()).isFalse();
  }

  @Test
  void aBalanceMayNotBeInventedForAnUnknownAccount() {
    assertThatThrownBy(
            () ->
                new CreditSnapshot(
                    ProviderId.OPENAI,
                    "team",
                    new BigDecimal("42.00"),
                    "USD",
                    CreditConfidence.UNKNOWN,
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyAProviderReportedBalanceCountsAsFact() {
    CreditSnapshot exact =
        new CreditSnapshot(
            ProviderId.OPENAI,
            "team",
            new BigDecimal("42.00"),
            "USD",
            CreditConfidence.EXACT,
            Instant.now());
    CreditSnapshot estimated =
        new CreditSnapshot(
            ProviderId.OPENAI,
            "team",
            new BigDecimal("40.00"),
            "USD",
            CreditConfidence.ESTIMATED,
            Instant.now());

    assertThat(exact.isFact()).isTrue();
    assertThat(estimated.isFact()).isFalse();
  }
}
