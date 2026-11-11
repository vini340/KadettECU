package kadettecu.modelo;

import java.util.Arrays;
import kadettecu.protocolo.BufferLE;

/** Conjunto de parâmetros do motor (corte de giro, launch, enriquecimento, marcha lenta...). */
public final class ParametrosMotor {

    public static final int TAMANHO = CampoParametro.tamanhoTotal();

    private final int[] valores = new int[CampoParametro.values().length];

    private ParametrosMotor() {
    }

    public static ParametrosMotor padrao() {
        ParametrosMotor p = new ParametrosMotor();
        for (CampoParametro c : CampoParametro.values()) {
            p.valores[c.ordinal()] = c.getPadrao();
        }
        return p;
    }

    public int get(CampoParametro campo) {
        return valores[campo.ordinal()];
    }

    public boolean getBool(CampoParametro campo) {
        return get(campo) != 0;
    }

    public double getFisico(CampoParametro campo) {
        return get(campo) / (double) campo.getDivisor();
    }

    public void set(CampoParametro campo, int valor) {
        if (!campo.dentroDaFaixa(valor)) {
            throw new IllegalArgumentException(campo.getRotulo() + ": " + valor + " fora da faixa "
                    + campo.getMin() + ".." + campo.getMax());
        }
        valores[campo.ordinal()] = valor;
    }

    public void setBool(CampoParametro campo, boolean valor) {
        set(campo, valor ? 1 : 0);
    }

    public ParametrosMotor copia() {
        ParametrosMotor p = new ParametrosMotor();
        System.arraycopy(valores, 0, p.valores, 0, valores.length);
        return p;
    }

    public void copiarDe(ParametrosMotor outro) {
        System.arraycopy(outro.valores, 0, valores, 0, valores.length);
    }

    public byte[] paraBytes() {
        BufferLE b = BufferLE.paraEscrita(TAMANHO);
        for (CampoParametro c : CampoParametro.values()) {
            if (c.getTipo().getBytes() == 2) {
                b.escreverU16(get(c));
            } else {
                b.escreverU8(get(c));
            }
        }
        return b.bytes();
    }

    public static ParametrosMotor deBytes(byte[] payload) {
        if (payload.length != TAMANHO) {
            throw new IllegalArgumentException("Parâmetros devem ter " + TAMANHO + " bytes, vieram "
                    + payload.length);
        }
        BufferLE b = BufferLE.paraLeitura(payload);
        ParametrosMotor p = new ParametrosMotor();
        for (CampoParametro c : CampoParametro.values()) {
            p.set(c, c.getTipo().getBytes() == 2 ? b.lerU16() : b.lerU8());
        }
        return p;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ParametrosMotor && Arrays.equals(valores, ((ParametrosMotor) o).valores);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(valores);
    }
}
