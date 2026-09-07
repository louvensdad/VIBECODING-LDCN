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
 * SEC-008: Detects unredacted sensitive tokens, passwords, or payment cards in logs or output.
 */
@Component
public class SensitiveDataInLogRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-008");

  private static final Pattern AUTH_HEADER_PATTERN =
      Pattern.compile("(?i)(authorization\\s*:\\s*bearer\\s+)([A-Za-z0-9._~+/-]{10,})");

  private static final Pattern SENSITIVE_KV_LOG =
      Pattern.compile("(?i)\\b(password|creditCard|cvv|access_token|refresh_token)\\s*(=|:)\\s*([\"']?)([^\\s,;\"'\\r\\n]+)([\"']?)");

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

    // Authorization Bearer
    Matcher authMatcher = AUTH_HEADER_PATTERN.matcher(content);
    while (authMatcher.find()) {
      String token = authMatcher.group(2);
      if (!SensitiveDataRedactor.isSafePlaceholder(token)) {
        candidates.add(
            new SecurityFindingCandidate(
                ID,
                SecurityCategory.SENSITIVE_LOGGING,
                SecuritySeverity.HIGH,
                "Token de autorização exposto em log",
                "Foi detectado cabeçalho Authorization: Bearer com token não mascarado em log ou saída.",
                "Authorization: Bearer [REDACTED]",
                "content:offset-" + authMatcher.start(),
                "Configure filtros de mascaramento de logs HTTP para evitar persistência de tokens de autenticação."));
      }
    }

    // Key-value in log (password, credit card, cvv)
    Matcher kvMatcher = SENSITIVE_KV_LOG.matcher(content);
    while (kvMatcher.find()) {
      String key = kvMatcher.group(1);
      String val = kvMatcher.group(4);
      if (!SensitiveDataRedactor.isSafePlaceholder(val) && val.length() >= 3) {
        candidates.add(
            new SecurityFindingCandidate(
                ID,
                SecurityCategory.SENSITIVE_LOGGING,
                SecuritySeverity.HIGH,
                "Dado sensível exposto em log (" + key + ")",
                "Foi identificada chave confidencial com valor real no conteúdo de saída ou log.",
                key + "=[REDACTED]",
                "content:offset-" + kvMatcher.start(),
                "Mascare valores sensíveis antes de enviá-los ao logger da aplicação."));
      }
    }

    return List.copyOf(candidates);
  }
}

