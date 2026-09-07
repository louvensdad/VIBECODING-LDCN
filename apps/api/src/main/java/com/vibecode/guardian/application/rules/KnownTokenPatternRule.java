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
 * SEC-003: Detects recognized third-party API token formats (OpenAI, GitHub, AWS, etc.).
 */
@Component
public class KnownTokenPatternRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-003");

  private static final Pattern OPENAI_PATTERN =
      Pattern.compile("\\b(sk-[A-Za-z0-9_-]{16,})\\b");

  private static final Pattern GITHUB_PATTERN =
      Pattern.compile("\\b((?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,})\\b");

  private static final Pattern AWS_KEY_PATTERN =
      Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b");

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

    // OpenAI
    Matcher openAi = OPENAI_PATTERN.matcher(content);
    while (openAi.find()) {
      String raw = openAi.group(1);
      if (!SensitiveDataRedactor.isSafePlaceholder(raw)) {
        candidates.add(
            new SecurityFindingCandidate(
                ID,
                SecurityCategory.SECRET_EXPOSURE,
                SecuritySeverity.CRITICAL,
                "Token de API de provedor detectado (OpenAI)",
                "Foi detectado um token com padrão de API key de modelo ou provedor externo.",
                "sk-****REDACTED****",
                "content:offset-" + openAi.start(),
                "Remova a chave do projeto e rotacione a credencial no provedor correspondente."));
      }
    }

    // GitHub
    Matcher github = GITHUB_PATTERN.matcher(content);
    while (github.find()) {
      String raw = github.group(1);
      if (!SensitiveDataRedactor.isSafePlaceholder(raw)) {
        candidates.add(
            new SecurityFindingCandidate(
                ID,
                SecurityCategory.SECRET_EXPOSURE,
                SecuritySeverity.CRITICAL,
                "Token de acesso do GitHub detectado",
                "Foi detectado um token pessoal de acesso do GitHub (PAT).",
                "ghp_****REDACTED****",
                "content:offset-" + github.start(),
                "Revogue o token imediatamente no GitHub e remova-o de qualquer arquivo ou evidência."));
      }
    }

    // AWS
    Matcher aws = AWS_KEY_PATTERN.matcher(content);
    while (aws.find()) {
      String raw = aws.group(1);
      if (!SensitiveDataRedactor.isSafePlaceholder(raw)) {
        candidates.add(
            new SecurityFindingCandidate(
                ID,
                SecurityCategory.CREDENTIAL_EXPOSURE,
                SecuritySeverity.CRITICAL,
                "AWS Access Key ID detectada",
                "Foi detectada uma chave de acesso identificadora da AWS.",
                raw.substring(0, 4) + "****************",
                "content:offset-" + aws.start(),
                "Inative a chave de acesso no AWS IAM e substitua por roles ou credenciais temporárias."));
      }
    }

    return List.copyOf(candidates);
  }
}

