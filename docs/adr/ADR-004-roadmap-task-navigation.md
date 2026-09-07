# ADR-004: Roadmap e Task são o modelo de navegação do projeto

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 2

## Contexto

O Project Brain guarda o que o projeto sabe — visão, decisões, regras. Ele não responde "onde
estamos" nem "o que falta", porque memória não é plano. Sem uma estrutura operacional, cada
resposta a essas perguntas teria que ser inferida do texto das entradas, e duas inferências
diferentes dariam duas respostas diferentes.

## Decisão

Introduzir uma estrutura operacional separada da memória:

```
PROJECT
   ├── BRAIN            memória e decisões
   └── ROADMAP
          └── PHASE
                 └── TASK
                        ├── ACCEPTANCE CRITERIA
                        ├── DEPENDENCIES
                        └── EVIDENCE
```

Três responsabilidades distintas, e a separação é explícita:

- **Roadmap/Task** — estado operacional: o que existe, em que ordem, em que situação.
- **Brain** — memória e decisões: por que o projeto é como é.
- **Evidence** — fatos observados: o que realmente aconteceu.

Nada derivável é persistido. Progresso, prontidão de tarefa e status de fase são **calculados**:

- uma tarefa é `READY` exatamente quando todas as dependências estão `COMPLETED`;
- o status de uma fase vem das tarefas dentro dela (`PhaseStatusCalculator`);
- `progressPercentage` é computado a cada leitura e não existe no banco.

## Consequências

Uma pergunta como "onde estamos" tem uma resposta só, e ela é reproduzível.

O custo é que a estrutura precisa ser mantida à mão: o usuário cria fases e tarefas. Não existe
geração automática de roadmap, e isso é deliberado nesta fase — um roadmap inventado por IA seria
exatamente o tipo de estado não auditável que o produto existe para evitar.

Um detalhe que decorre da regra: uma fase sem tarefas **não** conta como concluída. Trabalho
planejado que ninguém detalhou é trabalho pendente, e o Next Step Engine responde
`WAIT_FOR_USER` em vez de `PROJECT_COMPLETE`.
