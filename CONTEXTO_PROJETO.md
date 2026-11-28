# Contexto do projeto — B13 MAUSE PRO (reescrita em C++)

Este arquivo existe para que uma futura sessão (humana ou IA) entenda rápido
o estado do projeto sem precisar ler todo o código.

## Visão geral da arquitetura

```
┌─────────────────────────────┐        ┌──────────────────────────────────┐
│ Processo do app (com UI)    │        │ Processo do Shizuku (shell)      │
│                              │        │                                  │
│ MainActivity (Compose)      │        │ EngineUserService (Binder)       │
│ FloatingBubbleService        │ bind   │   └─ NativeEngine (JNI wrapper)  │
│   └─ ShizukuBridge ──────────┼───────▶│        └─ Engine (C++, cpp/)     │
│   └─ KeymapEditorOverlay     │        │             ├─ EvdevManager      │
│                              │        │             ├─ Keymap/Control    │
│                              │        │             └─ android_api.cpp  │
└─────────────────────────────┘        │               (JNI → MotionEvent,│
                                        │                KeyEvent, Input-  │
                                        │                Manager via       │
                                        │                reflexão)        │
                                        └──────────────────────────────────┘
```

- **`app/src/main/cpp/engine.{h,cpp}`** é o coração do mapeador. Lê eventos
  brutos do `EvdevManager`, decide o que cada tecla/movimento de mouse
  significa de acordo com o `Keymap` ativo, e injeta toques/teclas via
  `android_api.cpp`.
- **`evdev.{h,cpp}`** abre `/dev/input/event*` diretamente (o processo roda
  como `shell`, então tem permissão) e faz `EVIOCGRAB` para capturar o
  mouse/teclado com exclusividade.
- **`android_api.{h,cpp}`** é o único lugar que fala com a JVM. Usa reflexão
  para achar `InputManager#injectInputEvent` (API oculta), e JNI "normal"
  para construir `MotionEvent`/`KeyEvent` (APIs públicas).
- **`keymap.{h,cpp}` + `json.hpp`** fazem o parse/serialização do keymap.
  `json.hpp` é um parser mínimo escrito à mão (sem dependências externas)
  — só entende o suficiente para este documento.
- **`jni_bridge.cpp`** só expõe as funções `Java_com_mapeador_NativeEngine_*`
  e chama os métodos do `Engine`. Não tem lógica própria.

No lado Kotlin:

- **`EngineUserService`** é a classe que o Shizuku instancia no processo
  shell. Implementa um protocolo Binder simples (sem AIDL) e repassa tudo
  para `NativeEngine`.
- **`ShizukuBridge`** é o cliente desse Binder, usado por
  `FloatingBubbleService`.
- **`FloatingBubbleService`** mostra a bolha flutuante, o menu, os
  indicadores sobre o jogo e abre o `KeymapEditorOverlay` quando pedido.
- **`KeymapEditorOverlay`** é o editor visual (Views clássicas, não Compose)
  que desenha os controles como círculos arrastáveis sobre o jogo.
- **`MainActivity`** é a tela normal do app (Compose): lista de jogos e
  configurações.

## Convenções importantes

- O campo `radius` de um `Control` é **sobrecarregado**: para a maioria dos
  tipos é um tamanho relativo (0.2–1.5). Para `JOYSTICK`, quando > 1.5, é o
  alcance em pixels do manche virtual. Isso está assim tanto no C++
  (`keymap.cpp`) quanto no editor (`KeymapEditorOverlay.kt`) — se for mudar
  um lado, mude o outro.
- `Control.key` também é sobrecarregado para `JOYSTICK`: guarda `"WASD"` ou
  `"SETAS"` em vez de uma tecla única (um joystick usa 4 teclas fixas).
- O JSON do keymap salvo tem um envelope: `{"controls": [...], "emergencyKey":
  ..., "captureKeyboard": ..., "initialMouseMode": ...}`. O parser em C++
  também aceita um array solto (formato antigo), por via das dúvidas.
- `Engine` é inteiramente protegido por `lock_` (um `std::mutex`). Qualquer
  método novo que mexa em `fingers_`, `runtime_` ou no estado de mouse deve
  ser chamado com o lock seguro (olhe como `onRawEvent`, `setForeground` e
  `updateKeymap` fazem isso).
- Cada thread nativa (leitura de evdev, threads de swipe/joystick/foreground)
  tem que chamar `Engine::currentEnv()` para pegar seu próprio `JNIEnv` —
  nunca reusar um `JNIEnv*` de outra thread. Isso já está feito em todos os
  pontos de injeção; se adicionar um novo, siga o mesmo padrão.

## O que ainda falta / próximos passos sugeridos

1. **Testar em aparelho real** com o Shizuku rodando — é o passo mais
   importante, nada disso foi validado fora de leitura de código.
2. Captura de tecla física no editor (hoje é uma lista fixa de teclas).
3. Importar/exportar keymap como arquivo `.json` de verdade (hoje é só
   compartilhar/colar texto).
4. Concluir o fluxo de perfis: o armazenamento agora separa apps, perfis e
   controles em Room; o perfil padrão de cada app é carregado pelo serviço.
   Ainda falta editar/duplicar perfis pela tela Mapas.
5. Migrar os mapas globais antigos para o app correto quando houver mais de um
   app salvo. A migração os preserva e oferece importação manual, sem atribuir
   silenciosamente um mapa a vários apps.
6. Indicadores visuais mais ricos no editor (hoje são círculos simples).
7. Testar diagnóstico de mouse e movimento em aparelhos Android reais; o
   diagnóstico atual observa passivamente eventos entregues à Activity.

## Histórico

- **0.7.x e anteriores**: versão em Kotlin puro, com toda a lógica do
  mapeador dentro do `ShizukuInputUserService`. Relatada como "bugada" pelo
  usuário — motivou esta reescrita completa.
- **1.0.0 (este commit)**: reescrita do motor em C++ nativo (JNI), UI
  Kotlin/Compose simplificada. Ainda não testada em aparelho.
- **V1 em andamento**: biblioteca e perfis locais em Room, preferências em
  DataStore, migração dos dados antigos e diagnóstico passivo de mouse.
- **Correção de sessão/mouse**: overlay flutuante limitado ao app de jogo em
  primeiro plano; captura exclusiva liberada quando suspensa ou fora da sessão;
  movimento de gameplay encaminhado como eventos relativos, sem ciclo repetido
  de toques para mover a câmera. Precisa validação em Android real/jogo alvo.
