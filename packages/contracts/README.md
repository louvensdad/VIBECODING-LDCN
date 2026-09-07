# @vibecode/contracts

Tipos TypeScript compartilhados entre `apps/web` e a API.

O arquivo `src/index.ts` contém **apenas tipos** — nada aqui gera JavaScript, então importar este
pacote não traz código de runtime para nenhum dos lados. As formas espelham os DTOs de
`apps/api`: quando um DTO muda, este arquivo é a segunda metade dessa mudança.

Consumido pelo alias `@vibecode/contracts`, configurado em `apps/web/tsconfig.json`.
