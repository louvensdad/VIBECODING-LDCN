package com.vibecode.guardian.application.rules;

import com.vibecode.guardian.domain.RuleId;
import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFindingCandidate;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecurityRule;
import com.vibecode.guardian.domain.SecuritySeverity;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SEC-007: Detects the combination of wildcard CORS with credentials allowed.
 */
@Component
public class WildcardCorsCredentialsRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-007");

  private static final Pattern WILDCARD_ORIGIN =
      Pattern.compile("(?i)(?:Access-Control-Allow-Origin\\s*:\\s*\\*|allowedOrigins\\([\"']\\*[\"']\\))");

  private static final Pattern ALLOW_CREDENTIALS =
      Pattern.compile("(?i)(?:Access-Control-Allow-Credentials\\s*:\\s*true|allowCredentials\\(true\\))");

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

    boolean hasWildcard = WILDCARD_ORIGIN.matcher(content).find();
    boolean hasCredentials = ALLOW_CREDENTIALS.matcher(content).find();

    if (hasWildcard && hasCredentials) {
      return List.of(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.UNSAFE_CORS,
              SecuritySeverity.HIGH,
              "CORS inseguro: Wildcard Origin com Credentials",
              "A combinação de Access-Control-Allow-Origin: * com Access-Control-Allow-Credentials: true permite requisições autenticadas de qualquer origem arbitrária.",
              "Access-Control-Allow-Origin: *; Access-Control-Allow-Credentials: true",
              "content:cors-config",
              "Substitua o wildcard (*) por uma lista explícita de origens permitidas quando credentials estiver habilitado."));
    }

    return List.of();
  }
}

