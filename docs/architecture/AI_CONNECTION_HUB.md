# AI Connection Hub — especificação (P7-SPEC-01)

**Status:** especificação · **Data:** 2026-09-09 · **Fase:** 7 · **Base:** `v0.6.0-context-engine`

Especificação apenas. Nenhum código de produção, nenhuma migration, nenhuma chamada a provedor.

**Padrão de evidência.** Este documento herda a régua da Fase 6: afirmação verificada por leitura de
documentação é rotulada como tal, e o que não foi confirmado é marcado **NÃO VERIFICADO** em vez de
arredondado. As datas importam — mecanismos de provedor mudam, e uma afirmação sobre a OpenAI em
setembro de 2026 não é uma afirmação permanente.

---

## 1. O requisito, reformulado

O modelo antigo era estreito:

```
usuário cola API key  →  VibeCode chama a API do provedor
```

Isso continua sendo **um** modo suportado. O requisito real é maior:

```
USUÁRIO → AI Connection Hub → conecta pelo mecanismo oficial de cada provedor
        → descobre capacidades → usa por fronteiras controladas
```

A frase que carrega o peso: **"pelo mecanismo oficial de cada um"**. Provedores diferentes oferecem
mecanismos diferentes, e forçar todos num modelo único de autenticação é como esta arquitetura
falha.

## 2. A regra que não se negocia

**O VibeCode nunca pede a senha da conta do usuário, e nunca aceita credencial que autentique um
plano de consumidor.**

A proibição é **por princípio, não por artefato**. Uma lista de artefatos proibidos (cookie, token de
navegador) é contornável pela primeira credencial com codificação nova — foi assim que um token de
CI quase entrou nesta spec. A regra é:

> Nenhuma credencial que autentique uma **assinatura de consumidor** de provedor de IA pode ser
> coletada, armazenada ou intermediada pelo VibeCode, **em qualquer codificação** — cookie, token de
> sessão, token OAuth de plano, token de setup para CI, ou o que venha depois.

Casos concretos hoje cobertos: senha; cookie de `chatgpt.com`, `claude.ai`, `gemini.google.com`,
`deepseek.com`; token de sessão extraído do navegador; automação de login não oficial; engenharia
reversa de sessão de consumidor; **e o token de `claude setup-token`** (§6).

Mecanismos permitidos — **lista fechada**:

| Mecanismo | O servidor executa? |
| --- | --- |
| `OAUTH_ACCOUNT` | sim, com token no Vault |
| `API_CREDENTIAL` | sim, com chave no Vault |
| `LOCAL_TOOL` | não — a ferramenta local executa |
| `LOCAL_BRIDGE` | não — a ferramenta local executa, orquestrada pelo Bridge |

`IDENTITY_ONLY` **não está nesta lista**: um mecanismo que não executa nada não é mecanismo de
conexão de IA. Fica nomeado na §4 para que ninguém o classifique como `OAUTH_ACCOUNT`.

**Um mecanismo novo entra só por ADR próprio**, que nomeie a URL da documentação do fornecedor e o
raio de alcance da credencial. Sem isso, "mecanismo oficial futuro" transformaria uma allowlist
fechada em aberta, que é o oposto do [ADR-011](../adr/ADR-011-deny-by-default-resource-access.md).

**Como uma credencial é recusada na prática.** Um campo de texto livre para `API_CREDENTIAL` aceita
qualquer coisa, inclusive um cookie de sessão colado. Cada adaptador declara a **forma** da
credencial que aceita (prefixo, formato) e valida **antes** de escrever no Vault. O controle
negativo do teste é uma string de cookie de sessão real, recusada.

## 3. O que a investigação mudou

Três fatos, verificados na documentação oficial, mudaram o desenho. Nenhum é opinião.

**A OpenAI não delega assinatura a terceiros.** A documentação do Codex não descreve mecanismo pelo
qual uma aplicação servidora de terceiro obtenha autorização para usar a assinatura ChatGPT do
usuário. Existe **Sign in with ChatGPT** — produto real, em beta desde agosto de 2026, com parceiros
nomeados — mas ele concede **identidade apenas**: nome, e-mail e foto. Não concede acesso a API nem
uso da assinatura para inferência.

**A Anthropic proíbe explicitamente o caminho tentador.** A página legal do Claude Code diz, por
escrito: *"Anthropic does not permit third-party developers to offer Claude.ai login into their own
applications, or to route requests through Free, Pro, or Max plan credentials on behalf of their
users. Moreover, developers may not collect, store, or intermediate Claude.ai credentials or session
tokens."* Isso encerra a questão do `setup-token`: não é decisão de produto, é proibição.

