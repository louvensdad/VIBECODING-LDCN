# ADR-028: Credencial de ferramenta local fica local

**Status:** aceito · **Data:** 2026-09-09 · **Fase:** 7

## Contexto

Codex CLI, Claude Code e Gemini CLI já resolvem autenticação, cada um pelo mecanismo oficial do seu
provedor — login por navegador com a conta, ou chave de API. O usuário que os tem instalados já está
autenticado.

O VibeCode quer orquestrá-los. A pergunta é o que ele precisa saber para isso.

A resposta tentadora é "a credencial da ferramenta, para poder chamá-la". Ela transformaria o
servidor do VibeCode num agregador de credenciais de provedor de três fabricantes — o alvo mais
valioso que esta arquitetura poderia construir, e sem necessidade nenhuma.

## Decisão

**O servidor do VibeCode não recebe credencial de provedor de ferramenta local. A ferramenta é dona
da própria autenticação.**

```
VibeCode Bridge (máquina do usuário)  ──outbound──▶  VibeCode Web/API
      │                                (credencial própria, não é do provedor)
      └─ comando ─▶ Codex CLI · Claude Code · Gemini CLI
                    (cada um com a própria autenticação, local)
```

**A seta aponta para fora, e isso é parte da decisão.** Um servidor que abrisse conexão para um
endereço de dispositivo fornecido pelo usuário seria primitiva de SSRF e de alcance a rede interna.
O Bridge disca; o servidor nunca disca de volta.

O Bridge envia um **comando de execução**. A ferramenta local usa a credencial que já tem. Nada de
provedor sobe.

O split que decide onde cada segredo mora:

| Conexão | Credencial vive em |
| --- | --- |
| `API_CREDENTIAL` | Vault do servidor — caminho da Fase 5 |
| `OAUTH_ACCOUNT` | Vault do servidor — access token, refresh token, expiry, escopos |
| `LOCAL_TOOL` / `LOCAL_BRIDGE` | **máquina do usuário, e só ela** |

**A autenticação do Bridge é mecanismo próprio.** Não se reutiliza credencial de provedor de IA para
autenticar o Bridge: são camadas diferentes, e juntá-las faria o comprometimento de uma virar o
comprometimento da outra. Conceitualmente: registro de dispositivo, credencial de sessão de vida
curta, rotação e revogação.

O Bridge cria uma fronteira de confiança nova e **não é confiável por padrão**. As regras de comando,
a direção da conexão e onde a confirmação humana acontece estão na §12 do
[AI Connection Hub](../architecture/AI_CONNECTION_HUB.md), em um lugar só. O que este ADR fixa é o
princípio de credencial, e um corolário que decorre dele:

**Identidade de ferramenta não implica identidade de provedor.** A DeepSeek documenta um endpoint
compatível com a API da Anthropic, de modo que uma ferramenta local rotulada "Claude Code" pode estar
executando contra a DeepSeek. O que o Bridge reporta é a ferramenta; o provedor não é inferido a
partir dela.

## Alternativas consideradas

*Subir a credencial da ferramenta e chamar a API do provedor direto do servidor* — descartado por
duas razões independentes. Primeiro, agrega credenciais de três provedores num alvo só. Segundo, e
mais decisivo: para Codex e Claude Code autenticados por conta, essa credencial é de sessão de
consumidor, e usá-la a partir de um servidor é exatamente o que o
[ADR-025](ADR-025-only-official-connection-mechanisms.md) proíbe.

*Deixar o Bridge sem autenticação própria, confiando na máquina do usuário* — descartado. "Está na
máquina dele" não é fronteira: qualquer processo local falaria com o Bridge, e um deles seria o
comprometido.

## Consequências

Execução local exige que o usuário instale e pareie o Bridge. É fricção real, e é o preço de o
servidor nunca ter tido a credencial para vazar.

O Bridge é grande — pareamento de dispositivo, transporte local, segurança de comando, empacotamento
multiplataforma, reconexão, atualização, autorização local. Por isso a recomendação é sub-fase
própria, depois da primeira execução remota real: estrear duas fronteiras de confiança na mesma wave
faria a que falhasse contaminar o diagnóstico da outra.

O modelo de domínio acomoda `LOCAL_TOOL` e `LOCAL_BRIDGE` **desde agora**, mesmo sem implementação.
O que evita o retrofit caro é o modelo, não o código.

Ver [ADR-025](ADR-025-only-official-connection-mechanisms.md),
[ADR-027](ADR-027-connecting-is-not-executing.md) e o
[AI Connection Hub](../architecture/AI_CONNECTION_HUB.md).
