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

## Guardian e auditoria

- **Redação antes da persistência.** Segredo detectado é removido antes de qualquer escrita, nunca
  limpo depois. Vale para evidência, finding, auditoria e prompt.
- **Inspeção automática.** Toda evidência criada e todo prompt gerado passam pelo Guardian. Não há
  botão de "verificar" — o segredo já teria sido gravado.
- **O prompt devolvido é sempre redigido**, inclusive quando bloqueado: recusar a cópia e exibir o
  segredo na tela vazaria do mesmo jeito.
- **CRITICAL não pode ser aceito como risco.** Severidades menores podem, com ator e justificativa
  registrados.
- **A auditoria é append-only e não contém credencial** — nem senha, token, cookie, CSRF ou id de
  sessão. Eventos de falha são gravados em transação própria, para que a exceção que os origina não
  os apague.

## Ainda não existe

- **Rate limiting** em login e registro. Nada no código limita tentativas hoje; um atacante pode
  testar senhas na velocidade da rede. É o primeiro item da fase de endurecimento, e nenhuma parte
  desta documentação deve ser lida como se já existisse proteção contra força bruta.
- **Multi-tenancy** — organizações, times e associação de projeto. Existe apenas dono único.
- **MFA** e **recuperação de senha**: quem perde a senha perde a conta.
- **Revogação distribuída de sessão**: a sessão vive em memória do processo; reiniciar a API
  desconecta todo mundo, e não há como encerrar a sessão de um dispositivo específico.

## Identity and access

- Browser authentication uses Spring Security server-side sessions; JWTs and browser token storage are not used.
- CSRF remains enabled for every unsafe method, including login, registration, mutations, and logout.
- Projects are owner-scoped in database queries and authorized again in the application layer.
- Private resources belonging to another user return 404 consistently to avoid revealing existence.
- `ADMIN` has no implicit access to user projects; future administrative access requires a documented, tested policy.
- Email normalization is trim plus locale-independent lowercase. Passwords accept Unicode passphrases from 10 to 64 code points and are stored only through `DelegatingPasswordEncoder`.
- Pre-identity projects are preserved without an owner and denied to all users until explicitly assigned to a truthful owner.