#!/usr/bin/env python3
"""
exemplo_quadros.py - fala o protocolo do KadettECU "na unha", sem o aplicativo Java.

Serve a dois propósitos:
  1. mostrar, byte a byte, como é cada quadro (os exemplos de docs/PROTOCOLO.md foram gerados por este script);
  2. servir de ponto de partida para você escrever suas próprias ferramentas de bancada.

Uso (Linux, macOS ou WSL; precisa de Python 3 e do firmware em C compilado para o PC):

    make -C firmware/host
    python3 -I docs/exemplos/exemplo_quadros.py firmware/host/build/ecu_host

O script abre o ecu_host (a ECU virtual: o MESMO núcleo em C que roda na placa) como processo filho, manda alguns
comandos pela entrada padrão e mostra as respostas, que chegam pela saída padrão, em hexadecimal. Para falar com uma
placa de verdade o formato dos quadros é exatamente o mesmo; só mudaria o canal (a porta serial, 115200 8N1).
"""
import os
import select
import subprocess
import sys
import time


def crc16(dados):
    """CRC-16/CCITT-FALSE: polinômio 0x1021, valor inicial 0xFFFF, sem inversão. "123456789" -> 0x29B1."""
    crc = 0xFFFF
    for b in dados:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def quadro(tipo, seq, payload=b""):
    """A5 5A | LEN | TIPO | SEQ | PAYLOAD | CRC16 (little-endian). O CRC cobre LEN, TIPO, SEQ e PAYLOAD."""
    corpo = bytes([len(payload), tipo, seq]) + payload
    c = crc16(corpo)
    return bytes([0xA5, 0x5A]) + corpo + bytes([c & 0xFF, c >> 8])


def hexa(dados):
    return " ".join("%02X" % x for x in dados)


def decodificar(buf):
    """Extrai os quadros de um bloco de bytes (versão mínima: o bloco só tem quadros inteiros)."""
    quadros = []
    i = 0
    while i + 7 <= len(buf):
        if buf[i] == 0xA5 and buf[i + 1] == 0x5A:
            n = buf[i + 2]
            total = 5 + n + 2
            if i + total <= len(buf):
                corpo = buf[i + 2:i + 5 + n]
                crc = buf[i + 5 + n] | (buf[i + 6 + n] << 8)
                quadros.append((buf[i + 3], buf[i + 4], buf[i + 5:i + 5 + n], crc == crc16(corpo), buf[i:i + total]))
                i += total
                continue
        i += 1
    return quadros


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    assert crc16(b"123456789") == 0x29B1

    ecu = subprocess.Popen([sys.argv[1], "--rpm", "3000", "--tps", "25", "--ect", "85", "--map", "55"],
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    fd = ecu.stdout.fileno()

    def ler_por(segundos):
        fim = time.time() + segundos
        dados = b""
        while time.time() < fim:
            pronto, _, _ = select.select([fd], [], [], 0.05)
            if pronto:
                dados += os.read(fd, 4096)
        return dados

    def enviar(titulo, tipo, seq, payload=b"", espera=0.35, mostrar_telemetria=False):
        q = quadro(tipo, seq, payload)
        ecu.stdin.write(q)
        ecu.stdin.flush()
        respostas = decodificar(ler_por(espera))
        print("### " + titulo)
        print("PC  -> ECU : " + hexa(q))
        mostradas = 0
        for t, _, _, ok, bruto in respostas:
            if t == 0xF0:
                # a telemetria chega sozinha, uma a cada 20 ms: mostra no máximo duas e conta o resto
                if not mostrar_telemetria or mostradas >= 2:
                    continue
                mostradas += 1
            print("ECU -> PC  : " + hexa(bruto) + ("" if ok else "  (CRC RUIM)"))
        total = sum(1 for r in respostas if r[0] == 0xF0)
        if total:
            print("(%d quadros de telemetria chegaram nesta janela)" % total)
        print()
        return respostas

    time.sleep(0.3)
    enviar("HELLO (seq 1)", 0x01, 1)
    enviar("STREAM: telemetria a cada 20 ms (seq 2)", 0x02, 2, bytes([20, 0]))
    time.sleep(0.2)
    enviar("LER_LINHA: mapa 0 (combustível), linha 5 (seq 3)", 0x20, 3, bytes([0, 5]))
    enviar("LER_EIXOS (seq 4)", 0x22, 4)
    enviar("LER_PARAMS (seq 5)", 0x10, 5)
    enviar("ARMAR 1 em firmware de modo sombra: recusado com NACK 6 (seq 6)", 0x40, 6, bytes([1]))
    enviar("Comando que não existe (0x7E): NACK 1 (seq 7)", 0x7E, 7)
    enviar("ESCREVER_LINHA com payload curto: NACK 2 (seq 8)", 0x21, 8, bytes([0, 5]) + bytes(14))

    respostas = enviar("STREAM de novo, para capturar um quadro de telemetria (seq 9)", 0x02, 9, bytes([20, 0]),
                       espera=0.25, mostrar_telemetria=True)
    telemetrias = [r for r in respostas if r[0] == 0xF0]
    if telemetrias:
        pay = telemetrias[-1][2]

        def u16(o):
            return pay[o] | (pay[o + 1] << 8)

        def s16(o):
            v = u16(o)
            return v - 65536 if v >= 32768 else v

        print("telemetria decodificada: t_ms=%d rpm=%d vel=%.1f ect=%.1f iat=%.1f map=%.1f oleo=%.1f tps=%.1f "
              "lambda=%.3f vbat=%.3f avanco=%.1f pw=%d ve=%d ae=%d iac=%d pos_rpm=%.2f pos_carga=%.2f "
              "flags=0x%04X erros=0x%04X"
              % (u16(0) | (u16(2) << 16), u16(4), u16(6) / 10, s16(8) / 10, s16(10) / 10, u16(12) / 10,
                 u16(14) / 10, u16(16) / 10, u16(18) / 1000, u16(20) / 1000, s16(22) / 10, u16(24), pay[26],
                 pay[27], pay[28], pay[29] / 16, pay[30] / 16, u16(32), u16(34)))

    ecu.stdin.close()
    ecu.wait(timeout=3)
    return 0


if __name__ == "__main__":
    sys.exit(main())
