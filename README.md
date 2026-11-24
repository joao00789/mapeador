# B13 MAUSE PRO

Primeira versão em desenvolvimento do B13 MAUSE PRO, um mapeador Android com launcher
de jogos, editor de controles sobreposto e botão flutuante. A tela Iniciar jogo abre por
padrão (abas: Iniciar jogo, Keymap, Configurações); selecione um app instalado e toque em **Iniciar** para abrir o jogo com a
bolha do B13 MAUSE PRO.

Durante o jogo, toque na bolha B13 MAUSE PRO e escolha **Editar keymap neste jogo**.
No editor, use a barra sobre o jogo para adicionar componentes, salvar, importar,
compartilhar ou limpar o mapeamento. Arraste os indicadores redondos até as posições
correspondentes no HUD. Toque em um indicador para configurar a tecla; no joystick,
toque para ajustar o tamanho ou alterar o atalho. Mantenha um indicador pressionado
para removê-lo. Use **Sair** para fechar apenas o editor. Para encerrar a bolha e o
serviço, selecione **Desativar botão flutuante** no menu ou na notificação.

Para mapear teclas, inicie o Shizuku, autorize o B13 MAUSE PRO em **Ajustes → Acesso
Shizuku** e conecte o teclado e o mouse USB. O serviço remoto do Shizuku lê os
eventos de entrada e envia toques ao jogo; não é necessário ativar um serviço de
Acessibilidade. Se o Shizuku estiver rodando como ADB shell, o acesso aos dispositivos
de entrada depende da versão e das permissões do fabricante.

A biblioteca mostra os ícones dos apps instalados. No editor, Toque, Mouse Pick,
Deslizar, Joystick (WASD ou Setas) e Mouse FPS são executados. O Mouse FPS lê os deltas
REL_X/REL_Y do mouse e os converte em arrastos de dedo na área de câmera configurada.
A seta própria aparece ao iniciar o jogo, mesmo sem um componente Mouse FPS no keymap.
Com Mouse FPS, a mira começa ativa e a tecla configurada (Alt esquerdo por padrão)
alterna entre mira e cursor suspenso. Ajuste sensibilidades X/Y no marcador Mouse FPS.

Mouse próprio: o app tenta capturar os nós USB do mouse enquanto o jogo está na frente,
impedindo o Android de mover seu cursor por conta própria. No modo cursor, o botão esquerdo
gera um toque real na posição da seta do app; no modo mira, o movimento gera arrasto de dedo
na tela. Os deltas são agrupados em quadros curtos para evitar saturação de eventos, sem
descartar movimento ao recentralizar o toque. Ao sair do jogo, a captura é liberada. Se o
Android bloquear a captura, a mira ainda tenta usar os eventos relativos recebidos, mas o
cursor do sistema pode continuar visível. O menu Diagnóstico informa se o nó foi detectado
e capturado. O teclado continua sendo lido independentemente da captura do mouse.

## Gerar o APK

Cada envio para a branch `main` inicia o workflow **Android APK**. Também é possível
iniciá-lo manualmente em **Actions** no GitHub. Quando a execução terminar, baixe
`nexoplay-debug-apk` na seção **Artifacts** da execução.

O APK desta versão é de depuração e não está assinado para distribuição na Play Store.

## Novidades da 0.6.1

- Separa o modo cursor (mouse suspenso) do modo mira: o cursor próprio e os cliques de navegação
  só ficam ativos no modo cursor; na mira, os deltas controlam somente a câmera.
- Abrir o painel lateral suspende a mira automaticamente para permitir navegar com o mouse;
  fechar o painel restaura o modo anterior.
- Cancela o toque da mira ao recentralizar ou alternar modos, evitando que a soltura seja
  interpretada como clique; também limpa deltas pendentes para evitar saltos.

## Novidades da 0.6.0

- A seta do app aparece ao abrir um jogo mesmo sem adicionar Mouse FPS; quando a captura está
  ativa, ela substitui o cursor do Android.
- A seta é elevada sobre o painel lateral e acompanha o mouse também durante a mira FPS.
- Os movimentos são agrupados em quadros de 8 ms para suavizar a injeção; ao recentralizar
  o toque da câmera, o movimento restante não é descartado.

## Novidades da 0.5.8

- Mouse FPS começa em modo mira para mover a câmera sem exigir uma tecla de ativação inicial;
  o atalho configurado alterna para cursor próprio e suspende a mira.
- O botão esquerdo no modo cursor injeta um toque no ponto da seta do app. Eventos do mouse
  só são convertidos em ações quando o nó correspondente foi capturado, evitando cliques duplos.
- Os controles na prévia do editor ficaram menores.
- Diagnóstico agora mostra o modo do mouse e o último movimento relativo recebido.
- Mantém captura exclusiva do mouse USB e libera o dispositivo ao sair do jogo.

## Novidades da 0.5.9

