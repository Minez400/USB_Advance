# Guia de Contribuição - USB Advance

Agradecemos o seu interesse em contribuir para o **USB Advance**! Este é um projeto de código aberto e colaborações de desenvolvedores, testadores e tradutores são muito bem-vindas.

---

## 🛠️ Como Começar

1. **Faça um Fork do Repositório**:
   Crie uma ramificação a partir da branch `main`.

2. **Padrões de Código**:
   * **Kotlin**: Siga o guia oficial do Kotlin e mantenha o código modularizado.
   * **Jetpack Compose**: Use StateFlow e funções composable de responsabilidade única.
   * **C++20 (NDK)**: Utilize smart pointers (`std::unique_ptr`, `std::shared_ptr`), evite vazamentos de memória e garanta alinhamento de 16 KB no linker.

3. **Submissão de Mudanças**:
   * Crie uma branch com nome descritivo: `git checkout -b feature/suporte-f2fs` ou `fix/scsi-timeout-recovery`.
   * Escreva testes unitários cobrindo novas funcionalidades.
   * Abra um Pull Request utilizando o modelo oficial.

---

## 🧪 Regras de Testes
Antes de submeter o seu Pull Request, certifique-se de que a suíte completa de testes passa localmente:
```bash
./gradlew check
./gradlew testDebugUnitTest
```
