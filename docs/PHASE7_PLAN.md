# Plano da Fase 7 — AI Connection Hub e execução por provedor

**Status:** plano · **Data:** 2026-09-09 · **Base:** `v0.6.0-context-engine` (`761ad82`)

Plano, não autorização. Nenhuma wave está autorizada a executar por este documento.

Arquitetura: [AI_CONNECTION_HUB.md](architecture/AI_CONNECTION_HUB.md).

---

## Waves

| Wave | Escopo | Dono único de |
| --- | --- | --- |
| **P7-SPEC-01** | Arquitetura do Connection Hub — **este documento e o Hub** | — |
| **P7-SPEC-02** | Execution Boundary, estados de execução, idempotência, orçamento, refresh sob concorrência, regras ArchUnit literais | — |
| **P7-W1** | Domínio de conexão + persistência (**V10**) + reconciliar `IntegrationConnection` | migration, domínio de conexão |
| **P7-W2** | Segurança da conexão + modelo de token/credencial no Vault + **emenda ao ADR-021** | integração com Vault, incl. caminho de escrita de refresh |
| **P7-W3** | API de conexão + adaptadores **falsos** de conexão (incl. OAuth falso) | — |
| **P7-W4** | Descoberta de capacidades | — |
| **P7-W5** | Domínio de execução + idempotência + orçamento | domínio de execução |
| **P7-W6** | Execution Boundary + enforcement do D-16 | fronteira |
| **P7-W7** | Execução falsa E2E — **incluindo o canal OAuth falso** através da fronteira | — |
| **P7-W8** | **Red team contra o provedor falso** — porta de entrada da W9 | — |
| **P7-W9** | Primeiro mecanismo real: **Gemini `API_CREDENTIAL`** | — |
| **P7-W10** | Primeira execução automatizada real | — |
| **P7-W11** | Frontend do AI Connections | — |
| **P7-W12** | Red team completo, com provedor real | — |
| **P7-W13** | Certificação da Fase 7 | — |
| **P7-Wc** | **Condicional:** OAuth real — só abre se algum provedor tiver cobertura de inferência por OAuth de usuário final **confirmada** | OAuth/segurança |
| **P7-B1…** | **Bridge — sub-fase própria, posterior à W10** | fronteira do Bridge |

### Três mudanças em relação ao plano do briefing, e a razão de cada uma

**O red team foi partido em dois, e a primeira metade vem antes da primeira execução real.** O
portão de provedor real exige `red team PASS`; um plano que colocasse todo o red team depois da
primeira execução real contradiria o próprio portão. A W8 ataca o provedor falso e é pré-requisito
da W9; a W12 refaz o ataque com provedor real.

**O OAuth real virou wave condicional.** A versão anterior deste plano o colocava como W10, com o
argumento de que o Gemini tem os dois mecanismos oficiais. Esse argumento caiu: a cobertura de
inferência por OAuth de usuário final **não está confirmada** em nenhum provedor da matriz (§3 do
Hub). Agendar uma wave que depende disso seria agendar uma wave que pode não poder existir.

**O segundo mecanismo é exercitado contra o provedor falso, na W7.** É onde a separação
conexão/execução é realmente testada, e o teste é sobre a separação — não sobre o provedor. Isso
remove a dependência de verificação de app do Google, de revisão de escopo sensível, da avaliação
CASA, e de o usuário ter projeto Cloud faturado.

## Grafo de dependências

```
SPEC-01 ─┬─► SPEC-02 ──────────────────┐
         │                             │
         └─► W1 ─► W2 ─┬─► W3 ─► W4 ───┤
                       │               │
                       └─► W5 ─► W6 ◄──┘
                                 │
                                 ▼
                                W7 ─► W8 ─► W9 ─► W10 ─► W11 ─► W12 ─► W13
                                                    │
                                    Bridge (B1…) ◄──┘
                                    Wc (condicional) ◄── confirmação externa
```

**Caminho crítico:** SPEC-02 → W1 → W2 → W5 → W6 → W7 → **W8** → W9 → W10 → W11 → W12 → W13.

O caminho crítico agora vai até a certificação, e não para na primeira execução: W11 → W12 → W13 são
estritamente seriais. A versão anterior desenhava o caminho terminando na W9, o que escondia que
qualquer atraso ali empurra a certificação inteira.

**Paralelizável com segurança:**

| Podem correr juntas | Por quê |
| --- | --- |
| W3 e W4 | ambas dependem de W2, não uma da outra |
| W5 e W3/W4 | domínio de execução não toca no de conexão |
| SPEC-02 e W1 | especificação e domínio de conexão não colidem |
| Wc e qualquer coisa após W10 | é condicional e isolada |

**Não paralelizar:** W1 com nada que escreva schema; W2 com qualquer coisa que toque no Vault; W6
com W5; W11 com W12.

### Regras de posse

