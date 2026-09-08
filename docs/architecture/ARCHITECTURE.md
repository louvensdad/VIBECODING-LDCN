# Arquitetura

## Forma geral

Monólito modular em Java 21 / Spring Boot, com um único PostgreSQL como sistema de registro e
Flyway como dono do schema. Frontend Next.js separado, consumindo a API por HTTP.

```
apps/web  (Next.js, React, TypeScript, Tailwind)
    │  HTTP
apps/api  (Spring Boot, monólito modular)
    │  JDBC
PostgreSQL  (Flyway)
```

A escolha do monólito e seus limites estão em
[ADR-001](../adr/ADR-001-modular-monolith.md).

## Camadas dentro de um módulo

Quando um módulo tem implementação, ele se organiza assim:

```
domain/          entidades, value objects, portas (interfaces)
application/     serviços de caso de uso, transações
infrastructure/  repositórios, adaptadores
web/             controllers e DTOs
```

Módulos que ainda são só contrato têm apenas `domain/`. Criar as quatro pastas antes de haver
código nelas seria estrutura vazia, e o princípio 15 proíbe.

Regras que valem em toda a API:

- Nenhum endpoint retorna entidade JPA. Sempre DTO.
- Toda entrada pública é validada com Bean Validation.
- Erros saem em um único formato (`shared/web/ApiError`); a causa vai para o log, não para o
  cliente.
- Mudança de schema só por migration Flyway.

## Dependências entre módulos

O acoplamento é uma cadeia linear, sem ciclos:

```
project ← roadmap ← task ← output ← state ← guide ← prompt
              ↖________ brain ________↗
```

Cada módulo só conhece os anteriores. `state` monta a leitura consolidada sobre roadmap, task e
output; `guide` decide o próximo passo a partir de `state`; `prompt` monta o texto a partir de
ambos. `brain` é transversal: `output` propõe memória, `guide` e `prompt` leem regras e decisões.

`terminal`, `integration`, `usage` e `wellness` continuam contratos de domínio, sem implementação,
sem bean e sem tabela. Nada depende deles.

`vault` e `provider` entram fora dessa cadeia, e de propósito:

```
provider → vault → (audit)
```

`provider` é o único módulo que depende de `vault`. Nada mais na aplicação recebe `VaultService` —
em particular `prompt` não recebe, porque prompts são texto que sai da plataforma e um cofre
alcançável a partir de um construtor de texto é um cofre que uma refatoração distraída abre. A
dependência aponta numa direção só: `vault` não conhece provider accounts, e o que ele devolve é
uma referência, nunca material.

## Fluxo guiado

```
PROJECT → ROADMAP → PHASE → TASK → OUTPUT → EVIDENCE → ANALYSIS
                                                          ↓
                                                     TASK STATE
                                                          ↓
                                                    PROJECT STATE
                                                          ↓
                                                  NEXT STEP ENGINE
                                                          ↓
                                                   PROMPT BUILDER
```

Nenhum LLM participa deste ciclo. Ver
[ADR-004](../adr/ADR-004-roadmap-task-navigation.md),
[ADR-005](../adr/ADR-005-evidence-based-completion.md),
[ADR-006](../adr/ADR-006-deterministic-next-step.md) e
[ADR-007](../adr/ADR-007-deterministic-prompt-builder.md).

### Nada derivável é persistido

Progresso, prontidão e status de fase são calculados a cada leitura:

- `Task.applyReadiness` — `READY` quando toda dependência está `COMPLETED`;
- `PhaseStatusCalculator` — status da fase a partir das tarefas dentro dela;
- `ProjectState.progressPercentage()` — computado, sem coluna no banco.

`TaskStatusRecalculator` preserva os estados que o usuário está segurando (`IN_PROGRESS`,
`BLOCKED`, `NEEDS_VALIDATION`) e nunca reabre uma tarefa concluída.

### Conclusão de tarefa

