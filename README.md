# WA-NI-ULTRA

Módulo Xposed completo para **WhatsApp** e **WhatsApp Business** (via LSPosed / Vector / YukiHookAPI 1.5), **100% gratuito**, **100% traduzido em Português** e com **Bot de Figurinhas e Áudio integrado** — sem chaves de API externas, sem pareamento e sem planos pagos.

- **Telegram:** [@NickC137](https://t.me/NickC137)
- **Versão atual:** `1.6.9`

---

## Principais Novidades (v1.6.9)

### 1. Bot de Figurinhas e Áudio 100% Integrado (Sem Parear e 0% Bateria Parado)
- **Sem QR Code / Sem Pareamento:** roda diretamente dentro do próprio WhatsApp via Xposed. Quando parado, não mantém conexões nem threads ativas (**0% de consumo de bateria em segundo plano**).
- **Comandos no WhatsApp:**
  - `!s` ou `!fig` — transforma foto ou vídeo (até 10s) em figurinha 512×512 com metadados EXIF personalizados (Pacote e Autor) e salva automaticamente nos **Favoritos**.
  - `!fig inteiro` — mantém a imagem/vídeo inteira sem cortar (bordas transparentes).
  - `!som` — cria a figurinha animada do vídeo **+ extrai e envia o áudio sincronizado em Ogg/Opus 48 kHz** usando conversor nativo C++.
  - `!ajuda` — envia a lista de comandos no chat.
- **Atalho de 1 toque no WhatsApp:** segure o dedo em qualquer foto ou vídeo na conversa e toque em **Figurinha** / **Fig + Som**.
- **Painel + Mini Terminal em Tempo Real:** card direto na tela inicial do app com:
  - **Modo Seguro Anti-Ban** (processa apenas comandos enviados por você mesmo) + **Atalho direto `wa.me/+55...`** para abrir conversa consigo mesmo ou usar em grupo privado.
  - **Criador Rápido** pela galeria e **Mini Console** exibindo logs ao vivo de cada etapa.

### 2. Nova Página de Configurações do App, Backup pelo WhatsApp e Padrão Persistente
- **Novo botão de ajustes no topo** (substituindo o antigo ícone `i`): concentra todas as opções de Backup e informações do app, deixando a Home mais limpa.
- **Exportar para Celular ou WhatsApp:** escolha salvar o `.json` no aparelho ou enviar direto para o WhatsApp.
- **Importar clicando no arquivo pelo próprio WhatsApp:** basta tocar no arquivo de configuração dentro do WhatsApp para abrir o WA-NI-ULTRA com caixa de confirmação e opção **"Sempre importar automaticamente"** (ambos configuráveis em Geral).
- **Tornar configuração atual meu Padrão:** salva um arquivo padrão persistente em `Documents/WA-NI-ULTRA/default_config.json`. Mesmo desinstalando e instalando o app do zero, ele restaura automaticamente o seu padrão salvo!
- **Redefinir com confirmação dupla:** pergunta antes de redefinir e permite escolher entre **Restaurar meu padrão salvo** ou **Redefinir tudo (tudo desativado)**.

### 3. Interface Mais Limpa, Busca Automática e Listas Dropdown
- **Busca Dinâmica na Lupa:** indexa automaticamente qualquer função presente nos XMLs (sem precisar cadastrar IDs manualmente) e ignora acentos.
- **Listas Expansíveis (Dropdown na mesma página):** as abas **Privacidade**, **Mídia** e **Personalização** agrupam suas opções em categorias recolhíveis que abrem na mesma tela (pode ser desativado em *Personalização → Listas expansíveis nas abas*).
- **Visual limpo:** removido o ícone redundante do app na barra interna, removidos os dados desnecessários de hardware na Home e removidas opções que exigiam chaves de API pagas/externas.
- **Verificador de Atualizações corrigido:** verifica Releases do GitHub e, caso ainda não exista Release publicada, compara automaticamente pelo último commit da branch `master`.

---

## Demais Recursos de Destaque

- **Mostrar dispositivo do remetente:** exibe ícone indicando se a mensagem foi enviada pelo **Celular** ou **Dispositivo Conectado (PC/Web)**, com aviso ao tocar no ícone (*Geral → Conversa*).
- **Liberar prints e gravação de tela:** remove `FLAG_SECURE` em Visualização Única, fotos de perfil e conversas trancadas (*Privacidade → Conversa*).
- **Download paralelo de mídias:** acelera downloads grandes (6 MB+) em até 8 conexões simultâneas (*Mídia → Downloads*).
- **Ocultar notificações de conversas trancadas:** silencia notificações de chats trancados de forma independente (*Privacidade → Tela Inicial*).
- **Diff visual em mensagens editadas:** destaca remoções em vermelho riscado e adições em verde negrito no histórico de edições.
- **AB Props personalizadas:** sobrescreva flags internas do WhatsApp em *Geral → Geral*.

---

## Versões suportadas do WhatsApp

- **WhatsApp:** `2.26.40.xx` até **`2.26.46.xx`**
- **WhatsApp Business:** `2.26.40.xx` até **`2.26.46.xx`**

---

## Como compilar e publicar atualização no GitHub

1. Envie as alterações para o GitHub:
   ```powershell
   cd C:\Users\Pichau\Documents\ABOTS\WA-NI-ULTRA
   git add -A
   git commit -m "feat: WA-NI-ULTRA v1.6.9 com bot de figurinhas integrado, listas dropdown e novo sistema de backup"
   git push origin master
   ```
2. Na aba **Actions** do seu GitHub:
   - Rode **Build WhatsApp** / **Build Business** para gerar os APKs de teste, **ou**
   - Rode **Release** para compilar os dois APKs e publicar automaticamente uma **Release oficial** no seu repositório!
