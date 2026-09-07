# ADR-014: Portão de segurança do prompt

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 4

## Contexto

O prompt é o ponto exato em que o contexto do projeto sai do VibeCode em direção a um sistema de
terceiros. Se um segredo chegou à evidência, é o prompt que o entrega — colado pelo próprio usuário,
sem nada nem ninguém no caminho.

## Decisão

Todo `GeneratedPrompt` passa por `PromptSecurityInspector` antes de voltar ao cliente. O resultado é
`SAFE`, `WARNING` ou `BLOCKED`, acompanhado de `copyAllowed`.

Duas decisões que parecem detalhes e não são:

**O conteúdo devolvido é sempre a versão redigida.** Recusar a cópia e ainda assim renderizar o
segredo na tela seria vazá-lo de qualquer forma — bastaria selecionar o texto. O bloqueio é sobre o
dado, não sobre o botão.

**Um prompt bloqueado explica o que travou.** Ele é prefixado com os findings abertos: severidade,
onde, evidência já redigida e ação recomendada. Uma recusa sem explicação leva o usuário a procurar
outro caminho para o mesmo objetivo, que é o oposto do que se quer.

O bloqueio considera duas fontes: o texto do próprio prompt e os findings CRITICAL já abertos no
projeto. Um segredo detectado ontem em outra evidência continua bloqueando hoje.

## Consequências

O frontend desabilita a cópia e mostra o motivo. Como o backend já redigiu o conteúdo, um cliente
mal-comportado — ou um `curl` direto na API — também não obtém o segredo.

O custo é atrito real: enquanto houver problema crítico aberto, nenhum prompt é copiável, mesmo os
que não têm relação com ele. É deliberado — um projeto com credencial exposta não deveria estar
mandando seu contexto para lugar nenhum.
