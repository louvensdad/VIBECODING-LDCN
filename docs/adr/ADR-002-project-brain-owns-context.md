# ADR-002: O Project Brain é o dono do contexto

**Status:** aceito · **Data:** 2026-09-07

## Contexto

Quem desenvolve com LLMs guarda o estado real do projeto na janela de conversa. Esse estado é
perdido ao trocar de modelo, ao estourar contexto ou ao começar uma sessão nova. O modelo também
afirma conclusão com frequência maior do que conclui de fato.

## Decisão

O estado verdadeiro do projeto pertence ao VibeCode, no Project Brain, e não a nenhum modelo.

Disso decorrem três regras concretas:

1. **Escrita é controlada.** Nenhum modelo escreve na memória oficial. O caminho é
   `resultado do LLM → MemoryUpdateProposal → validação → BrainEntry`, e
   `MemoryUpdateProposal.toEntry()` recusa proposta não aceita.

2. **Toda memória tem procedência.** `source` é obrigatório em toda entrada, para que memória
   escrita por uma pessoa e memória transcrita de um modelo continuem distinguíveis.

3. **Evidência vence alegação.** Uma etapa não fecha porque o texto diz "concluído". O Output
   Analyzer classifica alegação sem prova como `NEEDS_VALIDATION`, e `shouldContinue` só é
   verdadeiro diante de evidência técnica de sucesso.

## Consequências

O usuário pode trocar de modelo no meio de um projeto sem perder nada, porque o que o projeto sabe
nunca esteve dentro do modelo.

O custo é atrito: memória proposta por modelo não vira contexto oficial sozinha, e alguém precisa
aceitá-la. Esse atrito é o produto, não um efeito colateral.
