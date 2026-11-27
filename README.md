# WA-NI-ULTRA

Fork personalizado e turbinado do **WaEnhancer** para WhatsApp e WhatsApp Business (via LSPosed / Vector / YukiHookAPI 1.5), reunindo **todas as atualizações oficiais do WaEnhancer v1.6.7** + **as funções exclusivas do WaEnhancerX** + suporte às versões mais recentes do WhatsApp (**até 2.26.46.xx**).

- **Repositório:** [NICKZZIN/WA-NI-ULTRA](https://github.com/NICKZZIN/WA-NI-ULTRA)
- **Telegram:** [@NickC137](https://t.me/NickC137)
- **Versão atual:** `1.6.8`

---

## O que foi atualizado e adicionado

### 1. Base atualizada para o WaEnhancer v1.6.7 (125+ commits)
Atualizado da antiga base `1.6.1` (WhatsApp `2.26.40.xx`) para a versão mais recente `1.6.7` (**WhatsApp e WhatsApp Business até `2.26.46.xx`**):

- **Motor modernizado e muito mais rápido:**
  - Migração para **YukiHookAPI 1.5** (libxposed API 101) e **DexKit 2.3.0** com abertura sob demanda (`DexKitManager`).
  - Cache binário único do `Unobfuscator`, cache de reflection em hot-paths e `ViewHookDispatcher` compartilhado.
  - Otimização R8 com shrink de recursos no build Release.
- **Novos recursos trazidos do WaEnhancer v1.6.2 → v1.6.7:**
  - **Backup e Visualizador de Mensagens Apagadas** (`DeletedMessageCapture` + `DeletedBackupViewer`).
  - **Controle de velocidade de áudio** (`AudioSpeedControl`): segure o botão de velocidade para escolher qualquer velocidade.
  - **Notas de voz em alta qualidade (48 kHz)** com **nível configurável de supressão de ruído** (`VoiceNoteQuality`).
  - **Transcodificação automática de áudio AAC não suportado** (`UnsupportedAudioTranscode`).
  - **Download de notas de vídeo** (`VideoNoteDownloader`).
  - **Tempo de expiração do Status** (`StatusExpirationTime`).
  - **Privacidade Personalizada por contato (v2)** baseada em LID, com seletor Padrão / Ativado / Desativado por opção.
  - **Ocultar confirmação de entrega para números desconhecidos** (`HideSeen`).
  - **Conversas Trancadas aprimoradas** (`LockedChatsEnhancer`): oculta contatos trancados do histórico de chamadas, seletor de contatos, lista de status e reprodução de status.
  - **Agendamento nativo de mensagens** e **envio de texto em Visualização Única**.
  - **Desativação completa da Meta AI** (busca, voz, imagine, aba inicial, bots e prompts).
  - **CustomThemeV2 aprimorado:** desativa automaticamente os temas nativos do WhatsApp enquanto cores personalizadas estiverem ativas e corrige flickering nas barras do sistema.
  - **RootDiagnostics aprimorado:** detecta e corrige bloqueios do Magisk DenyList e Hide My App List.

---

### 2. Funções exclusivas portadas / aprimoradas do WaEnhancerX

Todas as funções abaixo foram adaptadas para a arquitetura Kotlin + YukiHookAPI 1.5 do WA-NI-ULTRA, com tradução completa em **Inglês** e **Português**:

| Recurso | Onde ativar no app | O que faz |
| :--- | :--- | :--- |
| **Origem do Dispositivo aprimorada** (`CaptureDevice` + `DeviceJidParser`) | Geral → Conversa → *Capturar dispositivo* | Mostra o ícone indicando se a mensagem foi enviada pelo **Celular** ou por um **Dispositivo Conectado (PC/Web/Secundário)**. Aprimorado com extração em múltiplos argumentos da stanza + fallback por JID (`:device`) + **toast explicativo ao tocar no ícone**. |
| **Liberar Prints e Gravação de Tela** (`ScreenshotBypass`) | Privacidade → Conversa → *Liberar prints e gravação de tela* | Remove a flag `FLAG_SECURE` de todas as janelas e diálogos do WhatsApp, liberando prints e gravação de tela em visualização única, fotos de perfil e conversas trancadas. |
| **Download Paralelo de Mídias** (`ParallelDownloading`) | Mídia → Downloads → *Download paralelo de mídias* | Acelera downloads de mídias grandes (**6 MB+**) dividindo em blocos de 2 MB via múltiplas conexões HTTP Range simultâneas (configurável entre **2, 4, 6 ou 8 conexões**) com fallback automático para download sequencial. |
| **Ocultar Notificações de Conversas Trancadas** (`LockedChatNotifications`) | Privacidade → Tela Inicial → *Ocultar notificações de conversas trancadas* | Silencia notificações do Android vindas de conversas trancadas consultando `chat.chat_lock` + `jid_map` no banco do WhatsApp — funciona de forma independente, sem depender de nomes ofuscados. |
| **Diff Visual no Histórico de Edições** (`EditHistoryDiff` + `MessageAdapter`) | Geral → Conversa → *Mostrar mensagens editadas* | Ao abrir o histórico de uma mensagem editada, destaca automaticamente o texto **removido** (vermelho riscado) e o texto **adicionado** (verde em negrito) em cada revisão. |
| **AB Props Personalizadas** (`PropsHook`) | Geral → Geral → *AB Props personalizadas* | Permite sobrescrever qualquer feature flag interna do WhatsApp diretamente pelo app (uma por linha, ex.: `12345=true` ou `67890=2`; linhas com `#` são ignoradas), com prioridade máxima e checagem de tipo segura. |

---

### 3. Ajustes de Personalização e CI (GitHub Actions)

- Links de **Atualizações**, **Telegram** (`https://t.me/NickC137`) e **GitHub** (`NICKZZIN/WA-NI-ULTRA`) atualizados em `UpdateChecker.kt`, `AboutActivity.kt` e `HomeFragment.kt`, além da adição de `NICKZZIN` em contribuidores.
- Workflows do GitHub Actions corrigidos (`.github/workflows/build-whatsapp.yml`, `build-business.yml`, `release.yml`, `dependabot-ci.yml`):
  - Actions atualizadas para versões estáveis (`actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4`) usando **JDK 21** (exigido pelo KSP do YukiHookAPI 1.5).
  - Builds Release (`assembleWhatsappRelease` e `assembleBusinessRelease`) funcionando com ou sem keystore configurada nos Secrets.

---

## Versões suportadas do WhatsApp

- **WhatsApp:** `2.26.38.xx` até **`2.26.46.xx`**
- **WhatsApp Business:** `2.26.38.xx` até **`2.26.46.xx`**

*(Também é possível ativar a opção "Ignorar verificação de versão" nas configurações caso utilize uma beta mais recente.)*

---

## Como compilar o APK

### Pelo GitHub Actions (mais fácil)
1. Acesse a aba **Actions** no seu repositório no GitHub.
2. Selecione **Build WhatsApp** (ou **Build Business** / **Release**).
3. Clique em **Run workflow** e aguarde o build finalizar.
4. Baixe o APK pronto na seção **Artifacts** da execução.

### Localmente (com JDK 21 + Android SDK)
```bash
./gradlew assembleWhatsappRelease
# ou para WhatsApp Business:
./gradlew assembleBusinessRelease
```

---

## Créditos

- Base original: [Waenhancer-Dev/WaEnhancer](https://github.com/Waenhancer-Dev/WaEnhancer) & Dev4Mod
- Recursos portados/inspirados: [WaEnhancerX/WaEnhancerX](https://github.com/WaEnhancerX/WaEnhancerX)
- Manutenção deste fork: [NICKZZIN](https://github.com/NICKZZIN)
