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
   - MOUSE_DRAG: mantém um toque no ponto configurado enquanto a tecla está pressionada e
     aplica os deltas físicos do mouse a esse dedo.
4. Só age com o jogo confirmado em primeiro plano. O serviço tenta observar mudanças de tarefa
   com `ITaskStackListener` via reflexão e mantém `dumpsys activity activities` como fallback
   (5 s quando registrado; 1,5 s se indisponível). Se não confirmar o foco, solta toques e libera capturas.
5. Ao abrir o editor, o motor continua lendo o mouse, libera dedos virtuais do jogo e ativa o
   modo editor. O socket local encaminha botões e movimentos ao overlay; ao fechar, o JSON é
   atualizado no Binder sem reiniciar o motor, preservando a posição do cursor.
6. O serviço devolve uma mensagem de status que o app mostra em Toast.

## Visibilidade e mouse (desde 0.5.0; estado atual 0.7.2)
- O serviço observa foco por `ITaskStackListener` refletido e mantém polling de fallback;
  o app consulta o estado a cada 250 ms quando o cursor está visível e 750 ms quando oculto.
  Só o estado confirmado como foreground mantém captura; estados desconhecidos liberam entradas.
- Mouse FPS (`MOUSE_LOOK`): a tecla configurada (padrão `ALT_LEFT`) alterna MIRA e CURSOR.
  A mira injeta movimento relativo; suspender solta o dedo. O cursor do app preserva a posição,
  é atualizado por socket local e desenhado em janela própria. Android ainda pode manter seu
  ponteiro nativo se o fabricante bloquear `EVIOCGRAB`.
- Captura mouse/teclado usa `EVIOCGRAB` sem fechar os streams. Mouse é capturado apenas quando
  o keymap tem controle de mouse; captura de teclado é opcional. O menu → Diagnóstico mostra
  disponibilidade da API de foco, dispositivos, estado de captura e erros.

## Captura e teclado (0.7.0–0.7.2)
- O serviço só captura o mouse se houver controle Mouse FPS, Arrasto Mouse, tecla de mouse ou
  tecla de emergência configurada como botão do mouse. A captura é alternada em cada fd sem
  fechar/reabrir os streams. `EVIOCGRAB` recebe o valor inteiro (0/1), e os nós são agrupados
  por `EVIOCGPHYS`/`EVIOCGUNIQ` com fallback para identidade do dispositivo.
- O cursor considera capturado o nó REL_X/REL_Y, mesmo quando outros nós do mouse falham.
  Sua posição chega pelo socket local, é coalescida e desenhada em janela de tela cheia atualizada
  por frame; opacidade limitada permite toque-through em Android 12+;
  o estado mostra o indicador MIRA/CURSOR. Suspender/retomar preserva a última posição do cursor.
- A roda vertical/horizontal é injetada como `ACTION_SCROLL` quando não houver ação mapeada.
  É possível capturar a roda no editor e associá-la a um controle. No modo cursor, o botão
  direito envia Voltar ou segura um toque longo (opção do editor), e o botão do meio toca
  na posição do cursor; um mapeamento explícito desses botões substitui o padrão.
- O editor permite capturar uma tecla de emergência (padrão F12), que libera entradas e pausa
  o motor. A captura do teclado é opcional e vem desativada: quando ativada, teclas sem mapa
  são repassadas por KeyEvent; Enter libera o teclado para digitação até ser solto.
- O foco é observado por callback interno via reflexão, com fallback de polling. A disponibilidade
  e assinatura das APIs internas variam por Android/OEM; se o registro falhar, o diagnóstico
  informa o motivo e o polling continua. É necessário validar em aparelho.

## Tamanhos (FloatingBubbleService.kt, companion)
`KEY_SIZE_DP = 40` (ajustável por controle entre 50% e 200%), `JOYSTICK_SIZE_DP = 88`
(× raio ajustável entre 20% e 150%), `JOYSTICK_MIN_DP = 24`.

## Histórico
- 0.7.2 — O editor deixa de parar o motor: modo editor mantém a captura e envia eventos de botão
  pelo socket para interação com marcadores; ao sair, atualiza o mapeamento sem reinício. O
  mouse passa por curva progressiva de sensibilidade 0–100, filtro temporal com decaimento e
  recentralização por handoff em região limitada. Preferência para MIRA/CURSOR ao iniciar,
  migração única de escalas preservando os demais campos do JSON, tokens visuais em
  `B13Theme.kt`, menu hexagonal e barra compacta. Compilação local não validada: o NDK
  configurado está incompleto e o SDK Manager ficou sem espaço ao tentar instalá-lo. Requer
  teste em aparelho, especialmente EVIOCGRAB, passagem de toques e handoff.
- 0.7.1 — Corrigido o acúmulo de janelas do cursor: ao abrir o editor, a janela dedicada
  agora é ocultada e reutilizada, sem perder sua referência nem criar setas extras. Painéis
  de configuração/importação ficam acima da barra do editor, com dimensões mais compactas;
  ajustes dos sliders só são salvos ao soltar o controle. O Mouse FPS injeta no máximo a
  125 Hz, limita as coordenadas do toque aos pixels reais da tela, envia movimentos sem
  manter o lock global do motor e usa suavização baseada no tempo entre amostras para
  manter resposta consistente. A navegação e os cartões da
  biblioteca foram compactados e reorganizados visualmente. Necessário validar em aparelho,
  sobretudo a mira ao chegar às bordas físicas da tela.