A mesma página valida o caminho que o [ADR-028](../adr/ADR-028-local-tool-credentials-stay-local.md)
escolheu: *"Nor does it prevent an end user from signing in to the unmodified Claude Code binary with
their own Claude subscription, including where a platform hosts Claude Code."* Orquestrar um binário
**não modificado** que o usuário autenticou é contemplado como permitido.

**O OAuth do Gemini não está confirmado para inferência.** O guia oficial existe, mas se descreve
como *"appropriate for a testing environment"*, demonstra **apenas `models.list`** — não há exemplo
de `generateContent` — e usa escopos `cloud-platform` + `generative-language.retriever`. Exige
projeto Google Cloud próprio, gcloud CLI e cadastro como Test User.

> **NÃO VERIFICADO:** que OAuth de usuário final cubra inferência (`generateContent`) no Gemini.
> Isso invalida o argumento original desta spec para a primeira integração — ver §14.

## 4. Modelo de domínio

Conceitos que **não colapsam**:

```
AiProvider ─┬─ AiConnection ─┬─ CapabilitySnapshot ─┬─ ModelCapability
            │   (mecanismo,  │   (DECLARED/          └─ ToolCapability
            │    estado,     │    DISCOVERED/
            │    dono)       │    VERIFIED)
            │                └─ Entitlement  (KNOWN | UNKNOWN)
            └─ ExecutionChannel
                 REMOTE_API · REMOTE_AUTHORIZED_ACCOUNT · LOCAL_BRIDGE · MANUAL_COPY_PASTE
```

Por que a separação importa:

| Conceito | Valor |
| --- | --- |
| Provider | OpenAI |
| Connection | credencial de API do usuário, no Vault |
| Capability | geração de código |
| Tool | Codex |
| Model | um modelo específico disponível àquela credencial |
| Execution channel | `REMOTE_API` — **ou** `LOCAL_BRIDGE`, com Codex CLI local autenticado |

O mesmo Codex, por dois canais, com donos de credencial diferentes. Um campo `model = "codex"` não
representa nada disso.

**`IDENTITY_ONLY` está fora do escopo de execução do Hub.** Sign in with ChatGPT é login, não
conexão de IA. Classificá-lo como `OAUTH_ACCOUNT` — definido como "o servidor executa com estes
tokens" — seria exatamente errado. Se o produto quiser login social, é outra feature, com outro ADR.

**Tipos de conexão já existentes no código.** O `main` tem **três** tipos com forma de conexão, e a
W1 precisa reconciliá-los antes de criar um quarto:

| Tipo | Escopo | Observação |
| --- | --- | --- |
| `provider.domain.ProviderAccount` | **usuário** | é a forma `API_CREDENTIAL` de `AiConnection` |
| `usage.domain.ProviderAccount` | — | record, `credentialRef` como `String` |
| `integration.domain.IntegrationConnection` | **projeto** | `IntegrationId` já tem `ANTHROPIC`, `OPENAI`, `GOOGLE` |

O terceiro é o perigoso: **escopo de projeto para os mesmos provedores**, modelo de posse conflitante
com o de `AiConnection` (usuário). Construir `AiConnection` sem reconciliar deixaria duas tabelas de
conexão para os mesmos provedores com posses diferentes — a montagem clássica de vazamento de posse.
Decisão em aberto na W1: unificar, ou declarar `integration` como contrato morto e removê-lo.

## 5. Matriz de provedores

**SUPORTADO** = documentado oficialmente · **PROIBIDO** = o fornecedor veda · **NÃO SUPORTADO** = a
documentação não descreve · **NÃO VERIFICADO** = não confirmado.
Verificado em 2026-09-09; reverificar antes de implementar.

### OpenAI / ChatGPT

| Dimensão | Situação |
| --- | --- |
| Delegação de assinatura a terceiros | **NÃO SUPORTADO** |
| Sign in with ChatGPT | **SUPORTADO**, mas `IDENTITY_ONLY` — nome, e-mail, foto. Sem API, sem inferência |
| Credencial de API | **SUPORTADO** |
| CLI/ferramenta local | **SUPORTADO** — Codex CLI, extensão de IDE, app desktop |
| Codex Access Tokens | **SUPORTADO**, porém **restrito a workspace ChatGPT Enterprise** e para automação do próprio usuário |
| Assinatura ↔ API | **separadas** — Plus/Pro não paga chamadas de API |
| Caminho | `API_CREDENTIAL` + `LOCAL_TOOL`/`LOCAL_BRIDGE` (Codex) |

