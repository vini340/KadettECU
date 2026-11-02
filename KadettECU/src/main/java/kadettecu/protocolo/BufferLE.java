package kadettecu.protocolo;

import java.util.Arrays;

/** Leitura e escrita de inteiros em little-endian, o formato usado no protocolo. */
public final class BufferLE {

    private final byte[] dados;
    private int pos;

    private BufferLE(byte[] dados) {
        this.dados = dados;
    }

    public static BufferLE paraEscrita(int capacidade) {
        return new BufferLE(new byte[capacidade]);
    }

    public static BufferLE paraLeitura(byte[] dados) {
        return new BufferLE(dados);
    }

    public BufferLE escreverU8(int valor) {
        dados[pos++] = (byte) valor;
        return this;
    }

    public BufferLE escreverU16(int valor) {
        dados[pos++] = (byte) valor;
        dados[pos++] = (byte) (valor >> 8);
        return this;
    }

    public BufferLE escreverS16(int valor) {
        return escreverU16(valor & 0xFFFF);
    }

    public BufferLE escreverU32(long valor) {
        escreverU16((int) (valor & 0xFFFF));
        return escreverU16((int) ((valor >> 16) & 0xFFFF));
    }

    public BufferLE escreverBytes(byte[] origem) {
        System.arraycopy(origem, 0, dados, pos, origem.length);
        pos += origem.length;
        return this;
    }

    public int lerU8() {
        return dados[pos++] & 0xFF;
    }

    public int lerU16() {
        int baixo = lerU8();
        return baixo | (lerU8() << 8);
    }

    public int lerS16() {
        return (short) lerU16();
    }

    public long lerU32() {
        long baixo = lerU16();
        return baixo | ((long) lerU16() << 16);
    }

    public byte[] lerBytes(int quantidade) {
        byte[] saida = Arrays.copyOfRange(dados, pos, pos + quantidade);
        pos += quantidade;
        return saida;
    }

    public int posicao() {
        return pos;
    }

    public int restante() {
        return dados.length - pos;
    }

    /** Bytes escritos até agora. */
    public byte[] bytes() {
        return Arrays.copyOf(dados, pos);
    }
}
