# Context Engine

Contrato operacional da Fase 6, derivado do código em `feat/context-engine`.

Este documento descreve o que está implementado. Onde a implementação difere do que seria natural
supor, o texto segue a implementação.

## O pipeline

```
fontes oficiais do projeto
      ↓
coletores                    11 coletores, um por tipo de fonte
      ↓
ContextItem candidato
      ↓
ContextPolicy                admite ou recusa
      ↓                      recusado é descartado sem registrar o texto
ContextRedaction             conteúdo, rótulo e referência da tarefa
      ↓
BudgetedContextSelection     corta pelo teto
      ↓
ContextPack
      ↓
persistência                 context_packs / context_pack_items (V9)
      ↓
Context API                  3 rotas
      ↓
Context Inspector            /projects/[id]/context
      ↓
PARA
```

A execução por provedor está **fora da Fase 6** e não existe. Ver
[ADR-022](../adr/ADR-022-context-compilation-is-not-provider-execution.md).

A ordem acima é a de `ContextPackCompiler.compile`, lida do código e não suposta: para cada
candidato, `policy.admit(...)`; se recusado, `continue`; se admitido,
`new AdmittedContextItem(ContextRedaction.redact(candidate), admission)`; ao final,
`BudgetedContextSelection.select(admitted, budget)`.

Dois pontos dessa ordem são decisões:

**Redação antes da medição.** Redigir depois de medir dimensionaria o pacote por um texto que
ninguém veria; redigir depois de calcular o digest colocaria o segredo dentro do
`contentFingerprint`.

**A recusa não guarda o texto.** Não há tabela de rejeitados, nem trace com o corpo do item, nem
rota que devolveria um. Ver [ADR-023](../adr/ADR-023-context-is-deny-by-default.md).

## Fonte física × sentido semântico

São dois eixos independentes, e o contrato insiste que não sejam colapsados.

`ContextSourceType` — **de onde o registro foi lido** (11 constantes):

`PROJECT` · `CURRENT_STATE` · `BRAIN_ENTRY` · `ROADMAP` · `CURRENT_PHASE` · `CURRENT_TASK` ·
`ACCEPTANCE_CRITERIA` · `LATEST_EVIDENCE` · `LATEST_OUTPUT_ANALYSIS` · `ACTIVE_ERRORS` ·
`SECURITY_SUMMARY`

`ContextKind` — **o que o item significa** (18 constantes):

`PROJECT_IDENTITY` · `OBJECTIVE` · `CONSTRAINT` · `VISION` · `REQUIREMENT` · `ARCHITECTURE` ·
`TECHNOLOGY` · `DECISION` · `RULE` · `CURRENT_STATE` · `COMPLETED_STEP` · `NEXT_STEP` · `EVIDENCE` ·
`PROMPT_RESULT` · `ERROR` · `SOLUTION` · `SECURITY_NOTE` · `NOTE`

`CURRENT_STATE` aparece nos dois, e não quer dizer a mesma coisa: como *source type* é o estado
calculado do projeto tomado como lugar de leitura; como *kind* é o que um registro diz sobre onde as
coisas estão. Um item lido de uma entrada do Brain pode ter kind `CURRENT_STATE`; a origem
continua sendo `BRAIN_ENTRY`.

Uma entrada do Brain preserva o próprio nome ao virar contexto. `BrainEntryContextMapping.kindOf` é
um `switch` exaustivo **sem ramo `default`**, nome por nome, sobre os 13 valores de
`BrainEntryType` — `VISION`, `REQUIREMENT`, `ARCHITECTURE`, `TECHNOLOGY`, `DECISION`, `RULE`,
`CURRENT_STATE`, `COMPLETED_STEP`, `ERROR`, `SOLUTION`, `NEXT_STEP`, `PROMPT_RESULT`, `NOTE`. São 13
dos 18 kinds declarados; a origem é sempre `BRAIN_ENTRY`.

A ausência de `default` é o ponto: um tipo novo de entrada do Brain quebra a compilação em vez de
cair silenciosamente num kind genérico. O contrato TypeScript pina o mesmo com uma asserção de tipo
(`BrainEntryKindsAreContextKinds`).