Uma dona por área, com revisor independente: migration · domínio de conexão · domínio de execução ·
**integração com Vault** · OAuth/segurança · fronteira do Bridge.

**Colisão resolvida:** refresh e rotação de token são caminho de **escrita no Vault** e pertencem à
dona da integração com Vault (W2), não à de OAuth (Wc) — mesmo sendo necessários só no OAuth. Sem
isso, a Wc teria que modificar a área da W2. O contrato de escrever nova versão ativa sob
concorrência, a partir de refresh automático, é desenhado na SPEC-02: os
[ADR-018](adr/ADR-018-vault-has-no-read-api.md)/[ADR-019](adr/ADR-019-active-version-pointer-for-rotation.md)
foram feitos para credencial submetida por usuário, não para escrita iniciada pelo servidor.

## Plano da V10

**V1–V9 são imutáveis.** A próxima migration é a **V10**, criada na W1 e em lugar nenhum antes.

```
ai_connections
  id, owner_user_id, provider_id, mechanism, state,
  provider_account_ref (opaco, não é e-mail), display_label,
  created_at, updated_at, last_verified_at, disabled_at

ai_connection_authorizations        -- intent de uso único; SEM ele o OAuth exigiria V11
  id, owner_user_id, provider_id,
  connection_id ON DELETE CASCADE,                -- callback de conexão deletada não aterrissa
  state_token_hash,                               -- HASH, nunca o valor: é credencial portadora
  pkce_verifier_ref, nonce_ref,                   -- refs ao Vault, não material
  redirect_uri, created_at, expires_at, consumed_at

ai_connection_secrets               -- ponteiros, nunca material
  connection_id, secret_reference_id,
  kind (API_KEY | ACCESS_TOKEN | REFRESH_TOKEN),  -- ID token não é guardado: extrai-se `sub`
  expires_at, scopes (nomes, não segredo)

ai_capability_snapshots
  id, connection_id, discovered_at, source, verification_level

ai_capability_entries
  snapshot_id, kind (CAPABILITY | MODEL | TOOL), identifier, available, detail
```

**`ai_connection_authorizations` é a correção mais importante deste plano.** A versão anterior não a
tinha, e o efeito era concreto: a W1 congelaria um schema onde o OAuth da Wc teria que (a) criar uma
V11 — o retrofit que a ordenação existia para evitar — ou (b) pendurar estado de autorização na
linha de `ai_connections`, que é **exatamente o desenho que faz o account mix-up funcionar** (estado
ligado a uma conexão em vez de a um intent de uso único) e que cria uma conexão meio-criada em
`AUTHORIZATION_PENDING` sem dono definido.

Deferir o OAuth é seguro para o **domínio** e não era para o **schema**. A tabela entra na V10 mesmo
que a Wc nunca abra.

Propriedades obrigatórias desde a V10:

- **nenhum material secreto em coluna** — só referência ao Vault;
- **posse explícita** em `owner_user_id`, toda leitura escopada por ela, incluindo as tabelas de
  snapshot, cuja posse é derivada por join e portanto exige gate único;
- **`consumed_at` marcado atomicamente** — é o que torna o intent de uso único;
- **snapshot é append-only** — sobrescrever apagaria a evidência do que era verdade quando uma
  execução aconteceu.

Se a generalização de `ProviderAccount` exigir migração de dados, ela é da V10 e preserva as linhas
existentes, como `ContextPackMigrationTest` já faz para V8→V9.

## Plano de testes de segurança

Cada item precisa de **controle negativo**: visto falhando com o defeito reintroduzido.

**Posse e autorização**

- IDOR Alice/Bob em ler, usar, verificar, atualizar, desconectar, inspecionar snapshot
- recurso de outro dono responde **404, não 403**
- **trio de autorização**: quem chama é dono da conexão **e** pode ler o projeto **e** o dono da
  conexão é o dono do projeto — testado com membership de projeto simulada
- **id de conexão em linha de audit** não é legível por quem não é dono da conexão
- conexão sem permissão de execução não executa; execução sem `ContextPack` é recusada

**OAuth** (contra o adaptador falso, desde a W3)

- replay de `state`, **inclusive replay da mesma sessão** — o intent é de uso único
- account mix-up: Alice inicia, navegador de Bob completa
- duas abas: o callback da aba B não completa o intent da aba A
- callback para conexão deletada mid-flow (garantido por `ON DELETE CASCADE`)
- **callback sem sessão autenticada é recusado**, mesmo com state válido
- callback com sessão de outro usuário é recusado
- `state` ausente, inválido, de outra sessão, expirado
- token expirado; refresh revogado; redução de escopo detectada
- `redirect_uri` divergente recusada

**Credencial — oito superfícies**, não quatro. A Fase 6 certificou oito
([PHASE6_CERTIFICATION.md](PHASE6_CERTIFICATION.md)) e reduzir agora seria regressão de rigor:

banco (varredura de schema inteiro) · resposta HTTP do compile · GET · list · **caminhos de
rejeição** · audit · logs · payload canônico

