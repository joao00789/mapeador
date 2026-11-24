# CONTEXTO DO PROJETO — B13 MAUSE PRO (mapeador Android)

> Atualizar este arquivo a cada alteração feita no projeto.

## O que é
App Android (Kotlin + Jetpack Compose) inspirado no GG Mouse Pro. Abre um jogo com uma
bolha flutuante; pela bolha o usuário edita o keymap (teclas do teclado/mouse USB → toques
na tela do jogo). Usa Shizuku (UID shell) para ler os eventos USB e injetar toques.
Pacote `com.mapeador`, minSdk 24, compileSdk 35, build pelo GitHub Actions (`Android APK`).

## Arquivos principais
- `MainActivity.kt` — telas Compose (Iniciar jogo, Configurações), lança o jogo + serviço da bolha.
- `FloatingBubbleService.kt` — bolha, painel, editor do keymap (overlay), indicadores sobre o jogo,
  `mappingPayload()` (JSON enviado ao motor), `startEngine()`, `realScreenSize()`.
- `ShizukuInputBridge.kt` — `ShizukuInputBridge` (lado do app, bind/transact) e
  `ShizukuInputUserService` (roda no Shizuku: lê `/dev/input/event*`, trata teclas, injeta toques
  via `TouchInjector` = InputManager.injectInputEvent por reflexão).

## Como o keymap funciona (0.3.1)
1. Editor salva controles em SharedPreferences `mapeador` / chave `mapping` (JSON).
2. `startEngine()` manda ao serviço Shizuku: pacote do jogo, JSON (type, key, x, y, rangePx,
   stopOnOpposite) e o tamanho REAL da tela (x/y são frações da tela).
3. O serviço lê os eventos binários de `/dev/input/event*` (struct input_event, 24 bytes em 64 bits),
   converte o código Linux → nome Android (`LINUX_KEYS`, ex.: 42 → `SHIFT_LEFT`) e dispara:
   - TOUCH / MOUSE_PICK: toque DOWN ao apertar, UP ao soltar (segura = segura o toque).
   - SWIPE: arrasta 96 px na horizontal.
   - JOYSTICK: toque no centro e MOVE até `rangePx` na direção das teclas (WASD ou Setas).
4. Só age com o jogo em primeiro plano (`dumpsys activity activities`, a cada 1 s; se o formato
   for desconhecido, não bloqueia). Ao perder o foco, solta todos os toques.
5. Motor pausado enquanto o editor está aberto; reinicia ao fechar o editor ou ao mudar o mapeamento.
6. O serviço devolve uma mensagem de status que o app mostra em Toast.

## Tamanhos (FloatingBubbleService.kt, companion)
`KEY_SIZE_DP = 40`, `JOYSTICK_SIZE_DP = 88` (× raio 0.35–1.0), `JOYSTICK_MIN_DP = 56`.

## Histórico
- 0.3.1 — Teclas menores; motor reescrito (leitura direta de /dev/input, multitoque real,
  joystick, nomes de tecla corrigidos, tamanho real da tela/rotação, motor pausado no editor,
  avisos de status). NÃO foi compilado nem testado em aparelho (sem Android SDK no ambiente):
  testar no celular via APK do GitHub Actions.
- 0.3.0 — Renomeado para B13 MAUSE PRO, tema vermelho, keymap só pela bolha.

## Limitações conhecidas / ideias
- O mouse USB continua clicando no jogo por conta própria (não há "grab" do dispositivo).
- Macros, mira virtual, controle de recuo, scroll e modos de visão ainda não existem.
- Se o Shizuku rodar como ADB shell e o fabricante bloquear `/dev/input`, o app avisa.
