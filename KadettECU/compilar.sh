#!/usr/bin/env bash
# compilar.sh - compila o KadettECU só com o JDK (11 ou mais novo), sem Maven.
#
#   ./compilar.sh           compila o programa em build/classes
#   ./compilar.sh testes    compila também os programas de teste em build/test-classes
#
# A biblioteca de porta serial (jSerialComm) já vem em libs/. Para abrir o programa: ./executar.sh
set -euo pipefail
cd "$(dirname "$0")"

JAR="libs/jSerialComm-2.11.0.jar"
if [ ! -f "$JAR" ]; then
    echo "Não achei $JAR (a biblioteca de porta serial). Veja libs/LEIA-ME.txt." >&2
    exit 1
fi

rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/fontes-main.txt
javac --release 11 -encoding UTF-8 -Xlint:all -cp "$JAR" -d build/classes @build/fontes-main.txt
echo "Programa compilado em build/classes"

if [ "${1:-}" = "testes" ]; then
    rm -rf build/test-classes
    mkdir -p build/test-classes
    find src/test/java -name '*.java' | sort > build/fontes-test.txt
    javac --release 11 -encoding UTF-8 -Xlint:all -cp "build/classes:$JAR" -d build/test-classes @build/fontes-test.txt
    echo "Testes compilados em build/test-classes"
fi