### Google / Gemini

| Dimensão | Situação |
| --- | --- |
| OAuth de usuário final | **SUPORTADO para autenticação**; cobertura de `generateContent` **NÃO VERIFICADA** (guia é de ambiente de teste, só demonstra `models.list`) |
| Pré-requisito do OAuth | **o usuário precisa ter projeto Google Cloud próprio, com billing** |
| Credencial de API | **SUPORTADO** — inclui camada gratuita |
| Vertex AI | **SUPORTADO** — ADC, service account |
| CLI oficial | **SUPORTADO** — Gemini CLI |
| Assinatura ↔ API | **separadas, documentado**: benefícios de plano valem só na UI do AI Studio; uso direto da API é cobrado à parte |
| Caminho | `API_CREDENTIAL` agora; `OAUTH_ACCOUNT` condicionado a verificação |

### Anthropic / Claude

| Dimensão | Situação |
| --- | --- |
| Login Claude.ai em app de terceiro | **PROIBIDO** pelos termos |
| Rotear requisições por credencial de plano Free/Pro/Max | **PROIBIDO** |
| Coletar/armazenar/intermediar credencial ou token de sessão Claude.ai | **PROIBIDO** |
| `claude setup-token` / `CLAUDE_CODE_OAUTH_TOKEN` | **PROIBIDO** para este uso — é credencial de assinatura |
| Credencial de API | **SUPORTADO** — `Authorization: Bearer` (o `x-api-key` é fallback legado) |
| Binário Claude Code não modificado, usuário autenticado por conta própria | **explicitamente contemplado como permitido** |
| Caminho | `API_CREDENTIAL` + `LOCAL_TOOL`/`LOCAL_BRIDGE` |

### DeepSeek

| Dimensão | Situação |
| --- | --- |
| OAuth | **NÃO SUPORTADO** |
| Credencial de API | **SUPORTADO** — `Authorization: Bearer` |
| CLI oficial | **SIM** — DeepSeek Harness (`dsh`), em developer preview |
| Endpoint compatível com Anthropic | **SUPORTADO** — consequência em §12 |
| Caminho | `API_CREDENTIAL` |

### Ferramentas locais

| Ferramenta | Autenticação | Credencial chega ao servidor? |
| --- | --- | --- |
| Codex CLI | conta ChatGPT ou API key | **Não** |
| Claude Code | conta Claude, API key, ou `apiKeyHelper` | **Não** |
| Gemini CLI | conta Google ou API key | **Não** |

## 6. A UI diz o mecanismo

```
Google / Gemini      CONECTADO          via chave de API
OpenAI               CHAVE NECESSÁRIA   a OpenAI não oferece conexão de conta
                                        para aplicações de terceiros
Claude               CHAVE NECESSÁRIA   os termos da Anthropic vedam login de
                                        conta em apps de terceiros
Codex                LOCAL              detectado · requer Bridge pareado
DeepSeek             CHAVE NECESSÁRIA
```

As linhas de OpenAI e Claude **explicam a ausência** em vez de oferecer um botão que não pode
existir. Quatro botões idênticos mentiriam sobre quatro mecanismos diferentes.

## 7. Máquina de estados

```
DISCONNECTED
  → CONNECTING
      → AUTHORIZATION_PENDING          (só mecanismos com autorização fora-de-banda)
           → CONNECTED_UNVERIFIED
           → EXPIRED                   (intent venceu)
           → CANCELLED                 (usuário desistiu / abandonou)
      → CONNECTED_UNVERIFIED           (API_CREDENTIAL vai direto)

CONNECTED_UNVERIFIED → CONNECTED       (verificação mínima passou)
                     → ERROR           (falha transitória: provedor fora do ar, timeout)
                     → INVALID

CONNECTED → REAUTH_REQUIRED → CONNECTED    (re-auth bem-sucedida)
          → DISABLED        → CONNECTED    (reativada)
          → INVALID         → CONNECTED    (credencial substituída)
          → REVOKED                        (terminal — recriar é o caminho)
          → ERROR           → CONNECTED    (falha transitória resolvida)
```

`EXPIRED` e `CANCELLED` existem porque **um usuário que fecha a aba do provedor é o caso comum**, e
sem eles a linha ficaria presa em `AUTHORIZATION_PENDING` para sempre. `REAUTH_REQUIRED`, `DISABLED`,
`INVALID` e `ERROR` **não são sumidouros** — o produto precisa de volta de cada um.

