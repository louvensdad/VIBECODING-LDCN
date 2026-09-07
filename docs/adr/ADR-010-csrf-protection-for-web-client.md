# ADR-010: Proteção CSRF para o cliente web

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 3

## Contexto

Autenticação por cookie tem um custo conhecido: o navegador envia o cookie de sessão em toda
requisição para a origem, inclusive nas que uma página de terceiros provocou. Sem proteção, um
formulário em outro site consegue criar, alterar ou apagar dados do usuário logado.

Desabilitar CSRF é o atalho comum — e é exatamente a porta que este produto não pode deixar aberta.

## Decisão

CSRF permanece ligado para todos os métodos não seguros, incluindo o logout (forçar o logout alheio
é um ataque real de incômodo).

O token vai num cookie legível por JavaScript (`XSRF-TOKEN`) e volta num cabeçalho
(`X-XSRF-TOKEN`). A assimetria é a proteção: uma página cross-site consegue fazer o navegador
*enviar* o cookie, mas não consegue *lê-lo*, então não consegue produzir o cabeçalho.

Dois detalhes de implementação foram necessários no Spring Security 6.4:

- `CsrfTokenRequestAttributeHandler` no lugar do padrão. O handler padrão aplica máscara XOR por
  requisição como proteção contra BREACH, e um cliente JavaScript não consegue reproduzir esse
  valor a partir do cookie.
- `setCsrfRequestAttributeName(null)` mais um filtro que força a resolução do token, para que o
  cookie seja de fato escrito na resposta.

O cliente HTTP do frontend é único (`lib/api-client.ts`) e anexa o token sozinho em todo método não
seguro — não há `fetch()` solto com configuração própria espalhado pela interface.

## Consequências

O fluxo real é exercitado nos testes: requisição sem token é recusada, com token errado é recusada,
com token válido passa. Nenhum teste desliga CSRF para ficar verde.

Um efeito colateral apareceu nos testes: o post-processor `csrf()` do `spring-security-test` troca o
repositório de tokens dentro do bean `CsrfFilter` compartilhado, e o contexto Spring é cacheado
entre classes. Uma vez que qualquer classe o usa, o `CookieCsrfTokenRepository` real para de emitir
o cookie para as demais. As classes que exercitam o fluxo genuíno rodam em contexto próprio
(`@TestPropertySource`) — enfraquecer o fluxo para os testes concordarem seria trocar a proteção
pela conveniência.

A proteção depende de mesma origem. Se o frontend passar a ser servido de outro domínio, esta
decisão precisa ser revisitada junto com a de cookies.
