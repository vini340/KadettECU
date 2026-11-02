package kadettecu.modelo;

import java.util.Arrays;
import kadettecu.protocolo.BufferLE;
import kadettecu.protocolo.Protocolo;

/** Os dois eixos compartilhados por todas as tabelas: RPM (colunas) e carga/MAP em kPa (linhas). */
public final class EixosMapa {

    public static final int TAMANHO = Protocolo.TAMANHO_MAPA;

    private final int[] rpm;
    private final int[] carga;

    private EixosMapa(int[] rpm, int[] carga) {
        this.rpm = rpm;
        this.carga = carga;
    }

    public static EixosMapa padrao() {
        int[] rpm = {500, 800, 1100, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500, 7000, 7500};
        int[] carga = new int[TAMANHO];
        for (int i = 0; i < TAMANHO; i++) {
            carga[i] = 20 + 6 * i;
        }
        return new EixosMapa(rpm, carga);
    }

    public static EixosMapa deValores(int[] rpm, int[] carga) {
        EixosMapa e = new EixosMapa(rpm.clone(), carga.clone());
        e.validar();
        return e;
    }

    public void copiarDe(EixosMapa outro) {
        System.arraycopy(outro.rpm, 0, rpm, 0, TAMANHO);
        System.arraycopy(outro.carga, 0, carga, 0, TAMANHO);
    }

    public EixosMapa copia() {
        return new EixosMapa(rpm.clone(), carga.clone());
    }

    public int getRpm(int indice) {
        return rpm[indice];
    }

    public int getCarga(int indice) {
        return carga[indice];
    }

    public void setRpm(int indice, int valor) {
        rpm[indice] = valor;
    }

    public void setCarga(int indice, int valor) {
        carga[indice] = valor;
    }

    public int[] getRpmArray() {
        return rpm.clone();
    }

    public int[] getCargaArray() {
        return carga.clone();
    }

    /** Eixos precisam ser estritamente crescentes e caber em 16 bits. */
    public void validar() {
        validarEixo(rpm, "RPM");
        validarEixo(carga, "carga");
    }

    private static void validarEixo(int[] eixo, String nome) {
        if (eixo.length != TAMANHO) {
            throw new IllegalArgumentException("Eixo de " + nome + " deve ter " + TAMANHO + " pontos");
        }
        for (int i = 0; i < eixo.length; i++) {
            if (eixo[i] < 0 || eixo[i] > 65535) {
                throw new IllegalArgumentException("Eixo de " + nome + ": valor fora de 0..65535");
            }
            if (i > 0 && eixo[i] <= eixo[i - 1]) {
                throw new IllegalArgumentException("Eixo de " + nome + " precisa ser crescente");
            }
        }
    }

    public double posicaoRpm(double valor) {
        return posicao(rpm, valor);
    }

    public double posicaoCarga(double valor) {
        return posicao(carga, valor);
    }

    /** Índice fracionário (0..15) de um valor dentro do eixo, limitado às pontas. */
    static double posicao(int[] eixo, double valor) {
        int ultimo = eixo.length - 1;
        if (valor <= eixo[0]) {
            return 0;
        }
        if (valor >= eixo[ultimo]) {
            return ultimo;
        }
        for (int i = 0; i < ultimo; i++) {
            if (valor < eixo[i + 1]) {
                return i + (valor - eixo[i]) / (double) (eixo[i + 1] - eixo[i]);
            }
        }
        return ultimo;
    }

    public byte[] paraBytes() {
        BufferLE b = BufferLE.paraEscrita(4 * TAMANHO);
        for (int v : rpm) {
            b.escreverU16(v);
        }
        for (int v : carga) {
            b.escreverU16(v);
        }
        return b.bytes();
    }

    public static EixosMapa deBytes(byte[] payload) {
        if (payload.length != 4 * TAMANHO) {
            throw new IllegalArgumentException("Eixos devem ter " + 4 * TAMANHO + " bytes");
        }
        BufferLE b = BufferLE.paraLeitura(payload);
        int[] rpm = new int[TAMANHO];
        int[] carga = new int[TAMANHO];
        for (int i = 0; i < TAMANHO; i++) {
            rpm[i] = b.lerU16();
        }
        for (int i = 0; i < TAMANHO; i++) {
            carga[i] = b.lerU16();
        }
        return deValores(rpm, carga);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EixosMapa && Arrays.equals(rpm, ((EixosMapa) o).rpm)
                && Arrays.equals(carga, ((EixosMapa) o).carga);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(rpm) + Arrays.hashCode(carga);
    }
}
