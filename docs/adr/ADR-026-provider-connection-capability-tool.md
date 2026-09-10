# ADR-026: Provider, Connection, Capability e Tool são quatro coisas

**Status:** aceito · **Data:** 2026-09-09 · **Fase:** 7

## Contexto

A forma natural de modelar isso é um objeto só — algo como `ProviderAccount` com um campo `model` e
um campo `apiKey`. Funciona enquanto existe um provedor, um mecanismo e um tipo de capacidade.

Deixa de funcionar no primeiro caso real:

> O Codex é da OpenAI. Pode ser alcançado por credencial de API remota **ou** pelo CLI local
> autenticado com a conta ChatGPT. São donos de credencial diferentes, canais diferentes e modelos
> de cobrança diferentes — e é o mesmo Codex.

Um campo `model = "codex"` não representa nada disso.

## Decisão

Quatro conceitos, que não colapsam:

| Conceito | Responde |
| --- | --- |
| **`AiProvider`** | quem é o fornecedor |
| **`AiConnection`** | um vínculo autorizado *deste* usuário com *este* provedor, por *um* mecanismo |
| **`Capability`** | o que essa conexão comprovadamente permite fazer |
| **`Tool`** | uma ferramenta nomeada (Codex, Claude Code, Gemini CLI), distinta de um modelo |

Mais dois que a mesma pressão tende a esmagar juntos:

- **`Model`** — o catálogo do provedor é uma coisa; os modelos disponíveis **a esta conexão** são
  outra. Nem toda conta alcança todo modelo.
- **`ExecutionChannel`** — `REMOTE_API`, `REMOTE_AUTHORIZED_ACCOUNT`, `LOCAL_BRIDGE`,
  `MANUAL_COPY_PASTE`. Por onde a execução sairia.

`ToolCapability` é tipo separado de `ModelCapability`. O Codex não tem janela de contexto nem preço
por token; tratá-lo como modelo obriga a inventar esses campos ou deixá-los nulos, e nulo aqui
significa "a modelagem está errada".

`ProviderAccount`, que já existe desde a Fase 5, **é a forma `API_CREDENTIAL` de `AiConnection`** —
não um conceito concorrente a ser mantido em paralelo.

## Alternativas consideradas

*Um `Connection` com campos opcionais para tudo* — descartado. Metade dos campos nulos em qualquer
instância, e nenhuma forma de o tipo dizer quais combinações são válidas. `AUTHORIZATION_PENDING`
num vínculo por chave de API é estado impossível que o modelo permitiria representar.

*Tratar ferramentas como modelos de nome especial* — é o que a maioria dos produtos faz, e é o erro
que o caso do Codex acima expõe. Sobrevive até a primeira ferramenta alcançável por dois canais.

## Consequências

Mais tipos e mais cerimônia para o primeiro provedor. O ganho aparece no segundo canal: acrescentar
um mecanismo diferente à mesma conexão é o teste de que a separação funciona, e num modelo colapsado
isso seria uma segunda conexão fingindo ser a mesma conta.

Esse teste é feito contra o **adaptador falso** de OAuth, atravessando a fronteira de execução. Uma
versão anterior desta arquitetura propunha fazê-lo com o Gemini, no argumento de que ele teria OAuth
e chave de API oficiais; a cobertura de inferência por OAuth de usuário final não está confirmada em
nenhum provedor da matriz, e o teste é sobre a separação, não sobre o provedor.

A capacidade nunca é afirmada por catálogo. Saber que a OpenAI tem Codex não é saber que *esta*
conta tem, e o `CapabilitySnapshot` distingue `DECLARED`, `DISCOVERED` e `VERIFIED` exatamente por
isso — mesmo princípio do `CONNECTED_UNVERIFIED` em
[ADR-021](ADR-021-honest-credential-status-and-no-partial-disclosure.md).

Ver o [AI Connection Hub](../architecture/AI_CONNECTION_HUB.md).
