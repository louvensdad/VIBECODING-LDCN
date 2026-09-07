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
 * SEC-001: Detects exposed private keys.
 */
@Component
public class PrivateKeySecurityRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-001");
  private static final Pattern PATTERN =
      Pattern.compile(
          "-----BEGIN ([A-Z0-9 ]+)?PRIVATE KEY-----[\\s\\S]*?-----END ([A-Z0-9 ]+)?PRIVATE KEY-----");

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
      String block = matcher.group();
      // Check for safe placeholders
      if (isSafePlaceholderBlock(block)) {
        continue;
      }

      String redactedBlock = SensitiveDataRedactor.redact(block);
      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.SECRET_EXPOSURE,
              SecuritySeverity.CRITICAL,
              "Chave privada exposta",
              "Uma chave privada em texto claro foi identificada no conteúdo analisado.",
              redactedBlock,
              "content:offset-" + matcher.start(),
              "Remova imediatamente a chave privada do conteúdo e revogue/rotacione o par de chaves caso seja real."));
    }

    return List.copyOf(candidates);
  }

  private boolean isSafePlaceholderBlock(String block) {
    String upper = block.toUpperCase();
    return upper.contains("<YOUR_PRIVATE_KEY")
        || upper.contains("<PRIVATE_KEY>")
        || upper.contains("REPLACE_ME")
        || upper.contains("${PRIVATE_KEY}")
        || upper.contains("TODO_ADD_KEY");
  }
}