- vazamento zero de: chave de API, access token, refresh token, **authorization code**, **PKCE
  verifier**, **nonce**, **state token**, **client secret do VibeCode**
- **o valor do state token não aparece em coluna nenhuma** — só o hash
- **cookie de sessão de provedor recusado**, com uma string de cookie real como controle negativo
- token de assinatura (`setup-token`) recusado pela validação de forma
- senha nunca aceita como mecanismo
- **um header `Authorization` logado é pego pela redação** — a Fase 6 media silêncio; a W6 introduz
  um cliente HTTP e silêncio deixa de ser evidência (D-09)
- metadata de audit é conjunto fechado de chaves; payload livre de provedor é recusado

**Rede e limites**

- URL arbitrária de provedor recusada (SSRF)
- Registry não é lido de banco nem de configuração influenciável
- servidor nunca disca para endereço de dispositivo fornecido por usuário
- credencial não repassada por redirect não confiável
- **build falha** se qualquer cliente HTTP, SDK, transporte de Bridge, `ProcessBuilder` ou
  `Runtime.exec` aparecer fora do pacote permitido — com teste negativo por construto

**Capacidade e execução**

- spoofing: provedor declara capacidade que não tem → snapshot invalidado, **capacidade marcada
  indisponível, conexão inalterada**, audit — a credencial não tem nada de errado
- decisão de segurança na fronteira **não lê campo de snapshot** — provado removendo o campo
- execução duplicada não cobra duas vezes nem roda ferramenta local duas vezes
- corrida de orçamento
- erro de provedor e de ferramenta local viram `ExecutionResult` normalizado

**Bridge** (sub-fase)

- sequestro de pareamento · replay · comando não autorizado
- disponibilidade declarada não autoriza execução
- confirmação humana no dispositivo local não é satisfeita por sessão web

## Provedores falsos

**Conexão** (W3): sucesso de OAuth, `state` divergente, replay, expiração, refresh, credencial
inválida, forma de credencial recusada, capacidades retornadas, capacidade indisponível, capacidade
declarada e ausente.

**Execução** (W7): prova a Execution Boundary **antes** de qualquer chamada real — e é onde o canal
OAuth falso atravessa a fronteira, exercitando a separação conexão/execução sem provedor real.

## Portão para provedor real

Nenhuma execução automatizada real antes de **todos**:

- [ ] domínio de conexão implementado, `IntegrationConnection` reconciliado
- [ ] posse imposta, IDOR testado, trio de autorização escrito e testado
- [ ] credencial atrás do Vault; validação de forma recusa cookie e token de assinatura
- [ ] descoberta de capacidades funcionando, com divergência tratada sem derrubar a conexão
- [ ] `docs/PHASE7_DEBT.md` existe, com o oráculo residual do ADR-021 e a rota reflexiva do D-16
- [ ] Execution Boundary existe
- [ ] **enforcement do D-16 como allowlist, com teste negativo por construto**
- [ ] redação positiva de log provada por controle negativo
- [ ] metadata de audit fechada e redigida
- [ ] idempotência, orçamento e auditoria funcionando
- [ ] E2E do provedor falso passa, incluindo canal OAuth falso
- [ ] **W8 red team contra provedor falso: PASS**

## Auditoria

Conexão: `CONNECTION_STARTED`, `CONNECTION_COMPLETED`, `CONNECTION_FAILED`,
`CONNECTION_REAUTH_REQUIRED`, `CONNECTION_REVOKED`, `CAPABILITIES_DISCOVERED`,
`CAPABILITY_DIVERGENCE_DETECTED`. Depois, o ciclo de execução.

**Nunca auditar valor de segredo**, e o mecanismo existe: chaves tipadas fechadas mais o redator no
caminho de escrita. A trilha é append-only — um token escrito ali é indelével.

## Decisões de produto pendentes

1. Múltiplas contas por provedor na primeira UI — o domínio suporta; a tela mostra?
2. Entitlement é `UNKNOWN` na maioria — mostrar "desconhecido" ou omitir?
3. Descoberta de capacidades automática ao conectar, ou ato separado? (É chamada ao provedor com a
   cota do usuário.)
4. `integration.domain.IntegrationConnection` — unificar ou remover?
5. Bridge — confirmar sub-fase própria.

## Decisões de engenharia em aberto

- nomes finais de `ExecutionRequest`, mapeados contra o código (SPEC-02)
- pacote exato da allowlist de saída (SPEC-02)
- `ProviderAccount` renomeado ou envelopado (W1)
- política de redirect literal (SPEC-02)
- contrato de escrita de refresh sob concorrência no Vault (SPEC-02)
- `CapabilitySnapshot` expira por tempo ou só por revalidação (W4)
- classificação dos escopos `generativelanguage` como sensíveis/restritos — **NÃO VERIFICADO**,
  bloqueia a Wc
