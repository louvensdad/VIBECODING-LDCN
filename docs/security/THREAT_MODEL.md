# Initial Threat Model

| Threat | Impact | Current mitigation | Remaining gap |
|---|---|---|---|
| Credential theft | Account and project takeover | Passwords use delegated adaptive hashing; raw passwords are never stored or returned | MFA and compromised-password screening are future work |
| Password leakage | Cross-user exposure and account reuse | Hash-only schema, safe DTOs, no password logging, bounded passphrase policy | Formal log redaction tests and secret scanning remain |
| Session hijacking | Unauthorized project access | `HttpOnly` session cookie, production `Secure`, `SameSite=Lax`, fixation protection, invalidating logout | Distributed revocation and device/session management remain |
| CSRF | Unauthorized mutation through the user's browser | Spring Security CSRF with cookie/header pairing on all unsafe methods, including logout | Deployment must preserve same-origin and TLS |
| IDOR | Cross-user reads or writes | Central `ProjectAccessPolicy`; project children authorize transitively; foreign resources return 404 | Future memberships need equally explicit policy tests |
| Privilege escalation | User gains platform or project privileges | Registration always assigns `USER`; `ADMIN` has no cross-project shortcut | Administrative role assignment is intentionally absent |
| Account enumeration | Discovery of registered users | Login failures share the same status, code, and message | Registration conflict still reveals an existing account by product necessity; rate limiting is pending |
| Sensitive logging | Credentials or tokens enter logs | Structured events omit passwords, hashes, CSRF tokens, and session IDs | Production retention, PII minimization, and centralized audit storage remain |
| Cross-user data exposure | Project Brain, roadmap, tasks, or evidence leak | Owner-scoped queries plus service-layer authorization and Alice/Bob IDOR tests | Organizations and sharing are outside scope |
| Future LLM secret exposure | Provider secrets enter prompts or model output | No provider credentials exist; prompt context excludes environment and secret material | Future `ProviderAccount` secrets require authenticated ownership and encryption at rest |

## Explicit future rules

## Abuso de autenticação (fase 4.5)

| Threat | Impact | Current mitigation | Remaining gap |
|---|---|---|---|
| Brute force contra uma conta | Tomada de conta | Bucket por identificador, atingido venha de onde vier; a chave é um digest com salt, não o email | Um atacante muito lento continua abaixo do limite; sem MFA, a senha é o único fator |
| Volume a partir de uma origem | Enumeração e credential stuffing | Bucket por origem, independente dos identificadores usados | **Credential stuffing distribuído continua parcialmente mitigado**: muitas origens, uma tentativa cada, ficam abaixo dos dois limites |
| Lockout da vítima como ataque | Negação de serviço contra uma pessoa | O limitador tem estado próprio e temporário; `UserStatus.LOCKED` nunca é atribuído automaticamente | Enquanto o bucket está vazio, a senha correta também é recusada — temporário e deliberado |
| Falsificação de origem | Limitador contornado | Headers encaminhados são ignorados por padrão; só um proxy explicitamente confiável é honrado | **Medido**: o rewrite do Next.js repassa `X-Forwarded-For` do cliente, então nunca deve ser listado como confiável |
| Exaustão de memória pelo limitador | A defesa derruba o serviço | Store com teto configurável, descarte LRU e por ociosidade | Um bucket despejado volta cheio; o teto precisa ser grande o suficiente para o tráfego real |
| `MULTI_INSTANCE_RATE_LIMIT_STORE_REQUIRED` | Limite multiplicado pelo número de instâncias | Nenhuma — o store é por processo | **Não implementado.** Requisito bloqueante para escalar horizontalmente |

MFA: não implementado. CAPTCHA: não implementado. Limitador global distribuído: não implementado.

## Security Guardian (fase 4)

