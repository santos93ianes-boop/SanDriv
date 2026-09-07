# SanDriv V3

Projeto Android preparado para GitHub Actions gerar o APK.

## Implementado nesta V3
- Origem automática em **Minha localização** usando GPS do aparelho.
- Busca de destino e rota desenhada dentro do próprio SanDriv.
- Navegação em primeiro plano + serviço de localização em segundo plano.
- Orientação por voz usando o Text-to-Speech do Android.
- Botão **Centralizar** quando o usuário movimenta o mapa durante a navegação.
- Recálculo automático quando o aparelho se afasta significativamente da rota.
- Gravação local de distância e duração do percurso; mantém até 30 resumos de viagens.
- Clima atual e probabilidade de chuva usando Open-Meteo.
- Consulta de postos próximos por dados OpenStreetMap/Overpass.
- Alertas locais de buraco, acidente, obra e perigo na pista.
- Interface V3 mais limpa, dinâmica e focada na condução.

## Dados online
Este protótipo usa serviços públicos para geocodificação, rota, clima e postos. Para publicação com muitos usuários, use serviços com SLA/chave própria e backend para alertas comunitários em tempo real.

## Gerar APK
1. Envie **todo o conteúdo desta pasta** para a raiz do repositório GitHub.
2. Abra **Actions**.
3. Execute **Gerar APK SanDriv**.
4. Baixe o artefato `SanDriv-APK`.

A pasta `.github/workflows/android-apk.yml` já está incluída.
