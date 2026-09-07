package com.vibecode.prompt.application;

import com.vibecode.prompt.domain.PromptContext;
import com.vibecode.prompt.domain.PromptType;
import java.util.List;

/**
 * The prompt texts, kept apart from the assembly logic.
 *
 * <p>Every template ends by demanding real output — build results, test results, actual errors —
 * because the analyzer downstream can only work with evidence. A prompt that lets a model answer
 * "pronto!" produces exactly the unverifiable claim the platform exists to reject.
 */
final class PromptTemplates {

  private static final String NOTHING = "(nada registrado)";

  private PromptTemplates() {}

  static String render(PromptType type, PromptContext context) {
    return switch (type) {
      case START_TASK -> startTask(context);
      case CONTINUE_TASK -> continueTask(context);
      case FIX_ERROR -> fixError(context);
      case VALIDATE_RESULT -> validateResult(context);
      case MODEL_HANDOFF -> modelHandoff(context);
      case ASK_FOR_EVIDENCE -> askForEvidence(context);
      case RESOLVE_BLOCKER -> resolveBlocker(context);
    };
  }

  private static String startTask(PromptContext context) {
    return """
        CONTEXTO DO PROJETO

        Projeto:
        %s

        Objetivo:
        %s

        ETAPA ATUAL
        %s

        TAREFA
        %s

        OBJETIVO
        %s

        JÁ CONCLUÍDO
        %s

        NÃO REFAÇA
        Não reimplemente nada da lista "JÁ CONCLUÍDO". Não altere arquivos fora do escopo desta \
        tarefa.

        CRITÉRIOS DE ACEITE
        %s

        DECISÕES JÁ TOMADAS
        %s

        RESTRIÇÕES
        - Não invente credenciais, chaves ou secrets.
        - Não marque a tarefa como concluída sem evidência real de execução.
        - Se algo estiver ambíguo, pergunte antes de assumir.

        %s"""
        .formatted(
            context.projectName(),
            context.projectIdea(),
            context.currentPhase(),
            context.taskTitle(),
            context.taskObjective(),
            bullets(context.completedWork()),
            bullets(context.acceptanceCriteria()),
            bullets(context.relevantDecisions()),
            mandatoryReturn());
  }

  private static String continueTask(PromptContext context) {
    return """
        CONTINUAÇÃO DE TAREFA

        Projeto:
        %s

        ETAPA ATUAL
        %s

        TAREFA EM ANDAMENTO
        %s

        OBJETIVO
        %s

        JÁ CONCLUÍDO
        %s

        NÃO REFAÇA
        A tarefa já foi iniciada. Continue de onde parou; não recomece do zero e não altere o que \
        já está pronto.

        CRITÉRIOS DE ACEITE
        %s

        ÚLTIMA EVIDÊNCIA REGISTRADA
        %s

        %s"""
        .formatted(
            context.projectName(),
            context.currentPhase(),
            context.taskTitle(),
            context.taskObjective(),
            bullets(context.completedWork()),
            bullets(context.acceptanceCriteria()),
            context.latestEvidence(),
            mandatoryReturn());
  }

  private static String fixError(PromptContext context) {
    return """
        NÃO CONTINUE PARA NOVAS FUNCIONALIDADES.

        Estamos corrigindo um erro da tarefa atual.

        PROJETO
        %s

        TAREFA
        %s

        ERRO/EVIDÊNCIA
        %s

        SINAIS DETECTADOS
        %s

        ÚLTIMO ESTADO CONHECIDO
        %s

        OBJETIVO

        Investigue a causa raiz e aplique somente a correção necessária.

        NÃO REFAÇA
        Não reescreva partes que já funcionavam. Não introduza funcionalidades novas junto da \
        correção.

        DEPOIS DA CORREÇÃO

        1. execute novamente o comando que falhou;
        2. execute os testes relacionados;
        3. informe arquivos alterados;
        4. retorne a saída real;
        5. não avance para a próxima feature."""
        .formatted(
            context.projectName(),
            context.taskTitle(),
            context.latestEvidence(),
            bullets(context.latestSignals()),
            context.currentPhase() + " · " + bulletsInline(context.activeProblems()));
  }