Não existe `FREE_TEXT`, `SCRATCH` nem `MODEL_OUTPUT`: contexto que nenhum registro pode avalizar não
tem como entrar.

## Procedência

Cada item admitido carrega, em `ContextProvenanceResponse`:

| Pergunta | Campo |
| --- | --- |
| De que tipo de fonte veio? | `provenance.source.type` |
| De qual registro exatamente? | `provenance.source.sourceId` |
| Em que revisão? | `provenance.source.version` — `null` onde o registro não tem revisão |
| De que projeto? | `provenance.projectId` |
| Quando isso era verdade? | `provenance.recordedAt` |
| Por que este item entrou? | `admission.policyRuleId` + `admission.explanation` |

O identificador importa tanto quanto o tipo. "Isto veio de uma decisão do Brain" não é auditável;
"isto veio da decisão `7f3c…`, versão 4" pode ser consultado, contestado e superado. Essa é a
diferença entre procedência e rótulo.

Tudo isso chega ao Inspector e é exibido por item.

## Identidade × fingerprint

Não são a mesma coisa e o código diz isso explicitamente.

**`packId`** (UUID) é a identidade persistente e a única. Um pacote é encontrado, referenciado e
guardado por ele.

**`contentFingerprint`** é um digest do *que o pacote diz*, não de *qual pacote é*. O javadoc de
`ContextPack` registra a regra: «must never be used as a key». Ele é calculado sobre a sequência
canônica de itens — para cada item, `id`, `kind`, `label`, `content`, tipo de origem, id de origem e
versão — com cada campo precedido do próprio comprimento, para que o texto de um campo não consiga
forjar uma fronteira.

**São dois digests, e não são intercambiáveis.** `ContextPack.contentFingerprint()` cobre o texto
dos itens de um pacote, sob uma definição fixada antes de a política existir — é o valor que o
Inspector exibe. `CompiledContextPack.packDigest()` é outro: cobre a versão da política, o
orçamento, a ordem e a regra que admitiu cada item, e é `sha256Hex` do payload canônico.
`CompiledContextPackDigestTest.theTwoDigestsAreNotInterchangeable` pina a distinção.

Nenhum dos dois é chave: `ContextPackMigrationTest.theFingerprintIsNotAKey` e
`.thePackDigestIsNotAKey` verificam que nenhuma constraint ou índice os transforma em identidade.

Compilar duas vezes sobre o mesmo estado produz **dois pacotes**, com `packId` diferentes e
possivelmente o mesmo `contentFingerprint`. Isso é intencional: um pacote é o registro de uma
decisão num instante, e um registro que pudesse ser sobrescrito deixaria de ser evidência.

## Orçamento

Três dimensões, todas medidas sobre o conteúdo **já redigido** — que é o texto que de fato sairia:

| Dimensão | Padrão | Máximo pedível |
| --- | --- | --- |
| `maxItems` | 50 | 1 000 |
| `maxCharacters` (UTF-16) | 200 000 | 5 000 000 |
| `maxBytes` (UTF-8) | 400 000 | 20 000 000 |

Essas três são **tetos duros** — `ContextBudget.admits` é literalmente
`usage.items() <= maxItems && usage.characters() <= maxCharacters && usage.bytes() <= maxBytes`.
O número de tokens **não é uma dimensão de orçamento** e não limita nada: o javadoc de
`ContextBudget` diz que a ausência é deliberada, porque esta fase não tem tokenizador de provedor.

Duas propriedades da seleção, lidas de `BudgetedContextSelection.select`:

**Nenhum item é cortado ao meio.** Um item que não couber é pulado inteiro (`continue`) e a varredura
segue — um item menor mais adiante ainda pode entrar. Não é um ponto de parada e não é um ranking.

**A ordem do chamador não influi.** A lista é reordenada para `AdmittedContextItem.CANONICAL_ORDER`
antes da seleção, então quem chama não decide quem entra; a ordem canônica decide.

**Rótulos não entram no orçamento.** `maxCharacters` e `maxBytes` medem apenas `content`. É decisão
registrada em `ContextBudget` e pinada por `ContextPackTest.labelIsInspectorMetadataNotProviderPayload`:
o rótulo é metadado de inspeção, não carga que iria para um provedor.

