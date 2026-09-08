# ADR-020: Master key ausente derruba a aplicação

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 5

## Contexto

A KEK vem de `VIBECODE_VAULT_MASTER_KEY`. Alguém vai esquecer de definir essa variável — num
ambiente novo, num container recriado, num deploy feito às pressas.

## Decisão

### Nunca gerar uma chave

Sem master key válida, a aplicação **não sobe**. Não há default, não há fallback, não há geração
automática.

Gerar uma chave é a opção que parece gentil e é a pior possível: a aplicação sobe saudável, os
health checks passam, e todo secret já guardado sob a chave anterior fica permanentemente ilegível.
A falha só aparece depois, para um usuário cuja credencial parou de funcionar, quando já não há como
recuperar nada. Uma indisponibilidade barulhenta é preferível a uma perda silenciosa.

O mesmo vale para chave malformada e chave de tamanho errado. Em particular, uma chave curta não é
esticada nem hasheada até 32 bytes: isso faria uma chave fraca parecer forte.

### Nenhuma mensagem ecoa a chave

Toda mensagem de falha descreve o problema sem citar o valor. Erro de startup vai direto para log
e, com frequência, para um print colado num chat — citar a chave ali a publica melhor do que
qualquer ataque.

### O provedor local é de desenvolvimento

`LOCAL` mantém a KEK numa variável de ambiente, e ela é tão protegida quanto o ambiente que a
guarda. Rodar isso em produção exige
`vibecode.vault.allow-local-key-provider-outside-development=true`, escrito à mão. Existem
implantações de host único onde isso é legítimo; o que não pode acontecer é alguém chegar lá
copiando um arquivo de config sem perceber.

### `.env.example` não tem valor

A variável está lá, vazia. O arquivo é versionado: uma chave real nele é uma chave real no
histórico do repositório, para sempre.

## Consequências

Um ambiente mal configurado falha no boot, com mensagem que diz o que fazer.

Perder a master key significa perder todos os secrets — e isso está documentado no THREAT_MODEL
como consequência aceita, não como surpresa.
