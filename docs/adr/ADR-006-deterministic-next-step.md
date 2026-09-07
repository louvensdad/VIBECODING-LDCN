# ADR-006: O Next Step Engine é determinístico

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 2

## Contexto

"Qual é o próximo passo?" é a pergunta central do produto. A resposta óbvia seria perguntar a um
modelo. Mas se um modelo decide o próximo passo, o modelo volta a ser dono do projeto — exatamente
o que o [ADR-002](ADR-002-project-brain-owns-context.md) recusa. Além disso, a mesma situação
daria respostas diferentes a cada execução, e o usuário não teria como conferir nenhuma delas.

## Decisão

O próximo passo é calculado a partir do estado registrado, sem nenhum modelo.

O motor não contém lógica própria além de **ordem**: ele percorre uma lista de regras pequenas e
devolve a primeira que se aplica.

```java
public interface NextStepRule {
    Optional<NextStepRecommendation> evaluate(NextStepContext context);
}
```

A ordem é a política do produto:

| # | Regra | Resultado |
| --- | --- | --- |
| 1 | Sem roadmap ou tarefas | `WAIT_FOR_USER` |
| 2 | Tudo concluído, mas há fase sem tarefas | `WAIT_FOR_USER` |
| 3 | Tudo concluído | `PROJECT_COMPLETE` |
| 4 | Evidência `BLOCKED` | `RESOLVE_BLOCKER` |
| 5 | Evidência `FAILURE`/`PARTIAL` | `FIX_ERROR` |
| 6 | Aguardando validação | `VALIDATE_RESULT` |
| 7 | Tarefa em andamento | `CONTINUE_TASK` |
| 8 | Próxima tarefa é risco `CRITICAL` | `REVIEW_SECURITY` |
| 9 | Existe tarefa elegível | `START_TASK` |
| 10 | Nada elegível | `WAIT_FOR_USER` |

Bloqueio e falha vêm antes de qualquer coisa nova: **erro impede avanço**.

Toda recomendação carrega `reason` obrigatório. Uma recomendação que o usuário não consegue
auditar é indistinguível de um palpite.

## Consequências

A mesma situação produz sempre a mesma resposta, e cada regra é testável isoladamente — sem banco,
sem contexto Spring. Adicionar comportamento é adicionar uma regra, não fazer crescer um
condicional.

O custo é que o motor não tem julgamento: ele não sabe que duas tarefas poderiam ser feitas em
paralelo, nem que uma tarefa é mais urgente por motivo de negócio. Quando um LLM entrar, ele poderá
*sugerir* uma reordenação — via proposta revisável, nunca decidindo sozinho.
