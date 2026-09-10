# Certificação da Fase 6 — Context Engine

Data: 2026-09-09 · Branch: `feat/context-engine` · Base: `c69bf4c`

O que a Fase 6 entrega: um motor que reúne o contexto oficial de um projeto, decide o que entra,
redige o que reconhece como sensível, guarda o resultado e permite inspecioná-lo. **Nenhuma chamada
a provedor de IA existe.** Isso é o resultado pretendido, não uma pendência —
[ADR-022](adr/ADR-022-context-compilation-is-not-provider-execution.md).

Contrato operacional: [CONTEXT_ENGINE.md](architecture/CONTEXT_ENGINE.md).
Dívida: [PHASE6_DEBT.md](PHASE6_DEBT.md).

---

## Builds

| Verificação | Resultado |
| --- | --- |
| `mvn -B clean package` (1ª) | 610 testes, 0 falhas, 0 erros · BUILD SUCCESS |
| `mvn -B clean package` (2ª) | 610 testes, 0 falhas, 0 erros · BUILD SUCCESS |
| `npm test` | 41 testes, 0 falhas |
| `npm run build` | verde · `/projects/[id]/context` em 6,49 kB |
| `npx tsc --noEmit` | limpo |
| Regressão de segurança | **64 testes, 0 falhas** |

## Matriz de testes — backend

610 testes executados, em 107 classes. As contagens abaixo são de **classes**, não de métodos: o
Surefire reporta o total de testes de forma confiável, mas não por categoria, e inventar a
distribuição seria pior do que não tê-la.

| Área | Classes |
| --- | --- |
| `context` | 50 |
| `shared` | 14 |
| `identity` | 9 |
| `guardian` | 8 |
| `support` | 5 |
| `vault` | 5 |
| `brain` | 3 |
| `output`, `project`, `roadmap`, `workflow` | 2 cada |
| `guide`, `prompt`, `provider`, `task`, `usage` | 1 cada |

Dentro de `context`, por natureza da evidência:

| Natureza | Classes representativas |
| --- | --- |
| domínio | `ContextPackTest`, `ContextOrderingTest`, `CompiledContextPackDigestTest`, `BrainEntryContextMappingTest`, `ContextProvenanceTest`, `EstimatedTokenCountTest` |
| persistência | `ContextPackMigrationTest`, `ContextPackPersistenceTest`, `ContextPackTextWidthBoundaryTest` |
| política | `ContextPolicyTest`, `DefaultContextPolicyRulesTest`, `ContextDenyByDefaultTest` |
| redação | `ContextRedactionTest` |
| coletores / compilador | 17 classes, incluindo `CollectorOwnershipIsolationTest`, `NothingIsDroppedFromCollectionTest`, `BudgetedContextSelectionTest` |
| API / web | `ContextPackApiTest`, `ContextPackListBoundsTest`, `ContextHttpErrorSurfaceTest` |
| adversarial / segurança | 12 classes (abaixo) |
| determinismo | `ContextProvenanceAndDeterminismTest`, `CollectorDeterminismTest`, `ContextOrderingTest` |

## Matriz de testes — frontend

Dois mecanismos diferentes, contados separadamente porque são executados por ferramentas
diferentes:

| Mecanismo | Quantidade | Quem executa |
| --- | --- | --- |
| Asserções de runtime (Vitest) | **41** | `npm test` |
| Controles de compilação (`@ts-expect-error`) | **6** | `tsc` / `next build` |

Os 6 controles `@ts-expect-error` **não são casos de teste adicionais**: eles vivem dentro de 6 dos
41 blocos `it(...)`, e não devem ser somados a eles. O Vitest executa esses 6 blocos sem exercer o
controle de tipo que eles carregam — daí serem contados numa linha própria. Eles
provam que o contrato recusa uma forma inválida: se um campo obrigatório virar opcional, a recusa
deixa de acontecer, a diretiva fica sem uso e o `tsc` falha com `TS2578: Unused '@ts-expect-error'
directive`. Isso foi **verificado executando**: tornar `ContextItemResponse.provenance` opcional
produziu exatamente esse erro, mais três quebras reais no componente.

Consequência importante: `npm test` sozinho **não** exerce esses controles — o Vitest apaga tipos, e
sob ele as seis asserções degradam para verificações triviais de runtime. A evidência mora no
`tsc`, e o `tsconfig.json` inclui os arquivos de teste, então `next build` a cobre.

## Regressão de segurança

64 testes, 0 falhas. As classes e o que cada uma mede:

