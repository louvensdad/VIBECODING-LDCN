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
 * SEC-005: Detects highly dangerous or destructive commands in evidence and outputs.
 */
@Component
public class DangerousCommandRule implements SecurityRule {

  private static final RuleId ID = RuleId.of("SEC-005");

  private static final Pattern ROOT_RM_PATTERN =
      Pattern.compile("\\brm\\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\\s+(?:/|/\\*)(?:\\s|$)");

  private static final Pattern DROP_DB_PATTERN =
      Pattern.compile("(?i)\\bDROP\\s+(?:DATABASE|SCHEMA)\\b");

  private static final Pattern TRUNCATE_PATTERN =
      Pattern.compile("(?i)\\bTRUNCATE\\s+(?:TABLE\\s+)?[a-zA-Z0-9_.]+\\b");

  private static final Pattern CHMOD_777_PATTERN =
      Pattern.compile("\\bchmod\\s+(?:-[a-zA-Z]*R[a-zA-Z]*\\s+)?777\\b");

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

    // rm -rf /
    Matcher rootRm = ROOT_RM_PATTERN.matcher(content);
    if (rootRm.find()) {
      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.DANGEROUS_COMMAND,
              SecuritySeverity.CRITICAL,
              "Comando altamente destrutivo detectado (rm -rf /)",
              "Foi identificado comando de exclusão recursiva na raiz do sistema operacional.",
              rootRm.group(),
              "content:offset-" + rootRm.start(),
              "Nunca execute remoção forçada na raiz do sistema operacional. Especifique o diretório exato de trabalho."));
    }

    // DROP DATABASE / SCHEMA
    Matcher dropDb = DROP_DB_PATTERN.matcher(content);
    if (dropDb.find()) {
      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.DANGEROUS_COMMAND,
              SecuritySeverity.CRITICAL,
              "Comando DDL destrutivo detectado (DROP DATABASE/SCHEMA)",
              "Foi identificado comando SQL capaz de destruir bases de dados completas.",
              dropDb.group(),
              "content:offset-" + dropDb.start(),
              "Evite comandos destrutivos diretos. Utilize migrações versionadas controladas."));
    }

    // TRUNCATE
    Matcher truncate = TRUNCATE_PATTERN.matcher(content);
    if (truncate.find()) {
      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.DANGEROUS_COMMAND,
              SecuritySeverity.HIGH,
              "Comando de truncamento de tabela detectado",
              "Foi identificado comando de esvaziamento total de tabela.",
              truncate.group(),
              "content:offset-" + truncate.start(),
              "Certifique-se de que a operação não remove dados críticos sem backup prévio."));
    }

    // chmod 777
    Matcher chmod = CHMOD_777_PATTERN.matcher(content);
    if (chmod.find()) {
      candidates.add(
          new SecurityFindingCandidate(
              ID,
              SecurityCategory.UNSAFE_CONFIGURATION,
              SecuritySeverity.HIGH,
              "Permissões excessivas de arquivos (chmod 777)",
              "Foi identificada atribuição irrestrita de leitura, escrita e execução.",
              chmod.group(),
              "content:offset-" + chmod.start(),
              "Aplique o princípio do menor privilégio (ex: 644 para arquivos, 755 para diretórios)."));
    }

    return List.copyOf(candidates);
  }
}

