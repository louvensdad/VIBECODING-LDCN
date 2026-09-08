# ADR-021: Estado honesto e nenhuma exposição parcial da credencial

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 5

## Contexto

Depois de guardar uma chave, a interface precisa dizer alguma coisa. As duas escolhas óbvias — um
estado "conectado" e uma versão mascarada da chave — são as duas erradas.

## Decisão

### Não existe CONNECTED, não existe VALID

`ProviderAccountStatus` é `PENDING_CREDENTIAL`, `CREDENTIAL_STORED_UNVERIFIED` ou `DISABLED`.

Guardar uma credencial prova que ela foi guardada. Nada foi enviado à OpenAI, à Anthropic ou ao
Google, então a plataforma não sabe se a chave funciona, se tem saldo, ou se já foi revogada. Um
estado "conectado" seria a interface afirmando algo que ninguém verificou — e o usuário tomaria
decisões com base nessa afirmação.

Validar chamando o provider foi considerado e recusado nesta fase: gasta cota do usuário, acopla o
cadastro à disponibilidade de um serviço externo, e transforma o formulário num oráculo de
validade de chaves para quem tiver sessão roubada.

### Nenhum pedaço da chave volta

A resposta tem `hasCredential: boolean`. Não tem prefixo, não tem últimos quatro caracteres, não
tem fingerprint.

`sk-…4f7a` parece inofensivo e não é. É justamente o pedaço que permite a alguém com sessão
roubada confirmar **qual** chave está ali, e é o pedaço que sobrevive num print de tela ou num
ticket de suporte. Não construir isso é mais barato do que mantê-lo seguro.

Pela mesma razão não há fingerprint derivado do segredo em audit, log ou métrica: correlacionar
duas ocorrências da mesma chave é exatamente o que um fingerprint permite.

### O tipo de escrita não é o tipo de leitura

`StoreCredentialRequest` e `ProviderAccountResponse` são tipos separados, no backend e no pacote de
contratos. Um tipo único que carregasse a credencial acabaria serializado numa resposta por alguém
adicionando um campo, e o vazamento pareceria uma refatoração comum.

## Consequências

O usuário não consegue conferir qual chave está guardada. Se perder a original, gera outra no
provider — a tela diz isso explicitamente.

Um erro de digitação na chave só aparece quando o provider for efetivamente chamado, numa fase
futura. É o preço de não afirmar o que não se verificou.
