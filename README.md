# NexoPlay

Primeira versão em desenvolvimento do NexoPlay, um mapeador Android com launcher
de jogos, editor de controles sobreposto e botão flutuante. A tela Jogos abre por
padrão; selecione um app instalado e toque em **Iniciar** para abrir o jogo com a
bolha do NexoPlay.

Durante o jogo, toque na bolha NexoPlay e escolha **Editar keymap neste jogo**.
No editor, use a barra sobre o jogo para adicionar componentes, salvar, importar,
compartilhar ou limpar o mapeamento. Arraste os indicadores redondos até as posições
correspondentes no HUD. Toque em um indicador para configurar a tecla; no joystick,
toque para ajustar o tamanho ou alterar o atalho. Mantenha um indicador pressionado
para removê-lo. Use **Sair** para fechar apenas o editor. Para encerrar a bolha e o
serviço, selecione **Desativar botão flutuante** no menu ou na notificação.

Para mapear teclas durante o jogo, habilite o serviço **NexoPlay - Mapeamento de
teclado** nas configurações de Acessibilidade do Android e desligue **Digitação do
teclado** em Ajustes. O usuário escolhe os jogos permitidos na aba Jogos.
Na versão atual, os componentes Toque e Mouse Pick enviam toques, e Deslizar envia
um movimento horizontal. Joystick, macros, scroll e modos de visão continuam sendo
configurações visuais; seus gestos ainda não são executados.

## Gerar o APK

Cada envio para a branch `main` inicia o workflow **Android APK**. Também é possível
iniciá-lo manualmente em **Actions** no GitHub. Quando a execução terminar, baixe
`nexoplay-debug-apk` na seção **Artifacts** da execução.

O APK desta versão é de depuração e não está assinado para distribuição na Play Store.
