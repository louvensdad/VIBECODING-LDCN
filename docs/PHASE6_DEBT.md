# Dívida registrada — Fase 6

Achados conhecidos, não bloqueantes, com o estado real verificado no código em `feat/context-engine`.

Nada aqui foi corrigido durante o CTX-10. Corrigir em silêncio um item registrado é pior do que
deixá-lo aberto: o próximo leitor deixa de saber que ele existiu.

Cada item traz por que **não** bloqueia. "Não bloqueia" nunca quer dizer "não importa".

---

## Aberto

### D-01 · Rota reflexiva alcança o Vault
**Risco:** alto se explorável de dentro do processo; requer código malicioso já em execução no
backend.
**Evidência:** `ContextSecretMaterialIsolationTest.java:40-56` (javadoc), `:156-157` (comentário),
`:190-207` (mensagem de falha da regra de fecho).
As regras ArchUnit percorrem arestas de bytecode estaticamente resolvíveis. Um
`applicationContext.getBean("vaultService")` combinado com
`Class.forName("com.vibecode.vault.domain.SecretReference")` alcança plaintext vivo do Vault com
todas as regras verdes.

**Este item é argumentado, não demonstrado.** Nenhum teste da suíte executa essa rota — `getBean(` e
`Class.forName(` não aparecem em nenhum teste executável do repositório, só em javadoc e comentário.
O javadoc registra, em passado, que a rota foi demonstrada uma vez fora da suíte; a mensagem de
falha da regra de fecho diz o complemento honesto: «a reflective or bean-name route is **outside
what this can see**».

Contraste deliberado com o **D-02**, que é da mesma família mas **é** executado e asseverado. A
diferença entre os dois é o que separa limitação medida de limitação argumentada, e ela importa.
**Por que não bloqueia:** o vetor pressupõe que alguém já consegue introduzir código no backend.
Nesse ponto, o Vault não é a primeira coisa perdida. A regra continua valendo para todo caminho
normal.
**Fase futura:** exigiria controle em runtime (SecurityManager está deprecado; um agente Java ou
isolamento de módulos JPMS seriam os caminhos), o que é uma fase inteira.

> **Não afirmar** que o Vault é inalcançável "por qualquer rota". Ele não é — e a rota que resta é
> argumentada, não medida.

### D-02 · Forja reflexiva de `RedactedContextItem`
**Risco:** médio, mesma classe do D-01.
**Evidência:** `ContextSafeContentBypassTest.reflectionCanStillForgeAWrapperAndThatIsSaidOutLoud`
(`:226-286`). Quatro tentativas de bypass falham; a quinta, via `setAccessible(true)`, persiste um
item cru e o teste **afirma que ele chega** a `context_pack_items` — `isEqualTo(1)`.
**Por que não bloqueia:** a garantia de tipo vale contra erro de programação, que é a ameaça real.
Não vale contra reflexão, e o teste diz isso em voz alta em vez de fingir o contrário.

### D-03 · Chaves com sufixo não são reconhecidas pelo redator
**Risco:** alto para quem escreve chaves assim — o valor passa inteiro.
**Evidência:** `SecretAssignmentGrammarTest.aSuffixedKeyIsNotRecognised` (`:840-880`).
`DB_PASSWORD_VALUE=`, `DB_PASSWORDS=`, `DB_TOKEN_2=`, `dbPasswordValue=` e `creds[password]=`
atravessam sem redação. É o raio de alcance completo do CTX-09B-1 para essa grafia.
**Por que não bloqueia:** a forma comum (`DB_PASSWORD=`, `TOKEN=`) é reconhecida e está pinada por
teste. A extensão da gramática é trabalho de redator, não de contexto.

### D-04 · Valor com múltiplas palavras é redigido só em parte
**Risco:** médio.
**Evidência:** `SecretAssignmentGrammarTest.aMultiWordValueIsOnlyPartlyRemoved` (`:893-905`).
`"password": "correct horse battery staple"` vira `"[REDACTED] horse battery staple"`.
**Por que não bloqueia:** o redator para no primeiro delimitador porque não tem como saber onde o
valor termina sem um parser da linguagem em questão.

