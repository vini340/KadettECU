#!/usr/bin/env bash
# testar.sh - roda TODOS os testes do projeto. Precisa de JDK, gcc/g++ e make.
#
#   ./testar.sh             tudo
#   ./testar.sh sanitize    também roda os testes em C com AddressSanitizer + UBSan (mais lento)
#
# O teste da interface precisa de ambiente gráfico; sem tela, usa o xvfb-run se ele existir (senão pula esse teste).
# O TesteSerialReal abre uma porta serial de verdade (um pseudo-terminal) e só roda em Linux/macOS com Python 3; fora disso avisa e pula.
set -euo pipefail
cd "$(dirname "$0")"

echo "### 1/4 núcleo do firmware em C, rodando no PC"
# "all" compila o ecu_host (a ECU virtual em C), que o TesteInterface e o TesteIntegracaoFirmware usam; "test" roda os testes em C
make -C firmware/host all test

echo "### 2/4 código das placas (ESP32 e Arduino Mega) contra os simuladores"
make -C firmware/teste_placas test

if [ "${1:-}" = "sanitize" ]; then
    echo "### (sanitizers) núcleo e placas"
    make -C firmware/host sanitize
    make -C firmware/teste_placas sanitize
fi

echo "### 3/4 compilando o Java"
./compilar.sh testes

echo "### 4/4 testes em Java"
CP="build/test-classes:build/classes:libs/jSerialComm-2.11.0.jar"
JAVA="java"
if [ -z "${DISPLAY:-}" ] && command -v xvfb-run >/dev/null 2>&1; then
    JAVA="xvfb-run -a java"
fi
for t in TesteKadettECU TesteSistema TesteInterface TesteIntegracaoFirmware TesteSerialReal; do
    echo "=== $t"
    $JAVA -cp "$CP" "kadettecu.teste.$t"
done
echo
echo "Todos os testes terminaram sem falhas."
