package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.guardian.domain.SensitiveDataRedactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

  @Test
  @DisplayName("Redacts private keys, URLs with passwords, tokens, and assignments")
  void redactsSensitiveData() {
    String input =
        """
        -----BEGIN RSA PRIVATE KEY-----
        MIICXAIBAAKCAQEA0m4wfakekey12345
        -----END RSA PRIVATE KEY-----
        DATABASE_URL=postgres://myuser:secretpassword@localhost:5432/app
        OPENAI_API_KEY=sk-testingsynthetictokenvalue1234567890
        GITHUB_TOKEN=ghp_testingsynthetictokenvalue1234567890
        Authorization: Bearer myauthtoken1234567890
        PASSWORD=my-secret-value
        """;

    String redacted = SensitiveDataRedactor.redact(input);

    assertThat(redacted).doesNotContain("secretpassword");
    assertThat(redacted).doesNotContain("my-secret-value");
    assertThat(redacted).doesNotContain("myauthtoken1234567890");
    assertThat(redacted).doesNotContain("MIICXAIBAAKCAQEA0m4wfakekey12345");
    assertThat(redacted).contains("[REDACTED]");
    assertThat(redacted).contains("sk-****REDACTED****");
    assertThat(redacted).contains("ghp_****REDACTED****");
  }

  /**
   * <b>This test asserted the opposite until SEC-RED-02, and the change is deliberate.</b>
   *
   * <p>It read {@code assertThat(redact("PASSWORD=${DB_PASSWORD}\nAPI_KEY=<YOUR_KEY>\nTOKEN=
   * REPLACE_ME")).isEqualTo(safe)} — placeholders passed through even on the right-hand side of a
   * sensitive key. That exemption was a bypass: {@code isSafePlaceholder} has to decide from the
   * shape of a string whether {@code $ABC} is a variable read or a password, and
   * {@code $Pa55phrase_zqxw_610455} and {@code $DB_PASSWORD} are the same string to any pattern.
   * Since the caller controls the value, any sharpening of that heuristic is a new bypass with a
   * rule written for it.
   *
   * <p>So the architect ruled on precedence rather than on the policy:
   * <b>{@code SECRET ASSIGNMENT > PLACEHOLDER EXEMPTION}</b>. The key already supplies the context;
   * the value's shape is never consulted inside an assignment. The placeholder policy itself is
   * unchanged and still decides everywhere else, which is what the second half of this test now
   * pins.
   *
   * <p>The cost is that {@code password: ${DB_PASSWORD}} — the idiom Spring recommends precisely so
   * that a configuration file holds no secret — comes back as {@code password: [REDACTED]}. That
   * was measured over this repository and accepted; {@code SecretAssignmentGrammarTest} carries the
   * numbers and the pinned cases.
   */
  @Test
  @DisplayName("A placeholder is a placeholder everywhere except inside a secret assignment")
  void placeholdersAreExemptOnlyOutsideAnAssignment() {
    // Inside an assignment: the right-hand side goes, whatever it looks like.
    assertThat(
            SensitiveDataRedactor.redact(
                "PASSWORD=${DB_PASSWORD}\nAPI_KEY=<YOUR_KEY>\nTOKEN=REPLACE_ME"))
        .isEqualTo("PASSWORD=[REDACTED]\nAPI_KEY=[REDACTED]\nTOKEN=[REDACTED]");

    // Outside one: unchanged, and asserted on the policy itself rather than on an absence of
    // redaction, which could have had other causes.
    assertThat(SensitiveDataRedactor.isSafePlaceholder("${DB_PASSWORD}")).isTrue();
    assertThat(SensitiveDataRedactor.isSafePlaceholder("<YOUR_KEY>")).isTrue();
    assertThat(SensitiveDataRedactor.isSafePlaceholder("REPLACE_ME")).isTrue();
    String prose = "Set ${DB_PASSWORD} in the environment, or leave <YOUR_KEY> as REPLACE_ME.";
    assertThat(SensitiveDataRedactor.redact(prose)).isEqualTo(prose);

    // And the redactor's own markers still survive a second pass, so redaction stays idempotent.
    String marked = "PASSWORD=[REDACTED]\nOPENAI_API_KEY=sk-****REDACTED****";
    assertThat(SensitiveDataRedactor.redact(marked)).isEqualTo(marked);
  }
}

