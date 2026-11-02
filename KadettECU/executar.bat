@echo off
rem executar.bat - abre o KadettECU (compila antes se ainda nao foi compilado).
rem
rem   executar.bat                painel completo (instrumentos, mapas, parametros, binario, registro)
rem   executar.bat --simulador    painel ja conectado ao simulador em Java: nao precisa de placa nenhuma
rem   executar.bat --legado       o analisador de binario original (janela + console)
rem   executar.bat --console      so o analisador de binario no console
rem
rem ATENCAO: este script nao foi testado no Windows (foi escrito no Linux); a versao .sh foi.
setlocal
cd /d "%~dp0"

if not exist build\classes call compilar.bat
if errorlevel 1 exit /b 1
java -cp "build\classes;libs\jSerialComm-2.11.0.jar" kadettecu.Inicio %*
endlocal
