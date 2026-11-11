@echo off
rem testar.bat - roda os testes em Java (TesteKadettECU, TesteSistema, TesteInterface, TesteIntegracaoFirmware e TesteSerialReal).
rem O TesteSerialReal usa um pseudo-terminal (so existe no Linux e no macOS): no Windows ele avisa e termina sem testar nada.
rem
rem O TesteIntegracaoFirmware compila o firmware em C para o PC e por isso precisa de gcc e make no PATH (por exemplo, MSYS2
rem ou MinGW); sem eles, esse teste avisa e termina sem testar nada. Os testes em C das placas (make -C firmware\teste_placas test)
rem tambem precisam de gcc/g++/make: no Windows e mais simples rodar o testar.sh dentro do WSL ou do Git Bash.
rem ATENCAO: este script nao foi testado no Windows (foi escrito no Linux); a versao .sh foi.
setlocal
cd /d "%~dp0"

call compilar.bat testes
if errorlevel 1 exit /b 1

set CP=build\test-classes;build\classes;libs\jSerialComm-2.11.0.jar
for %%T in (TesteKadettECU TesteSistema TesteInterface TesteIntegracaoFirmware TesteSerialReal) do (
    echo === %%T
    java -cp "%CP%" kadettecu.teste.%%T
    if errorlevel 1 (
        echo FALHOU: %%T
        exit /b 1
    )
)
echo.
echo Todos os testes terminaram sem falhas.
endlocal
