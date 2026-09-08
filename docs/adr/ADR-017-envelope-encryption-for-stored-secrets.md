# ADR-017: Envelope encryption com AES-256-GCM para secrets armazenados

**Status:** aceito · **Data:** 2026-09-07 · **Fase:** 5

## Contexto

A plataforma passa a receber credenciais de providers — chaves que pertencem ao usuário, que
custam dinheiro e que dão acesso a uma conta externa. Elas precisam ser guardadas, porque o
usuário não vai digitá-las de novo a cada requisição, e precisam sobreviver a um dump do banco.

## Decisão

### Cifra padrão, nunca própria

AES-256-GCM, da JCA. Não há XOR, não há AES-ECB, não há AES-CBC sem autenticação, não há Base64
apresentado como criptografia. Não escrevemos primitiva nova: uma cifra caseira parece funcionar em
todos os testes que o autor imagina, e falha exatamente nos que ele não imagina.

GCM é autenticado, e isso não é detalhe. Sem tag de autenticação, um ciphertext alterado
decifra para bytes corrompidos, e esses bytes seguiriam adiante como se fossem uma credencial. Com
tag, a alteração vira erro.

Nonce de 96 bits aleatório por operação, gerado com `SecureRandom`. Nonce fixo com a mesma chave
quebra GCM por completo — dois ciphertexts vazam o XOR dos plaintexts.

### Envelope: DEK por versão, KEK por ambiente

Cada versão de secret é cifrada com uma **data key** (DEK) nova. A DEK é embrulhada pela **key
encryption key** (KEK), que vive fora do banco.

Duas consequências práticas:

- rotacionar a KEK re-embrulha DEKs curtas, em vez de decifrar e recifrar todo secret armazenado;
- trocar o provedor de chave (hoje ambiente, amanhã um KMS) não toca em nenhum ciphertext já
  gravado.

E uma consequência de segurança: uma DEK recuperada abre uma versão de um secret, não o cofre.

### AAD amarra o ciphertext à sua identidade

Os dados autenticados adicionais são
`vibecode:vault:v1|secret=…|owner=…|purpose=…|version=…`.

Uma linha copiada para outro secret, ou para o secret de outro usuário, deixa de decifrar em vez de
funcionar em silêncio. Isso vale contra quem tem escrita no banco — um cenário realista, porque
quem consegue ler o banco frequentemente também consegue escrever nele.

O prefixo com versão de formato é o que permite mudar esse formato depois sem tornar as linhas
antigas ilegíveis.

### Cada linha carrega como foi cifrada

`algorithm`, `format_version`, `key_provider` e `key_version` ficam gravados ao lado do ciphertext.
"A gente sabe que usou AES" não é plano de recuperação: sem esses campos, a primeira mudança de
formato transforma migração em arqueologia.

## Consequências

Um dump do banco sozinho não revela nenhuma credencial. Quem tiver o dump **e** a master key
consegue ler tudo — daí a ADR-020.

Falha de decifragem é sempre a mesma mensagem, independentemente da causa. Distinguir "chave
errada" de "ciphertext adulterado" seria um oráculo que diz ao atacante qual das duas hipóteses
perseguir.
