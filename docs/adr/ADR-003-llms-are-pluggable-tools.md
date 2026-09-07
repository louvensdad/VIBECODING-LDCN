# ADR-003: LLMs são ferramentas plugáveis

**Status:** aceito · **Data:** 2026-09-07

## Contexto

Claude, ChatGPT, Gemini, DeepSeek e outros mudam de preço, de capacidade e de disponibilidade
constantemente. Um deles pode ser o melhor para uma etapa e inadequado para a seguinte. Amarrar a
plataforma a um SDK específico transformaria essa troca numa refatoração.

## Decisão

Todo acesso a modelo passa por uma única costura, no módulo `model`:

```java
public interface ModelProvider {
    ProviderId provider();
    List<ModelDescriptor> availableModels();
    ModelResponse execute(ModelRequest request);
}
```

`ModelRequest` e `ModelResponse` são neutros: nenhum tipo de SDK de fornecedor pode aparecer
neles. Nenhum outro módulo importa nada de fornecedor.

Nesta fase não existe implementação alguma — nem sequer uma dependência de SDK no `pom.xml`. A
interface existe para que a fundação já seja incapaz de depender de um fornecedor.

## Consequências

Adicionar um provider é escrever um adaptador e nada mais. Trocar de provider não altera brain,
roadmap, prompt ou output.

O custo é o denominador comum: capacidades específicas de um fornecedor não cabem na interface
diretamente. `ModelCapability` existe para declarar essas diferenças de forma explícita — e para
avisar o usuário quando um handoff perde uma capacidade — em vez de escondê-las.
