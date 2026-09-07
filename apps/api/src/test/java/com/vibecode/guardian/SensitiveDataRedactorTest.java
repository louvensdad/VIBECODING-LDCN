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

  @Test
  @DisplayName("Preserves safe placeholders")
  void preservesSafePlaceholders() {
    String safe = "PASSWORD=${DB_PASSWORD}\nAPI_KEY=<YOUR_KEY>\nTOKEN=REPLACE_ME";
    String result = SensitiveDataRedactor.redact(safe);
    assertThat(result).isEqualTo(safe);
  }
}

