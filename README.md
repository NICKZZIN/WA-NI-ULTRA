# WA-NI-ULTRA

Módulo Xposed completo para **WhatsApp** e **WhatsApp Business** (via LSPosed / Vector / YukiHookAPI 1.5), **100% gratuito** e com todos os recursos desbloqueados — sem licenças, sem planos pagos e sem limitações.

- **Telegram:** [@NickC137](https://t.me/NickC137)
- **Versão atual:** `1.6.8`

---

## Novidades da versão 1.6.8

### Recursos em destaque

| Recurso | Onde ativar no app | O que faz |
| :--- | :--- | :--- |
| **Origem do Dispositivo (Celular / Conectado)** | Geral → Conversa → *Capturar dispositivo* | Mostra na mensagem se ela foi enviada pelo **Celular** ou por um **Dispositivo Conectado (PC/Web/Secundário)**, com detecção em múltiplos argumentos da stanza, fallback por JID e **aviso explicativo ao tocar no ícone**. |
| **Liberar Prints e Gravação de Tela** | Privacidade → Conversa → *Liberar prints e gravação de tela* | Remove a proteção `FLAG_SECURE` das janelas do WhatsApp, liberando prints e gravação de tela em mídia de visualização única, fotos de perfil e conversas trancadas. |
| **Download Paralelo de Mídias** | Mídia → Downloads → *Download paralelo de mídias* | Acelera downloads de mídias grandes (**6 MB+**) dividindo em blocos de 2 MB com **2, 4, 6 ou 8 conexões simultâneas** e fallback automático para download sequencial. |
| **Ocultar Notificações de Conversas Trancadas** | Privacidade → Tela Inicial → *Ocultar notificações de conversas trancadas* | Silencia notificações do Android vindas de conversas trancadas de forma independente. |
| **Diff Visual no Histórico de Edições** | Geral → Conversa → *Mostrar mensagens editadas* | Destaca automaticamente no histórico o texto **removido** (vermelho riscado) e o texto **adicionado** (verde em negrito) em cada edição. |
| **AB Props Personalizadas** | Geral → Geral → *AB Props personalizadas* | Permite sobrescrever qualquer flag interna do WhatsApp diretamente pelo app (uma por linha, ex.: `12345=true` ou `67890=2`; linhas com `#` são ignoradas). |

### Outras melhorias e recursos incluídos

- **Suporte atualizado** para WhatsApp e WhatsApp Business até **`2.26.46.xx`**.
- **Motor otimizado:** YukiHookAPI 1.5 (libxposed API 101), DexKit 2.3.0 sob demanda, cache binário do `Unobfuscator`, cache de reflection em hot-paths e `ViewHookDispatcher` compartilhado.
- **Backup e Visualizador de Mensagens Apagadas:** captura e permite visualizar/restaurar mensagens apagadas.
- **Áudio e Notas de Voz:** controle de velocidade de áudio (segure o botão de velocidade), gravação em alta qualidade (48 kHz) com nível configurável de supressão de ruído e transcodificação automática de áudio AAC não suportado.
- **Mídia e Status:** download de notas de vídeo, download de status e visualização única, tempo restante de expiração no visualizador de status.
- **Privacidade avançada:** privacidade personalizada por contato (baseada em LID), ocultar confirmação de entrega para números desconhecidos, conversas trancadas ocultas do histórico de chamadas, seletor de contatos e status.
- **Extras de conversa e visual:** agendamento nativo de mensagens, envio de texto em Visualização Única, desativação completa da Meta AI, melhorias de tema personalizado (`CustomThemeV2`) e diagnósticos de root (`RootDiagnostics` com detecção de Magisk DenyList e Hide My App List).

---

## 100% Gratuito

Todas as funcionalidades do **WA-NI-ULTRA** são totalmente gratuitas e desbloqueadas:
- Sem sistema de licença, assinatura ou paywall.
- Sem anúncios ou bloqueios artificiais.

---

## Versões suportadas do WhatsApp

- **WhatsApp:** `2.26.38.xx` até **`2.26.46.xx`**
- **WhatsApp Business:** `2.26.38.xx` até **`2.26.46.xx`**

*(Você também pode ativar a opção "Ignorar verificação de versão" nas configurações caso use uma versão beta mais recente.)*

---

## Como compilar o APK

### Pelo GitHub Actions
1. Acesse a aba **Actions** no repositório no GitHub.
2. Selecione **Build WhatsApp** (ou **Build Business** / **Release**).
3. Clique em **Run workflow** e aguarde finalizar.
4. Baixe o APK gerado na seção **Artifacts**.

### Localmente (JDK 21 + Android SDK)
```bash
./gradlew assembleWhatsappRelease
# ou para WhatsApp Business:
./gradlew assembleBusinessRelease
```

---

## Aviso

Este projeto tem fins educacionais e de personalização. O uso de módulos Xposed em aplicativos de terceiros é de responsabilidade do usuário.