| Threat | Impact | Current mitigation | Remaining gap |
|---|---|---|---|
| Secret pasted into evidence | Credential stored by the platform and later handed to an external model | Deterministic inspection runs before persistence; evidence and findings store only redacted text; a test sweeps every column of every table for the raw value | Detection is pattern-based, so a secret in an unknown format is stored as pasted |
| Secret leaving in a prompt | Credential handed to a third-party model by the user | Every prompt is inspected; the returned content is always redacted and copying is refused while a critical finding is open | The user can still retype a secret by hand into an external tool |
| Security finding tampering | A risk decision is rewritten after the fact | Findings carry actor and reason; the audit trail is append-only with no update or delete route | No tamper-evident chaining; database access still implies full control |
| Audit trail losing the events that matter | An attack leaves no record | Denied access and failed login are written in their own transaction, so the failure they document cannot roll them back | No retention, archival or off-host shipping |
| Risk acceptance abuse | A critical problem is waved through | CRITICAL cannot be accepted as risk, in the backend and in the UI | Lower severities can be accepted by a single person with no review |

Provider accounts will belong to an authenticated user or organization. API keys, OAuth tokens, and refresh tokens must never be stored as plaintext. Login and registration need rate limiting in the hardening phase; no rate limiter is claimed in the current implementation.

## Secrets vault e provider accounts (fase 5)

| Threat | Impact | Current mitigation | Remaining gap |
|---|---|---|---|
| Dump do banco | Todas as credenciais de todos os usuários | Nenhuma coluna guarda plaintext; AES-256-GCM com data key por versão, embrulhada por uma KEK que vive fora do banco; um teste lê os bytes brutos de cada coluna procurando o valor | **Dump mais master key revela tudo.** O envelope reduz o custo de rotação, não essa exposição |
| Vazamento da master key | Equivale ao item acima | A chave nunca está no repositório, no `.env.example`, em log ou em mensagem de erro; o provedor local exige opção explícita fora de desenvolvimento | Sem KMS, sem HSM, sem rotação implementada |
| Perda da master key | Toda credencial guardada fica ilegível | A aplicação recusa subir sem ela, em vez de gerar uma nova e transformar a perda em surpresa posterior | **Consequência aceita**: não há custódia nem recuperação da chave |
| Adulteração de linha por quem tem escrita no banco | Credencial substituída, ou ciphertext movido para outro usuário | GCM autenticado rejeita ciphertext alterado; os dados autenticados adicionais amarram cada linha a secret, dono, propósito e versão | Quem tem escrita ainda pode apagar linhas e negar serviço |
| Vazamento por resposta HTTP | Credencial exposta a quem tiver sessão | Nenhum endpoint devolve material; a resposta tem só `hasCredential`; sem prefixo, últimos quatro ou fingerprint; testes varrem toda resposta atrás do valor e de fragmentos | Quem tem a sessão pode substituir a chave, ainda que não possa lê-la |
| Vazamento por log | Credencial indexada num agregador e retida por um ano | `SecretMaterial` tem `toString()` redigido; um teste captura tudo em DEBUG durante o ciclo completo e procura o valor e fragmentos | Log de infraestrutura fora do processo (proxy, banco) não é coberto por esse teste |
| Vazamento por auditoria | O registro projetado para ser lido por humanos carrega o segredo | O evento grava conta, provider e ação; nunca credencial, ciphertext, nonce, chave embrulhada ou fingerprint; um teste varre as colunas | — |
| Vazamento por prompt ou Project Brain | Credencial entregue a um modelo externo | O Prompt Builder não recebe `VaultService`; a dependência não existe | O usuário ainda pode digitar a chave à mão num prompt — o Guardian é a defesa aí |
| Rotação que deixa a conta sem credencial | Usuário perde acesso e não recupera a chave antiga | A nova versão é gravada e durável antes de o ponteiro mudar; a troca é uma coluna só; teste força falha de cifragem no meio | — |
| Credencial de outro usuário | Exposição entre contas | Consultas escopadas por dono, checagem na aplicação, 404 em vez de 403; AAD amarra o ciphertext ao dono | — |
| Estado enganoso | Usuário confia numa chave que não funciona | `CREDENTIAL_STORED_UNVERIFIED`, nunca `CONNECTED` | Um erro de digitação só aparece quando o provider for chamado |
| Backup antigo | Credencial removida continua num backup | A remoção sobrescreve a data key embrulhada, tornando o ciphertext irrecuperável no banco vivo | **Backups tomados antes disso continuam contendo o que capturaram**, até expirarem por sua própria política |