## Estimativa de tokens

`estimatedTokenCount` traz três campos: `estimatedTokens`, `heuristic` (a regra que produziu o
número) e `isExact`, tipado no contrato como o literal `false`.

É **estimativa**, nunca medição. Não existe tokenizador de provedor na Fase 6 — não existe provedor.
Um provedor conta tokens com o próprio tokenizador e chegará a outro número. A interface exibe o
número com til, com o rótulo `ESTIMADO`, com o nome da heurística e com a frase "não medido".

## Fronteira de redação

O que a plataforma faz: **valores sensíveis que o redator reconhece são substituídos por
`[REDACTED]` antes de o pacote ser gravado.**

O que a plataforma **não** afirma:

- que todos os segredos são removidos
- que um `ContextPack` não pode conter segredo
- qualquer coisa com a forma "100% seguro"

A detecção é por padrões. Um segredo com forma que o redator não conhece atravessa.

A garantia que **existe** tem duas metades, e só a primeira é do compilador.

**Do compilador:** `ContextRedaction.redact` devolve um `RedactedContextItem`, e
`AdmittedContextItem` não aceita outro tipo. Remover a chamada de redação de
`ContextPackCompiler` é erro de compilação.

**De regra de build:** que *nenhuma outra classe* possa cunhar um item redigido não é garantia de
linguagem. `RedactedContextItem.rehydratedFromStorage` é um `public static` que não redige nada —
existe para a persistência reidratar um item já redigido — e compila de qualquer lugar. O que o
confina são três regras ArchUnit (`ContextModuleArchitectureTest`), que falham a build no mesmo
lugar. O próprio código diz isso: «what the compiler cannot express, without a JPMS module this
project does not have, is "one package may call this factory and no other" — so it is expressed
here instead».

Isso não é teoria. O teste registra que a garantia de tipo sozinha foi contornada **duas vezes sem
reflexão**, e em ambos os episódios o fixture cru chegou a `context_pack_items`:

- um campo `Function<ContextItem, RedactedContextItem> MINT = RedactedContextItem::producedByRedaction`
  — «no reflection, no `setAccessible` — a launderer with a Function field, and the raw fixture
  reached `context_pack_items`»;
- uma classe aninhada, em três passos: chamada direta foi barrada pela primeira regra; por
  referência de método passou com nove regras verdes; e só com **uma quinta classe colaborando** em
  `application.compiler` o fixture chegou à tabela.

A cerca de dependência é o que fecha isso, não o tipo — e a primeira versão dela falhou porque
`doNotBelongToAnyOf` fazia da allowlist quatro **arquivos** em vez de quatro classes. O teste
registra que essa lição vale mais que a correção.

Limitações registradas estão em [PHASE6_DEBT.md](../PHASE6_DEBT.md).

## Fronteira do Vault

O Context Engine **não busca material secreto no Vault**.

Verificado no código: nenhum arquivo sob `com.vibecode.context` importa `com.vibecode.vault` ou
`com.vibecode.provider`. As dependências entre módulos do `context` são `brain`, `guardian`,
`output`, `project`, `roadmap`, `shared`, `state` e `task` — todas fontes de leitura do próprio
projeto.

Do lado do Vault, não há leitura de material por HTTP; a única leitura em Java é `withSecret`, que
entrega o material a um callback e o limpa em seguida ([ADR-018](../adr/ADR-018-vault-has-no-read-api.md)).
Não existe `getSecret`.

Contexto pode conter referências e metadados onde o contrato permite. Não pode conter
`SecretMaterial` decifrado.

## Fronteira de provedor

Não existe cliente HTTP de saída no backend: `RestTemplate`, `WebClient`, `HttpClient`, OkHttp,
Feign e `java.net.http` não aparecem em nenhum arquivo de `apps/api/src/main/java`, e não há hostname
de provedor em `main`.

**Isso é verificado por grep sobre a árvore atual e não está pinado por teste** — nenhuma regra
ArchUnit o proíbe. É a afirmação mais estrutural da fase e a menos protegida; está registrada como
D-16 em [PHASE6_DEBT.md](../PHASE6_DEBT.md). Quem construir a fronteira de execução da Fase 7 deve
criar a regra junto com ela.