  private static String validateResult(PromptContext context) {
    return """
        A implementação foi reportada como concluída,
        mas ainda não existe evidência suficiente para considerar a tarefa pronta.

        PROJETO
        %s

        TAREFA
        %s

        Execute as validações abaixo:

        %s

        O QUE AINDA FALTA
        %s

        Não faça novas funcionalidades.

        Retorne os resultados completos: comando executado, saída real, contagem de testes e \
        eventuais erros."""
        .formatted(
            context.projectName(),
            context.taskTitle(),
            bullets(context.acceptanceCriteria()),
            bullets(context.activeProblems()));
  }

  private static String modelHandoff(PromptContext context) {
    return """
        PROJECT HANDOFF

        Projeto:
        %s

        Objetivo:
        %s

        Fase atual:
        %s

        Tarefa atual:
        %s

        Já concluído:
        %s

        Não refaça:
        Tudo que está na lista acima já está pronto e validado. Não reimplemente.

        Problemas ativos:
        %s

        Última evidência:
        %s

        Decisões relevantes:
        %s

        Próxima ação:
        %s"""
        .formatted(
            context.projectName(),
            context.projectIdea(),
            context.currentPhase(),
            context.taskTitle(),
            bullets(context.completedWork()),
            bullets(context.activeProblems()),
            context.latestEvidence(),
            bullets(context.relevantDecisions()),
            context.taskObjective());
  }

  private static String askForEvidence(PromptContext context) {
    return """
        PEDIDO DE EVIDÊNCIA

        Projeto:
        %s

        Tarefa:
        %s

        Não preciso de uma afirmação de que funcionou. Preciso da prova.

        Execute e retorne a saída literal de:

        1. o comando de build;
        2. o comando de testes;
        3. qualquer comando que demonstre o comportamento descrito nos critérios de aceite.

        CRITÉRIOS DE ACEITE
        %s

        Cole a saída completa, sem resumir e sem omitir erros ou warnings."""
        .formatted(context.projectName(), context.taskTitle(), bullets(context.acceptanceCriteria()));
  }

  private static String resolveBlocker(PromptContext context) {
    return """
        RESOLUÇÃO DE BLOQUEIO

        Isto não é um erro de código. Algo externo está impedindo o progresso.

        PROJETO
        %s

        TAREFA
        %s

        EVIDÊNCIA DO BLOQUEIO
        %s

        SINAIS DETECTADOS
        %s

        OBJETIVO

        Identifique qual recurso, permissão, credencial ou limite está faltando e descreva os \
        passos exatos para restabelecê-lo.

        RESTRIÇÕES
        - Não sugira desabilitar verificações de segurança para contornar o bloqueio.
        - Não peça para colar credenciais nesta conversa.

        DEPOIS

        Repita o comando que falhou e retorne a saída real."""
        .formatted(
            context.projectName(),
            context.taskTitle(),
            context.latestEvidence(),
            bullets(context.latestSignals()));
  }

  private static String mandatoryReturn() {
    return """
        AO FINAL, RETORNE OBRIGATORIAMENTE

        1. resumo;
        2. arquivos criados;
        3. arquivos alterados;
        4. comandos executados;
        5. build;
        6. testes;
        7. erros;
        8. warnings;
        9. pendências;
        10. próximo passo sugerido.""";
  }

  private static String bullets(List<String> items) {
    if (items.isEmpty()) {
      return NOTHING;
    }
    return items.stream().map(item -> "- " + item).reduce((a, b) -> a + "\n" + b).orElse(NOTHING);
  }

  private static String bulletsInline(List<String> items) {
    return items.isEmpty() ? NOTHING : String.join("; ", items);
  }
}
