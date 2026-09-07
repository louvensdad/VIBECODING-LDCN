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
 * SEC-004: Detects embedded database or service credentials in connection URLs.
 */
@Component
public class PasswordInUrlRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-004");
  private static final Pattern PATTERN =
      Pattern.compile("((?:postgres(?:ql)?|mysql|mongodb|redis|amqp)://[^:]+:)([^@\\s]+)(@[^\\s\"']+)");

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
      String prefix = matcher.group(1);
      String password = matcher.group(2);
      String host = matcher.group(3);

      if (SensitiveDataRedactor.isSafePlaceholder(password)) {
        continue;
      }

      String redactedUrl = prefix + "[REDACTED]" + host;
      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.INSECURE_URL,
              SecuritySeverity.HIGH,
              "Senha embutida em URL de conexão",
              "Uma URL de conexão com banco de dados ou serviço contém credenciais em texto claro.",
              redactedUrl,
              "content:offset-" + matcher.start(),
              "Separe o usuário, host e senha utilizando variáveis de ambiente dedicadas em vez de incluir a senha na URI."));
    }

    return List.copyOf(candidates);
  }
}

