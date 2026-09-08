# ADR-016: Proteção contra abuso automatizado na autenticação

**Status:** aceito · **Data:** 2026-09-08 · **Fase:** 4.5

## Contexto

Antes de guardar credenciais de providers externos, a porta de entrada da conta precisa resistir a
tentativa automatizada. Até aqui nada limitava `POST /api/auth/login` e `POST /api/auth/register`:
a política de senha é boa, mas sem limite de tentativas ela protege muito menos do que parece.

## Decisão

### Dois buckets independentes, não uma chave só

A chave óbvia seria `IP + email`. Ela falha: cada email inventado cria um bucket novo, então uma
única máquina percorre milhares de contas sem nunca encher nada.

Medimos duas coisas separadamente, e **ambas** precisam permitir:

- **origem** — volume de um cliente, quaisquer que sejam os identificadores;
- **identificador** — pressão sobre uma conta, venha de onde vier.

Isso cobre os dois ataques reais: uma origem contra muitas contas, e muitas origens contra uma
conta. Nenhum dos dois passa por um limitador de dimensão única.

A origem é verificada primeiro. Um cliente já acima do volume é recusado sem tocar no bucket da
conta — senão uma enxurrada poderia também drenar os buckets das contas que ela nomeasse.

### Sem lockout permanente

`UserStatus.LOCKED` continua sendo decisão administrativa. Se tentativas erradas bloqueassem a
conta, qualquer pessoa poderia bloquear a de outra de propósito — a proteção viraria a arma. O
estado do limitador é temporário e vive fora do usuário.

### Chave derivada, nunca o email

O limitador precisa contar por conta; não precisa saber qual conta. A chave é
`SHA-256(salt || email normalizado)`, com salt aleatório gerado por processo. Um digest simples não
bastaria: o espaço de emails é pequeno o suficiente para ser percorrido, e um heap dump viraria a
lista de quem andou tentando entrar.

Limitações aceitas: o salt vive só em memória e muda a cada reinício, então os buckets não
sobrevivem a um restart. Para um contador de abuso medido em minutos isso é adequado, e evita
introduzir um segredo gerenciado antes de existir onde guardá-lo.

### Implementação local, com interface para substituir

Token bucket próprio em vez de biblioteca. A implementação são ~60 linhas com refil contínuo, e o
que realmente precisávamos — um store **limitado** — teria exigido integração extra de qualquer
forma: um mapa que cresce com cada identificador inventado transforma a defesa no ataque.

O store é `LinkedHashMap` LRU com teto configurável, descarte por ociosidade e acesso
sincronizado. A operação inteira de verificar-e-consumir é atômica; um lock aqui não custa nada
perto do bcrypt que ele protege.

**`RateLimitStore` é interface porque o estado é por processo.** Com duas instâncias, cada uma
concede a cota inteira. Ver `MULTI_INSTANCE_RATE_LIMIT_STORE_REQUIRED` no threat model: substituir
a implementação é a mudança inteira necessária, e não pretendemos fingir que o limitador local é
global.

### Onde entra na cadeia

O limite de origem é um filtro logo após o CSRF: um cliente já barrado é recusado sem consulta ao
banco e sem cálculo de hash. O limite por identificador precisa do corpo da requisição, e roda no
controller — ainda antes de `authenticate()`, portanto ainda antes de qualquer bcrypt.

O filtro casa a rota pelo URI da requisição, não por `getServletPath()`: este último vem vazio em
alguns contêineres e muda com o mapeamento do servlet, e um filtro que silenciosamente deixa de
casar é um limitador que silenciosamente deixa de limitar.

## Consequências

Força bruta contra uma conta e volume a partir de uma origem passam a ser limitados, com evento de
auditoria e métrica em cada recusa, sem revelar se a conta existe.

O custo é real e deliberado: enquanto o bucket de uma conta está vazio, **nem a senha correta
entra**. É temporário por construção, e é o preço de não permitir que um atacante bloqueie a conta
de uma vítima de forma permanente.

Confiar em `X-Forwarded-For` sem proxy confiável configurado seria pior que não limitar nada —
ver `ClientOriginResolver`, onde isso está medido e documentado.
