# ADR-005: Conclusão de tarefa exige evidência

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 2

## Contexto

O modo mais comum de um projeto conduzido por LLM sair do controle é uma etapa ser marcada como
pronta porque o texto disse que estava pronta. O Output Analyzer já recusava tratar uma alegação
como sucesso; faltava impedir que uma tarefa fechasse sem prova.

Uma primeira implementação desta fase satisfazia automaticamente todos os critérios de aceite
quando qualquer saída de sucesso chegava. Isso tornava os critérios decorativos: um critério como
"uma pessoa revisou o modelo de ameaças" era marcado como satisfeito por um `BUILD SUCCESS` sem
relação nenhuma com ele.

## Decisão

Uma tarefa só pode ser concluída quando **as quatro condições** valem, verificadas por
`TaskCompletionPolicy`:

1. todas as dependências obrigatórias estão concluídas;
2. a evidência mais recente não é falha nem bloqueio;
3. todos os critérios de aceite obrigatórios estão satisfeitos;
4. existe evidência de sucesso técnico.

E, decisivo: **um critério de aceite nunca é satisfeito por inferência.** Ele muda de estado apenas
por decisão explícita, registrada com o nome de quem decidiu (`decidedBy`). Um build verde prova
que o código compila — não prova que a condição que alguém escreveu foi verificada.

`Task.complete()` é o único caminho para `COMPLETED`, e `transitionTo()` recusa esse valor, para
que nenhum chamador conclua uma tarefa passando um enum.

A política devolve **por que** a tarefa não pode fechar, não apenas que não pode. Essas razões
viram os `blockingIssues` que o usuário vê.

## Consequências

Uma tarefa concluída no VibeCode significa algo verificável.

O custo é atrito: o usuário precisa confirmar cada critério obrigatório. Esse atrito é o produto.
Quando a evidência é boa mas os critérios seguem pendentes, a tarefa vai para `NEEDS_VALIDATION` —
um estado honesto, que diz exatamente o que falta.

Consequência secundária: falha de build **não** marca a tarefa como `BLOCKED`. `BLOCKED` é reservado
a obstáculo externo — permissão, credencial, cota. Um build quebrado é trabalho normal em
andamento, e confundir os dois esconderia a tarefa de quem precisa consertá-la.
