# B13 MAUSE PRO — reescrita em C++ (v1.0.0)

App Android que transforma teclado e mouse USB conectados por OTG em um
mapeador de toques para jogos, usando [Shizuku](https://shizuku.rikka.app/)
para capturar `/dev/input` e injetar toques sem precisar de root.

## O que mudou nesta reescrita

A versão anterior (0.7.x) tinha toda a lógica do mapeador em Kotlin dentro do
serviço do Shizuku. Nesta reescrita:

- **O núcleo do mapeador agora é 100% C++** (`app/src/main/cpp/`): leitura de
  `/dev/input` via evdev, motor de keymap (toque, joystick, deslizar, mouse
  FPS, arrasto de mouse), e a injeção de toques/teclas no Android — tudo
  roda em código nativo, chamado via JNI.
- **Kotlin ficou só com o que o Android exige em Java/Kotlin**: a `Activity`,
  o `Service` da bolha flutuante, as janelas de overlay e o bind com o
  Shizuku. Essas classes não têm lógica de mapeamento — só repassam chamadas
  para o motor nativo.
- A detecção de "jogo em primeiro plano" passou a usar `dumpsys activity`
  (via um processo filho, chamado pelo C++) em vez de um listener reflexivo
  de `ITaskStackListener`, que era uma das partes mais frágeis da versão
  anterior.
- A interface (lista de jogos, configurações) foi reescrita em Jetpack
  Compose, mais simples que antes.
- O editor de keymap (que fica sobre o jogo) usa Views clássicas do Android
  em vez de Compose, porque um overlay de `WindowManager` fora de uma
  Activity não tem o ciclo de vida que o Compose exige.

## Interface mobile-first

A tela principal do B13 abre em orientação horizontal, com navegação compacta,
tema Material 3 personalizado e biblioteca adaptável em grade. A busca filtra
os apps salvos; o seletor consulta os apps instalados e exibe seus ícones
originais sem carregar o catálogo no thread de interface. A orientação é
definida somente para a atividade do B13 — ela não altera a orientação dos
jogos iniciados.

O indicador da biblioteca ainda reflete o mapeamento global existente; perfis
independentes por jogo e a migração do armazenamento continuam pendentes.

## Limitações conhecidas desta primeira versão

- **Nada aqui foi testado em aparelho ainda.** Foi escrito e revisado
  cuidadosamente, mas só compilar pelo GitHub Actions e instalar no celular
  vai mostrar os problemas reais — especialmente a parte de injeção de
  toques via reflexão (`InputManager.injectInputEvent`), que depende de como
  cada versão do Android trata APIs ocultas.
- O keymap é único e global (não um por jogo) — igual à versão anterior.
  Suporta os tipos: Toque, Deslizar, Mouse Pick, Joystick (WASD/Setas),
  Mouse FPS e Arrasto de Mouse.
- Capturar uma tecla física diretamente no editor (apontar e apertar a
  tecla) não foi reimplementado ainda — o editor usa uma lista de teclas
  comuns. Importar/exportar keymap usa compartilhar texto e colar da área
  de transferência, em vez de arquivo `.json`.
- A tecla de emergência (padrão `F12`) pausa e libera todos os toques.

## Compilar

Todo o build roda pelo GitHub Actions (`.github/workflows/android-apk.yml`):
basta dar push para `main` ou rodar o workflow manualmente. O APK debug fica
disponível como artifact da action. Localmente, é necessário o NDK
27.2.12479018 e o CMake 3.22.1 (configurados no `app/build.gradle.kts`).

## Estrutura

```
app/src/main/cpp/        núcleo do mapeador em C++ (motor, evdev, keymap, JNI)
app/src/main/java/...    casca Android: Activity, Service, editor overlay, bind Shizuku
```

Veja `CONTEXTO_PROJETO.md` para detalhes de arquitetura, pensados para quem
for continuar o desenvolvimento (inclusive uma futura sessão do Claude).
