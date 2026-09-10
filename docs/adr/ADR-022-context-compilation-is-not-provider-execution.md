# ADR-022: Compilar contexto não é executar num provedor

**Status:** aceito · **Data:** 2026-09-09 · **Fase:** 6

## Contexto

O Context Engine monta um `ContextPack`: o conjunto de itens que um modelo *receberia* para
trabalhar numa tarefa. É natural ler isso como "o contexto foi enviado". Não foi, e a distância
entre as duas leituras é onde mora o risco.

Um pacote compilado é uma decisão registrada. Uma chamada a provedor é uma transmissão para fora da
plataforma, que gasta dinheiro, sai do controle de quem clicou e não pode ser desfeita. Tratar a
primeira como se autorizasse a segunda significaria que qualquer caminho que monta contexto — um
job, um retry, um teste, uma tela — é um caminho que fala com a OpenAI.

Esta fase precisava decidir isso antes de existir qualquer adaptador, e não depois.

## Decisão

**A criação de um `ContextPack` não autoriza transmissão nenhuma.** São dois atos separados, e a
Fase 6 implementa somente o primeiro.

O que existe hoje, verificável no código:

- Não há cliente HTTP de saída no backend. `RestTemplate`, `WebClient`, `HttpClient`, OkHttp, Feign
  e `java.net.http` não aparecem em nenhum arquivo de `apps/api/src/main/java`. Não há hostname de
  provedor em lugar nenhum de `main`.
- O módulo `context` não importa `com.vibecode.vault` nem `com.vibecode.provider`. Suas únicas
  dependências entre módulos são `brain`, `guardian`, `output`, `project`, `roadmap`, `shared`,
  `state` e `task` — todas fontes de leitura.
- `ContextPackController` expõe três rotas: compilar, ler um pacote, listar pacotes. Não há uma
  quarta, e o cliente web declara exatamente essas três.
- `provider_accounts` guarda credencial, não executa nada. O estado de uma conta com credencial é
  `CREDENTIAL_STORED_UNVERIFIED` justamente porque nem a validação da chave chama o provedor
  (ADR-021).

A execução por provedor, quando existir, atravessa uma fronteira explícita. Essa fronteira — e não
o compilador, não a API de contexto, não a tela — passa a ser responsável por:

- **autorização**: quem mandou, e podia mandar
- **seleção de provedor e modelo**
- **acesso à credencial**, via `withSecret` (ADR-018), nunca por leitura de material
- **orçamento e custo**, que hoje só existe como contrato de domínio em `usage`
- **auditoria** do que saiu, para onde e quando
- **a transmissão em si**

Um `ContextPack` é entrada dessa fronteira. Não é gatilho dela.

## Alternativas consideradas

*Compilar e enviar no mesmo endpoint* — é o que a maioria dos produtos faz, e foi descartado porque
apaga a única janela em que alguém pode olhar o contexto antes de ele sair. O Context Inspector
(ADR-024) só faz sentido se essa janela existir.

*Deixar a fronteira implícita, para criar quando o adaptador chegar* — descartado porque a fronteira
é a parte difícil. Autorização, orçamento e auditoria são fáceis de esquecer quando o objetivo
imediato é "fazer a chamada funcionar", e caros de acrescentar depois que algo já funciona sem eles.

## Consequências

A Fase 6 termina com um motor de contexto completo e nenhuma chamada de IA. Isso é o resultado
pretendido, não uma pendência: nenhum `ContextPack` real deixou a plataforma.

O custo é que o produto ainda não faz a coisa pela qual existe. A Fase 7 terá que construir a
fronteira acima antes de qualquer adaptador, e a ordem importa — um adaptador que funciona é mais
difícil de submeter a uma fronteira depois do que antes.

Uma consequência menor e deliberada: a tela de contexto afirma que não há execução por provedor
*nesta versão*, e essa frase é sobre o cliente, que verificadamente não tem caminho para um modelo.
Quando a fronteira existir, essa frase precisa mudar junto — está escrita para envelhecer de forma
visível, não para continuar verdadeira por acidente.

Ver [ADR-023](ADR-023-context-is-deny-by-default.md) sobre o que entra num pacote e
[ADR-024](ADR-024-context-inspector-shows-only-stored-packs.md) sobre a janela de inspeção.
