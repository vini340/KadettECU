#!/usr/bin/env bash
# executar.sh - abre o KadettECU (compila antes se ainda não foi compilado).
#
#   ./executar.sh                painel completo (instrumentos, mapas, parâmetros, binário, registro)
#   ./executar.sh --simulador    painel já conectado ao simulador em Java: não precisa de placa nenhuma
#   ./executar.sh --legado       o analisador de binário original (janela + console)
#   ./executar.sh --console      só o analisador de binário no console
#
# Para usar o firmware em C rodando no PC como se fosse a placa: make -C firmware/host   (precisa de gcc)
# e escolha "FIRMWARE C (PC)" na lista de portas do painel.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d build/classes ]; then
    ./compilar.sh
fi
exec java -cp "build/classes:libs/jSerialComm-2.11.0.jar" kadettecu.Inicio "$@"
