# ADR-007: Prompt Builder determinístico antes de integrar LLM

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 2

## Contexto

Integrar um provider é fácil; a parte difícil é decidir **o que** enviar. Se a montagem do contexto
nascer junto do primeiro SDK, ela nasce acoplada a ele e ninguém consegue inspecionar o que está
sendo mandado para fora.

## Decisão

O Prompt Builder é implementado e usado **antes** de qualquer integração. Ele recebe estado
registrado e devolve texto:

```
Project + ProjectState + Task + Acceptance Criteria + Evidence + Brain + Recommendation
        → GeneratedPrompt
```

Sete tipos: `START_TASK`, `CONTINUE_TASK`, `FIX_ERROR`, `VALIDATE_RESULT`, `MODEL_HANDOFF`,
`ASK_FOR_EVIDENCE`, `RESOLVE_BLOCKER`.

Três propriedades foram tratadas como requisitos, não detalhes:

**Todo prompt termina exigindo evidência real.** Build, testes, saída literal. Um prompt que
permite ao modelo responder "pronto!" produz exatamente a alegação não verificável que o Output
Analyzer vai recusar — o formato do prompt e a regra da análise precisam concordar.

**`contextSources` lista o que entrou.** O usuário vê o que está prestes a enviar para fora antes
de enviar.

**Nenhum secret entra.** `PromptContext` só tem campos declarados explicitamente, montados a partir
da memória oficial. Não existe campo de credencial, e nada entra num prompt por estar por perto.

`FIX_ERROR` carrega a evidência real e os sinais técnicos detectados, e começa com "NÃO CONTINUE
PARA NOVAS FUNCIONALIDADES" — a regra de não avançar sobre erro atravessa até o texto entregue ao
modelo.

## Consequências

O usuário copia o prompt e escolhe onde executá-lo. O VibeCode não envia nada por ele, e não há
botão "enviar para Claude".

Quando os providers entrarem, a integração será apenas o transporte: o conteúdo do prompt já estará
decidido, testado e auditável. Trocar de modelo não muda o que o projeto sabe sobre si.

O custo é manutenção de templates em código. É aceitável enquanto forem sete; virando dezenas,
merecem um formato próprio e um ADR novo.
