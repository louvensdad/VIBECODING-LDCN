package com.vibecode.context.application.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.identity.domain.User;
import com.vibecode.shared.domain.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bob knows Alice's project id and collects nothing from it.
 *
 * <p>The distinction that matters is between an empty result and a refusal. An empty list would tell
 * Bob that the id is real and that the project has no context yet — a fact about Alice's data. Every
 * collector must therefore report not-found, exactly as the rest of the system does, so that a
 * guessed UUID teaches nothing.
 */
class CollectorOwnershipIsolationTest extends CollectorTestSupport {

  @Test
  @DisplayName("No collector reads across users, and none of them answers with an empty list")
  void bobCollectsNothingFromAlicesProject() {
    Fixture alices = createFullProject("Alice");
    UUID alicesProject = alices.projectId();

    // Alice really does have context, so an empty result below could only mean a leak of absence.
    assertThat(candidates.collect(alicesProject)).isNotEmpty();

    User bob = identity.createUser("Bob");
    identity.authenticateAs(bob);

    assertThatThrownBy(() -> candidates.collect(alicesProject))
        .isInstanceOf(ResourceNotFoundException.class);

    for (ContextSourceType sourceType : ContextSourceType.values()) {
      assertThatThrownBy(
              () ->
                  candidates.collectFrom(
                      sourceType, alicesProject, ContextReadWindow.DEFAULT))
          .as("collector for %s must refuse, not return an empty list", sourceType)
          .isInstanceOf(ResourceNotFoundException.class);
    }
  }

  @Test
  @DisplayName("Bob's own collection contains nothing of Alice's")
  void bobsOwnCollectionIsHisOwn() {
    Fixture alices = createFullProject("AliceAgain");
    Fixture bobs = createFullProject("BobAgain");

    assertThat(candidates.collect(bobs.projectId()))
        .isNotEmpty()
        .allSatisfy(
            item -> {
              assertThat(item.provenance().projectId()).isEqualTo(bobs.projectId());
              assertThat(item.provenance().sourceId())
                  .isNotEqualTo(alices.projectId().toString());
            });
  }
}
