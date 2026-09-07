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

**Roadmap** — o caminho planejado: fases ordenadas, cada uma com etapas.

**Task** — uma etapa. Tem objetivo, dependências, **critério de conclusão** e riscos. Não fica
pronta porque alguém disse que ficou.

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

## Vigilância e bem-estar

**Guardian** — um dos sete vigias (segurança, custo, testes, arquitetura, privacidade,
dependências, performance). Observa e reporta; não altera o projeto nem bloqueia o usuário.

**Guardian Finding** — um risco observado, com recomendação acionável. Nunca cita o valor
sensível encontrado — aponta o local.

**Wellness Signal** — um lembrete opcional de pausa, água, café ou foco. Desligado por padrão.