**Desconectar remove a linha**, não é estado — e `ai_connection_authorizations` cai junto por
`ON DELETE CASCADE`. Uma credencial cuja **forma** é recusada na entrada não cria linha nenhuma: a
validação roda antes da escrita no Vault, então não há o que persistir.

`CONNECTED_UNVERIFIED` segue o princípio do
[ADR-021](../adr/ADR-021-honest-credential-status-and-no-partial-disclosure.md): guardar credencial
não é saber que funciona.

**`AUTHORIZATION_PENDING` pertence a mecanismos com autorização fora-de-banda** — hoje
`OAUTH_ACCOUNT` e `LOCAL_BRIDGE` (parear a máquina é autorização fora-de-banda). Continua impossível
para `API_CREDENTIAL`, que é o ponto do
[ADR-026](../adr/ADR-026-provider-connection-capability-tool.md). A versão anterior desta spec dizia
"só OAuth", o que teria quebrado quando o Bridge chegasse.

### Emenda ao ADR-021

O ADR-021 decidiu *"não existe CONNECTED, não existe VALID"*, e a razão era de **segurança**:
validar credencial contra o provedor transforma o formulário num oráculo de validade de chaves para
quem tiver sessão roubada.

Esta spec reintroduz `CONNECTED` e verificação. **Isso é uma reversão e precisa ser paga**, não
citada pela metade:

- a verificação é **rate-limited por usuário e por conexão**;
- exige sessão autenticada e é auditada;
- o oráculo continua existindo em grau reduzido, e isso vira entrada em **`docs/PHASE7_DEBT.md`**,
  criado na W2 — dívida sem lugar nomeado tem a mesma forma do teste sem mecanismo que esta spec
  critica em outros pontos, e não vale mais do que ele.

O ADR-021 é emendado por escrito na W2, não implicitamente por esta spec.

## 8. Descoberta de capacidades

```
Connection → Capability Discovery → CapabilitySnapshot
```

`connectionId`, `provider`, `discoveredAt`, `source`, `capabilities`, `models`, `tools`,
`verificationLevel`.

| Nível | Significa |
| --- | --- |
| `DECLARED` | o registro do VibeCode diz que este provedor geralmente tem |
| `DISCOVERED` | o provedor respondeu, para **esta** conexão, que tem |
| `VERIFIED` | uma operação mínima real confirmou |

**Nada é apresentado como disponível só por ser `DECLARED`.**

**E `DISCOVERED` não autoriza nada.** É auto-declaração do provedor ou do Bridge — exatamente o que
um adaptador comprometido ou um Bridge malicioso controla. Portanto:

> Reivindicação de capacidade é **entrada consultiva** para UI e roteamento. Toda decisão de
> segurança na Provider Execution Boundary — orçamento, allowlist, canal — é tomada a partir de
> estado do servidor, **nunca de campo de snapshot**.

Divergência detectada (o provedor declarou o que não tem): **snapshot invalidado e a capacidade
marcada como indisponível**, com linha de auditoria. A **conexão não muda de estado** — a credencial
dela não tem nada de errado, e mandá-la para `INVALID` faria uma resposta instável do provedor
derrubar a conexão e empurrar o usuário para o único reparo que a §7 oferece a partir de `INVALID`:
substituir uma credencial que nunca esteve errada. A conexão só se move se a **credencial** for
recusada.

**Descoberta é chamada ao provedor**, com a credencial e a cota do usuário, e **sem nenhum dado do
projeto**. Se é automática na conexão ou ato separado do usuário: decisão de produto (§15).

**Modelos:** o catálogo do provedor é metadado; os modelos disponíveis **a esta conexão** são outra
coisa. Sem listagem oficial, o snapshot registra `modelDiscovery: UNSUPPORTED` — não uma lista
adivinhada.

**Ferramentas:** `ToolCapability` é tipo separado de `ModelCapability`.

## 9. Entitlement e cobrança

`Entitlement`: `KNOWN` | `UNKNOWN`. **`UNKNOWN` é o padrão e não é falha.** Não se raspa página de
conta.

Origem de cobrança: `VIBECODE_CREDIT` · `PROVIDER_ACCOUNT` · `PROVIDER_API_BILLING` ·
**`END_USER_CLOUD_PROJECT`** · `LOCAL_TOOL_SUBSCRIPTION` · `UNKNOWN`.

