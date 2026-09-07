# ADR-009: Autorização por posse do projeto

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 3

## Contexto

Até a fase 2, `GET /api/projects` devolvia todos os projetos do banco. Não havia usuário, então não
havia a quem pertencer. Com identidade, a pergunta "este usuário pode tocar neste projeto?" passa a
existir em toda requisição — e a forma errada de respondê-la é espalhar
`if (project.getOwnerId().equals(currentUser))` por vinte controllers, onde uma verificação
esquecida é um vazamento de dados.

Também não basta proteger `/projects/{id}`: brain, roadmap, fases, tarefas, evidências, propostas de
memória, guide, state e prompts pertencem indiretamente ao projeto. Conhecer um UUID não pode dar
acesso a nenhum deles.

## Decisão

`Project` ganha `ownerUserId`, e `ProjectAccessPolicy` é o único lugar que decide acesso, com três
níveis separados — `canRead`, `canWrite`, `canManage` — para que uma associação futura possa conceder
leitura sem escrita sem mexer nos chamadores.

A política é aplicada num único ponto de estrangulamento: `ProjectService.requireReadable` /
`requireWritable` / `requireManageable`. Todo módulo que pendura dados num projeto passa por ali. Um
portão só é uma coisa para auditar; uma verificação por controller é uma lista para esquecer.

`ADMIN` **não** recebe acesso a projetos alheios. Conceder isso é uma decisão real sobre quem pode
ler o trabalho de um estranho, e esta fase não tem requisito para tal — menor privilégio vence até
que algo concreto argumente o contrário.

## Consequências

Isolamento entre usuários é uma propriedade da camada de aplicação, não da interface. O frontend
poderia ser inteiramente reescrito sem afetar quem enxerga o quê.

O custo é que todo caminho novo precisa passar pelo portão. Os testes de IDOR existem exatamente
para pegar quem não passar: eles exercitam 23 tentativas de leitura e escrita por endpoint.

Enquanto não há membros além do dono, os três níveis são equivalentes. Mantê-los separados agora é
o que evita ter que redescobrir depois quais chamadas eram leitura e quais eram escrita.
