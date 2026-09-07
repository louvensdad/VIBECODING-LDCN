# Princípios Fundamentais

Estes princípios têm precedência sobre conveniência de implementação. Quando um deles conflitar
com uma feature, a feature muda.

1. **O usuário continua responsável pelo próprio software.** O VibeCode acompanha; não substitui.

2. **O Project Brain é o dono do estado.** Nenhum outro componente — e nenhum modelo — guarda a
   verdade do projeto.

3. **Um LLM nunca é fonte oficial de verdade.** Saída de modelo é entrada para análise, não fato.

4. **Providers são substituíveis.** Nenhum SDK de fornecedor pode vazar para fora do módulo
   `model`. Trocar de provider não pode exigir mudança em outro módulo.

5. **A memória sobrevive à troca de modelo.** É a razão de o Brain existir.

6. **Saídas são analisadas antes de avançar.** Uma alegação de conclusão não é evidência de
   conclusão. Ver [ADR-002](../adr/ADR-002-project-brain-owns-context.md).

7. **Erro impede avanço automático.** Evidência técnica de falha vence qualquer texto afirmando
   sucesso. O Next Step Engine responde `FIX_ERROR` e nenhuma funcionalidade nova é recomendada
   sobre um erro aberto. Ver [ADR-006](../adr/ADR-006-deterministic-next-step.md).

8. **Conclusão exige prova, e critério não se satisfaz sozinho.** Uma tarefa fecha apenas com
   dependências concluídas, critérios obrigatórios satisfeitos e evidência de sucesso. Um critério
   de aceite muda de estado só por decisão explícita e atribuída — jamais por inferência de um
   build verde. Ver [ADR-005](../adr/ADR-005-evidence-based-completion.md).

9. **Orientação é determinística e auditável.** O próximo passo é calculado do estado registrado,
   nunca perguntado a um modelo, e sempre acompanhado da razão. Ver
   [ADR-006](../adr/ADR-006-deterministic-next-step.md).

10. **Nenhum secret entra em prompt automaticamente.** Nem em log, nem em banco, nem em código.

11. **Custos separam fato de estimativa.** Um saldo exato e uma estimativa nunca são exibidos da
   mesma forma. Saldo desconhecido não recebe número inventado.

12. **Segurança faz parte do fluxo desde o início**, não de uma fase de endurecimento posterior.

13. **Fundação antes de features.** Nada do futuro é construído antes do que o sustenta.

14. **Nada derivável é persistido.** Progresso, prontidão de tarefa e status de fase são
    calculados. Estado duplicado é estado que diverge.

15. **Nenhuma abstração sem necessidade real.** Um contrato declarado sem implementação precisa
    justificar por que existe agora.
