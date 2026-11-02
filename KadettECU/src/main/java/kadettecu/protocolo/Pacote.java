package kadettecu.protocolo;

/** Um quadro do protocolo, imutável: tipo, número de sequência e payload. */
public final class Pacote {

    private final int tipo;
    private final int seq;
    private final byte[] payload;

    public Pacote(int tipo, int seq, byte[] payload) {
        if (tipo < 0 || tipo > 0xFF) {
            throw new IllegalArgumentException("Tipo fora de 0..255: " + tipo);
        }
        byte[] dados = payload == null ? new byte[0] : payload;
        if (dados.length > Protocolo.PAYLOAD_MAX) {
            throw new IllegalArgumentException("Payload maior que " + Protocolo.PAYLOAD_MAX + " bytes");
        }
        this.tipo = tipo;
        this.seq = seq & 0xFF;
        this.payload = dados.clone();
    }

    public int getTipo() {
        return tipo;
    }

    public int getSeq() {
        return seq;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    public int getTamanhoPayload() {
        return payload.length;
    }

    /** Monta os bytes que vão pela serial, já com SOF e CRC. */
    public byte[] codificar() {
        int len = payload.length;
        byte[] quadro = new byte[Protocolo.TAMANHO_CABECALHO + len + Protocolo.TAMANHO_CRC];
        quadro[0] = (byte) Protocolo.SOF1;
        quadro[1] = (byte) Protocolo.SOF2;
        quadro[2] = (byte) len;
        quadro[3] = (byte) tipo;
        quadro[4] = (byte) seq;
        System.arraycopy(payload, 0, quadro, Protocolo.TAMANHO_CABECALHO, len);
        int crc = Crc16.calcular(quadro, 2, 3 + len);
        quadro[5 + len] = (byte) crc;
        quadro[6 + len] = (byte) (crc >> 8);
        return quadro;
    }

    @Override
    public String toString() {
        return String.format("Pacote[tipo=0x%02X seq=%d payload=%d bytes]", tipo, seq, payload.length);
    }
}