| Classe | Superfícies medidas |
| --- | --- |
| `ContextShapelessSecretEightSurfaceTest` | banco, resposta HTTP do compile, GET, list, audit, logs, payload canônico |
| `ContextHttpSecretExposureTest` | corpos das 3 rotas, payload canônico e digest, **6 caminhos de rejeição**, logs, `audit_events`, varredura de tabelas |
| `ContextShapelessSecretBlastRadiusTest` | conteúdo do item, rótulo, payload canônico, coluna `content`, coluna `label` (+ audit e logs em teste separado) |
| `ContextSchemaWideLeakTest` | **colunas de banco**, todas as tabelas base do schema PUBLIC |
| `ContextPackPlaintextLeakTest` | `context_packs` + `context_pack_items`, e tudo logado em DEBUG ou acima |
| `ContextAuditLeakTest`, `ContextCompilationLogLeakTest` | trilha de auditoria, logs de compilação |
| `ContextSecretMaterialIsolationTest` | 8 regras de isolamento do Vault, incl. fecho transitivo |
| `ContextModuleArchitectureTest` | 9 regras ArchUnit do módulo |
| `ContextSafeContentBypassTest` | 4 tentativas de bypass falham; a 5ª (reflexão) é documentada como bem-sucedida |
| `ContextDenyByDefaultTest`, `ContextMaterialisationBoundaryTest` | espaço de negação; prova de forma dos tipos |

O ataque original que produziu o **CTX-09B-1** — um segredo sem forma reconhecível atrás de uma
chave (`DATABASE_PASSWORD=S3cr3tP4ssw0rd_…`) — é reexecutado por três dessas classes. Ocorrências do
plaintext nas superfícies medidas: **0**.

### O que estas medições **não** cobrem

Ler esta seção é parte de ler a anterior.

- A varredura de schema cobre **colunas de banco**, não corpos HTTP nem logs. Estes são cobertos por
  outras classes, não por ela.
- Colunas binárias (`vault_secret_versions.ciphertext`, `.nonce`, `.wrapped_data_key`) são varridas e
  **sempre voltam limpas**, porque `String.valueOf` renderiza `byte[]` como `[B@1f2c3d4`. Ver D-08.
- **O digest não é varrido em busca do segredo.** A propriedade provada é outra: que o payload
  canônico é a única entrada do digest (`packDigest == sha256Hex(canonicalPayload)`). O próprio teste
  chama a varredura do digest de decoração.
- "Logs limpos" significa que o motor é silencioso, não que ele loga com cuidado. Ver D-09.
- O Vault **não** é inalcançável por qualquer rota. A rota reflexiva/por nome de bean está fora do
  que as regras ArchUnit conseguem enxergar — e essa limitação é **argumentada, não medida**:
  nenhum teste da suíte a executa. Ver D-01, e o contraste com D-02, que é executado.

## Controles negativos

O princípio que emergiu da Fase 6: **teste verde não é evidência por si só.** Um teste que passaria
igualmente com o defeito presente não prova nada, e a única forma de saber é reintroduzir o defeito.

Não é um framework de mutação global — é prática de evidência no caminho crítico. Controles que
existem no código:

| Controle | O que prova |
| --- | --- |
| `ContextSchemaWideLeakTest.theProbeReachesNoTableItDidNotStartIn` | a varredura enxerga dados reais — `brain_entries`, `tasks`, `projects` **contêm** a sonda |
| `ContextSecretMaterialIsolationTest.theClosureWalkIsNotBlind` | o fecho, partido do vault, escapa do pacote; partido de `provider`, alcança o vault |
| `ContextSecretMaterialIsolationTest.theVocabularyRuleIsNotBlind` | a varredura de vocabulário, rodada sobre o vault, encontra `secretmaterial` |
| `ContextProvenanceAndDeterminismTest.theResolutionCheckIsNotBlind` | um id que não nomeia nada resolve para zero |
| `OneErrorResponseBoundaryTest.everyExceptionHandlerAnswersThroughTheSharedBoundary` | não-vacuidade: `hasSizeGreaterThanOrEqualTo(12)` |
| `...EightSurfaceTest.theRedactorRecognisesThePrefixedKey` | falha primeiro se o redator for revertido |
| `anUnmappedSiblingServesNothing` (2 classes) e `anUnmappedPathServesNothing` (1) | os 404 são 404 de verdade |

No frontend, cada proteção crítica foi confirmada quebrando-a: XSS (`dangerouslySetInnerHTML`
reintroduzido → 2 testes falham), seleção obsoleta (guarda de token removida → 1 falha), ordenação da
lista (invertida → 1 falha), teto de itens (elevado → 1 falha), escopo de projeto (guarda neutralizada
→ 1 falha), timeout (removido → 1 falha), e a projeção da listagem (campo extra → `TS2353`).

