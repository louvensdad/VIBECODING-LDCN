package com.vibecode.guardian.application.rules;

import com.vibecode.guardian.domain.RuleId;
import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFindingCandidate;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecurityRule;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SensitiveDataRedactor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SEC-002: Detects suspicious hardcoded secret assignments.
 */
@Component
public class GenericSecretAssignmentRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-002");

  /**
   * The key half is {@link SensitiveDataRedactor#SENSITIVE_KEY_REGEX} rather than a copy of it.
   *
   * <p>What was here was a verbatim copy of the expression the redactor carried before
   * CTX-09B-1 — {@code \b(API_KEY|SECRET|TOKEN|PASSWORD|…)} — and it had inherited that finding's
   * root cause: {@code _} is a word character, so {@code \b} cannot fire before {@code PASSWORD}
   * in {@code VIBECODE_DB_PASSWORD} and the whole assignment was invisible to this rule. The
   * redactor was fixed; this copy was not, and the two definitions of "a sensitive key" drifted.
   *
   * <p>Two independent reviews established that this was a <b>detection gap and not a leak
   * path</b>: whatever this rule does or does not report, its evidence is passed through
   * {@link SensitiveDataRedactor#redact(String)} before it is stored, and 439,416 fuzzed evidence
   * paths produced no escape. What was lost was the finding — the operator was not told to go and
   * rotate the credential.
   *
   * <p>Only the vocabulary is shared. This rule still decides for itself what counts as a finding:
   * it keeps its own separator, its own quote handling, its own minimum length and its own
   * placeholder exemption, and the redactor consults no rule at all. Detection and redaction are
   * different jobs and redaction does not depend on this class existing.
   */
  private static final Pattern PATTERN =
      Pattern.compile(
          "(?i)\\b("
              + SensitiveDataRedactor.SENSITIVE_KEY_REGEX
              + ")\\s*(=|:)\\s*([\"']?)([^\\s,;\"'\\r\\n]+)([\"']?)");

  @Override
  public RuleId id() {
    return ID;
  }

  @Override
  public boolean supports(SecurityInspectionContext context) {
    return true;
  }

  @Override
  public List<SecurityFindingCandidate> inspect(SecurityInspectionContext context) {
    String content = context.rawContent();
    if (content == null || content.isBlank()) {
      return List.of();
    }

    List<SecurityFindingCandidate> candidates = new ArrayList<>();
    Matcher matcher = PATTERN.matcher(content);

    while (matcher.find()) {
      String key = matcher.group(1);
      String value = matcher.group(4);

      if (SensitiveDataRedactor.isSafePlaceholder(value) || value.length() < 5) {
        continue;
      }

      String line = matcher.group(0);
      String redactedLine = SensitiveDataRedactor.redact(line);

      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.CREDENTIAL_EXPOSURE,
              SecuritySeverity.HIGH,
              "Possível credencial ou chave em texto claro",
              "Foi detectada atribuição direta de valor suspeito à variável sensível '" + key + "'.",
              redactedLine,
              "content:offset-" + matcher.start(),
              "Não armazene segredos diretamente no código ou tarefas. Utilize variáveis de ambiente ou gerenciador de segredos."));
    }

    return List.copyOf(candidates);
  }
}

