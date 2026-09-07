package com.vibecode.brain.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryUpdateProposalTest {

  private final MemoryUpdateProposal proposal =
      MemoryUpdateProposal.proposed(
          UUID.randomUUID(),
          BrainEntryType.SOLUTION,
          "Fix the failing migration",
          "Add the missing column",
          "claude");

  @Test
  void aProposalStartsPending() {
    assertThat(proposal.status()).isEqualTo(MemoryProposalStatus.PENDING);
  }

  @Test
  void anUnacceptedProposalCannotBecomeOfficialMemory() {
    assertThatThrownBy(proposal::toEntry)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accepted");

    assertThatThrownBy(() -> proposal.reject().toEntry())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anAcceptedProposalKeepsItsSourceWhenItEntersMemory() {
    BrainEntry entry = proposal.accept().toEntry();

    assertThat(entry.getType()).isEqualTo(BrainEntryType.SOLUTION);
    assertThat(entry.getSource()).isEqualTo("claude");
    assertThat(entry.getProjectId()).isEqualTo(proposal.projectId());
  }
}