## Achados da Fase 6 e o invariante que cada um produziu

| Achado | Invariante que ficou |
| --- | --- |
| **CTX-09B-1** — segredo sem forma atrás de uma chave escapava | o redator reconhece a **chave**, não só a forma do valor; medido em 7 superfícies |
| Negociação de conteúdo no handler de erro | um `Accept` escolhido pelo chamador não transforma um 400 em 500; `ApiErrorResponder` decide se o corpo pode ser escrito |
| Amplificação de log de erro SQL | a mensagem do driver é retida — ela embute o valor que causou a falha |
| Listagem sem limite / N+1 | `?limit=` com gramática única, ids paginados e depois fetch join; 40 pacotes eram 130 KB em 42 statements |
| Ordem canônica | ordem total `sourceType → kind → sourceId → itemId`; a ordem do chamador não influi em nada |
| Correção do digest | campos prefixados pelo comprimento — o texto de um campo não forja uma fronteira |
| Travessia de procedência | id **e** versão da origem sobrevivem; "veio do Brain" não é auditável, "veio da decisão 7f3c, v4" é |
| Controles negativos | toda varredura de segurança tem um controle que prova que ela enxerga |

## Migrations

Flyway V1–V9, congelado. **Nenhuma V10 criada.**

```
V1__create_projects.sql                        V6__add_project_ownership.sql
V2__create_brain_entries.sql                   V7__create_security_guardian_and_audit.sql
V3__add_guided_workflow.sql                    V8__create_vault_and_provider_accounts.sql
V4__criterion_decisions_and_memory_proposals.sql   V9__create_context_packs.sql
V5__create_users.sql
```

`spring.flyway.enabled: true`, `spring.jpa.hibernate.ddl-auto: validate`.

Não existe teste que asseverre **checksums** de migration. O que existe é teste de **execução**:
`ContextPackMigrationTest` roda `migrate()` do zero e a partir de V8, verificando que linhas
existentes sobrevivem. Nenhuma migration foi editada nesta Wave, então nenhum checksum mudou.

## Dependências

As quatro dependências introduzidas na Wave 5 são **devDependencies**, e nenhuma dependência de
runtime foi adicionada ou alterada — verificado comparando os dois lockfiles: 60 pacotes de produção
antes, 60 depois, conjunto idêntico.

| Dependência | Por que existe |
| --- | --- |
| `vitest` 5.0.0 | executor; não havia nenhum no frontend antes da Wave 5 |
| `@testing-library/react` 16.3.3 | renderizar componentes em teste |
| `@testing-library/jest-dom` 7.0.1 | matchers de DOM |
| `jsdom` 30.0.1 | ambiente DOM |

Nenhum upgrade foi feito no CTX-10. Não foi adicionada biblioteca para destaque de sintaxe,
virtualização, estado ou markdown — o Inspector usa o que já existia.

## Estado das Waves

| Wave | Escopo | Estado |
| --- | --- | --- |
| 1 | Domínio de contexto | fechada |
| 2 | Coletores | fechada |
| 3 | Compilador, política, redação | fechada |
| 4 | Persistência, API, endurecimento adversarial | fechada |
| 5 | Context Inspector (CTX-08b) | fechada |
| 6 | CTX-10 — integração, documentação, certificação | esta |

## Fronteiras verificadas

| Afirmação | Como foi verificada |
| --- | --- |
| Redação acontece antes da persistência | ordem lida em `ContextPackCompiler.compile`; `AdmittedContextItem` só aceita `RedactedContextItem` (tipo) **+** 3 regras ArchUnit que confinam quem pode cunhar um item redigido (build) |
| A política é deny-by-default | `ContextPolicy.DEFAULT_DENY_RULE_ID` reservado; id declarado que o use é recusado na construção |
| Nenhum `ContextPack` causa execução em provedor | zero clientes HTTP de saída em `apps/api/src/main/java`; zero hostnames de provedor — **verificado por grep sobre a árvore atual, não pinado por teste**. Ver D-16 |
| O Context Engine não alcança plaintext do Vault | `context` não importa `vault` nem `provider`; 8 regras ArchUnit + fecho transitivo — **com a exceção reflexiva D-01** |
| O número de tokens é estimativa | `isExact` tipado como literal `false`; sem dimensão de tokens no orçamento |
| O Inspector usa os contratos reais | 6 controles `@ts-expect-error`, verificados falhando |
| A posse é imposta na Context API | toda rota começa por `requireReadable`; projeto invisível é 404, não 403 |
