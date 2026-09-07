# ADR-015: Trilha de auditoria append-only

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 4

## Contexto

Decisões de segurança precisam sobreviver a quem as tomou. Quem aceitou aquele risco, quando, e com
que justificativa? Um registro que pode ser editado depois não responde a isso.

## Decisão

`AuditEvent` é append-only: sem setters, sem endpoint de alteração ou remoção, apenas leitura
escopada ao dono do projeto.

Eventos: criação, reconhecimento, resolução e aceitação de risco de findings; bloqueio do gate;
bloqueio de prompt; acesso negado entre usuários; login com sucesso, falha de login e logout.

**A trilha nunca contém credencial.** Nem senha, nem header `Authorization`, nem cookie, nem token
CSRF, nem id de sessão, nem chave de API. Há teste que verifica.

Um detalhe de implementação que se revelou essencial: `recordWithActor` roda em transação própria
(`REQUIRES_NEW`). Os eventos mais valiosos — acesso negado, falha de login — acontecem em caminhos
que terminam lançando exceção, e na transação do chamador o registro seria desfeito pela própria
exceção que ele existe para documentar. Sem isso, a trilha perdia silenciosamente exatamente os
eventos que um ataque produz.

## Alternativas consideradas

*Hash encadeado entre eventos* (à prova de adulteração) — adiaria: protege contra quem tem acesso ao
banco, e hoje quem tem acesso ao banco já tem tudo. Vale quando houver separação real de
privilégios operacionais.

## Consequências

Uma decisão de risco é rastreável até o ator e a justificativa. O dono do projeto vê inclusive as
tentativas de acesso negadas ao seu projeto — enquanto quem tentou continua recebendo 404 e não
descobre nada.

O custo é crescimento sem limite: não há retenção nem arquivamento. Vira problema com volume, e
resolvê-lo antes de existir volume seria construir um SIEM sem necessidade.
