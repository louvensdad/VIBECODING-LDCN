# ADR-018: O Vault não tem leitura — acesso só por callback

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 5

## Contexto

Um cofre precisa devolver o segredo em algum momento, senão não serve para nada. A questão é
**como**, porque a forma da API decide o que é fácil fazer de errado depois.

## Decisão

### Não existe `getSecret`

`VaultService` não expõe nenhum método que devolva material. A única leitura é:

```java
<T> T withSecret(SecretReference reference, Function<SecretMaterial, T> operation)
```

O material vale apenas dentro do callback e é limpo ao sair. Um método `String getSecret(UUID)`
tornaria cada um destes um deslize de uma linha:

- guardar em campo de um bean singleton;
- devolver de um controller;
- interpolar numa mensagem de log;
- serializar num DTO por causa de um `@JsonProperty` acidental.

Nenhum deles quebra teste. Todos vazam.

### `SecretMaterial`, não `String`

Plaintext trafega em `SecretMaterial`, que tem `toString()` redigido, `close()` que sobrescreve os
bytes e igualdade por identidade. `String` é imutável e fica no heap até o GC decidir o contrário —
não há como apagá-la.

Isto é uma redução de exposição, **não** exposição zero: o parsing do corpo JSON já criou cópias
antes de a aplicação ver o valor, e essas cópias estão fora do alcance deste código. A classe
documenta isso em vez de prometer o que não entrega.

### Referência, não conteúdo

O que circula pela aplicação é `SecretReference` — um id e um propósito. `ProviderAccount` guarda o
id; nunca a chave. Por isso a entidade pode ser lida, listada, serializada em DTO e escrita em
audit sem que nenhum desses caminhos toque em segredo.

### O Prompt Builder não conhece o Vault

Nesta fase, nada além do módulo de provider accounts depende de `VaultService`. Em particular o
Prompt Builder não recebe essa dependência: prompts são texto que vai para fora, e um cofre
alcançável a partir de um construtor de texto é um cofre que uma refatoração distraída abre.

## Consequências

Quem for integrar um provider de verdade terá de passar por `withSecret`. É uma restrição real, e é
a intenção: existe exatamente uma porta, e ela é auditável.

Nenhum endpoint HTTP devolve material. Não há `GET /credential`, `GET /api-key` nem
`GET /secrets/{id}/plaintext`, e um teste verifica que essas rotas continuam ausentes.
