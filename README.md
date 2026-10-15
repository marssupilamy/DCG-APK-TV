# DCG TV

Aplicativo de IPTV para Fire TV Stick e Android TV. Ele lê uma lista M3U e separa o conteúdo em três abas: **TV ao Vivo**, **Filmes** e **Séries**.

## Como o app organiza a lista

Premissa: uma lista M3U é um arquivo de texto em que cada item tem um nome, um grupo (`group-title`) e um link.

- **TV ao Vivo**: links de transmissão contínua, como `.ts`, `.m3u8` ou links sem extensão.
- **Filmes**: links com `/movie/` (padrão Xtream Codes) ou arquivos de vídeo (`.mp4`, `.mkv` etc.).
- **Séries**: links com `/series/` ou arquivos de vídeo com padrão de episódio no nome, como `S01E02` ou `1x02`. Os episódios da mesma série ficam juntos e separados por temporada.

Os grupos da lista viram as categorias da coluna da esquerda.

## Recursos

- Link M3U direto ou login com servidor, usuário e senha (Xtream Codes).
- A lista fica salva no aparelho e é atualizada a cada 12 horas, ou pelo botão **Atualizar**.
- Busca por nome dentro da aba aberta.
- No ao vivo, as setas cima e baixo do controle trocam de canal.
- Séries passam para o próximo episódio sozinhas.

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
