# VibeCode

Plataforma que mantém o contexto oficial de projetos conduzidos com apoio de LLMs.

O Project Brain — e não um modelo externo — é o dono do estado do projeto. Modelos são ferramentas
substituíveis: a memória sobrevive à troca de qualquer um deles.

> **Fase atual: fluxo guiado (fase 2).** Projeto, Project Brain, roadmap, tarefas, evidência,
> estado consolidado, Next Step Engine e Prompt Builder estão implementados — todos
> determinísticos. `model`, `usage`, `guardian`, `terminal`, `integration` e `wellness` continuam
> contratos de domínio. **Nenhuma integração com LLM foi construída.**

O ciclo que o VibeCode fecha sem nenhum modelo:

```
projeto → roadmap → tarefa → saída → evidência → análise → próximo passo → prompt
```

## Executar localmente

```bash
cp .env.example .env          # ajuste se quiser; nada aqui é secret de produção

docker compose up -d postgres

cd apps/api && mvn spring-boot:run

cd apps/web && npm install && npm run dev
```

| | |
| --- | --- |
| API | http://localhost:9000 |
| Web | http://localhost:4000 |
| Saúde | http://localhost:9000/actuator/health |

## Endpoints

| Método | Rota | Finalidade |
| --- | --- | --- |
| `POST` | `/api/projects` | Cria um projeto acompanhado |
| `GET` | `/api/projects` | Lista projetos |
| `GET` | `/api/projects/{id}` | Lê um projeto |
| `GET` | `/api/projects/{id}/brain` | Lê a memória oficial, com contagem por tipo |
| `POST` | `/api/projects/{id}/brain/entries` | Escreve uma entrada na memória oficial |
| `POST` | `/api/projects/{id}/outputs/analyze` | Analisa uma saída sem persistir nada |
| `POST` | `/api/projects/{id}/roadmap` | Cria o roadmap do projeto |
| `GET` | `/api/projects/{id}/roadmap` | Lê o plano com status derivado das tarefas |
| `POST` | `/api/projects/{id}/roadmap/phases` | Adiciona uma fase |
| `PUT` | `/api/projects/{id}/roadmap/phases/{phaseId}/position` | Reordena uma fase |
| `POST` | `/api/projects/{id}/roadmap/phases/{phaseId}/tasks` | Adiciona uma tarefa |
| `GET` | `/api/projects/{id}/tasks` | Lista as tarefas em ordem de plano |
| `POST` | `/api/projects/{id}/tasks/{taskId}/dependencies` | Registra uma dependência |
| `POST` | `/api/projects/{id}/tasks/{taskId}/criteria` | Adiciona um critério de aceite |
| `PATCH` | `/api/projects/{id}/tasks/{taskId}/criteria/{criterionId}` | Decide um critério (exige quem decidiu) |
| `POST` | `/api/projects/{id}/tasks/{taskId}/evidence` | Registra evidência e move a tarefa |
| `GET` | `/api/projects/{id}/tasks/{taskId}/evidence` | Histórico append-only da tarefa |
| `GET` | `/api/projects/{id}/state` | Onde o projeto está, com progresso calculado |
| `GET` | `/api/projects/{id}/guide` | Onde estamos, o que falta, o que fazer agora |
| `GET` | `/api/projects/{id}/next-step` | Próximo passo determinístico, com a razão |
| `POST` | `/api/projects/{id}/prompts/generate` | Gera o prompt do próximo passo |
| `GET` | `/api/projects/{id}/brain/proposals` | Memória proposta, aguardando revisão |
| `POST` | `/api/projects/{id}/brain/proposals/{id}/accept` | Aceita uma proposta e escreve a entrada |

Exemplo — evidência técnica vence alegação de sucesso:

```bash
curl -X POST http://localhost:9000/api/projects/$ID/outputs/analyze \
  -H 'Content-Type: application/json' \
  -d '{"content":"Tudo concluído com sucesso!\nTests run: 8, Failures: 2, Errors: 0"}'

# {"status":"FAILURE","shouldContinue":false,"signals":["TESTS_FAILED","CLAIMED_COMPLETION"], ...}
```

## Testes

```bash
cd apps/api && mvn test      # 89 testes
cd apps/web && npm run build
```

## Estrutura

```
apps/api           Spring Boot, monólito modular (com.vibecode.*)
apps/web           Next.js, React, TypeScript, Tailwind
packages/contracts Tipos TypeScript compartilhados entre web e API
packages/ui        Reservado — sem segundo consumidor ainda
infrastructure/    Docker; Redis e sandbox entram com caso de uso real
docs/              Visão, princípios, arquitetura, domínio, segurança e ADRs
```

## Antes de expandir

Leia, nesta ordem:

1. [Princípios fundamentais](docs/constitution/CORE_PRINCIPLES.md) — o que não pode ser violado
2. [Arquitetura](docs/architecture/ARCHITECTURE.md) — estado real de cada módulo
3. [Linguagem do domínio](docs/domain/DOMAIN_LANGUAGE.md) — os termos têm um significado só
4. [Princípios de segurança](docs/security/SECURITY_PRINCIPLES.md)
5. Os ADRs [004](docs/adr/ADR-004-roadmap-task-navigation.md) a
   [007](docs/adr/ADR-007-deterministic-prompt-builder.md) — por que o fluxo guiado é determinístico
6. [Context Engine](docs/architecture/CONTEXT_ENGINE.md) — o contrato operacional da Fase 6, e
   [ADR-022](docs/adr/ADR-022-context-compilation-is-not-provider-execution.md): compilar contexto
   não é executar num provedor

A Fase 6 está certificada em [PHASE6_CERTIFICATION.md](docs/PHASE6_CERTIFICATION.md), com a dívida
conhecida em [PHASE6_DEBT.md](docs/PHASE6_DEBT.md).
