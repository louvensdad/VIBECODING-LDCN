# PrincÃ­pios de SeguranÃ§a

Valem desde a fundaÃ§Ã£o, nÃ£o a partir de uma fase futura de endurecimento.

## Secrets

- Nenhum secret em cÃ³digo, migration, teste ou log. `.env` estÃ¡ no `.gitignore`; `.env.example`
  documenta os nomes sem os valores.
- Credenciais chegam por variÃ¡vel de ambiente. `application.yml` sÃ³ referencia
  (`${DATABASE_PASSWORD}`), nunca contÃ©m valor de produÃ§Ã£o.
- Registros que representam conexÃµes externas (`ProviderAccount`, `IntegrationConnection`) guardam
  um `credentialRef` â€” um ponteiro para o segredo â€” e nunca o segredo.
- **Nenhum secret entra em prompt automaticamente.** `PromptContext` sÃ³ carrega campos declarados
  explicitamente, montados a partir da memÃ³ria oficial. Nada entra num prompt por estar por perto.

## Fronteira da API

- Toda entrada pÃºblica Ã© validada com Bean Validation, com limite de tamanho em todo campo de
  texto livre.
- Entidades JPA nunca sÃ£o serializadas. Toda resposta Ã© DTO.
- Um Ãºnico formato de erro (`ApiError`). O handler genÃ©rico registra a exceÃ§Ã£o no log e devolve
  mensagem genÃ©rica â€” detalhe interno nÃ£o vaza pela API.
- Mensagem de erro Ã© escrita para o usuÃ¡rio: sem stack trace, sem SQL, sem credencial.

## Dados

- Schema sÃ³ evolui por migration Flyway, versionada no repositÃ³rio.
- Integridade no banco: chave estrangeira de `brain_entries` para `projects` com `ON DELETE
  CASCADE`, colunas `NOT NULL` onde o domÃ­nio exige.
- Log nÃ£o recebe conteÃºdo de memÃ³ria, prompt ou saÃ­da do usuÃ¡rio.

## ExecuÃ§Ã£o

- O processo da API **nÃ£o executa comandos do usuÃ¡rio** e nÃ£o deve ganhar essa capacidade.
- `TerminalExecutionGateway` Ã© uma porta sem implementaÃ§Ã£o. Quando existir, executa em container
  isolado, com sistema de arquivos prÃ³prio, sem rede para os serviÃ§os da plataforma e sem acesso a
  nenhuma credencial.
- A porta foi declarada agora justamente para manter esse requisito visÃ­vel, em vez de deixar um
  caminho de execuÃ§Ã£o crescer dentro do processo principal por acidente.

## Modelos externos

- SaÃ­da de LLM Ã© dado nÃ£o confiÃ¡vel. Passa pelo Output Analyzer; nunca Ã© executada nem gravada
  direto na memÃ³ria.
- MemÃ³ria proposta por modelo exige validaÃ§Ã£o antes de virar contexto oficial.
- IntegraÃ§Ãµes recebem credencial de menor privilÃ©gio possÃ­vel.

## Ainda nÃ£o existe

Sem autenticaÃ§Ã£o, autorizaÃ§Ã£o, multi-tenancy ou rate limiting â€” a API Ã© de uso local nesta fase.
**Isto precisa existir antes de qualquer exposiÃ§Ã£o em rede**, e Ã© o primeiro item de seguranÃ§a da
prÃ³xima fase.

## Identity and access

- Browser authentication uses Spring Security server-side sessions; JWTs and browser token storage are not used.
- CSRF remains enabled for every unsafe method, including login, registration, mutations, and logout.
- Projects are owner-scoped in database queries and authorized again in the application layer.
- Private resources belonging to another user return 404 consistently to avoid revealing existence.
- `ADMIN` has no implicit access to user projects; future administrative access requires a documented, tested policy.
- Email normalization is trim plus locale-independent lowercase. Passwords accept Unicode passphrases from 10 to 64 code points and are stored only through `DelegatingPasswordEncoder`.
- Pre-identity projects are preserved without an owner and denied to all users until explicitly assigned to a truthful owner.