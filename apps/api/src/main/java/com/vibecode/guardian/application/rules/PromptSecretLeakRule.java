package com.vibecode.guardian.application.rules;

import com.vibecode.guardian.domain.RuleId;
import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFindingCandidate;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecurityRule;
import com.vibecode.guardian.domain.SecuritySeverity;
import com.vibecode.guardian.domain.SecuritySourceType;
import com.vibecode.guardian.domain.SensitiveDataRedactor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SEC-009: Detects potential secret or credential leakage in generated prompts destined for external models.
 */
@Component
public class PromptSecretLeakRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-009");

  private static final Pattern PROMPT_SECRET_PATTERN =
      Pattern.compile(
          "(?i)\\b(?:sk-[A-Za-z0-9_-]{16,}|(?:ghp|gho)_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z0-9 ]+PRIVATE KEY-----|(?:API_KEY|SECRET|PASSWORD)\\s*=\\s*[^\\s,;\"']{8,})\\b");

  @Override
  public RuleId id() {
    return ID;
  }

  @Override
  public boolean supports(SecurityInspectionContext context) {
    return context.sourceType() == SecuritySourceType.PROMPT;
  }

  @Override
  public List<SecurityFindingCandidate> inspect(SecurityInspectionContext context) {
    String content = context.rawContent();
    if (content == null || content.isBlank()) {
      return List.of();
    }

    List<SecurityFindingCandidate> candidates = new ArrayList<>();
    Matcher matcher = PROMPT_SECRET_PATTERN.matcher(content);

    while (matcher.find()) {
      String match = matcher.group();
      if (!SensitiveDataRedactor.isSafePlaceholder(match)) {
        candidates.add(
            new SecurityFindingCandidate(
                ID,
                SecurityCategory.PROMPT_SECRET_LEAK,
                SecuritySeverity.CRITICAL,
                "Possível credencial detectada em prompt",
                "O prompt gerado contém possível credencial ou chave privada em texto claro.",
                SensitiveDataRedactor.redact(match),
                "prompt:offset-" + matcher.start(),
                "O prompt contém possível credencial. Remova ou sanitize antes de enviar para qualquer modelo externo."));
      }
    }

    return List.copyOf(candidates);
  }
}

