# SanDriv 1.0

**Seu caminho. Tudo à frente.**

Projeto Android do SanDriv, navegador próprio que não abre Google Maps nem Waze.

## Recursos desta versão
- Mapa OpenStreetMap dentro do aplicativo (osmdroid)
- Busca de destino por endereço/cidade no Brasil
- Cálculo e desenho da rota dentro do SanDriv (OSRM)
- GPS, velocidade, distância restante e horário aproximado de chegada
- Orientação por voz em português
- Escolha entre as vozes em português instaladas no aparelho e velocidade da fala
- Serviço de navegação em primeiro plano para continuar orientando ao usar outro app ou apagar a tela
- Clima atual e chance de chuva nas próximas horas (Open-Meteo)
- Busca de postos de combustível próximos (OpenStreetMap/Overpass)
- Registro de buraco, acidente, obra e perigo na pista
- Aviso por voz ao se aproximar de ocorrências registradas no aparelho
- Interface escura SanDriv + splash screen própria

## Importante
Os alertas de ocorrência desta versão são locais ao aparelho. Para compartilhar ocorrências em tempo real entre todos os usuários será necessário conectar um backend/banco online. O mapa precisa de internet para baixar áreas ainda não armazenadas em cache; navegação offline completa ainda não faz parte desta versão.

## Gerar APK no GitHub
1. Crie um repositório vazio e envie TODO o conteúdo desta pasta para a raiz.
2. Abra a guia **Actions**.
3. Escolha **Gerar APK SanDriv**.
4. Clique em **Run workflow**.
5. Quando terminar, baixe o artefato **SanDriv-APK**.
6. Dentro do artefato estará `app-debug.apk`, instalável em Android.

O workflow também roda automaticamente a cada push na branch `main`.
