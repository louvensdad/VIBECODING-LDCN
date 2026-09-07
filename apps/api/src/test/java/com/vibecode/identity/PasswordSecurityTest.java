package com.vibecode.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibecode.identity.application.IdentityService;
import com.vibecode.identity.domain.PasswordPolicy;
import com.vibecode.identity.domain.User;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class PasswordSecurityTest {

  private static final String PASSWORD = "uma senha bem longa";

  @Autowired IdentityService identity;
  @Autowired PasswordEncoder passwordEncoder;

  private String uniqueEmail() {
    return "pw-" + UUID.randomUUID() + "@example.com";
  }

  @Test
  @DisplayName("the stored value is a hash, not the password")
  void passwordIsNeverStoredInClear() {
    User user = identity.register(uniqueEmail(), PASSWORD, "Pw");

    assertThat(user.getPasswordHash())
        .isNotEqualTo(PASSWORD)
        .doesNotContain(PASSWORD)
        // The delegating encoder prefixes the algorithm, which is what allows a later upgrade.
        .startsWith("{bcrypt}");
    assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
    assertThat(passwordEncoder.matches("something else entirely", user.getPasswordHash()))
        .isFalse();
  }

  @Test
  @DisplayName("two accounts with the same password get different hashes")
  void hashesAreSalted() {
    User first = identity.register(uniqueEmail(), PASSWORD, "One");
    User second = identity.register(uniqueEmail(), PASSWORD, "Two");

    assertThat(first.getPasswordHash()).isNotEqualTo(second.getPasswordHash());
  }

  @Test
  @DisplayName("a User printed into a log carries no hash")
  void toStringDoesNotLeakTheHash() {
    User user = identity.register(uniqueEmail(), PASSWORD, "Logged");

    assertThat(user.toString()).doesNotContain(user.getPasswordHash()).doesNotContain(PASSWORD);
  }

  @Test
  @DisplayName("length is the rule; composition is not")
  void policyChecksLengthOnly() {
    assertThatThrownBy(() -> PasswordPolicy.validate("short"))
        .isInstanceOf(PasswordPolicy.WeakPasswordException.class);
    assertThatThrownBy(() -> PasswordPolicy.validate(null))
        .isInstanceOf(PasswordPolicy.WeakPasswordException.class);
    assertThatThrownBy(() -> PasswordPolicy.validate("           "))
        .isInstanceOf(PasswordPolicy.WeakPasswordException.class);

    assertThatCode(() -> PasswordPolicy.validate("todas minúsculas e sem símbolos"))
        .doesNotThrowAnyException();
    assertThatCode(() -> PasswordPolicy.validate("senha com acentuação çãé"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an over-long password is refused rather than silently truncated")
  void overLongPasswordIsRejected() {
    String tooLong = "a".repeat(PasswordPolicy.MAXIMUM_LENGTH + 1);

    assertThatThrownBy(() -> PasswordPolicy.validate(tooLong))
        .isInstanceOf(PasswordPolicy.WeakPasswordException.class)
        .hasMessageContaining("máximo");
  }

  @Test
  @DisplayName("length counts code points, so emoji are not miscounted")
  void lengthIsCountedInCodePoints() {
    // Ten emoji: ten characters to a person, twenty UTF-16 units to Java.
    String tenEmoji = "🔒".repeat(10);

    assertThatCode(() -> PasswordPolicy.validate(tenEmoji)).doesNotThrowAnyException();
    assertThatThrownBy(() -> PasswordPolicy.validate("🔒".repeat(9)))
        .isInstanceOf(PasswordPolicy.WeakPasswordException.class);
  }
}
