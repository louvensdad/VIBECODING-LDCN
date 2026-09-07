# ADR-013: Redação antes da persistência

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 4

## Contexto

Detectar um segredo não adianta se ele já foi gravado. A ordem ingênua — gravar a evidência, depois
inspecionar, depois limpar — deixa o valor no banco, no WAL, no backup e provavelmente num log,
mesmo que a linha seja atualizada depois. Uma limpeza posterior remove a aparência do problema, não
o problema.

## Decisão

A ordem é fixa e não negociável:

```
conteúdo cru  →  inspeção (em memória)  →  redação  →  persistência
```

Concretamente:

- `EvidenceService` inspeciona `rawContent` em memória e grava `SensitiveDataRedactor.redact(...)`.
  A tabela `task_evidence` nunca recebe o valor original.
- `SecurityGuardianService` redige a evidência do candidato **antes** de construir o
  `SecurityFinding`, e o fingerprint de deduplicação é calculado sobre o texto já redigido — nunca
  sobre o segredo, que de outra forma viveria dentro de um hash.
- `PromptSecurityInspector` devolve `safeContent` redigido. Um prompt bloqueado ainda é exibido na
  interface; se carregasse o segredo, `copyAllowed=false` só teria impedido o atalho de teclado.

## Alternativas consideradas

*Criptografar a evidência crua em vez de redigir* — foi descartado por escopo: exigiria gestão de
chaves (KMS, rotação), que é uma fase inteira, e mesmo assim o valor continuaria recuperável. O
produto não precisa do segredo; precisa saber que ele apareceu.

## Consequências

Um segredo colado por engano não fica no sistema. Há teste que varre **toda coluna de toda tabela**
do schema atrás do valor sintético, com um controle negativo que prova que a varredura enxerga
dados reais.

O custo é que a evidência guardada não é literalmente o que o usuário colou. Isso é aceitável: a
parte removida é justamente a que não deveria estar ali, e o marcador `[REDACTED]` deixa visível
que houve remoção.
