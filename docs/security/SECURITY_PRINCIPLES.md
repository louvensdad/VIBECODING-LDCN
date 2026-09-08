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

## Abuso de autenticação

- **Dois limites independentes** em login e registro: por origem e por identificador. Ambos
  precisam permitir. Uma chave única `IP + email` daria ao atacante um bucket novo por email
  inventado.
- **Nunca lockout permanente por tentativa errada.** Isso permitiria bloquear a conta de uma
  vítima de propósito. O estado do limitador é temporário e não toca em `UserStatus`.
- **A chave do bucket é um digest com salt**, nunca o email; o salt vive só em memória.
- **A resposta 429 não revela nada**: nem qual limite disparou, nem quantas tentativas restam, nem
  se a conta existe.
- **Headers encaminhados não são confiáveis por padrão.** Só um proxy explicitamente listado é
  honrado, e apenas se ele sobrescrever `X-Forwarded-For` — o rewrite do Next.js não sobrescreve.

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

## Secrets são referências, nunca contexto

- **Um secret entra uma vez e não volta.** Não existe `GET /credential`, `GET /api-key` nem
  `GET /secrets/{id}/plaintext`, e não deve passar a existir. Um teste verifica que essas rotas
  continuam ausentes.
- **O que circula pela plataforma é uma referência** — um id e um propósito. `ProviderAccount`
  guarda o id; nunca a chave. Por isso a entidade pode ser lida, listada, serializada e auditada
  sem que nenhum desses caminhos toque em segredo.
- **Credencial nunca vira contexto.** Não vira `BrainEntry`, não entra em prompt, não entra em
  task, evidência ou finding. O Prompt Builder não tem acesso ao Vault, e não é por disciplina: a
  dependência não existe.
- **Plaintext só existe entre a requisição e a fronteira de cifragem.** Nunca em entidade, DTO de
  resposta, evento de auditoria, log, mensagem de exceção ou métrica.
- **Criptografia é padrão, nunca própria.** AES-256-GCM com nonce aleatório de 96 bits por
  operação e envelope encryption (DEK por versão, KEK por ambiente). Sem XOR, sem ECB, sem CBC não
  autenticado, sem Base64 chamado de criptografia.
- **O ciphertext é amarrado à sua identidade** por dados autenticados adicionais (secret, dono,
  propósito, versão). Uma linha copiada para outro usuário deixa de decifrar em vez de funcionar.
- **Falha de decifragem tem sempre a mesma mensagem.** Distinguir chave errada de ciphertext
  adulterado seria um oráculo.
- **Master key ausente ou inválida derruba a aplicação.** Nunca gerar uma: a aplicação subiria
  saudável e todo secret anterior ficaria ilegível para sempre. Nenhuma mensagem de erro ecoa a
  chave.
- **Rotação nunca deixa a conta sem credencial.** A nova versão é gravada e durável antes de a
  versão em vigor mudar, e a troca é uma atualização de uma coluna só.
- **Remoção destrói a data key embrulhada**, o que torna o ciphertext irrecuperável mesmo para quem
  tenha a master key. Backups anteriores continuam contendo o que capturaram.
- **Nenhum pedaço da chave volta ao cliente.** Sem prefixo, sem últimos quatro caracteres, sem
  fingerprint — é justamente o pedaço que confirma qual chave está ali, e o que sobrevive num print
  de tela.
- **O estado é honesto.** `CREDENTIAL_STORED_UNVERIFIED`, nunca `CONNECTED`: nada foi enviado ao
  provider, então nada se sabe sobre a chave funcionar.
- **A credencial não é persistida no navegador.** Fica em estado do componente enquanto o
  formulário está aberto e é limpa quando a requisição resolve, com sucesso ou com erro. Nunca em
  `localStorage`, `sessionStorage` ou `IndexedDB`.

## Ainda não existe

- **Limitador distribuído.** O rate limiting de login e registro existe, mas o estado vive no
  processo: com mais de uma instância, cada uma concede a cota inteira. Ver
  `MULTI_INSTANCE_RATE_LIMIT_STORE_REQUIRED`.
- **CAPTCHA** e proteção contra credential stuffing distribuído (muitas origens, uma tentativa
  cada). Fica abaixo dos dois limites por construção.
- **Multi-tenancy** — organizações, times e associação de projeto. Existe apenas dono único.
- **MFA** e **recuperação de senha**: quem perde a senha perde a conta.
- **Revogação distribuída de sessão**: a sessão vive em memória do processo; reiniciar a API
  desconecta todo mundo, e não há como encerrar a sessão de um dispositivo específico.
- **KMS externo.** A master key vem do ambiente e é tão protegida quanto o ambiente. O provedor
  local é de desenvolvimento; usá-lo em produção exige uma opção escrita à mão.
- **Rotação da master key.** O envelope torna isso barato — bastaria re-embrulhar as data keys —
  mas a rotina não existe. `key_version` já é gravado em cada linha para quando existir.
- **Validação de credencial.** Guardar uma chave não prova que ela funciona; um erro de digitação
  só aparecerá quando um provider for efetivamente chamado.
- **Chamada real a provider.** Nenhum modelo externo é contatado nesta fase.

## Identity and access

- Browser authentication uses Spring Security server-side sessions; JWTs and browser token storage are not used.
- CSRF remains enabled for every unsafe method, including login, registration, mutations, and logout.
- Projects are owner-scoped in database queries and authorized again in the application layer.
- Private resources belonging to another user return 404 consistently to avoid revealing existence.
- `ADMIN` has no implicit access to user projects; future administrative access requires a documented, tested policy.
- Email normalization is trim plus locale-independent lowercase. Passwords accept Unicode passphrases from 10 to 64 code points and are stored only through `DelegatingPasswordEncoder`.
- Pre-identity projects are preserved without an owner and denied to all users until explicitly assigned to a truthful owner.