#!/usr/bin/env python3
"""
ponte_pty.py - cria uma "porta serial virtual" (pseudo-terminal) ligada ao ecu_host.

Para que serve: testar, sem placa e sem cabo, a classe do aplicativo que abre a porta serial de verdade
(PortaJSerialComm, que usa a biblioteca jSerialComm). O sistema operacional trata o lado "escravo" do
pseudo-terminal (/dev/pts/N no Linux) como uma porta serial comum; o que o aplicativo escreve nele chega
ao ecu_host (o firmware em C, rodando no PC) e o que o ecu_host responde volta pelo mesmo caminho.

Uso:
    python3 -I firmware/host/ponte_pty.py <caminho do ecu_host> [argumentos do ecu_host, ex.: --rpm 3000]

A PRIMEIRA linha escrita na saída padrão é o nome do dispositivo a abrir (por exemplo, /dev/pts/3).
A ponte termina quando a entrada padrão é fechada, quando o ecu_host termina ou depois de 5 minutos.
Terminar a ponte equivale a arrancar o cabo USB: o lado escravo passa a dar erro de leitura.

Só funciona em Linux e macOS (o módulo "pty" não existe no Windows).
"""
import os
import select
import subprocess
import sys
import time

try:
    import pty
    import tty
except ImportError:  # Windows
    sys.stderr.write("ponte_pty.py: este sistema não tem pseudo-terminais (Linux e macOS têm).\n")
    sys.exit(2)

LIMITE_S = 300


def main():
    if len(sys.argv) < 2:
        sys.stderr.write(__doc__)
        return 2
    mestre, escravo = pty.openpty()
    tty.setraw(escravo)
    # O lado escravo fica aberto aqui também: se ficasse só com o aplicativo, o fechamento dele faria o mestre
    # receber erro de leitura antes da hora.
    sys.stdout.write(os.ttyname(escravo) + "\n")
    sys.stdout.flush()

    ecu = subprocess.Popen(sys.argv[1:], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           bufsize=0)
    fd_ecu = ecu.stdout.fileno()
    fim = time.time() + LIMITE_S
    try:
        while time.time() < fim and ecu.poll() is None:
            pronto, _, _ = select.select([mestre, fd_ecu, 0], [], [], 0.05)
            if 0 in pronto and not os.read(0, 4096):
                break  # quem nos iniciou fechou a entrada: é o sinal para terminar
            if mestre in pronto:
                try:
                    dados = os.read(mestre, 4096)
                except OSError:
                    dados = b""
                if dados:
                    ecu.stdin.write(dados)
                    ecu.stdin.flush()
            if fd_ecu in pronto:
                dados = os.read(fd_ecu, 4096)
                if not dados:
                    break
                os.write(mestre, dados)
    except (BrokenPipeError, OSError):
        pass
    finally:
        try:
            ecu.stdin.close()
        except OSError:
            pass
        try:
            ecu.wait(timeout=2)
        except subprocess.TimeoutExpired:
            ecu.kill()
        os.close(mestre)
        os.close(escravo)
    return 0


if __name__ == "__main__":
    sys.exit(main())
