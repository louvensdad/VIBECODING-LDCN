# ADR-024: O Inspector mostra o pacote guardado, e só ele

**Status:** aceito · **Data:** 2026-09-09 · **Fase:** 6

## Contexto

Um `ContextPack` é a decisão de o que um modelo receberia. Se ninguém pode olhar essa decisão antes
de ela virar uma chamada, a decisão é do sistema e não de quem responde por ela.

A tela existe para isso. A pergunta de projeto era o que exatamente ela pode mostrar — e a resposta
tinha que ser dada antes de a tela existir, porque "mostrar um pouco mais para ajudar a depurar" é
uma frase que só é dita depois.

## Decisão

**A tela mostra o que a API devolve. Nada mais, e nada recalculado.**

Rota: `/projects/[id]/context`.

O que isso exclui, e por quê:

- **Não existe candidato pré-política.** A API não tem rota que devolva um item antes de a política
  o julgar, nem conteúdo antes de a redação reescrevê-lo — e não deve passar a ter. O compilador
  descarta o recusado sem registrar o texto, então uma rota dessas exigiria primeiro construir o
  lugar onde o texto negado passaria a morar (ADR-023).
- **A tela não redige nada.** Um segundo passe em JavaScript, necessariamente mais fraco que o do
  servidor, produziria um ecrã que discorda do pacote guardado parecendo mais confiável do que é.
  O conteúdo é exibido literalmente como veio.
- **Nada é persistido no navegador.** Sem `localStorage`, `sessionStorage` ou IndexedDB. Recarregar
  relê da API.

Três afirmações da tela foram escritas para serem exatas:

**"REDIGIDO"** é informativo, não um selo verde. A plataforma substitui por `[REDACTED]` os valores
que reconhece, antes de gravar qualquer pacote — isso é uma afirmação sobre o compilador, e é
verdadeira. O que a tela não diz é que o pacote está seguro: a detecção é por padrão, e um segredo
com forma que o redator não conhece passa. A frase de ressalva está ao lado do selo, não numa nota
de rodapé.

**"Não há execução por provedor nesta versão"** é uma afirmação sobre o cliente, verificável:
nenhuma das rotas declaradas envia contexto a um modelo (ADR-022).

**O número de tokens é estimativa.** Vem com til, com o rótulo `ESTIMADO`, com o nome da heurística
que o produziu e com a frase "não medido". O contrato tipa `isExact` como o literal `false`, e o
orçamento não tem dimensão de tokens — o teto é itens, caracteres e bytes.

Duas defesas contra o tamanho: conteúdo é texto não confiável e é renderizado como filho de texto de
`<pre>`, sem `dangerouslySetInnerHTML` em lugar nenhum da tela; e o volume é limitado nos dois eixos
— 600 caracteres de prévia, 20 000 expandido, 100 cards por pacote — com cada corte dizendo contra
qual total ele foi feito. O pacote guardado não é tocado.

## Alternativas consideradas

*Renderizar markdown ou destacar sintaxe do conteúdo* — descartado. No momento em que contexto não
confiável é interpretado como marcação, um inspetor de entrada não confiável vira um jeito de
executá-la.

*Usar a resposta da listagem para desenhar o detalhe* — a listagem devolve pacotes inteiros por
contrato, mas o pacote inspecionado é relido da própria rota dele. É a rota cujas regras de posse
decidem se aquele pacote pode ser mostrado; ler dali significa que a tela nunca exibe um pacote que
a rota de detalhe recusaria.

## Consequências

O Inspector não ajuda a depurar a política: ele não mostra o que ficou de fora. Essa é a troca aceita
em ADR-023, e a tela herda-a.

A tela ficará desatualizada de propósito quando a Fase 7 construir a fronteira de execução. A frase
sobre provedor está escrita para quebrar de forma visível nesse momento, em vez de continuar
verdadeira por acidente.

Ver [ADR-022](ADR-022-context-compilation-is-not-provider-execution.md) e
[ADR-023](ADR-023-context-is-deny-by-default.md).
