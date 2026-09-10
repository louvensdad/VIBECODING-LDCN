# ADR-023: Contexto é deny-by-default, e redação vem antes da medição

**Status:** aceito · **Data:** 2026-09-09 · **Fase:** 6

## Contexto

Coletar não é incluir. Os coletores leem tudo que o projeto tem a dizer — Brain, roadmap, tarefa
atual, critérios de aceite, evidência, erros abertos, resumo de segurança — e a pergunta seguinte é
o que disso entra no pacote.

A resposta ingênua é "tudo que não foi barrado". Ela falha de um jeito específico: quando aparece um
tipo de item novo, ou um coletor passa a devolver algo que ninguém previu, o item entra porque
nenhuma regra se pronunciou. A ausência de decisão vira uma decisão de admitir, e ninguém revisa
aquilo que não aparece num diff.

## Decisão

**Um item entra num pacote porque uma regra diz que pode, nunca porque nada objetou.**

`ContextPolicy` guarda um id reservado para o caso em que nenhuma regra falou:

```java
public static final String DEFAULT_DENY_RULE_ID = "context.policy.default-deny";
```

Uma regra declarada que tente usar esse id é recusada na construção da política, com a razão dita em
voz alta: uma decisão considerada não pode ficar indistinguível da ausência de uma. Ids duplicados
também são recusados — um pacote guardado precisa poder dizer *qual* regra o admitiu.

A ordem do pipeline é fixa, e cada passo acontece onde acontece por um motivo:

```
fontes oficiais
   → coletores          (11 coletores, um por tipo de fonte)
   → ContextItem candidato
   → ContextPolicy      admite ou recusa; recusado é descartado sem registrar o texto
   → ContextRedaction   redige conteúdo, rótulo e a referência da tarefa
   → BudgetedContextSelection   corta pelo teto
   → ContextPack        → persistência → API → Inspector → PARA
```

Dois pontos dessa ordem são decisões, não acaso:

**A recusa não guarda o texto.** `ContextPackCompiler` faz `continue` sobre o candidato negado. Não
existe tabela de rejeitados, não existe trace com o corpo do item, e não existe endpoint que
devolveria um. Guardar o que a política recusou seria construir exatamente o lugar onde o texto
negado passaria a viver.

**Redação vem antes da medição.** `ContextRedaction.redact` roda sobre o que sobreviveu à política e
antes de qualquer coisa medir tamanho ou calcular digest. Se fosse depois, o pacote seria
dimensionado por um texto que ninguém veria, e o `contentFingerprint` seria o digest do segredo.

A garantia de que a redação não pode ser pulada **no compilador** é de tipo:
`ContextRedaction.redact` devolve um `RedactedContextItem`, `AdmittedContextItem` não aceita outra
coisa, e remover a chamada é erro de compilação.

A garantia de que ninguém **mais** cunha um item redigido é regra de build, não de linguagem —
`RedactedContextItem.rehydratedFromStorage` é público e não redige. Três regras ArchUnit fecham
isso, e o teste registra que o tipo sozinho já foi contornado duas vezes sem reflexão. Ver
[CONTEXT_ENGINE.md](../architecture/CONTEXT_ENGINE.md) e o D-02 em
[PHASE6_DEBT.md](../PHASE6_DEBT.md).

## Alternativas consideradas

*Allow-list de `ContextKind` em vez de regras* — descartado porque o `kind` é semântica, não
procedência. Um `DECISION` vindo do Brain e um `DECISION` inferido de outro lugar não merecem o
mesmo tratamento, e uma lista por kind não sabe distinguir.

*Registrar o que foi recusado, para depuração* — a tentação é real e foi recusada. O valor de
depuração é pequeno perto de criar um repositório de texto que a política acabou de decidir que não
devia entrar.

## Consequências

Acrescentar um coletor não basta para o que ele lê aparecer num pacote. É preciso uma regra, com id
próprio, e ela aparece no diff. Isso é mais trabalho e é o ponto.

Quem depura uma ausência tem menos com que trabalhar: o pacote diz o que entrou e sob qual regra,
não o que ficou de fora nem por quê. A `ContextAdmission` de cada item admitido carrega
`policyRuleId` e `explanation`, então a pergunta "por que isto está aqui" tem resposta; "por que
aquilo não está" se responde lendo as regras, não os dados.

Ver [ADR-013](ADR-013-redaction-before-persistence.md), de que este é a aplicação ao contexto, e
[ADR-011](ADR-011-deny-by-default-resource-access.md), o mesmo princípio no acesso a recursos.
