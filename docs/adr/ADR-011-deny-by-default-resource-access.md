# ADR-011: Negar por padrão, e responder 404

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 3

## Contexto

Duas perguntas diferentes precisavam de resposta explícita.

A primeira: o que acontece com uma rota que ninguém lembrou de proteger? Se a regra for "liberado
salvo indicação contrária", cada endpoint novo é uma chance de vazar dados por esquecimento.

A segunda: o que responder quando alguém pede um projeto que existe mas não é dele? Um `403`
significa "existe, mas você não pode" — e isso é exatamente o fato que alguém varrendo UUIDs quer
descobrir. A resposta confirmaria a existência do recurso mesmo negando o conteúdo.

## Decisão

**Negar por padrão.** A cadeia de filtros termina em `anyRequest().authenticated()`. Público é
apenas o que é tecnicamente impossível de outro jeito: `POST /api/auth/register`,
`POST /api/auth/login`, `GET /api/auth/csrf` e `GET /actuator/health`. Todo o resto do actuator é
`denyAll()`, inclusive para usuário autenticado — `env`, `configprops`, `heapdump`, `beans` e
`mappings` entregam informação que ninguém precisa para usar o produto.

**404 para recurso alheio.** Um projeto que o usuário não pode ver é reportado como inexistente. A
resposta é byte a byte igual à de um UUID que nunca existiu, e há teste garantindo isso. A regra é
consistente: não se alterna entre 403 e 404 conforme o endpoint.

`403` fica reservado para o que não revela existência de nada: falha de CSRF e endpoint negado.

Projetos anteriores à identidade ficam com `owner_user_id` nulo. A política não casa com ninguém,
então eles são inalcançáveis — preservados no banco, invisíveis para todos, até que alguém os
reivindique deliberadamente.

## Consequências

Esquecer de proteger uma rota nova a torna inacessível, não pública. O modo de falha aponta para o
lado seguro.

O custo é diagnóstico: durante o desenvolvimento, um `404` pode significar "id errado", "projeto de
outro usuário" ou "rota inexistente", e o log de `ACCESS_DENIED` é o que distingue os casos para
quem opera o sistema — sem entregar essa distinção a quem faz a requisição.
