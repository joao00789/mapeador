# NexoPlay

Primeira versão em desenvolvimento de um mapeador Android com editor de controles,
lista de jogos e botão flutuante.

Durante o jogo, toque na bolha NexoPlay e escolha **Editar keymap neste jogo**.
Adicione um tipo de controle, pressione a tecla ou o botão do mouse que deseja
associar e arraste o indicador para a posição correspondente no HUD. Toque em um
indicador existente para alterar a tecla ou mantenha-o pressionado para removê-lo.
Use **Sair** para fechar apenas o editor. Para encerrar a bolha e o serviço,
selecione **Desativar** no menu flutuante, na barra do editor ou na notificação.

## Gerar o APK

Cada envio para a branch `main` inicia o workflow **Android APK**. Também é possível
iniciá-lo manualmente em **Actions** no GitHub. Quando a execução terminar, baixe
`nexoplay-debug-apk` na seção **Artifacts** da execução.

O APK desta versão é de depuração e não está assinado para distribuição na Play Store.