- 0.7.0 — Captura do mouse condicionada a controles de mouse; identificação/agrupamento de nós
  físicos, captura considerada pelo nó REL_X/REL_Y, `EVIOCGRAB` corrigido e alternância sem
  reabrir streams. Foco por `ITaskStackListener` refletido com fallback e liberação segura ao
  perder foco. Cursor separado em janela própria e atualizado por socket local, sem polling Binder
  a 16 ms; posição preservada e indicador MIRA/CURSOR. Scroll passa a ser injetado/mapeável.
  Inclui tecla de emergência configurável (F12), captura opcional do teclado, repasse de teclas
  sem mapa e modo chat por Enter; no cursor, botão direito configurável (Voltar/toque longo)
  e botão do meio = toque.
- 0.6.6 — Sensibilidades X/Y e velocidade do cursor passam a usar escala 0–100;
  mapeamentos em formatos anteriores são convertidos para manter a resposta equivalente.
  Suavização da mira é limitada sem drenar deltas em quadros posteriores, reduzindo a
  latência ao mover a câmera enquanto segura o tiro. Alternância do Mouse FPS só ocorre
  uma vez por pressão física da tecla, agregando eventos de todos os dispositivos e
  liberando teclas ativas quando uma entrada desconecta ou o jogo perde foco. A seta
  responde mais rápido e tentativas de captura do mouse são refeitas quando falham.
- 0.6.5 — Sensibilidade X/Y do Mouse FPS passa a 0–3000 (1000 = escala anterior) e inclui
  suavização ajustável 0–100; os mapeamentos antigos são migrados ao serem lidos/salvos.
  Cursor próprio centralizado ao suspender a mira; movimento processado em quadros de 4 ms.
  Adicionado Arrasto Mouse (tecla segura um toque que acompanha o mouse), toque duplo em
  área vazia para adicionar Toque, tamanho individual de teclas e raio de joystick ajustáveis.
  Contraste da biblioteca e estilo de botões/sliders refinados.
- 0.6.4 — Mouse FPS passa a acumular deltas além das bordas da tela, sem limitar a posição do
  dedo virtual; a entrada relativa segue fluida durante movimentos longos.
- 0.6.3 — Deltas da mira FPS suavizados em quadros curtos com preservação do movimento acumulado;
  cursor livre sem suavização e seta com elevação acima do painel lateral.
- 0.6.2 — Cursor do app inicia e volta a `(0,0)` ao alternar modos. O toque de tiro em HOLD
  permanece ativo enquanto o dedo independente do Mouse FPS move a câmera.
- 0.6.1 — Mouse FPS não recentraliza mais por raio: o dedo segue os deltas até as bordas reais
  da tela; só a tecla `MOUSE_LOOK` suspende/retoma a mira e recria o dedo na âncora.
- 0.5.6 — Remove `EVIOCGRAB`; deixa os eventos do hub seguirem pelo Android e lê o mesmo fluxo
  para teclado/mapeamento. Tenta aplicar um ícone PointerIcon próprio pelo Shizuku nas versões
  que mantêm a API global antiga; exibe cursor desenhado pelo app como fallback. O código do
  serviço Shizuku sobe para versão 7 para substituir instâncias antigas. Mantém a transição WASD
  com tolerância de 80 ms. Não compilado nem testado em aparelho.
- 0.5.5 — Joystick mantém o mesmo dedo por 80 ms ao ficar neutro para cobrir o intervalo entre key-up e key-down na troca rápida de WASD; preserva ordem de pressão sem reordenar teclas repetidas. EVIOCGRAB tenta sobrecargas com referência inteira, captura todos os nós EV_REL e mostra captura completa/parcial com falhas por nó. Cursor próprio fica como modo inicial depois de capturar o mouse.
- 0.5.2 — CORRIGIDO o erro "Keymap parado: process name suffix must not be null": o
  `Shizuku.UserServiceArgs` agora define `.processNameSuffix("input")` (obrigatório no Shizuku API 13)
  e `.version(6)` para o Shizuku trocar o serviço antigo. `bindUserService` captura qualquer erro
  (Throwable). EVIOCGRAB compatível com Android ≤9 (ioctlInt com Int32Ref). Permissão
  POST_NOTIFICATIONS (Android 13+) declarada e pedida ao abrir o app. NÃO compilado em aparelho.
- 0.5.1 — Bolha com borda de status (verde/amarelo/vermelho); reconexão automática; timeout de 8 s no
  bind do Shizuku (antes um serviço que não iniciava deixava tudo mudo); Diagnóstico com versão e
  relatório de conexão. Causa suspeita do "nada funciona": serviço no Shizuku não iniciando.
- 0.5.0 — Bolha/indicadores só com o jogo na frente; Mouse FPS corrigido (arrasto contínuo);
  mouse capturado + cursor próprio; Diagnóstico no menu. NÃO compilado/testado em aparelho.
- 0.4.0 — Componente Mouse FPS (REL_X/REL_Y → toque), sensibilidade X/Y no editor.
- 0.3.1 — Teclas menores; motor reescrito (leitura direta de /dev/input, multitoque real,
  joystick, nomes de tecla corrigidos, tamanho real da tela/rotação, motor pausado no editor,
  avisos de status). NÃO foi compilado nem testado em aparelho (sem Android SDK no ambiente):
  testar no celular via APK do GitHub Actions.
- 0.3.0 — Renomeado para B13 MAUSE PRO, tema vermelho, keymap só pela bolha.

## Limitações conhecidas / ideias
- Captura de entradas, injeção, socket do cursor e APIs internas de foco ainda precisam de
  teste em aparelho nas versões Android/OEM suportadas; APIs ocultas podem não estar disponíveis.
- Se o Shizuku rodar como ADB shell e o fabricante bloquear `/dev/input` ou `INJECT_EVENTS`,
  o app avisa no diagnóstico.
- Macros e controle de recuo continuam fora do escopo.
