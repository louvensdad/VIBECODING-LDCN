# ADR-019: Ponteiro de versão ativa no secret, não flag por versão

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 5

## Contexto

Rotação de credencial tem duas exigências que parecem compatíveis e não são, se modeladas de
qualquer jeito:

1. **no máximo uma versão ativa por secret** — senão "a credencial atual" fica ambígua;
2. **nunca zero versões ativas** — a nova só entra depois de estar gravada e durável, porque uma
   falha no meio não pode deixar o usuário sem credencial e sem como recuperar a antiga.

## Contexto da descoberta

A primeira modelagem usava uma coluna `active_secret_id` em `vault_secret_versions`, com índice
único: a versão carregava o id do secret enquanto ativa e `NULL` depois. O invariante ficava no
banco, o que era o objetivo.

O teste de rotação falhou com violação desse índice único. E estava certo: gravar a nova versão
**antes** de aposentar a antiga significa, por um instante, duas linhas ativas. O índice proibia
exatamente a ordem segura. As duas únicas saídas eram aposentar antes de gravar — o que é o
problema que a ordem existia para evitar — ou remover o índice e rebaixar o invariante a convenção.

## Decisão

O ponteiro fica no secret: `vault_secrets.active_version_id`.

- "no máximo uma ativa" passa a ser **estrutural** — é uma coluna, não uma regra que algo precisa
  manter;
- "nunca zero" passa a ser possível — a nova versão é cifrada, gravada e submetida ao banco
  enquanto o ponteiro ainda aponta para a antiga, e a troca é uma atualização de uma coluna só.

Antes dessa linha a antiga vale; depois dela a nova vale; não existe estado intermediário. Aposentar
a versão antiga vem depois, e é escrituração do que já aconteceu.

`SecretVersionStatus` continua existindo, mas é descritivo (ACTIVE, RETIRED, DESTROYED). Quem está
**em vigor** é decidido pelo ponteiro e por nada mais — por isso o repositório de versões não tem
nenhuma consulta "ache a ativa": duas formas de responder a mesma pergunta acabam discordando.

## Consequências

As duas tabelas passam a referenciar-se mutuamente, e a foreign key do ponteiro é adicionada por
`ALTER TABLE` depois de ambas existirem.

`destroy()` limpa o ponteiro primeiro e só então sobrescreve o material: a partir dessa linha nada
considera nenhuma versão em vigor, mesmo que a limpeza falhe no meio.

Um teste força falha de cifragem durante a rotação e verifica que a credencial anterior continua
funcionando.