### D-05 · Valor terminando em espaço vaza a cauda
**Risco:** médio.
**Evidência:** `SecretAssignmentGrammarTest.aValueEndingAtWhitespaceStillLeaksItsTail` —
`@DisplayName("STILL OPEN: ...")` (`:747-790`). Inclui um quarto membro antes sem nome: um container
completo seguido de cauda.

### D-06 · Formas de bloco YAML não são redigidas
**Risco:** médio.
**Evidência:** `SecretAssignmentGrammarTest.java:493-510`. Escalares de bloco `|` e `>`, âncoras `&`,
tags `!!str` e a chave de merge `<<:` são asseverados **intocados**.
**Por que não bloqueia:** a razão está escrita no teste — nada na linha diz onde essas formas
terminam, e descobrir exige um parser YAML e não um redator.

### D-07 · Caso `[prod, secret]` deixado aberto deliberadamente
**Evidência:** `SecretAssignmentGrammarTest.java:683-700`, "left open deliberately, on three
grounds".

### D-08 · Colunas binárias são invisíveis à varredura de schema
**Risco:** baixo — é limitação do instrumento, não do sistema.
**Evidência:** `ContextSchemaWideLeakTest.java:30-40`. `String.valueOf` renderiza `byte[]` como
`[B@1f2c3d4`, então `vault_secret_versions.ciphertext`, `.nonce` e `.wrapped_data_key` são varridas
e **sempre voltam limpas**, seja lá o que contenham.
**Por que não bloqueia:** essas colunas guardam ciphertext por construção. O ponto é não contar a
varredura como prova sobre elas.

### D-09 · "Os logs estão limpos" significa que o motor é silencioso
**Risco:** baixo hoje, cresce sozinho.
**Evidência:** `ContextShapelessSecretBlastRadiusTest` e `ContextShapelessSecretEightSurfaceTest`.
O resultado medido é que o Context Engine não loga conteúdo — não que ele logue com cuidado.
Só o segundo sobreviveria a alguém acrescentar uma linha de debug.

### D-10 · Visibilidade da regra ArchUnit de `ResponseEntity`/`ProblemDetail`
**Evidência:** `OneErrorResponseBoundaryTest.java:56`, `:79`, `:87`, `:137`, `:197`, `:211`.
A regra é por classe e cobre chamadas de construtor, três nomes de fábrica e três tipos de retorno.
Não enxerga um helper em **outra** classe, nem um handler escrevendo direto no
`HttpServletResponse`. Verbatim: "this rule documents the boundary and catches the near-misses; the
behavioural tests are what hold it."

### D-11 · Passagem de mensagem interna num 422
**Evidência:** `ContextPackController.java:71-77`.
`ContextPackEntity.toCompiled` lança `IllegalStateException` nomeando cada item id quando a ordem das
linhas de um pacote guardado não é a canônica, e essa mensagem chega ao cliente num 422. Carrega ids
e nenhum conteúdo, e só é alcançável editando linhas por trás da API.
**Por que não bloqueia:** ids sem conteúdo, e o caminho exige acesso direto ao banco.

### D-12 · Conteúdo de item não tem teto de tamanho
**Risco:** baixo para segurança, real para desempenho.
**Estado:** **registrado aqui pela primeira vez.** Verificado: `ContextItem.MAX_LABEL_LENGTH = 500`
limita o rótulo; `ContextItem.content()` não tem limite. O que existe são tetos **por requisição**
(`MAX_REQUESTABLE_CHARACTERS = 5_000_000`, `MAX_REQUESTABLE_BYTES = 20_000_000`) e o
`ContextReadWindow`, que limita linhas lidas por fonte. Um registro isolado grande demais é pulado
inteiro (`ContextBoundaryInputTest.anEnormousRecordIsSkippedRatherThanTruncated`).
**Por que não bloqueia:** o orçamento impede que um pacote inteiro cresça sem limite, e o Inspector
limita o que renderiza (600 de prévia, 20 000 expandido, 100 cards). O que falta é um teto no
domínio.

### D-13 · Colunas de identificador com largura 200
**Evidência:** `ContextPersistenceFailure.java:31-33`. Campos de domínio ilimitados contra colunas de
200. Nenhum coletor chega perto disso hoje.