`END_USER_CLOUD_PROJECT` existe porque o OAuth do Gemini cobra no **projeto Google Cloud do próprio
usuário** (`x-goog-user-project`, com `serviceusage.services.use` e billing vinculado). Sem essa
origem, o modelo não representaria o caso.

A **AI Battery** mostra linhas, nunca um saldo universal falso — provedores heterogêneos não
compartilham unidade.

## 10. Segredos e Vault

| Conexão | Credencial vive em |
| --- | --- |
| `API_CREDENTIAL` | Vault (caminho da Fase 5) |
| `OAUTH_ACCOUNT` | Vault |
| `LOCAL_TOOL` / `LOCAL_BRIDGE` | **máquina do usuário** |

**Inventário completo do que é segredo** — a versão anterior desta spec listava só dois:

access token · refresh token · chave de API · **authorization code** · **PKCE verifier** · **nonce**
· **state token** · **ID token** (carrega PII) · **o client secret OAuth do próprio VibeCode** ·
segredo do Bridge.

**O state token é credencial portadora, e por isso está nesta lista.** Ele é o que resolve um intent
de autorização; quem o lê pode completar o fluxo pendente de outra pessoa e vincular a conta de
provedor que quiser. Guardar seu valor em coluna comum o deixaria alcançável por dump, réplica,
backup ou log de query. **Guarda-se apenas o hash**, como se faz com token de sessão — a versão
anterior desta spec o deixou ao lado de dois campos `_ref` sob um comentário que só cobria os dois,
contradizendo a própria regra de "nenhum material secreto em coluna".

**O ID token não é guardado.** O que o sistema precisa é a claim `sub`, extraída e persistida como
referência opaca; reter o token inteiro seria manter PII sem consumidor declarado.

Três disposições, não uma: a maioria vai **para o Vault**, com `withSecret` de escopo estreito e sem
`getSecret`; o state token fica **como hash em coluna**; e o ID token **não é retido**. Dizer que
todos ficam atrás do Vault seria falso para dois itens da própria lista
([ADR-018](../adr/ADR-018-vault-has-no-read-api.md)).

Não é segredo: id da conexão, provedor, mecanismo, estado, timestamps, nomes de escopos concedidos,
referência opaca à conta no provedor. **O e-mail do provedor não é identidade de segurança do
VibeCode.**

**Escopos: menor privilégio.** Nunca pedir dados de usuário não relacionados porque o provedor os
oferece.

**Refresh iniciado pelo servidor é questão de contrato do Vault sem dono hoje.** O ADR-018/019 foram
feitos para credencial submetida pelo usuário; escrever nova versão ativa sob concorrência, a partir
de um refresh automático, precisa ser desenhado na SPEC-02.

## 11. Log e auditoria — a garantia da Fase 6 não sobrevive de graça

A Fase 6 mediu logs limpos, e o [D-09](../PHASE6_DEBT.md) registra o que isso significava: **o motor
é silencioso**, não que ele logue com cuidado. Aquilo era consequência de não existir cliente HTTP
nenhum.

A W6 introduz exatamente o componente cujo logging padrão emite URL, headers e mensagens de exceção —
e **authorization code e token viajam em URL**. Portanto:

- a superfície de log exige **redação positiva**, não silêncio;
- o controle negativo tem que provar que um header `Authorization` logado é pego.

**Auditoria.** `AuditEvent.metadata` é hoje texto livre e o redator do
[ADR-013](../adr/ADR-013-redaction-before-persistence.md) **não está ligado ao módulo de audit**. A
trilha é append-only ([ADR-015](../adr/ADR-015-append-only-audit-trail.md)): um token escrito ali é
indelével. Portanto:

- metadata de eventos de conexão/execução é **conjunto fechado e tipado de chaves**, nunca payload
  livre do provedor;
- o redator é aplicado no caminho de escrita de audit.

## 12. Bridge

```
Bridge (máquina do usuário)  ──outbound──▶  VibeCode API
       │
       └──▶ Codex CLI · Claude Code · Gemini CLI · git · maven · npm · docker
```

**O Bridge disca para fora.** O servidor **nunca** abre conexão para endereço fornecido por usuário
ou por registro de pareamento — isso seria primitiva de SSRF/rede interna. A versão anterior desta
spec desenhava a seta na direção perigosa.

**`terminal` foi removido da lista.** Terminal numa allowlist é a negação de uma allowlist.

E allowlist de **nomes de comando não é fronteira**: `npm install` roda scripts de postinstall,
`git -c core.sshCommand=…` executa, `docker run -v /:/host` monta o host, `mvn` roda plugins do POM.
Cada um é primitiva de execução arbitrária. Portanto:

