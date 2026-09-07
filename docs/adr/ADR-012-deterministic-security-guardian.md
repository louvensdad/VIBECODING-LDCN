# ADR-012: Security Guardian determinístico

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 4

## Contexto

O produto pede ao usuário que cole saídas cruas: logs de build, respostas de modelo, stack traces.
Esse material carrega credencial com frequência — uma variável de ambiente ecoada, uma URL de banco
com senha, uma chave num comando de exemplo. Sem inspeção, o VibeCode passa a ser o lugar onde os
segredos do usuário se acumulam.

A tentação seria pedir a um modelo que encontrasse os segredos. Isso teria dois problemas: mandaria
o segredo para fora justamente para descobrir que era segredo, e daria uma resposta diferente a cada
execução.

## Decisão

Inspeção determinística por regras, sem nenhum modelo. Cada regra implementa `SecurityRule`, é
registrada em `SecurityRuleRegistry` e devolve `SecurityFindingCandidate`.

Nove regras: chave privada, atribuição genérica de secret, padrão de token conhecido, senha em URL,
comando perigoso, TLS desabilitado, CORS curinga com credenciais, dado sensível em log, e
vazamento de secret em prompt.

Duas propriedades foram tratadas como requisito, não como refinamento:

- **Placeholder não é segredo.** `${API_KEY}`, `<YOUR_API_KEY>`, `REPLACE_ME` e `[REDACTED]` são
  reconhecidos e ignorados. Um scanner que grita em todo arquivo de exemplo é desligado na primeira
  semana, e um scanner desligado não protege nada.
- **Nem todo `rm` é perigoso.** A regra de comando perigoso casa `rm -rf /`, `DROP DATABASE`,
  `chmod -R 777` — não qualquer remoção de arquivo.

## Alternativas consideradas

*Entropia sobre strings* — pega chaves aleatórias mas gera muito falso positivo em hashes, UUIDs e
código minificado, e não explica ao usuário o que encontrou. Regras nomeadas dizem qual é o problema
e o que fazer.

*Inspeção sob demanda por botão* — descartada: o segredo já teria sido gravado quando o usuário
lembrasse de clicar. A inspeção acontece na criação da evidência e na geração do prompt.

## Consequências

A mesma entrada produz sempre o mesmo resultado, e cada regra é testável isoladamente.

O custo é cobertura: um segredo de formato desconhecido passa. É por isso que o gate e a redação
existem em camadas, e por que a lista de regras é um ponto de extensão explícito, não um detalhe
interno.
