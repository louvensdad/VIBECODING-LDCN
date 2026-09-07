# Princípios de Segurança

Valem desde a fundação, não a partir de uma fase futura de endurecimento.

## Secrets

- Nenhum secret em código, migration, teste ou log. `.env` está no `.gitignore`; `.env.example`
  documenta os nomes sem os valores.
- Credenciais chegam por variável de ambiente. `application.yml` só referencia
  (`${DATABASE_PASSWORD}`), nunca contém valor de produção.
- Registros que representam conexões externas (`ProviderAccount`, `IntegrationConnection`) guardam
  um `credentialRef` — um ponteiro para o segredo — e nunca o segredo.
- **Nenhum secret entra em prompt automaticamente.** `PromptContext` só carrega campos declarados
  explicitamente, montados a partir da memória oficial. Nada entra num prompt por estar por perto.

## Fronteira da API

- Toda entrada pública é validada com Bean Validation, com limite de tamanho em todo campo de
  texto livre.
- Entidades JPA nunca são serializadas. Toda resposta é DTO.
- Um único formato de erro (`ApiError`). O handler genérico registra a exceção no log e devolve
  mensagem genérica — detalhe interno não vaza pela API.
- Mensagem de erro é escrita para o usuário: sem stack trace, sem SQL, sem credencial.

## Dados

- Schema só evolui por migration Flyway, versionada no repositório.
- Integridade no banco: chave estrangeira de `brain_entries` para `projects` com `ON DELETE
  CASCADE`, colunas `NOT NULL` onde o domínio exige.
- Log não recebe conteúdo de memória, prompt ou saída do usuário.

## Execução

- O processo da API **não executa comandos do usuário** e não deve ganhar essa capacidade.
- `TerminalExecutionGateway` é uma porta sem implementação. Quando existir, executa em container
  isolado, com sistema de arquivos próprio, sem rede para os serviços da plataforma e sem acesso a
  nenhuma credencial.
- A porta foi declarada agora justamente para manter esse requisito visível, em vez de deixar um
  caminho de execução crescer dentro do processo principal por acidente.

## Modelos externos

- Saída de LLM é dado não confiável. Passa pelo Output Analyzer; nunca é executada nem gravada
  direto na memória.
- Memória proposta por modelo exige validação antes de virar contexto oficial.
- Integrações recebem credencial de menor privilégio possível.

## Ainda não existe

Sem autenticação, autorização, multi-tenancy ou rate limiting — a API é de uso local nesta fase.
**Isto precisa existir antes de qualquer exposição em rede**, e é o primeiro item de segurança da
próxima fase.
