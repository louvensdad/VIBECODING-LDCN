package com.vibecode.guardian.application.rules;

import com.vibecode.guardian.domain.RuleId;
import com.vibecode.guardian.domain.SecurityCategory;
import com.vibecode.guardian.domain.SecurityFindingCandidate;
import com.vibecode.guardian.domain.SecurityInspectionContext;
import com.vibecode.guardian.domain.SecurityRule;
import com.vibecode.guardian.domain.SecuritySeverity;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * SEC-006: Detects disabled TLS/SSL certificate validation.
 */
@Component
public class DisabledTlsRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-006");

  private static final Pattern PATTERN =
      Pattern.compile(
          "(?i)\\b(NODE_TLS_REJECT_UNAUTHORIZED\\s*=\\s*0|rejectUnauthorized\\s*:\\s*false|verify\\s*=\\s*False|sslVerify\\s*=\\s*false|trustAllCerts\\s*=\\s*true)\\b");

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
      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.UNSAFE_CONFIGURATION,
              SecuritySeverity.HIGH,
              "Validação TLS/SSL desabilitada",
              "Foi identificada desativação de validação de certificados SSL/TLS, permitindo ataques Man-in-the-Middle (MitM).",
              matcher.group(),
              "content:offset-" + matcher.start(),
              "Habilite a validação estrita de certificados e instale certificados válidos ou adicione autoridades confiáveis à keystore."));
    }

    return List.copyOf(candidates);
  }
}

