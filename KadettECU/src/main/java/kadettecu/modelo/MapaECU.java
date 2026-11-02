package kadettecu.modelo;

import java.util.Arrays;
import kadettecu.protocolo.Protocolo;

/**
 * Uma tabela 16x16 de bytes. A linha é o índice de carga (MAP) e a coluna é o índice de RPM:
 * raw[linha][coluna]. As conversões físicas ficam no {@link TipoMapa}.
 */
public final class MapaECU {

    public static final int TAMANHO = Protocolo.TAMANHO_MAPA;

    private static final int[] FORMA_VE = {0, 4, 8, 12, 16, 19, 22, 24, 25, 24, 22, 18, 12, 6, 0, -6};
    private static final int[] FORMA_IGN = {0, 2, 4, 6, 9, 11, 13, 15, 17, 18, 19, 20, 21, 21, 20, 20};

    private final TipoMapa tipo;
    private final int[][] raw;

    private MapaECU(TipoMapa tipo, int[][] raw) {
        this.tipo = tipo;
        this.raw = raw;
    }

    /** Tabela de partida conservadora. O firmware usa as mesmas fórmulas (kecu_maps.c). */
    public static MapaECU padrao(TipoMapa tipo) {
        int[][] r = new int[TAMANHO][TAMANHO];
        for (int linha = 0; linha < TAMANHO; linha++) {
            for (int col = 0; col < TAMANHO; col++) {
                switch (tipo) {
                    case COMBUSTIVEL:
                        r[linha][col] = Math.max(20, Math.min(110, 40 + (linha * 30) / 15 + FORMA_VE[col]));
                        break;
                    case IGNICAO:
                        r[linha][col] = (14 + FORMA_IGN[col] - (linha * 8) / 15 + 10) * 2;
                        break;
                    default:
                        r[linha][col] = 80 - (linha >= 10 ? (linha - 9) * 4 : 0);
                        break;
                }
            }
        }
        return new MapaECU(tipo, r);
    }

    public TipoMapa getTipo() {
        return tipo;
    }

    public int getRaw(int linha, int coluna) {
        return raw[linha][coluna];
    }

    public void setRaw(int linha, int coluna, int valor) {
        raw[linha][coluna] = Math.max(0, Math.min(255, valor));
    }

    public double getFisico(int linha, int coluna) {
        return tipo.rawParaFisico(raw[linha][coluna]);
    }

    public byte[] linhaParaBytes(int linha) {
        byte[] b = new byte[TAMANHO];
        for (int c = 0; c < TAMANHO; c++) {
            b[c] = (byte) raw[linha][c];
        }
        return b;
    }

    public void linhaDeBytes(int linha, byte[] bytes) {
        if (bytes.length != TAMANHO) {
            throw new IllegalArgumentException("Uma linha tem " + TAMANHO + " bytes");
        }
        for (int c = 0; c < TAMANHO; c++) {
            raw[linha][c] = bytes[c] & 0xFF;
        }
    }

    /** Copia todos os valores de outra tabela do mesmo tipo (usado pelo "desfazer"). */
    public void copiarDe(MapaECU outro) {
        if (outro.tipo != tipo) {
            throw new IllegalArgumentException("Tipos de mapa diferentes");
        }
        for (int l = 0; l < TAMANHO; l++) {
            System.arraycopy(outro.raw[l], 0, raw[l], 0, TAMANHO);
        }
    }

    /** true se a linha tem algum valor diferente da mesma linha da outra tabela. */
    public boolean linhaDiferente(int linha, MapaECU outro) {
        return !Arrays.equals(raw[linha], outro.raw[linha]);
    }

    public int minimoRaw() {
        int m = 255;
        for (int[] linha : raw) {
            for (int v : linha) {
                m = Math.min(m, v);
            }
        }
        return m;
    }

    public int maximoRaw() {
        int m = 0;
        for (int[] linha : raw) {
            for (int v : linha) {
                m = Math.max(m, v);
            }
        }
        return m;
    }

    /** Interpolação bilinear em índices fracionários (posCarga = linha, posRpm = coluna). */
    public double interpolarRaw(double posRpm, double posCarga) {
        int c0 = (int) Math.min(Math.floor(posRpm), TAMANHO - 2);
        int l0 = (int) Math.min(Math.floor(posCarga), TAMANHO - 2);
        double fc = posRpm - c0;
        double fl = posCarga - l0;
        double topo = raw[l0][c0] * (1 - fc) + raw[l0][c0 + 1] * fc;
        double base = raw[l0 + 1][c0] * (1 - fc) + raw[l0 + 1][c0 + 1] * fc;
        return topo * (1 - fl) + base * fl;
    }

    public double interpolarFisico(double posRpm, double posCarga) {
        return tipo.rawParaFisico(interpolarRaw(posRpm, posCarga));
    }

    public MapaECU copia() {
        int[][] r = new int[TAMANHO][];
        for (int i = 0; i < TAMANHO; i++) {
            r[i] = raw[i].clone();
        }
        return new MapaECU(tipo, r);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MapaECU && tipo == ((MapaECU) o).tipo && Arrays.deepEquals(raw, ((MapaECU) o).raw);
    }

    @Override
    public int hashCode() {
        return 31 * tipo.hashCode() + Arrays.deepHashCode(raw);
    }
}
