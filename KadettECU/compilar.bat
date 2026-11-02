@echo off
rem compilar.bat - compila o KadettECU so com o JDK (11 ou mais novo), sem Maven.
rem
rem   compilar.bat            compila o programa em build\classes
rem   compilar.bat testes     compila tambem os programas de teste em build\test-classes
rem
rem A biblioteca de porta serial (jSerialComm) ja vem em libs\. Para abrir o programa: executar.bat
rem ATENCAO: este script nao foi testado no Windows (foi escrito no Linux); a versao .sh foi.
setlocal
cd /d "%~dp0"

set JAR=libs\jSerialComm-2.11.0.jar
if not exist "%JAR%" (
    echo Nao achei %JAR% ^(a biblioteca de porta serial^). Veja libs\LEIA-ME.txt.
    exit /b 1
)

if exist build\classes rmdir /s /q build\classes
mkdir build\classes
rem Compila a partir das classes de entrada: o javac acha o resto pelo -sourcepath.
javac --release 11 -encoding UTF-8 -Xlint:all -cp "%JAR%" -sourcepath src\main\java -d build\classes src\main\java\kadettecu\Inicio.java
if errorlevel 1 exit /b 1
echo Programa compilado em build\classes

if /i "%~1"=="testes" (
    if exist build\test-classes rmdir /s /q build\test-classes
    mkdir build\test-classes
    javac --release 11 -encoding UTF-8 -Xlint:all -cp "build\classes;%JAR%" -sourcepath src\test\java -d build\test-classes ^
        src\test\java\kadettecu\teste\TesteKadettECU.java src\test\java\kadettecu\teste\TesteSistema.java ^
        src\test\java\kadettecu\teste\TesteInterface.java src\test\java\kadettecu\teste\TesteIntegracaoFirmware.java ^
        src\test\java\kadettecu\teste\TesteSerialReal.java
    if errorlevel 1 exit /b 1
    echo Testes compilados em build\test-classes
)
endlocal