> O Bridge expõe **operações tipadas e parametrizadas** que ele mesmo implementa — não nomes de
> comando com argumentos livres.

**E tipar o verbo não fecha o caso — é preciso restringir o operando.** Um
`installDependencies(pacote)` perfeitamente tipado continua sendo `npm install <pacote escolhido
pelo atacante>`, e o postinstall roda do mesmo jeito. Um `runContainer(imagem)` tipado é
`docker run <imagem arbitrária>`. Portanto:

> Os parâmetros vêm de **estado do servidor sobre o projeto** — instalar só o que o manifesto do
> projeto já declara, rodar só imagens que o projeto fixa — nunca de entrada livre do usuário ou do
> modelo.

**Quando há confirmação:** cada operação tipada **declara** se exige confirmação local, e o padrão
para operação que não declarou é **exigir**. A versão anterior dizia "operações que mutam pedem
confirmação" sem dizer quem classifica; deixar isso implícito é como `npm install` acabaria do lado
que não pede.

**Confirmação humana acontece no dispositivo local**, não na sessão web. Se fosse na web, quem
roubasse a sessão web seria dono da máquina do desenvolvedor, e a credencial separada do Bridge não
teria comprado nada.

**O Bridge invoca a ferramenta com a configuração do próprio usuário.** Não injeta credencial, não
sobrescreve endpoint (`ANTHROPIC_BASE_URL` e equivalentes), não troca `apiKeyHelper` e não
acrescenta flags de autenticação. Essa regra é o que mantém aplicável a permissão da Anthropic
citada na §3, que é **condicionada ao binário não modificado** — orquestrar um binário cujo endpoint
ou credencial o orquestrador trocou é discutivelmente outra coisa.

**Autenticação do Bridge é mecanismo próprio** — registro de dispositivo, credencial de sessão curta,
rotação, revogação. Nunca credencial de provedor de IA.

**Disponibilidade declarada não é autorização.** "Codex instalado" descreve a máquina, não o
permitido.

**Saída de modelo nunca autoriza terminal.**

**Identidade de ferramenta não implica identidade de provedor.** A DeepSeek documenta endpoint
compatível com Anthropic: um `LOCAL_TOOL` rotulado "Claude Code" pode estar executando contra a
DeepSeek. O snapshot registra a ferramenta, e **não infere o provedor a partir dela**.

## 13. Fronteira de execução

```
REMOTE_API ─┐
REMOTE_AUTHORIZED_ACCOUNT ─┼─▶ Provider Execution Boundary ─▶ ExecutionResult normalizado
LOCAL_BRIDGE ─┘               (autorização · orçamento · idempotência · auditoria)
```

**Uma porta só**, três canais atrás dela, divergindo no transporte e não na autorização.
`MANUAL_COPY_PASTE` é representado no modelo mas não atravessa a fronteira — nada sai do servidor.

Requisição referencia **conexão e capacidade, nunca credencial crua**. O payload é **derivado no
servidor** do `ContextPack` persistido mais a saída aprovada do Prompt Builder; o frontend não é
autoritativo — senão a política de contexto da Fase 6 vira decoração.

## 14. Primeira integração — recomendação revista

A recomendação anterior era Gemini porque *"é o único provedor com os dois mecanismos oficiais"*.
**Esse argumento caiu**: o OAuth do Gemini não está confirmado para inferência (§3).

Além disso, a tabela de pontuação anterior era infalsificável — a margem inteira entre Gemini (33) e
OpenAI (32) era um ponto numa coluna subjetiva atribuída por mim. Foi removida.

**Recomendação:**

**W9 — Gemini `API_CREDENTIAL`** (a wave é a W9 no [plano](../PHASE7_PLAN.md); a W8 é o red team
contra o provedor falso, que a antecede de propósito). O argumento agora se sustenta sozinho, sem
depender do OAuth:

- chave de API documentada, sem callback, sem refresh, sem account mix-up — a menor superfície nova
  possível ao lado de uma fronteira de execução também estreando;
- **listagem oficial de modelos** dá dado `DISCOVERED` real por conexão, exercitando a §8;
- existe **camada gratuita**, o que reduz o custo de exercitar a transição falso→real mais de uma
  vez. **NÃO VERIFICADO:** que os limites de taxa, a cota por chave e a disponibilidade regional
  dessa camada a tornem adequada a CI. Confirmar antes da W9.

