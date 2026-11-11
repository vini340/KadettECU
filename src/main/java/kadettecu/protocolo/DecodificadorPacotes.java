package kadettecu.protocolo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Remonta quadros a partir do fluxo de bytes da serial. Tolera ruído, quadros partidos
 * em vários blocos e quadros colados; ao falhar o CRC, ressincroniza no próximo SOF.
 * Não é thread-safe: use uma instância por conexão, alimentada por uma única thread
 * (os contadores são volatile só para a interface poder lê-los de outra thread).
 */
public final class DecodificadorPacotes {

    private final byte[] buf = new byte[2 * (Protocolo.TAMANHO_CABECALHO + Protocolo.PAYLOAD_MAX
            + Protocolo.TAMANHO_CRC)];
    private int n;
    private volatile long pacotesValidos;
    private volatile long errosCrc;
    private volatile long bytesDescartados;

    public List<Pacote> alimentar(byte[] dados, int tamanho) {
        List<Pacote> saida = new ArrayList<>();
        for (int i = 0; i < tamanho; i++) {
            if (n == buf.length) {
                descartarRuido(1);
            }
            buf[n++] = dados[i];
            extrair(saida);
        }
        return saida;
    }

    private void extrair(List<Pacote> saida) {
        while (true) {
            int ini = 0;
            while (ini + 1 < n && !(u(buf[ini]) == Protocolo.SOF1 && u(buf[ini + 1]) == Protocolo.SOF2)) {
                ini++;
            }
            if (ini + 1 >= n) {
                int manter = (n > 0 && u(buf[n - 1]) == Protocolo.SOF1) ? 1 : 0;
                descartarRuido(n - manter);
                return;
            }
            descartarRuido(ini);
            if (n < Protocolo.TAMANHO_CABECALHO) {
                return;
            }
            int len = u(buf[2]);
            if (len > Protocolo.PAYLOAD_MAX) {
                descartarRuido(1);
                continue;
            }
            int total = Protocolo.TAMANHO_CABECALHO + len + Protocolo.TAMANHO_CRC;
            if (n < total) {
                return;
            }
            int crcRecebido = u(buf[total - 2]) | (u(buf[total - 1]) << 8);
            if (crcRecebido == Crc16.calcular(buf, 2, 3 + len)) {
                saida.add(new Pacote(u(buf[3]), u(buf[4]), Arrays.copyOfRange(buf, 5, 5 + len)));
                pacotesValidos++;
                remover(total);
            } else {
                errosCrc++;
                descartarRuido(1);
            }
        }
    }

    private void remover(int k) {
        if (k <= 0) {
            return;
        }
        System.arraycopy(buf, k, buf, 0, n - k);
        n -= k;
    }

    private void descartarRuido(int k) {
        if (k > 0) {
            bytesDescartados += k;
            remover(k);
        }
    }

    private static int u(byte b) {
        return b & 0xFF;
    }

    public long getPacotesValidos() {
        return pacotesValidos;
    }

    public long getErrosCrc() {
        return errosCrc;
    }

    public long getBytesDescartados() {
        return bytesDescartados;
    }
}
