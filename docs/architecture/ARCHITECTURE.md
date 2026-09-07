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
código nelas seria estrutura vazia, e o princípio 12 proíbe.

Regras que valem em toda a API:

- Nenhum endpoint retorna entidade JPA. Sempre DTO.
- Toda entrada pública é validada com Bean Validation.
- Erros saem em um único formato (`shared/web/ApiError`); a causa vai para o log, não para o
  cliente.
- Mudança de schema só por migration Flyway.

## Dependências entre módulos

O acoplamento hoje é deliberadamente raso:

```
project  ←──  brain  ←──  guide (contrato)
   ↑            ↑
 output      roadmap (contrato)
              prompt (contrato)
```

`brain` e `output` dependem de `project` para garantir que memória e evidência sempre pertencem a
um projeto existente. Nada depende de `guide`, `prompt`, `roadmap`, `guardian`, `terminal`,
`integration`, `usage` ou `wellness` — são contratos aguardando implementação, e essa direção de
dependência é o que permite extrair um módulo depois sem desmontar o resto.

## Estado de cada módulo

| Módulo | Responsabilidade | Fase atual |
| --- | --- | --- |
| `project` | Identidade e ciclo de vida do projeto acompanhado | **Implementado** (entidade, serviço, API, tabela) |
| `brain` | Memória oficial estruturada | **Implementado** (entrada, leitura, tabela) + proposta de memória como contrato |
| `output` | Análise determinística de evidência | **Implementado** (analisador + API) |
| `shared` | Erros e tipos comuns da API | **Implementado** |
| `roadmap` | Fases, etapas, progresso, próximo passo | Contrato de domínio |
| `guide` | Onde estamos, o que falta, o que fazer agora | Contrato de domínio |
| `task` | Etapas com critério de conclusão e riscos | Contrato de domínio |
| `prompt` | Construção de prompts a partir do contexto oficial | Contrato de domínio |
| `model` | Abstração de provider e modelo | Contrato de domínio |
| `usage` | Tokens, custo, crédito, orçamento, autonomia | Contrato de domínio |
| `guardian` | Sete vigias de risco do projeto | Contrato de domínio |
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

Nenhum modelo externo escreve na memória oficial. O caminho é:

```
resultado do LLM  →  MemoryUpdateProposal  →  validação  →  BrainEntry
```

`MemoryUpdateProposal.toEntry()` recusa uma proposta que não tenha sido aceita, e
`MemoryProposalValidator` é a porta onde as regras de validação entrarão. A proposta ainda não é
persistida: sem o fluxo de revisão, a tabela não teria uso — ver princípio 12.

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

## O que não existe nesta fase

Sem chamada real a Claude, ChatGPT ou Gemini. Sem terminal remoto funcional, execução de código do
usuário, deploy, Redis, Kafka, Kubernetes, pgvector, microservices, autopilot ou scanners de
guardian. Cada um entra com seu próprio ADR.