Sejamos exatos sobre a força deste argumento: **os dois primeiros itens não são exclusivos do
Gemini** — DeepSeek e Anthropic também têm listagem de modelos e chave documentada. O único
discriminador real é a camada gratuita, e ela está marcada como não verificada. É uma escolha
razoável, não uma escolha forçada.

E ela tem um custo que precisa ser dito: **E2E real em CI significa chave de provedor viva nos
segredos de CI** — superfície de exposição nova, introduzida pelo próprio argumento de escolha. A W9
precisa tratá-la como tal.

**O segundo mecanismo é exercitado contra o provedor FALSO, não contra um real.** Esta é a mudança
mais importante. O teste da separação conexão/execução é sobre **a separação**, não sobre o
provedor. Promover o adaptador OAuth falso da W3 a canal de primeira classe através da fronteira na
W7 prova a mesma propriedade com **zero** dependência de o OAuth do Google cobrir inferência, zero
de verificação de app, e zero de o usuário ter projeto Cloud faturado.

**OAuth real vira wave condicional**, aberta só quando algum provedor tiver cobertura de inferência
por OAuth de usuário final confirmada. Riscos de cronograma que a versão anterior não nomeava:
verificação de app pelo Google, revisão de escopo sensível, e possivelmente a avaliação de segurança
**CASA** para escopos restritos acessados por servidor de terceiro. Se `generativelanguage` é
sensível ou restrito: **NÃO VERIFICADO**.

**Não construir quatro provedores em paralelo.**

## 15. Posse, consentimento e privacidade

**Toda conexão tem dono explícito.** Recurso de outro dono responde **404, não 403** — herdado da
Fase 6.

**Autorização em tempo de execução é um trio, não um par:**

> quem chama **é** dono da conexão **e** pode ler o projeto **e** o dono da conexão **é** o dono do
> projeto.

O [ADR-009](../adr/ADR-009-project-ownership-authorization.md) construiu `canRead/canWrite/canManage`
deliberadamente para que associação futura possa conceder leitura sem escrita. No dia em que
membership de projeto existir, um leitor de projeto poderia acionar a conexão do dono se o trio não
estiver escrito **agora**.

**Superfície de auditoria também é superfície de posse.** A rota de leitura de audit é escopada por
projeto. Eventos de execução são inerentemente de projeto e carregam id de conexão — logo, com
membership, vazariam identificador de conexão e provedor a um leitor que não é dono da conexão. Id de
conexão em linha de audit fica sujeito ao **mesmo** gate de posse da conexão.

**Snapshots** não têm coluna de dono; a posse é derivada por join, o que é aceitável **apenas** com
gate único, e isso precisa estar escrito.

**Múltiplas conexões por provedor: sim, tecnicamente.** UI inicial pode limitar.

**Dois momentos de consentimento:**

```
conectar conta   →  o VibeCode pode usar capacidades   (não envia nada do projeto)
enviar contexto  →  dados deste projeto saem
```

Conectar não dispara chamada com dado de projeto. **Verificação usa a menor operação oficial segura,
com entrada que é constante da aplicação — nunca texto do usuário ou do projeto.** Execução inicial é
iniciada pelo usuário; sem autopilot.

**Copiar e colar continua de primeira classe.** O VibeCode funciona inteiro sem nenhuma conexão. Se
essa frase deixar de valer, o produto mudou de natureza.

## 16. Registry, SSRF e redirect

**O Provider Registry é constante de compilação.** Nenhum endpoint é lido de banco ou de
configuração influenciável por usuário — senão o próprio Registry seria alvo de SSRF. A versão
anterior dizia que ele "guarda endpoints conhecidos" e que "adaptadores são donos das próprias URLs",
o que era contraditório.

Usuário **não** fornece base URL para provedor de primeira linha. Provedor customizado é modelo de
ameaça separado, de fase futura.

**`redirect_uri` é constante da aplicação**, nunca derivada da requisição — o Google exige
correspondência exata com a URI registrada no console. Consequência de implantação que a versão
anterior omitia: **uma instância self-hosted ou multi-tenant não pode cunhar callback próprio** sem
registrar cada um.

**Credencial não é repassada através de redirect não confiável.**

**Autorização OAuth é ligada a um registro de intent de uso único**, não à sessão:

`id` · dono · conexão alvo · hash do state token · PKCE verifier · nonce · redirect · expiração ·
`consumed_at`

Consumo é atômico — é o que faz o intent ser de uso único, e o que derruba replay de callback antigo
**mesmo vindo da mesma sessão**.