`TaskCompletionPolicy` é o único caminho para `COMPLETED`, e exige as quatro condições do
[ADR-005](../adr/ADR-005-evidence-based-completion.md). Critério de aceite muda de estado apenas
por decisão explícita e atribuída — nunca por inferência de um build verde.

## Estado de cada módulo

| Módulo | Responsabilidade | Fase atual |
| --- | --- | --- |
| `project` | Identidade e ciclo de vida do projeto acompanhado | **Implementado** (entidade, serviço, API, tabela) |
| `brain` | Memória oficial estruturada e fila de propostas | **Implementado** (entradas, propostas, revisão) |
| `output` | Análise determinística, evidência persistida e análise registrada | **Implementado** |
| `shared` | Erros e tipos comuns da API | **Implementado** |
| `roadmap` | Fases ordenadas do plano | **Implementado** (entidades, serviço, API, tabelas) |
| `task` | Tarefas, dependências, critérios de aceite, política de conclusão | **Implementado** |
| `state` | Leitura consolidada de onde o projeto está | **Implementado** |
| `guide` | Próximo passo determinístico e orientação | **Implementado** |
| `prompt` | Construção de prompts a partir do estado registrado | **Implementado** |
| `model` | Abstração de provider e modelo | Contrato de domínio |
| `usage` | Tokens, custo, crédito, orçamento, autonomia | Contrato de domínio |
| `guardian` | Inspeção determinística, findings, score e gate de segurança | **Implementado** |
| `audit` | Trilha append-only de eventos de segurança | **Implementado** |
| `terminal` | Fronteira de execução em sandbox | Contrato de domínio |
| `integration` | Conexões externas | Contrato de domínio |
| `wellness` | Pausas, foco, Pomodoro | Contrato de domínio |

"Contrato de domínio" significa: os tipos existem, nada os implementa, nenhum bean é registrado e
nenhuma tabela foi criada.

## Project Brain

O Brain é a memória oficial. `BrainEntry` é append-only e carrega `source` obrigatório — memória
sem procedência não pode ser julgada depois. `ProjectBrain` é um modelo de leitura montado sob
demanda a partir das entradas; não existe linha "brain" no banco.

`BrainSnapshot` e o campo `version` preparam versionamento: uma entrada futura poderá superar
outra em vez de sobrescrever histórico. Nada escreve snapshots ainda.

## Regra de memória

Nada escreve na memória oficial sozinho — nem um modelo, nem a própria plataforma. O caminho é:

```
evento ou resultado do LLM  →  MemoryUpdateProposal  →  revisão  →  BrainEntry
```

Os eventos do fluxo (`TASK_COMPLETED`, `ERROR_FOUND`, `ERROR_RESOLVED`) geram **propostas**
persistidas em `memory_update_proposals`. Elas ficam pendentes até alguém aceitar, e só então uma
entrada existe — com o vínculo `resulting_entry_id` para rastreabilidade.

`MemoryUpdateProposal.toEntry()` recusa proposta não aceita, o que torna a revisão estrutural em
vez de opcional. `MemoryProposalValidator` continua sendo a porta para validação automática
futura.

## Output Analyzer

Determinístico, sem modelo. Detecta sinais e classifica por severidade:

| Severidade | Exemplo | Efeito |
| --- | --- | --- |
| `BLOCKING` | permissão negada, crédito esgotado | `BLOCKED` |
| `HARD_FAILURE` | build failure, teste falhando, exception | `FAILURE` |
| `SOFT_FAILURE` | menção a erro sem falha estruturada | `FAILURE`, ou `PARTIAL` junto com sucesso |
| `STRONG_SUCCESS` | build success, testes passando | `SUCCESS` |
| `SUCCESS_CLAIM` | "concluído", "pronto", "done" | `NEEDS_VALIDATION` — nunca `SUCCESS` |

Duas decisões sustentam isso:

