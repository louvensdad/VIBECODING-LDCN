# ADR-025: Só mecanismos oficiais de conexão

**Status:** aceito · **Data:** 2026-09-09 · **Fase:** 7

## Contexto

O produto quer "conectar sua conta de IA". Existe um caminho tentador para isso que funciona hoje e
é indefensável: pedir a senha do usuário, ou importar o cookie de sessão de `chatgpt.com`, e agir
como se fosse o navegador dele.

Funciona. Dá acesso à assinatura de consumidor sem passar por API nenhuma. E é a razão pela qual
precisa estar escrito antes de alguém tentar sob pressão de prazo.

## Decisão

**O VibeCode nunca pede a senha da conta do usuário, e nunca coleta, armazena ou intermedia
credencial que autentique um plano de consumidor de provedor de IA.**

A proibição é **por princípio, não por artefato**. Uma lista de artefatos — cookie, token de
navegador — é contornada pela primeira credencial com codificação nova. E foi: um token de CI quase
entrou nesta arquitetura como "decisão de produto pendente", porque não era um cookie. A regra é:

> Nenhuma credencial que autentique uma **assinatura de consumidor**, em qualquer codificação.

Cobre hoje: senha; cookie de `chatgpt.com`, `claude.ai`, `gemini.google.com`, `deepseek.com`; token
de sessão extraído do navegador; automação de login não oficial; engenharia reversa de sessão;
**e o token de `claude setup-token` / `CLAUDE_CODE_OAUTH_TOKEN`**, que autentica um plano Pro/Max.

Permitidos — **lista fechada**: `OAUTH_ACCOUNT`, `API_CREDENTIAL`, `LOCAL_TOOL`, `LOCAL_BRIDGE`,
`IDENTITY_ONLY` (que não executa nada). **Mecanismo novo entra só por ADR próprio**, nomeando a URL
da documentação do fornecedor e o raio de alcance da credencial. Sem esse portão, "mecanismo oficial
futuro" transforma uma allowlist fechada em aberta, que é o oposto do
[ADR-011](ADR-011-deny-by-default-resource-access.md).

**Um provedor sem mecanismo oficial não é conectável**, e a interface diz isso.

### O que a documentação oficial diz, verificado em 2026-09-09

**A Anthropic veda por escrito**, na página legal do Claude Code:

> *"Anthropic does not permit third-party developers to offer Claude.ai login into their own
> applications, or to route requests through Free, Pro, or Max plan credentials on behalf of their
> users. Moreover, developers may not collect, store, or intermediate Claude.ai credentials or
> session tokens — sign-in to a Claude account must complete through Anthropic's own flow."*

Isso encerra o `setup-token`: não é decisão de produto, é proibição do fornecedor. A mesma página
valida o caminho do [ADR-028](ADR-028-local-tool-credentials-stay-local.md) — um usuário assinando no
binário **não modificado** do Claude Code com a própria assinatura é explicitamente contemplado.

**A OpenAI não delega assinatura a terceiros.** A documentação do Codex não descreve mecanismo pelo
qual uma aplicação servidora de terceiro use a assinatura ChatGPT do usuário. Existe **Sign in with
ChatGPT** — produto real, em beta desde agosto de 2026 — mas concede **identidade apenas**: nome,
e-mail, foto. É `IDENTITY_ONLY`, e classificá-lo como `OAUTH_ACCOUNT` seria exatamente errado.

Portanto **"Conectar ChatGPT" e "Conectar OpenAI API" não são o mesmo botão**, e nenhum desenho de UI
transforma um no outro.

### Como uma credencial proibida é recusada na prática

Um campo de texto livre aceita qualquer coisa, inclusive um cookie colado. Cada adaptador declara a
**forma** da credencial que aceita e valida **antes** de escrever no Vault. O controle negativo do
teste é uma string de cookie de sessão real, recusada — não um teste cujo mecanismo ninguém
especificou.

## Alternativas consideradas

*Um "modo avançado" com cookie, para usuários que aceitem o risco* — descartado. O usuário não tem
como avaliar esse risco: ele não sabe que o cookie dá acesso a todo o histórico da conta, que
qualquer comprometimento do VibeCode vaza sessões de consumidor, e que o provedor pode encerrar a
conta dele por violação de termos. Consentimento sobre um risco que a pessoa não pode dimensionar
não é consentimento.

*Esperar para decidir caso a caso* — é o mesmo que decidir sim, na primeira vez que um provedor
importante não tiver OAuth e a data apertar.

## Consequências

A matriz de provedores fica desigual, e a interface mostra isso: Gemini, OpenAI, Claude e DeepSeek
conectam por **chave de API** — nenhum provedor da matriz tem hoje OAuth de conta confirmado para
inferência — e Codex e Claude Code conectam pela máquina do usuário.
Um produto que apresentasse quatro botões idênticos estaria mentindo sobre quatro mecanismos
diferentes.

O custo é real: o usuário com ChatGPT Plus não consegue usar a assinatura dele pelo VibeCode. Isso
não é limitação do VibeCode, é ausência de mecanismo na OpenAI, e a tela diz exatamente isso em vez
de fingir que a culpa é de configuração.

O benefício é que nada nesta arquitetura depende de comportamento não suportado, que some sem aviso
quando um provedor muda o front-end.

Ver [ADR-018](ADR-018-vault-has-no-read-api.md) e o
[AI Connection Hub](../architecture/AI_CONNECTION_HUB.md).
