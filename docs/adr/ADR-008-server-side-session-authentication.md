# ADR-008: Autenticação por sessão no servidor

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 3

## Contexto

O único cliente hoje é o aplicativo web. A escolha reflexa seria JWT, mas um JWT no navegador
precisa ser guardado em algum lugar, e todos os lugares disponíveis — `localStorage`,
`sessionStorage`, `IndexedDB` — são legíveis por JavaScript. Qualquer XSS, inclusive de uma
dependência transitiva, vira roubo de credencial de longa duração. Um JWT também não pode ser
revogado antes de expirar sem uma lista de revogação, que é exatamente o estado no servidor que o
JWT prometia evitar.

## Decisão

Sessão do lado do servidor com Spring Security. O identificador de sessão vive apenas em um cookie
`HttpOnly` — inacessível a JavaScript por construção. `Secure` é ligado onde há TLS
(`SESSION_COOKIE_SECURE`), `SameSite=Lax` mantém o cookie fora de requisições cross-site, o login
rotaciona o identificador (`ChangeSessionIdAuthenticationStrategy`) e o logout invalida a sessão.

Nenhum JWT. Nenhum token em armazenamento do navegador.

A camada de aplicação enxerga apenas `CurrentUser`, obtido por `CurrentUserProvider`. O domínio
nunca toca em `SecurityContextHolder`, `HttpServletRequest` ou `Principal`.

## Consequências

O backend é a fonte da verdade sobre quem está autenticado, e encerrar uma sessão é imediato.

O custo é estado no servidor: a sessão vive na memória do processo, então reiniciar a API
desconecta todo mundo e mais de uma instância exigiria armazenamento compartilhado de sessão. É um
custo aceitável para um único processo e uma decisão a revisitar quando houver mais de um.

Outro cliente — CLI, mobile — vai precisar de um mecanismo próprio. Isso não obriga a refazer nada:
esse mecanismo produz um `CurrentUser` e o resto da aplicação não percebe a diferença.
