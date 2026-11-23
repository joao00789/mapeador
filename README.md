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

A biblioteca mostra os ícones dos apps instalados. No editor, Toque, Mouse Pick e
Deslizar são executados; joystick, macros, scroll e modos de visão ainda não executam
ações no jogo.

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