### D-14 · Crescimento append-only de pacotes
**Evidência:** `ContextPackController.java:179` — "and it needs doing". Um pacote nunca é
substituído, então a tabela cresce pela vida do projeto. A listagem já é limitada (padrão 20,
máximo 100), o que era a parte urgente.

### D-15 · `theBrainMappingStillHasNoDefaultBranch` depende de detalhe de codegen
**Evidência:** `ContextModuleArchitectureTest.java:~99`. A regra detecta o ramo sintético
`MatchException` gerado pelo javac, que não é garantia de linguagem. Instrução registrada: se
quebrar, **substituir**, nunca apagar.

### D-16 · A ausência de cliente HTTP de saída não é imposta por nada
**Risco:** é a afirmação mais estrutural da Fase 6, e a menos protegida.
**Estado:** **registrado aqui pela primeira vez.**
**Evidência:** `RestTemplate`, `WebClient`, `HttpClient`, OkHttp, Feign, `java.net.http`,
`HttpURLConnection`, `new URL(` e `URI.create` não aparecem em nenhum arquivo de
`apps/api/src/main/java`, e não há hostname de provedor em `main`. Isso é **verdade hoje e
verificado por grep** — mas não existe regra ArchUnit nem teste que o proíba. O único uso de
`java.net.http` no repositório é um cliente de teste legítimo
(`ExpectedHttpErrorWireContractTest.java:7-9,56`).
**Por que não bloqueia:** a propriedade é verdadeira agora, e o
[ADR-022](adr/ADR-022-context-compilation-is-not-provider-execution.md) registra a intenção.
**O que isso custa:** o primeiro bean `WebClient` da Fase 7 quebra a propriedade com build verde e
nenhuma asserção falhando. Uma regra ArchUnit — mesma forma da que proíbe `context → vault` —
resolveria; não foi acrescentada no CTX-10 porque esta Wave não implementa, e acrescentar teste
novo aqui seria esconder trabalho de implementação numa Wave de documentação.
**Fase futura:** a fronteira de execução da Fase 7 deve nascer junto com a regra que a torna a
**única** porta de saída, e não depois dela.

> A mesma régua aplicada ao `?LIMIT` maiúsculo mais abaixo se aplica aqui: verificado por leitura
> não é o mesmo que asseverado por teste, e a diferença é registrada em vez de arredondada.

---

## Fechado durante a Fase 6

### R4-B · O responder não via todos os converters
**Estado:** **fechado, pinado por teste.**
**Evidência:** `ApiErrorResponder.java:116`;
`ApiErrorResponderNegotiationTest.theResponderSeesEveryConverterThatCouldWriteAnError`
(`:208`). O responder recebe a lista do bean `HttpMessageConverters` (9); o adapter tem 10. O extra é
`ProjectingJackson2HttpMessageConverter`, que não consegue escrever um `ApiError`. Injetar a lista do
adapter é impossível — referência circular dura.

---

## Itens do briefing que **não existem** neste código

Foram procurados e não encontrados. Registrados aqui para que ninguém os documente por inércia.

| Item procurado | Resultado |
| --- | --- |
| Lacuna de autorização temporal | **NÃO VERIFICADO.** Sem ocorrência de "temporal", "time-of-check" ou "revoked mid" em `apps/api/src` ou `docs/`. Não registrado em lugar nenhum. |
| Tratamento de homóglifos | **NÃO VERIFICADO.** Sem ocorrência de "homoglyph", "cyrillic", "unicode confus" ou "NFKC". Dígitos não-ASCII **são** recusados no `limit` (U+0665, U+FF12 — `ContextPackController.java:250-270`), mas isso é outra questão. |
| Comportamento de `?LIMIT=5` maiúsculo | **NÃO VERIFICADO como dívida registrada.** Nenhum teste usa `param("LIMIT", …)` e nenhum javadoc menciona caixa. Da leitura do código, `@RequestParam(name = "limit")` não casaria com `LIMIT`, que seria lido como ausente e responderia o padrão 20 — mas isso é **inferência**, não comportamento asseverado. Se importa, precisa de teste. |

Manter estes três como "abertos" seria inventar dívida — o oposto do que um registro serve para
fazer.
