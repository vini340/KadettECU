package kadettecu.protocolo;

/** CRC-16/CCITT-FALSE (polinômio 0x1021, valor inicial 0xFFFF). "123456789" resulta em 0x29B1. */
public final class Crc16 {

    private Crc16() {
    }

    public static int calcular(byte[] dados, int inicio, int tamanho) {
        int crc = 0xFFFF;
        for (int i = inicio; i < inicio + tamanho; i++) {
            crc ^= (dados[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public static int calcular(byte[] dados) {
        return calcular(dados, 0, dados.length);
    }
}
