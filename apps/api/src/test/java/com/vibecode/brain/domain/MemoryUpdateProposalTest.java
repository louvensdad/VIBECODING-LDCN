package com.vibecode.brain.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryUpdateProposalTest {

  private MemoryUpdateProposal proposal;

  @BeforeEach
  void setUp() {
    proposal =
        new MemoryUpdateProposal(
            UUID.randomUUID(),
            MemoryProposalTrigger.ERROR_RESOLVED,
            BrainEntryType.SOLUTION,
            "Fix the failing migration",
            "Add the missing column",
            "claude");
  }

  @Test
  void aProposalStartsPending() {
    assertThat(proposal.getStatus()).isEqualTo(MemoryProposalStatus.PENDING);
  }

  @Test
  void anUnacceptedProposalCannotBecomeOfficialMemory() {
    assertThatThrownBy(proposal::toEntry)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accepted");

    proposal.reject();
    assertThatThrownBy(proposal::toEntry).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anAcceptedProposalKeepsItsSourceWhenItEntersMemory() {
    proposal.accept();
    BrainEntry entry = proposal.toEntry();

    assertThat(entry.getType()).isEqualTo(BrainEntryType.SOLUTION);
    assertThat(entry.getSource()).isEqualTo("claude");
    assertThat(entry.getProjectId()).isEqualTo(proposal.getProjectId());
  }
}
