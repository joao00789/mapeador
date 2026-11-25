# B13 MAUSE PRO

Primeira versão em desenvolvimento do B13 MAUSE PRO, um mapeador Android com launcher
de jogos, editor de controles sobreposto e botão flutuante. A tela Iniciar jogo abre por
padrão (abas: Iniciar jogo e Configurações); selecione um app instalado e toque em **Iniciar** para abrir o jogo com a
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
Deslizar, Joystick (WASD ou Setas), Mouse FPS e Arrasto Mouse são executados. O Mouse FPS
lê os deltas REL_X/REL_Y do mouse e os converte em arrastos de dedo na área de câmera configurada.
A tecla configurável também inclui Arrasto Mouse: mantenha a tecla pressionada e mova
o mouse para arrastar um toque virtual. Dê dois toques numa área vazia do editor para
adicionar um controle Toque; o tamanho dos indicadores e o raio do joystick podem ser
ajustados individualmente.
A seta própria aparece quando há controle de mouse e o nó de movimento foi capturado.
Com Mouse FPS, a mira começa ativa e a tecla configurada (Alt esquerdo por padrão)
alterna entre mira e cursor suspenso, preservando a posição da seta. Ajuste sensibilidades
X/Y (0–100) e suavização (0–100) no marcador Mouse FPS.

Mouse próprio: o app captura o mouse somente quando o keymap usa controles de mouse e o jogo
está confirmado em primeiro plano. No modo cursor, o botão esquerdo toca na posição da seta;
o direito envia Voltar por padrão (configurável para toque longo) e o botão do meio toca nessa
posição. Um mapeamento explícito dos botões substitui o padrão. O cursor usa uma janela opaca própria e recebe posição por socket
local; sua seta fica sobre o painel. A tecla de Mouse FPS alterna MIRA/CURSOR sem recentralizar
a posição do cursor.

A roda vertical/horizontal é injetada no jogo quando não tiver ação mapeada, e pode ser capturada
no editor para disparar um controle. A tecla de emergência é configurável (padrão F12) e libera
as entradas, pausando o motor. Em **Teclado: normal/capturado**, a captura do teclado é opcional;
quando ativada, teclas sem mapeamento são repassadas e Enter libera o teclado para chat até ser
solto. O foco do jogo é acompanhado por callback interno quando disponível e por polling fallback;
o diagnóstico informa se a API interna foi registrada.

Esta versão ainda não foi validada em aparelho. Captura evdev, injeção, janelas sobrepostas,
socket local e APIs internas de foco precisam de teste em dispositivos e versões Android/OEM
suportadas. Se o Android bloquear `EVIOCGRAB`, a captura pode falhar; consulte Diagnóstico.

## Gerar o APK

Cada envio para a branch `main` inicia o workflow **Android APK**. Também é possível
iniciá-lo manualmente em **Actions** no GitHub. Quando a execução terminar, baixe
`nexoplay-debug-apk` na seção **Artifacts** da execução.

O APK desta versão é de depuração e não está assinado para distribuição na Play Store.

## Novidades da 0.7.1

- Corrigido o cursor duplicado após abrir/fechar o editor: a janela própria é ocultada e
  reutilizada em vez de perder sua referência. As configurações agora aparecem acima da
  barra do editor e usam cartões menores; os sliders gravam o valor ao serem soltos.
- Reduzida a carga do mouse: mira limitada a 125 atualizações/s, cursor da janela a 60/s,
  movimentos enviados fora do lock global, suavização ajustada pelo tempo e coordenadas do
  toque limitadas à tela.
- Compactados a navegação, os cartões de jogos, os botões e a página de configurações.

## Novidades da 0.7.0

- Captura do mouse condicional, identificada por nó de movimento, com `EVIOCGRAB` corrigido
  e alternância sem recriar as threads de leitura.
- Detecção de foco via `ITaskStackListener` refletido e fallback; estados desconhecidos liberam
  mouse e toques para evitar entrada presa.
- Cursor independente em janela própria, posição enviada por socket local, indicador MIRA/CURSOR
  e posição preservada ao alternar modos.
- Scroll injetado ou mapeável. Tecla de emergência configurável (F12), captura opcional do
  teclado com repasse de teclas sem mapa e modo chat por Enter.
- Clique direito configurável entre Voltar e toque longo; botão do meio toca na posição do
  cursor. Controles mapeados substituem esses padrões.

## Novidades da 0.6.6

- Sensibilidades X/Y e velocidade do cursor unificadas em controles de 0 a 100;
  os valores de perfis antigos são convertidos sem alterar a escala efetiva.
- A mira não continua soltando eventos de suavização depois que o mouse para, reduzindo
  o atraso ao puxar a câmera enquanto mantém o botão de tiro pressionado.
- Teclas agora alternam apenas na transição real de soltar para pressionar, com eventos
  agregados por dispositivo para evitar alternâncias duplicadas. Teclas presas são
  liberadas quando um dispositivo desconecta ou o jogo perde o foco. A seta é atualizada
  mais rapidamente e a captura do mouse tenta se recuperar quando uma tentativa falha.

## Novidades da 0.6.5

- Sensibilidade X/Y do Mouse FPS ajustável de 0 a 3000 e suavização configurável de 0 a 100;
  os valores salvos em versões anteriores são convertidos ao novo intervalo.
- O cursor próprio começa no centro da tela ao suspender a mira, e a atualização dos
  movimentos usa quadros de 4 ms.
- Novo Arrasto Mouse: uma tecla mantém um toque ativo enquanto os deltas do mouse movem
  esse toque. Dois toques numa área vazia do editor adicionam um controle Toque.
- Tamanho individual das teclas e raio do joystick ajustáveis; sliders e biblioteca de
  aplicativos receberam cores e formas de maior contraste.

## Novidades da 0.6.4

- O Mouse FPS não limita mais o dedo virtual às bordas da tela; o delta relativo continua
  sendo aplicado mesmo quando a posição simulada passa para fora da área visível.

## Novidades da 0.6.3

- Suaviza os deltas da mira FPS em quadros curtos, preservando o movimento acumulado e sem
  aplicar suavização ao cursor livre.
- A seta recebe prioridade de desenho acima do painel lateral e dos indicadores.

## Novidades da 0.6.2

- O cursor do app começa em `(0,0)` e retorna a essa posição quando o modo do mouse é alternado.
- O toque de um botão configurado para **Manter pressionado** permanece ativo enquanto o dedo
  separado do Mouse FPS acompanha o movimento da câmera.

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
