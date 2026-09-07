# ADR-001: Começar como monólito modular

**Status:** aceito · **Data:** 2026-09-07

## Contexto

O VibeCode tem muitos módulos previstos — brain, roadmap, guide, prompt, output, model, usage,
guardian, terminal, integration, wellness. A quantidade de módulos sugere microservices, mas o
produto ainda não tem nenhuma fronteira de escala, time ou disponibilidade que os justifique.

## Decisão

Um único deployment Spring Boot e um único PostgreSQL, organizados por módulos de domínio dentro
do pacote `com.vibecode`. Cada módulo separa `domain`, `application`, `infrastructure` e `web`
quando tem implementação.

Não entram nesta fase: Kafka, Kubernetes, service discovery, API gateway distribuído ou múltiplos
bancos.

## Consequências

Bom: o contexto do projeto fica coerente numa transação só; iteração rápida; nada de operação
distribuída antes de haver problema distribuído.

Custo: os módulos compartilham processo e banco, então a disciplina de fronteira depende de
revisão, não do compilador. A direção das dependências (documentada em
[ARCHITECTURE.md](../architecture/ARCHITECTURE.md)) é mantida deliberadamente rasa para que a
extração futura de um módulo seja possível.

Um módulo só será extraído quando tiver uma razão própria — escala, isolamento de falha ou
propriedade de time — registrada em novo ADR.