**O endpoint de callback exige sessão autenticada cujo usuário seja o `owner_user_id` do intent**, e
a correspondência do state é a segunda verificação, não a única. Isso importa porque callback de
provedor normalmente chega sem sessão: sem essa exigência, o state seria sozinho o que autoriza a
conclusão do fluxo — e um state vazado bastaria para vincular uma conta de provedor à conexão de
outra pessoa. A versão anterior amarrava à sessão sem dizer que o callback a exigia, o que deixava
a porta aberta pelo campo que ela mesma não classificara.

`ai_connection_authorizations.connection_id` é **`ON DELETE CASCADE`**: é isso, e não uma
propriedade emergente, que faz um callback para conexão deletada mid-flow não ter onde aterrissar.

**Geração e hash do state token:** valor de CSPRNG com no mínimo 128 bits de entropia, guardado como
**SHA-256 simples** — mesmo tratamento de um token de sessão. Hash só protege token de alta entropia,
e deixar o algoritmo sem nome convida a duas escolhas erradas: um KDF de senha (ferramenta e custo
errados para um valor aleatório) ou um digest truncado.

## 17. D-16, reformulado como allowlist

O [D-16](../PHASE6_DEBT.md) da Fase 6 dizia que a ausência de cliente HTTP de saída era verificada
por inspeção, não por regra de build.

A versão anterior desta spec o reformulou como **blocklist sobre `application`, `web` e `domain`** —
e o repositório tem **quatro** camadas por módulo. `com.vibecode.context.infrastructure` poderia
importar `WebClient` e passar na regra. Isso invertia o
[ADR-011](../adr/ADR-011-deny-by-default-resource-access.md). A regra correta é **allowlist**:

> Nenhuma classe **fora** de `..provider.infrastructure..` (pacote exato a fixar na SPEC-02) pode
> depender de cliente HTTP, SDK de provedor, transporte de Bridge, ou tipo de execução de processo.

Construtos cobertos — a lista da versão anterior perdia metade do que o próprio D-16 nomeava:

`WebClient` · `RestClient` · `RestTemplate` · `HttpClient` · OkHttp · Feign · `java.net.http` ·
`HttpURLConnection` · `new URL(` · `URI.create` · SDK de provedor · transporte do Bridge ·
**`ProcessBuilder`** · **`Runtime.exec`**

**A afirmação é limitada de propósito:** a execução não escapa **por nenhuma via estaticamente
resolvível**. O D-01 da Fase 6 estabeleceu que ArchUnit só enxerga arestas de bytecode e que
`getBean(…)` + `Class.forName(…)` derrota a regra análoga do Vault — com a instrução explícita de
**não afirmar** inalcançabilidade "por qualquer rota". A rota reflexiva vira entrada em `docs/PHASE7_DEBT.md`, dívida
própria na Fase 7.

**E regra de pacote não é a fronteira.** ArchUnit restringe *quem pode importar um cliente*. Não
impede um adaptador **dentro** do pacote permitido de chamar o provedor sem passar por orçamento,
idempotência e auditoria. Isso é imposto por desenho da fronteira e por teste de comportamento — a
regra de pacote impõe a **camada**, não a fronteira.

Testes negativos obrigatórios: cada regra vista falhando com o defeito reintroduzido.

## 18. Decisões de produto pendentes

1. **Múltiplas contas por provedor** na primeira UI — o domínio suporta; a tela mostra?
2. **Entitlement** é `UNKNOWN` na maioria dos casos — mostrar "desconhecido" ou omitir?
3. **Descoberta de capacidades** é automática ao conectar, ou ato separado? (É chamada ao provedor
   com a cota do usuário.)
4. **`integration.domain.IntegrationConnection`** — unificar com `AiConnection` ou remover?
5. **Bridge** — confirmar sub-fase própria (recomendado).

O `claude setup-token` **saiu** desta lista: não é decisão, é proibição (§3).

## 19. O que esta spec não decide

Para a P7-SPEC-02: fronteira de execução em detalhe, estados de execução, idempotência, gate de
orçamento, refresh de token sob concorrência, política de redirect literal, pacote exato da
allowlist, e as regras ArchUnit literais.

---

Ver [ADR-025](../adr/ADR-025-only-official-connection-mechanisms.md),
[ADR-026](../adr/ADR-026-provider-connection-capability-tool.md),
[ADR-027](../adr/ADR-027-connecting-is-not-executing.md),
[ADR-028](../adr/ADR-028-local-tool-credentials-stay-local.md),
[plano da Fase 7](../PHASE7_PLAN.md).
