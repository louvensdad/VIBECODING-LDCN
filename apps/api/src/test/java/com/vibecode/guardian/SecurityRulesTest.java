package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.guardian.application.rules.DangerousCommandRule;
import com.vibecode.guardian.application.rules.DisabledTlsRule;
import com.vibecode.guardian.application.rules.GenericSecretAssignmentRule;
import com.vibecode.guardian.application.rules.KnownTokenPatternRule;
import com.vibecode.guardian.application.rules.PasswordInUrlRule;
import com.vibecode.guardian.application.rules.PrivateKeySecurityRule;
import com.vibecode.guardian.application.rules.PromptSecretLeakRule;
import com.vibecode.guardian.application.rules.SensitiveDataInLogRule;
import com.vibecode.guardian.application.rules.WildcardCorsCredentialsRule;
import com.vibecode.guardian.domain.SecurityFindingCandidate;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SecuritySourceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecurityRulesTest {

  private final UUID projectId = UUID.randomUUID();

  @Test
  @DisplayName("SEC-001: Private key detected, but safe placeholder is ignored")
  void privateKeyRule() {
    PrivateKeySecurityRule rule = new PrivateKeySecurityRule();

    String exposedKey =
        """
        -----BEGIN RSA PRIVATE KEY-----
        MIIEowIBAAKCAQEA0m4w...fakekeycontent...
        -----END RSA PRIVATE KEY-----
        """;
    SecurityInspectionContext ctx1 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", exposedKey);
    List<SecurityFindingCandidate> findings1 = rule.inspect(ctx1);
    assertThat(findings1).hasSize(1);
    assertThat(findings1.get(0).severity()).isEqualTo(SecuritySeverity.CRITICAL);
    assertThat(findings1.get(0).rawEvidence()).contains("[REDACTED]");

    String safePlaceholder =
        """
        -----BEGIN RSA PRIVATE KEY-----
        <YOUR_PRIVATE_KEY_HERE>
        -----END RSA PRIVATE KEY-----
        """;
    SecurityInspectionContext ctx2 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", safePlaceholder);
    List<SecurityFindingCandidate> findings2 = rule.inspect(ctx2);
    assertThat(findings2).isEmpty();
  }

  @Test
  @DisplayName("SEC-002: Hardcoded secret assignment detected, environment placeholder ignored")
  void genericSecretAssignmentRule() {
    GenericSecretAssignmentRule rule = new GenericSecretAssignmentRule();

    String dangerous = "API_KEY=supersecretkeyvalue12345\nPASSWORD=mypassword9876";
    SecurityInspectionContext ctx1 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", dangerous);
    List<SecurityFindingCandidate> findings1 = rule.inspect(ctx1);
    assertThat(findings1).hasSize(2);
    assertThat(findings1.get(0).severity()).isEqualTo(SecuritySeverity.HIGH);

    String safe = "API_KEY=${API_KEY}\nPASSWORD=<YOUR_PASSWORD>\nTOKEN=REPLACE_ME";
    SecurityInspectionContext ctx2 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", safe);
    List<SecurityFindingCandidate> findings2 = rule.inspect(ctx2);
    assertThat(findings2).isEmpty();
  }

  @Test
  @DisplayName("SEC-003: Known provider token patterns detected")
  void knownTokenPatternRule() {
    KnownTokenPatternRule rule = new KnownTokenPatternRule();

    String input = "OPENAI_API_KEY=sk-testingsynthetictokenvalue1234567890";
    SecurityInspectionContext ctx =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", input);
    List<SecurityFindingCandidate> findings = rule.inspect(ctx);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).severity()).isEqualTo(SecuritySeverity.CRITICAL);
    assertThat(findings.get(0).rawEvidence()).contains("sk-****REDACTED****");
  }

  @Test
  @DisplayName("SEC-004: Password in DB URL detected and redacted")
  void passwordInUrlRule() {
    PasswordInUrlRule rule = new PasswordInUrlRule();

    String url = "DATABASE_URL=postgres://appuser:realpassword123@db.internal:5432/production";
    SecurityInspectionContext ctx =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", url);
    List<SecurityFindingCandidate> findings = rule.inspect(ctx);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).severity()).isEqualTo(SecuritySeverity.HIGH);
    assertThat(findings.get(0).rawEvidence())
        .isEqualTo("postgres://appuser:[REDACTED]@db.internal:5432/production");
  }

  @Test
  @DisplayName("SEC-005: Destructive command detected, safe rm ignored")
  void dangerousCommandRule() {
    DangerousCommandRule rule = new DangerousCommandRule();

    String dangerous = "sudo rm -rf / \nDROP DATABASE production;\nchmod 777 /app";
    SecurityInspectionContext ctx1 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", dangerous);
    List<SecurityFindingCandidate> findings1 = rule.inspect(ctx1);
    assertThat(findings1).hasSize(3);

    String safe = "rm target/app.jar\nrm -rf node_modules\nchmod 644 config.json";
    SecurityInspectionContext ctx2 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", safe);
    List<SecurityFindingCandidate> findings2 = rule.inspect(ctx2);
    assertThat(findings2).isEmpty();
  }

  @Test
  @DisplayName("SEC-006: Disabled TLS detected, safe config ignored")
  void disabledTlsRule() {
    DisabledTlsRule rule = new DisabledTlsRule();

    String dangerous = "export NODE_TLS_REJECT_UNAUTHORIZED=0\nrejectUnauthorized: false";
    SecurityInspectionContext ctx1 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", dangerous);
    List<SecurityFindingCandidate> findings1 = rule.inspect(ctx1);
    assertThat(findings1).hasSize(2);
    assertThat(findings1.get(0).severity()).isEqualTo(SecuritySeverity.HIGH);

    String safe = "export NODE_TLS_REJECT_UNAUTHORIZED=1\nrejectUnauthorized: true";
    SecurityInspectionContext ctx2 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", safe);
    List<SecurityFindingCandidate> findings2 = rule.inspect(ctx2);
    assertThat(findings2).isEmpty();
  }

  @Test
  @DisplayName("SEC-007: Wildcard CORS with credentials detected")
  void wildcardCorsCredentialsRule() {
    WildcardCorsCredentialsRule rule = new WildcardCorsCredentialsRule();

    String dangerous =
        "Access-Control-Allow-Origin: *\nAccess-Control-Allow-Credentials: true";
    SecurityInspectionContext ctx1 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", dangerous);
    List<SecurityFindingCandidate> findings1 = rule.inspect(ctx1);
    assertThat(findings1).hasSize(1);
    assertThat(findings1.get(0).severity()).isEqualTo(SecuritySeverity.HIGH);

    String safe =
        "Access-Control-Allow-Origin: https://app.example.com\nAccess-Control-Allow-Credentials: true";
    SecurityInspectionContext ctx2 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", safe);
    List<SecurityFindingCandidate> findings2 = rule.inspect(ctx2);
    assertThat(findings2).isEmpty();
  }

  @Test
  @DisplayName("SEC-008: Sensitive authorization log detected, masked log ignored")
  void sensitiveDataInLogRule() {
    SensitiveDataInLogRule rule = new SensitiveDataInLogRule();

    String dangerous = "2026-09-07 Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
    SecurityInspectionContext ctx1 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", dangerous);
    List<SecurityFindingCandidate> findings1 = rule.inspect(ctx1);
    assertThat(findings1).hasSize(1);
    assertThat(findings1.get(0).rawEvidence()).isEqualTo("Authorization: Bearer [REDACTED]");

    String safe = "2026-09-07 Authorization: Bearer [REDACTED]";
    SecurityInspectionContext ctx2 =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.TASK_EVIDENCE, "test", safe);
    List<SecurityFindingCandidate> findings2 = rule.inspect(ctx2);
    assertThat(findings2).isEmpty();
  }

  @Test
  @DisplayName("SEC-009: Prompt secret leak detected")
  void promptSecretLeakRule() {
    PromptSecretLeakRule rule = new PromptSecretLeakRule();

    String promptWithKey = "Aqui está o contexto:\nOPENAI_API_KEY=sk-testingsynthetictokenvalue1234567890";
    SecurityInspectionContext ctx =
        SecurityInspectionContext.forText(
            projectId, null, SecuritySourceType.PROMPT, "prompt", promptWithKey);
    List<SecurityFindingCandidate> findings = rule.inspect(ctx);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).severity()).isEqualTo(SecuritySeverity.CRITICAL);
  }
}

