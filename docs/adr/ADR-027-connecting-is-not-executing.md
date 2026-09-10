# ADR-027: Conectar uma conta não é executar nela

**Status:** aceito · **Data:** 2026-09-09 · **Fase:** 7

## Contexto

O [ADR-022](ADR-022-context-compilation-is-not-provider-execution.md) separou compilar contexto de
executar num provedor. A Fase 7 introduz um terceiro ato que a mesma pressão quer fundir aos outros
dois: **conectar uma conta**.

A leitura natural de "conectei minha conta Gemini" é "agora o VibeCode usa Gemini". Se a arquitetura
concordar com essa leitura, conectar uma conta vira consentimento para enviar o contexto do projeto
a um terceiro — e o usuário terá dado esse consentimento sem que ninguém lhe perguntasse.

## Decisão

**São três atos separados, com três autorizações separadas.**

```
compilar contexto   →  não autoriza transmissão   (ADR-022)
conectar conta      →  não autoriza transmissão   (este ADR)
executar            →  ato próprio, do usuário
```

Concretamente:

- Conectar Gemini/ChatGPT/Claude/DeepSeek **não dispara chamada nenhuma** ao provedor com dados do
  projeto. Sem chamada de fundo, sem aquecimento, sem pré-carga de contexto.
- **Verificação de conexão usa a menor operação oficial segura** disponível — e nunca o `ContextPack`
  do usuário. Testar uma credencial enviando o contexto do projeto seria transmitir dados do usuário
  para descobrir se uma chave funciona.
- **Execução inicial é iniciada pelo usuário.** Sem autopilot na Fase 7 inicial.
- **Copiar e colar continua de primeira classe.** O VibeCode funciona inteiro sem nenhuma conexão:
  Prompt Builder → copiar → o usuário roda onde quiser → colar → Output Analyzer. Contas conectadas
  são aditivas.

**Continua havendo uma porta só.** Três canais de execução — `REMOTE_API`,
`REMOTE_AUTHORIZED_ACCOUNT`, `LOCAL_BRIDGE` — atravessam a **mesma** Provider Execution Boundary, que
é onde vivem autorização, orçamento, idempotência e auditoria. Os canais divergem no transporte, não
na autorização. `MANUAL_COPY_PASTE` é representado no modelo de produto mas não é canal automatizado:
nada sai do servidor, então não há fronteira a atravessar.

O payload transmitido é **derivado no servidor** a partir do `ContextPack` persistido mais a saída
aprovada do Prompt Builder. O frontend não é autoritativo sobre o que sai — se fosse, a política de
contexto da Fase 6 seria decoração.

**Enforcement.** O [D-16](../PHASE6_DEBT.md) da Fase 6 cresce na Fase 7 para cobrir toda via de
escape, e vira **allowlist** — nenhuma classe fora do pacote de infraestrutura do provedor depende de
cliente HTTP, SDK, transporte de Bridge ou execução de processo. A lista de construtos, o pacote
exato e os limites do que ArchUnit consegue ver estão em **um lugar só**, a §17 do
[AI Connection Hub](../architecture/AI_CONNECTION_HUB.md); repeti-la aqui garantiria que as duas
versões divergissem.

O que este ADR fixa é o princípio: uma regra que nunca foi vista falhando não é enforcement, é
decoração — e regra de pacote impõe a camada, não a fronteira.

## Alternativas consideradas

*Conectar já verificando com uma chamada real de geração* — descartado. É a forma mais rápida de
saber que a conexão funciona e transmite dados do projeto num momento em que o usuário acha que só
está fazendo login.

*Uma porta por canal, já que o transporte difere* — descartado. Três portas significam três lugares
onde orçamento e auditoria precisam existir, e o terceiro sempre fica para depois.

## Consequências

O usuário passa por dois momentos de consentimento em vez de um, e o segundo é mais explícito do que
a maioria dos produtos oferece. Isso é fricção deliberada: é o momento em que o contexto do projeto
sai da plataforma.

A tela de conexões pode mostrar "conectado" para uma conta que nunca recebeu nada — e isso é
informação correta, não estado incompleto.

Ver [ADR-022](ADR-022-context-compilation-is-not-provider-execution.md),
[ADR-025](ADR-025-only-official-connection-mechanisms.md) e o
[AI Connection Hub](../architecture/AI_CONNECTION_HUB.md).
