# Visão de Produto

O VibeCode acompanha pessoas que desenvolvem software com apoio de LLMs. Ele organiza uma ideia,
transforma em etapas explícitas, preserva decisões e contexto, avalia a evidência que volta de
ferramentas externas e recomenda o próximo passo verificável.

## O que ele é

**Memória + navegador do projeto + orientador + analisador de saída + gerador de prompts.**

O usuário continua escrevendo o software. O VibeCode responde às perguntas que se perdem entre uma
sessão e outra: onde estamos, o que já foi feito, o que falta, o que fazer agora e por quê.

## O que ele não é

O VibeCode **não** cria o projeto do usuário automaticamente. Não existe autopilot, não existe
geração de software sem supervisão, e nenhuma etapa avança sem evidência.

## O fluxo central

```
IDEIA DO USUÁRIO
      ↓
PROJECT GUIDE  →  PROJECT BRAIN  →  ROADMAP  →  CURRENT STEP
                        ↑                            ↓
                        │                      PROMPT BUILDER
                        │                            ↓
                        │                   LLM EXTERNO OU INTEGRADO
                        │                            ↓
                        │                RESPOSTA / LOG / ERRO / BUILD
                        │                            ↓
                        └──────────────────  OUTPUT ANALYZER
                                                     ↓
                                            NEXT STEP ENGINE
                                                     ↓
                                               NOVO PROMPT
```

O ciclo sempre volta ao Project Brain. É por isso que trocar de modelo no meio do caminho não
custa nada ao projeto: o que o projeto sabe nunca esteve dentro do modelo.

## Estado desta fase

Implementado: projeto, Project Brain e o Output Analyzer determinístico. Todo o resto do fluxo
existe como contrato de domínio, sem implementação. Ver
[ARCHITECTURE.md](../architecture/ARCHITECTURE.md).
