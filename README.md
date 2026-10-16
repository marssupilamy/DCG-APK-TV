# DCG TV

Aplicativo de IPTV para Fire TV Stick e Android TV. Ele lê uma lista M3U e separa o conteúdo em três abas: **TV ao Vivo**, **Filmes** e **Séries**.

## Como o app organiza a lista

Premissa: uma lista M3U é um arquivo de texto em que cada item tem um nome, um grupo (`group-title`) e um link.

- **TV ao Vivo**: links de transmissão contínua, como `.ts`, `.m3u8` ou links sem extensão.
- **Filmes**: links com `/movie/` (padrão Xtream Codes) ou arquivos de vídeo (`.mp4`, `.mkv` etc.).
- **Séries**: links com `/series/` ou arquivos de vídeo com padrão de episódio no nome, como `S01E02` ou `1x02`. Os episódios da mesma série ficam juntos e separados por temporada.

Os grupos da lista viram as categorias da coluna da esquerda.

## Recursos

- Login com servidor, usuário e senha (Xtream Codes, o mesmo padrão do Smarters), com vários servidores: se um cair, o app tenta o próximo.
- Link M3U direto. Links `get.php` viram login Xtream automaticamente.
- DNS seguro próprio (Cloudflare e Google), para funcionar mesmo quando o provedor de internet bloqueia os endereços.
- A lista fica salva no aparelho e é atualizada a cada 12 horas, ou pelo botão **Atualizar**.
- Busca por nome dentro da aba aberta.
- Favoritos e vistos recentemente no topo de cada aba. Para favoritar, segure o OK sobre o item (ou aperte o botão de menu).
- Filmes e episódios continuam de onde pararam. Na série, cada episódio mostra "visto" ou "parou em".
- Abre na hora com a cópia salva e baixa a versão nova em segundo plano quando ela tem mais de 12 horas.

## Controle remoto no player

| Tecla | Filmes e séries | TV ao vivo |
|---|---|---|
| OK | pausa e retoma | mostra a barra de controles |
| Esquerda / direita | volta / avança 10 s (segurar acelera) | mostra a barra de controles |
| Cima / baixo | mostra a barra de controles | troca de canal |
| Play/pause, avançar, voltar | funcionam normalmente | play/pause |
| Voltar | fecha a barra; se ela estiver fechada, sai do player | sai do player |

Séries passam para o próximo episódio sozinhas.

## Instalação no Fire TV Stick

1. Em **Configurações > Minha Fire TV > Opções do desenvolvedor**, ative **Instalar apps desconhecidos** para o Downloader.
2. Baixe o `DCG-TV.apk` da página de versões (Releases) do repositório.
3. Abra o Downloader, digite o link do APK e confirme a instalação.

O Downloader só baixa de links públicos. Se o repositório for privado, o link do GitHub não funciona direto no Fire Stick.

## Compilação

Cada envio ao GitHub roda o workflow `Gerar APK`, que testa, compila e publica o APK como uma nova versão.

Para compilar no computador, com o Android SDK instalado:

```
./gradlew assembleRelease
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`.
