# VibeCode

Plataforma que mantém o contexto oficial de projetos conduzidos com apoio de LLMs.

O Project Brain — e não um modelo externo — é o dono do estado do projeto. Modelos são ferramentas
substituíveis: a memória sobrevive à troca de qualquer um deles.

> **Fase atual: fundação.** Projeto, Project Brain e o Output Analyzer determinístico estão
> implementados. Os demais módulos existem como contrato de domínio, sem implementação. Nenhuma
> integração real com LLM foi construída.

## Executar localmente

```bash
cp .env.example .env          # ajuste se quiser; nada aqui é secret de produção

docker compose up -d postgres

cd apps/api && mvn spring-boot:run

cd apps/web && npm install && npm run dev
```

| | |
| --- | --- |
| API | http://localhost:8080 |
| Web | http://localhost:3000 |
| Saúde | http://localhost:8080/actuator/health |

## Endpoints

| Método | Rota | Finalidade |
| --- | --- | --- |
| `POST` | `/api/projects` | Cria um projeto acompanhado |
| `GET` | `/api/projects` | Lista projetos |
| `GET` | `/api/projects/{id}` | Lê um projeto |
| `GET` | `/api/projects/{id}/brain` | Lê a memória oficial, com contagem por tipo |
| `POST` | `/api/projects/{id}/brain/entries` | Escreve uma entrada na memória oficial |
| `POST` | `/api/projects/{id}/outputs/analyze` | Analisa uma saída em busca de evidência |

Exemplo — evidência técnica vence alegação de sucesso:

```bash
curl -X POST http://localhost:8080/api/projects/$ID/outputs/analyze \
  -H 'Content-Type: application/json' \
  -d '{"content":"Tudo concluído com sucesso!\nTests run: 8, Failures: 2, Errors: 0"}'

# {"status":"FAILURE","shouldContinue":false,"signals":["TESTS_FAILED","CLAIMED_COMPLETION"], ...}
```

## Testes

```bash
cd apps/api && mvn test      # 38 testes
cd apps/web && npm run build
```

## Estrutura

```
apps/api           Spring Boot, monólito modular (com.vibecode.*)
apps/web           Next.js, React, TypeScript, Tailwind
packages/contracts Tipos TypeScript compartilhados entre web e API
packages/ui        Reservado — sem segundo consumidor ainda
infrastructure/    Docker; Redis e sandbox entram com caso de uso real
docs/              Visão, princípios, arquitetura, domínio, segurança e ADRs
```

## Antes de expandir

Leia, nesta ordem:

1. [Princípios fundamentais](docs/constitution/CORE_PRINCIPLES.md) — o que não pode ser violado
2. [Arquitetura](docs/architecture/ARCHITECTURE.md) — estado real de cada módulo
3. [Linguagem do domínio](docs/domain/DOMAIN_LANGUAGE.md) — os termos têm um significado só
4. [Princípios de segurança](docs/security/SECURITY_PRINCIPLES.md)