- Sumários de teste são lidos como **contagem**, não como palavra-chave. `Failures: 0` é aprovação;
  procurar a palavra "failures" transformaria todo build limpo em falso negativo.
- `shouldContinue` só é verdadeiro em `SUCCESS`. Nenhuma outra classificação avança o roadmap.

## Limitador de autenticação

```
POST /api/auth/{login,register}
        ↓
CSRF                       (inalterado)
        ↓
ORIGIN BUCKET   ← filtro: recusa antes de qualquer consulta ou bcrypt
        ↓
IDENTIFIER BUCKET ← controller: antes de authenticate(), portanto antes do bcrypt
        ↓
AUTENTICAÇÃO
        ↓
AUDIT + METRICS
```

Ambos precisam permitir. Estado em memória, limitado, com relógio injetado. Ver
[ADR-016](../adr/ADR-016-authentication-abuse-protection.md).

## Pipeline de segurança

```
TEXTO / EVIDÊNCIA / PROMPT
        ↓
SECURITY INSPECTION  (SecurityRuleRegistry, 9 regras determinísticas)
        ↓
FINDING CANDIDATES
        ↓
REDAÇÃO              ← antes de qualquer escrita
        ↓
DEDUPLICAÇÃO         (fingerprint sobre o texto já redigido)
        ↓
SECURITY FINDINGS
        ↓
SECURITY ASSESSMENT  (score 0..100, indicador operacional)
        ↓
SECURITY GATE        (PASS / WARNING / REQUIRES_APPROVAL / BLOCKED)
        ↓
NEXT STEP            (CRITICAL aberto ⇒ REVIEW_SECURITY)
        ↓
PROMPT SAFETY        (SAFE / WARNING / BLOCKED, conteúdo sempre redigido)
        ↓
AUDIT                (append-only, sem credencial)
```

Nenhum modelo participa. Ver [ADR-012](../adr/ADR-012-deterministic-security-guardian.md) a
[ADR-015](../adr/ADR-015-append-only-audit-trail.md).

A inspeção é automática: acontece ao criar evidência e ao gerar prompt, não por botão — um segredo
já teria sido gravado quando alguém lembrasse de clicar.

## Vault e provider accounts

O caminho de uma credencial é curto e tem uma direção:

```
USER → PROVIDER ACCOUNT → INGESTÃO → SecretMaterial → CIFRAGEM → VAULT → SecretReference
```

O plaintext existe entre o corpo da requisição e a fronteira de cifragem, e não depois. O que volta
para a aplicação é uma referência — um id e um propósito. `ProviderAccount` guarda esse id e nada
mais, por isso pode ser lido, listado, serializado em DTO e escrito em audit sem que nenhum desses
caminhos toque em segredo.

Duas tabelas guardam material: `vault_secrets` (identidade, dono, propósito, ciclo de vida, e a
coluna que aponta para a versão em vigor) e `vault_secret_versions` (ciphertext, nonce, data key
embrulhada, e como cada linha foi cifrada). `provider_accounts` guarda a conexão e um ponteiro.

Não há leitura de material por HTTP. A única leitura em Java é `withSecret`, que entrega o material
a um callback e o limpa em seguida; não existe `getSecret` e não deve passar a existir.

Ver [ADR-017](../adr/ADR-017-envelope-encryption-for-stored-secrets.md) a
[ADR-021](../adr/ADR-021-honest-credential-status-and-no-partial-disclosure.md).

## O que não existe nesta fase

Sem chamada real a Claude, ChatGPT ou Gemini — inclusive para validar uma credencial guardada, o
que é o motivo de o estado ser `CREDENTIAL_STORED_UNVERIFIED` e não `CONNECTED`. Sem KMS externo:
a master key vem do ambiente e o provedor local é de desenvolvimento. Sem terminal remoto funcional, execução de código do
usuário, deploy, Redis, Kafka, Kubernetes, pgvector, microservices, autopilot ou scanners de
guardian. Cada um entra com seu próprio ADR.
