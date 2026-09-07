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
  private static final Pattern PATTERN =
      Pattern.compile(
          "(?i)\\b(API_KEY|SECRET|TOKEN|PASSWORD|CLIENT_SECRET|ACCESS_TOKEN|REFRESH_TOKEN|PRIVATE_KEY)\\s*(=|:)\\s*([\"']?)([^\\s,;\"'\\r\\n]+)([\"']?)");

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