- Detecta mouse pelos recursos reais dos nós evdev (movimento REL_X/REL_Y e botões), em vez
  de depender do nome/ordem informado pelo fabricante Android.
- A mira pode usar os deltas relativos recebidos mesmo se a captura exclusiva for bloqueada;
  nesse caso o ponteiro do sistema pode continuar visível e o Diagnóstico informa a falha.
- O Diagnóstico lista os nós identificados e diferencia movimento do mouse de nós de botões.

## Novidades da 0.2.0

- Tela de jogos refeita para paisagem (cartões em fileira rolável, botão **Iniciar jogo**,
  indicadores de mouse e teclado). Corrige a lista vazia depois de adicionar um jogo.
- Configurações no estilo GG Mouse Pro: Modo de Ativação (Shizuku), Assistente de ativação
  e Gerenciamento de permissões.
- Bolha flutuante abre um painel lateral com atalhos (editar keymap, indicadores, monitor
  de bateria/tempo/memória, abrir app, desativar). Mira virtual, macro e controle de recuo
  aparecem como "em breve".
- Editor com barra compacta (Sair, Adicionar componente, Salvar, Compartilhar, Importar,
  Limpar), seletor em 4 colunas e joystick com tipo WASD/Setas.

## Novidades da 0.3.0

- App renomeado para **B13 MAUSE PRO**, com a logo B13 e tema vermelho escuro.
- A aba Keymap saiu do app: o keymap agora é configurado só pela bolha flutuante.
- As teclas aparecem sobre o jogo como círculos com nomes curtos (L-Shift, Espaço, Mouse-D).
- O motor de teclas fica pausado enquanto o editor está aberto, para não disparar toques sem querer.

## Novidades da 0.3.1

- Teclas e joystick do keymap bem menores na tela (tecla 40 dp, joystick 88 dp por padrão).
- As teclas agora funcionam de verdade: o motor lê o teclado/mouse direto de `/dev/input` e
  injeta toques reais com multitoque (segurar a tecla = segurar o toque; soltar = soltar).
- Joystick WASD/Setas funciona (segura o dedo no centro e arrasta na direção das teclas).
- Corrigidos os nomes de tecla (L-Shift, Ctrl, Alt, Backspace, Caps, PgUp, Num... não batiam
  com os nomes do Linux e nunca disparavam).
- Posições calculadas com o tamanho real da tela (inclusive sob o notch) e refeitas ao girar.
- O motor fica pausado no editor (antes reiniciava a cada ajuste e disparava toques).
- O app avisa na tela o estado do keymap (ativo, sem acesso a /dev/input, sem Shizuku...).


## Novidades da 0.4.0

- Novo componente **Mouse FPS** para converter movimento relativo do mouse (REL_X/REL_Y)
  em pequenos movimentos de toque na área de mira configurada.
- Tecla configurável no componente alterna entre Mouse FPS ativo e suspenso; ao suspender,
  o toque virtual é liberado.
- Sensibilidade horizontal e vertical configuráveis no editor.
- Marcador de Mouse FPS aparece apenas no editor, não sobre o jogo.
- Serviço Shizuku atualizado para a nova versão do motor.

## Novidades da 0.5.0

- A bolha e os indicadores do keymap só aparecem com o jogo na frente: saiu do jogo, somem;
  voltou, reaparecem (sem precisar abrir o app de novo).
- Mouse FPS corrigido: a câmera agora arrasta de verdade (antes o toque ia e voltava e a
  câmera não girava) e reinicia o arrasto ao chegar na borda da área.
- Mouse próprio: captura do mouse (EVIOCGRAB) com seta própria desenhada pelo app.
- Novo item **Diagnóstico** no menu da bolha: mostra Shizuku, jogo na frente, dispositivos lidos,
  mouse capturado, toques enviados e a última tecla recebida (teste em tempo real).

## Novidades da 0.5.1

- A borda da bolha mostra o estado do motor: **verde** = conectado e jogo na frente, **amarelo** =
  conectado mas não deu para saber se o jogo está na frente, **vermelho** = o motor do Shizuku não responde.
- Se o motor não responde, o app tenta reconectar sozinho (a cada ~6 s) e avisa o motivo.
- O bind do serviço Shizuku agora tem limite de 8 s (antes podia ficar pendurado para sempre, sem aviso).
- O Diagnóstico mostra a versão do app e, se o motor não responde, um relatório do Shizuku
  (iniciado? autorizado? serviço conectado? último erro).

## Novidades da 0.5.2

- Corrigido o erro **"Keymap parado: process name suffix must not be null"**: o serviço do Shizuku
  agora é iniciado com `processNameSuffix` definido, então o motor do keymap conecta.
- Captura do mouse (EVIOCGRAB) compatível com mais versões do Android.
- O app pede a permissão de notificação no Android 13+ (para a notificação "Desativar").
