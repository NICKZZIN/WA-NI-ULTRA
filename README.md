# WA-NI-ULTRA

Módulo Xposed completo para **WhatsApp** e **WhatsApp Business** (via LSPosed / Vector / YukiHookAPI 1.5), **100% gratuito**, **100% traduzido em Português** e com **Bot de Figurinhas e Áudio integrado** — sem chaves de API externas, sem pareamento e sem planos pagos.

- **Telegram:** [@NickC137](https://t.me/NickC137)
- **Versão atual:** `1.7.4`

---

## Principais Novidades (v1.7.4)

### 1. Bot de Figurinhas e Áudio 100% Integrado (Sem Parear e 0% Bateria Parado)
- **Sem QR Code / Sem Pareamento / Envio 100% em Segundo Plano:** roda diretamente dentro do próprio WhatsApp via Xposed. Cria e envia figurinhas estáticas (imagem) e animadas (vídeo/GIF) — além do áudio Ogg/Opus 48 kHz sincronizado no `/figsom` — silenciosamente em segundo plano **sem reiniciar o WhatsApp e sem abrir tela de pré-visualização**. Quando parado, não mantém conexões nem threads ativas (**0% de consumo de bateria em segundo plano**).
- **Comandos no WhatsApp com prefixo `/` (funcionam na legenda da foto/vídeo, respondendo/marcando a mídia ou mandando logo em seguida):**
  - **`/fig`** — transforma foto, GIF ou vídeo (padrão 6s, até 10s) em figurinha 512×512 com metadados EXIF personalizados (Pacote e Autor), envia na conversa e salva automaticamente nos **Favoritos**.
    - Aceita modos (`cortar` padrão, `inteiro`/`ajustar` com bordas transparentes, `esticar`, `redondo`), trecho de vídeo (`/fig 2-7` ou `/fig 5s`), cor e legenda estilo meme (`/fig redondo amarelo bom dia`).
  - **`/figsom`** — cria figurinha **com áudio** 🔊 (enviando/respondendo a um vídeo com `/figsom` para usar o som do vídeo, ou enviando foto/vídeo com `/figsom` respondendo a um áudio).
    - Aceita trecho (`/figsom 3-8`), `/figsom parada` (quadro do vídeo vira figurinha parada com até 10s de som) e `/figsom longo` (até 10s animada / 20s parada), injeta `sound-sha256` e `🔊` no EXIF e envia a nota de voz Ogg/Opus 48 kHz sincronizada.
  - **`/limites`** — informa no chat todos os limites de foto (100 KB), vídeo/GIF (6s padrão, máx 10s, 500 KB, entrada até 16 MB) e áudio (5s animada / 10s parada, Ogg/Opus 48 kHz até 256 KB).
- **Outras pessoas podem usar no seu Chat Privado (ou Grupos):** no painel do bot você pode ativar/desativar com 1 toque para que amigos enviem `/fig`, `/figsom` e `/limites` na conversa privada com você e recebam a figurinha + áudio automaticamente em segundo plano!
- **Atalho de 1 toque no WhatsApp (Opcional):** segure o dedo em qualquer foto ou vídeo na conversa e toque em **Figurinha** (pode ser ativado ou desativado no painel do Bot de Figurinhas caso prefira usar apenas comandos).
- **Painel + Mini Terminal Rolável em Tempo Real:** card direto na tela inicial do app com controle de permissões (Só Eu / Chat Privado / Grupos), opção de ocultar o botão do menu do WhatsApp, atalho `wa.me/+55...`, Criador Rápido e Mini Terminal com rolagem independente e botão Copiar.

### 2. Nova Página de Configurações do App, Backup Automático na Instalação e Assinatura Fixa
- **Assinatura permanente do APK:** todas as atualizações usam a mesma chave de assinatura, permitindo atualizar o app por cima sem precisar desinstalar.
- **Detecção automática de Backup ao instalar:** ao abrir o app pela primeira vez, ele solicita permissão de acesso aos arquivos e verifica automaticamente se existe um backup salvo no aparelho (`Documents/WA-NI-ULTRA/default_config.json`), perguntando se você deseja restaurar suas configurações na hora.
- **Novo botão de ajustes no topo** (substituindo o antigo ícone `i`): concentra todas as opções de Backup e informações do app, deixando a Home mais limpa.
- **Exportar para Celular ou WhatsApp:** escolha salvar o `.json` no aparelho ou enviar direto para o WhatsApp.
- **Importar clicando no arquivo pelo próprio WhatsApp:** basta tocar no arquivo de configuração dentro do WhatsApp para abrir o WA-NI-ULTRA com caixa de confirmação e opção **"Sempre importar automaticamente"** (ambos configuráveis em Geral).
- **Tornar configuração atual meu Padrão:** salva um arquivo padrão persistente em `Documents/WA-NI-ULTRA/default_config.json`.
- **Redefinir com confirmação dupla:** pergunta antes de redefinir e permite escolher entre **Restaurar meu padrão salvo** ou **Redefinir tudo (tudo desativado)**.

### 3. Interface Mais Limpa, Busca Automática e Listas Dropdown
- **Busca Dinâmica na Lupa:** indexa automaticamente qualquer função presente nos XMLs (sem precisar cadastrar IDs manualmente) e ignora acentos.
- **Listas Expansíveis em TODAS as abas (Dropdown na mesma página):** as abas **Geral** (*Geral, Tela Inicial, Conversa, Figurinhas, Status*), **Privacidade**, **Mídia** e **Personalização** agrupam suas opções em categorias recolhíveis que abrem na mesma tela ao tocar (pode ser desativado em *Personalização → Listas expansíveis nas abas*).
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
<<<<<<< HEAD

---
=======
>>>>>>> bd0792b1 (fix: WA-NI-ULTRA v1.7.1 figurinhas animadas, opcao ocultar atalho, assinatura fixa e restauracao de backup ao instalar)
