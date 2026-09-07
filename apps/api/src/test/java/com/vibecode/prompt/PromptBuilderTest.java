package com.vibecode.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.EvidenceType;
import com.vibecode.project.application.ProjectService;
import com.vibecode.prompt.application.DeterministicPromptBuilder;
import com.vibecode.prompt.domain.GeneratedPrompt;
import com.vibecode.prompt.domain.PromptRequest;
import com.vibecode.prompt.domain.PromptType;
import com.vibecode.roadmap.application.RoadmapService;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.vibecode.support.TestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PromptBuilderTest {

  @Autowired ProjectService projects;
  @Autowired RoadmapService roadmaps;
  @Autowired TaskService tasks;
  @Autowired EvidenceService evidence;
  @Autowired BrainService brain;
  @Autowired DeterministicPromptBuilder prompts;
  @Autowired TestIdentity identity;

  @AfterEach
  void signOut() {
    identity.clear();
  }

  private UUID projectId;
  private Task task;

  @BeforeEach
  void setUp() {
    // Ordering between two @BeforeEach methods is not defined, so the fixture authenticates itself
    // rather than relying on a separate hook having run first.
    identity.createAndAuthenticate("Owner");
    projectId = projects.create("PromptProj", "", "Sistema de agendamento").getId();
    roadmaps.createOrGet(projectId);
    RoadmapPhase phase = roadmaps.addPhase(projectId, 1, "Authentication", "Login");
    task =
        tasks.addTask(
            projectId, phase.getId(), 1, "Configurar Spring Security", "Proteger os endpoints",
            RiskLevel.HIGH);
    tasks.addCriterion(projectId, task.getId(), "Endpoints exigem autenticação", true);
  }

  private GeneratedPrompt generate(PromptType type) {
    return prompts.build(new PromptRequest(projectId, task.getId(), type));
  }

  @Test
  @DisplayName("START_TASK carries the project, the task, the criteria and the mandatory return")
  void startTask() {
    GeneratedPrompt prompt = generate(PromptType.START_TASK);

    assertThat(prompt.type()).isEqualTo(PromptType.START_TASK);
    assertThat(prompt.taskId()).isEqualTo(task.getId());
    assertThat(prompt.content())
        .contains("CONTEXTO DO PROJETO")
        .contains("PromptProj")
        .contains("Sistema de agendamento")
        .contains("Authentication")
        .contains("Configurar Spring Security")
        .contains("Proteger os endpoints")
        .contains("CRITÉRIOS DE ACEITE")
        .contains("Endpoints exigem autenticação")
        .contains("NÃO REFAÇA")
        .contains("AO FINAL, RETORNE OBRIGATORIAMENTE")
        .contains("10. próximo passo sugerido.");
  }

  @Test
  @DisplayName("FIX_ERROR carries the real error and forbids moving on")
  void fixError() {
    evidence.record(
        projectId,
        task.getId(),
        EvidenceType.BUILD_RESULT,
        "BUILD FAILURE\nCompilation error in SecurityConfig.java:42",
        "maven");

    GeneratedPrompt prompt = generate(PromptType.FIX_ERROR);

    assertThat(prompt.content())
        .contains("NÃO CONTINUE PARA NOVAS FUNCIONALIDADES")
        .contains("Compilation error in SecurityConfig.java:42")
        .contains("SINAIS DETECTADOS")
        .contains("COMPILATION_ERROR")
        .contains("não avance para a próxima feature");
    assertThat(prompt.contextSources()).contains("Latest evidence");
  }

  @Test
  @DisplayName("VALIDATE_RESULT asks for proof and forbids new features")
  void validateResult() {
    GeneratedPrompt prompt = generate(PromptType.VALIDATE_RESULT);

    assertThat(prompt.content())
        .contains("ainda não existe evidência suficiente")
        .contains("Não faça novas funcionalidades")
        .contains("Endpoints exigem autenticação");
  }

  @Test
  @DisplayName("MODEL_HANDOFF carries everything a different model would need")
  void modelHandoff() {
    brain.add(
        projectId,
        BrainEntryType.DECISION,
        "Autenticação por JWT",
        "Sessões stateless para permitir escala horizontal",
        "louvens");
    evidence.record(
        projectId, task.getId(), EvidenceType.BUILD_RESULT, "BUILD SUCCESS", "maven");

    GeneratedPrompt prompt = generate(PromptType.MODEL_HANDOFF);

    assertThat(prompt.content())
        .contains("PROJECT HANDOFF")
        .contains("PromptProj")
        .contains("Fase atual")
        .contains("Authentication")
        .contains("Tarefa atual")
        .contains("Não refaça")
        .contains("Última evidência")
        .contains("BUILD SUCCESS")
        .contains("Decisões relevantes")
        .contains("Autenticação por JWT");
    assertThat(prompt.contextSources())
        .anyMatch(source -> source.startsWith("Project Brain: rules and decisions"));
  }

  @Test
  @DisplayName("RESOLVE_BLOCKER works on the obstacle and refuses to weaken security")
  void resolveBlocker() {
    evidence.record(
        projectId, task.getId(), EvidenceType.TERMINAL_OUTPUT, "permission denied", "shell");

    GeneratedPrompt prompt = generate(PromptType.RESOLVE_BLOCKER);

    assertThat(prompt.content())
        .contains("RESOLUÇÃO DE BLOQUEIO")
        .contains("Isto não é um erro de código")
        .contains("permission denied")
        .contains("Não sugira desabilitar verificações de segurança")
        .contains("Não peça para colar credenciais");
  }

  @Test
  @DisplayName("with no type given the engine's suggestion is used")
  void typeDefaultsToTheRecommendation() {
    evidence.record(projectId, task.getId(), EvidenceType.BUILD_RESULT, "BUILD FAILURE", "maven");

    GeneratedPrompt prompt = prompts.build(new PromptRequest(projectId, null, null));

    assertThat(prompt.type()).isEqualTo(PromptType.FIX_ERROR);
    assertThat(prompt.taskId()).isEqualTo(task.getId());
  }

  @Test
  @DisplayName("a prompt for a project with no plan still says what to do")
  void promptWithoutATaskDoesNotBreak() {
    UUID emptyProject = projects.create("Vazio", "", "Ainda sem roadmap").getId();

    GeneratedPrompt prompt = prompts.build(new PromptRequest(emptyProject, null, null));

    assertThat(prompt.taskId()).isNull();
    assertThat(prompt.content()).contains("Vazio").isNotBlank();
  }

  @Test
  @DisplayName("no prompt carries a secret, because no secret is ever in the context")
  void promptsCarryOnlyRecordedContext() {
    GeneratedPrompt prompt = generate(PromptType.START_TASK);

    assertThat(prompt.contextSources()).contains("Project", "Project state");
    assertThat(prompt.content()).doesNotContain("password").doesNotContain("API_KEY");
  }
}