Criar um `ContextPack` não autoriza transmissão. Quando a execução existir, ela atravessa uma
fronteira explícita responsável por autorização, seleção de provedor, acesso a credencial,
orçamento, auditoria e a transmissão em si. Ver
[ADR-022](../adr/ADR-022-context-compilation-is-not-provider-execution.md).

## Context API

Três rotas, todas sob `/api/projects/{projectId}/context`:

| Método | Rota | Resposta |
| --- | --- | --- |
| `POST` | `/compile` | `201` + `ContextPackResponse` |
| `GET` | `/{packId}` | `ContextPackResponse` |
| `GET` | `?limit=N` | `ContextPackResponse[]`, mais novos primeiro |

**Posse.** Toda rota começa por `ProjectService.requireReadable(projectId)`. Um projeto que o
chamador não pode ver é reportado como **não encontrado**, não como proibido — um `403` confirmaria
que o id pertence a um projeto real, que é justamente o que alguém sondando UUIDs quer descobrir.
No `GET /{packId}`, `findByIdAndProjectId` escopa o pacote ao projeto: um id de pacote real sob o
projeto errado é 404.

**`limit`.** Aceita um sinal de menos opcional seguido de 1 a 10 dígitos ASCII, faixa 1–100, padrão
20. Fora da faixa é **recusado**, não aparado: quem pediu mil e recebesse cem em silêncio leria uma
lista parcial como completa. **Ausente é a única grafia que significa "não nomeei número"**; vazio
(`?limit=`) é um chamador que escreveu o parâmetro e errou o valor, e é 400. `0x10`, `+7`, `" 5 "` e
dígitos não-ASCII são recusados — o parâmetro é lido como texto justamente para que
`Integer.decode`/`parseInt` não aceitem grafias que ninguém enumerou.

**Erros.** Nenhuma resposta carrega SQL, mensagem de driver ou stack trace. Projeto ou pacote
inexistente é 404; valor que o domínio recusa é 422; path variable que não é UUID é 400. Um `limit`
recusado é sempre um 400 com o mesmo `code` (`VALIDATION_ERROR`), variando apenas a frase dentro da
violação.

**Negociação de conteúdo.** O corpo de erro é escrito por `ApiErrorResponder`, que verifica se algum
converter consegue escrever a resposta para aquele `Accept`; se não, mantém o status e omite o
corpo. Um `Accept` escolhido pelo chamador não transforma um 400 em 500.

**CSRF.** O `POST /compile` é método não seguro e carrega o header `X-XSRF-TOKEN`, lido do cookie
que o servidor definiu ([ADR-010](../adr/ADR-010-csrf-protection-for-web-client.md)). As duas rotas
`GET` não precisam dele.

## Context Inspector

Rota `/projects/[id]/context`. Ver
[ADR-024](../adr/ADR-024-context-inspector-shows-only-stored-packs.md).

Exibe, do pacote selecionado: `packId`, `assembledAt`, referência da tarefa, contagem de itens,
caracteres e bytes, tokens estimados, `contentFingerprint`, as três dimensões de orçamento com o
usado contra o teto, a contagem de origens, e cada item com kind, rótulo, posição, procedência
completa, regra de admissão e tamanhos.

Propriedades que a tela mantém:

- **Não executa IA.** Não há botão, link ou rota que envie contexto a um modelo.
- **Não redige nada.** O conteúdo é exibido literalmente como a API o devolveu.
- **Conteúdo é texto não confiável** e é renderizado como filho de texto de `<pre>`. Não há
  `dangerouslySetInnerHTML` na tela.
- **Nada é persistido no navegador** — sem `localStorage`, `sessionStorage` ou IndexedDB.
- **Renderização limitada nos dois eixos**: 600 caracteres de prévia por item, 20 000 expandido,
  100 cards por pacote. Todo corte declara contra qual total foi feito; o pacote guardado não é
  tocado.
- **A listagem é projetada no servidor** para cinco escalares por linha, de modo que o conteúdo dos
  itens não atravessa para o cliente; o pacote inspecionado é relido da própria rota dele.
