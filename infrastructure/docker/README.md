# Docker

O `compose.yml` na raiz sobe apenas o PostgreSQL — é o único serviço com uso real nesta fase.

Este diretório existe para o que vem depois, e cada item entra somente quando houver um caso de
uso concreto:

- **Redis** — quando existir cache ou fila com necessidade demonstrada. Ainda não existe.
- **Sandbox de execução** — o container isolado por trás de `TerminalExecutionGateway`. Precisa de
  sistema de arquivos próprio, sem rede para os serviços da plataforma e sem acesso a nenhuma
  credencial. O processo da API nunca executa comandos do usuário.

Enquanto um serviço não tiver arquivo aqui, ele não faz parte da infraestrutura.
