# Linguagem do Domínio

Estes termos têm um significado só. Código, documentação e interface usam o mesmo.

## Projeto e memória

**Project** — o projeto que o usuário está acompanhando. Possui identidade, estado e fase; não
possui conhecimento. O que se sabe sobre ele mora no Brain.

**Project Brain** — a memória oficial e estruturada de um projeto. É o dono do estado verdadeiro.
Modelo de leitura montado a partir das entradas.

**Brain Entry** — uma unidade de memória oficial: tipo, título, conteúdo, **source** e versão.
Append-only.

**Source** — de onde veio o conhecimento: uma pessoa, um modelo nomeado, um build. Obrigatório.
Memória sem procedência não pode ser avaliada depois.

**Memory Update Proposal** — memória *proposta*, tipicamente vinda de um LLM. Não é memória.
Precisa passar por validação para virar Brain Entry.

**Brain Snapshot** — cópia imutável da memória em um instante. Reservado para versionamento.

**Decision Record** — uma decisão junto com a razão dela. Persistida como entrada do tipo
`DECISION`.

## Execução e evidência

**Roadmap** — o caminho planejado. Um por projeto.

**Roadmap Phase** — uma etapa ordenada do roadmap ("Setup", "Authentication"). Seu status é
**derivado** das tarefas dentro dela, nunca digitado. Uma fase sem tarefas está `PLANNED`, não
concluída: trabalho planejado que ninguém detalhou continua pendente.

**Task** — uma tarefa. Tem objetivo, posição, risco, dependências e critérios de aceite. Não fica
pronta porque alguém disse que ficou.

**Task Status** — `PLANNED` (tem dependência aberta), `READY` (pode começar), `IN_PROGRESS`,
`BLOCKED`, `NEEDS_VALIDATION` (reportada pronta, sem prova suficiente), `COMPLETED`, `SKIPPED`.

**BLOCKED vs. falha** — dois conceitos diferentes, e a distinção importa. `BLOCKED` é obstáculo
externo: permissão, credencial, cota. Um build quebrado **não** é bloqueio — é trabalho normal em
andamento, e continua com o usuário.

**Task Dependency** — "esta tarefa não começa antes daquela terminar". Ciclos são recusados na
criação.

**Acceptance Criterion** — uma condição verificável que fecha a tarefa, escrita antes do trabalho
começar. Estados: `PENDING`, `SATISFIED`, `FAILED`, `UNKNOWN`. **Só muda por decisão explícita,
registrada com o nome de quem decidiu.** Um build verde prova que o código compila, não que a
condição foi verificada.

**Task Evidence** — algo que realmente aconteceu durante a tarefa: um build, um teste, um stack
trace, uma resposta de modelo. Append-only, sem setters.

**Output Analysis Record** — o veredito guardado sobre uma evidência, com os sinais técnicos
preservados — não só o resumo.

**Task Completion Policy** — a única porta para `COMPLETED`. Exige dependências satisfeitas,
critérios obrigatórios satisfeitos, ausência de evidência bloqueadora e evidência de sucesso. Diz
**o que falta** quando recusa.

**Project State** — a leitura consolidada de onde o projeto está. Montada a cada requisição;
`progressPercentage` é calculado, nunca gravado.

**Next Step Recommendation** — o que fazer agora, com `reason` obrigatório e as ações concretas.

**Memory Proposal Trigger** — o evento que originou uma proposta de memória: `TASK_COMPLETED`,
`ERROR_FOUND`, `ERROR_RESOLVED`, `CURRENT_STATE_CHANGED`, `NEXT_STEP_CHANGED`, `MANUAL`.

**Output** — evidência trazida pelo usuário: resposta de LLM, terminal, stack trace, build, teste,
log, resposta HTTP, SQL ou deploy.

**Output Analysis** — classificação determinística dessa evidência.

**Signal** — um indício encontrado na saída (`BUILD_SUCCESS`, `TESTS_FAILED`, `EXCEPTION`).

**Claim** — uma afirmação de conclusão sem evidência. Nunca vale como sucesso.

**Next Step Recommendation** — o que fazer agora, sempre acompanhado da razão e do que a
sustenta.

## Modelos e custo

**Provider** — um fornecedor externo de modelos. Substituível por definição.

**Model Descriptor** — um modelo concreto de um provider, com suas capacidades.

**Model Handoff** — passar o contexto oficial a um modelo diferente sem perder o que o projeto
sabe.

**Usage Event** — uma interação medida com um provider. Tokens são **fato**; custo é cálculo
local.

**Credit Snapshot** — quanto se acredita que uma conta tem, com a confiança explícita:

- `EXACT` — informado pelo provider. É fato.
- `ESTIMATED` — derivado do uso observado. É cálculo, e pode estar errado.
- `UNKNOWN` — sem base para afirmar. **Não carrega valor algum.**

**Cost Forecast** — projeção de gasto e autonomia. Sempre estimativa.

## Segurança

**Security Rule** — uma regra determinística que procura um problema específico. Nomeada
(`SEC-001`…`SEC-009`), testável isoladamente, sem modelo.

**Security Finding** — um problema registrado. Sua `evidence` é **sempre** a forma redigida: não
existe estado deste tipo que carregue um segredo vivo.

**Redaction** — a substituição do valor sensível por `[REDACTED]`, feita **antes** de qualquer
escrita. Nunca depois.

**Placeholder** — `${API_KEY}`, `<YOUR_API_KEY>`, `REPLACE_ME`. Não é segredo e não vira finding.

**Fingerprint** — identidade do problema, calculada sobre o texto já redigido. Repetir o mesmo
problema incrementa `occurrenceCount`; não cria um segundo finding.

**Security Score** — indicador operacional de 0 a 100 derivado dos findings abertos. **Não** é uma
porcentagem de segurança.

**Security Gate** — o veredito: `PASS`, `WARNING`, `REQUIRES_APPROVAL` ou `BLOCKED`. Um CRITICAL
aberto bloqueia.

**Accepted Risk** — decisão humana registrada com ator e justificativa. **CRITICAL não pode ser
aceito.**

**Prompt Security Status** — `SAFE`, `WARNING` ou `BLOCKED`, com `copyAllowed`. Bloqueado, o
conteúdo continua sendo devolvido — porém redigido.

**Audit Event** — registro append-only. Nunca contém senha, token, cookie, CSRF ou id de sessão.

## Vigilância e bem-estar

**Guardian** — um dos sete vigias (segurança, custo, testes, arquitetura, privacidade,
dependências, performance). Observa e reporta; não altera o projeto nem bloqueia o usuário.

**Guardian Finding** — um risco observado, com recomendação acionável. Nunca cita o valor
sensível encontrado — aponta o local.

**Wellness Signal** — um lembrete opcional de pausa, água, café ou foco. Desligado por padrão.
