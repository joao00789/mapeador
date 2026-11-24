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
REL_X/REL_Y do mouse e os converte em movimentos curtos de toque na posição configurada.
A tecla atribuída ao componente Mouse FPS alterna entre mira ativa e mouse suspenso.
Ajuste a sensibilidade horizontal e vertical tocando no marcador Mouse FPS no editor.

Limitação do Android: esta implementação converte movimento do mouse em gestos de toque,
mas não consegue ocultar globalmente o ponteiro físico do Android em todos os jogos. A
captura exclusiva/ocultação do cursor depende de suporte de captura de ponteiro no próprio
jogo ou de integração privilegiada específica do fabricante.

## Gerar o APK

Cada envio para a branch `main` inicia o workflow **Android APK**. Também é possível
iniciá-lo manualmente em **Actions** no GitHub. Quando a execução terminar, baixe
`nexoplay-debug-apk` na seção **Artifacts** da execução.

O APK desta versão é de depuração e não está assinado para distribuição na Play Store.

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
