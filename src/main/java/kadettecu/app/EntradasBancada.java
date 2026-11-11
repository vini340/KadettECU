package kadettecu.app;

/**
 * Valores que a "bancada" fixa nas entradas da ECU (rotação, acelerador, sensores...). Objeto
 * imutável: cada mudança de controle na tela gera um novo, que é entregue à {@link Bancada}.
 */
public final class EntradasBancada {

    private final double rpm;
    private final double velocidadeKmh;
    private final double tpsPct;
    private final double mapKpa;
    private final double ectC;
    private final double iatC;
    private final double bateriaV;
    private final double oleoBar;
    private final double lambda;

    public EntradasBancada(double rpm, double velocidadeKmh, double tpsPct, double mapKpa, double ectC, double iatC,
                           double bateriaV, double oleoBar, double lambda) {
        this.rpm = rpm;
        this.velocidadeKmh = velocidadeKmh;
        this.tpsPct = tpsPct;
        this.mapKpa = mapKpa;
        this.ectC = ectC;
        this.iatC = iatC;
        this.bateriaV = bateriaV;
        this.oleoBar = oleoBar;
        this.lambda = lambda;
    }

    /** Valores de um motor quente parado, com a chave ligada. */
    public static EntradasBancada padrao() {
        return new EntradasBancada(0, 0, 0, 100, 85, 30, 13.8, 0, 1.0);
    }

    public double getRpm() {
        return rpm;
    }

    public double getVelocidadeKmh() {
        return velocidadeKmh;
    }

    public double getTpsPct() {
        return tpsPct;
    }

    public double getMapKpa() {
        return mapKpa;
    }

    public double getEctC() {
        return ectC;
    }

    public double getIatC() {
        return iatC;
    }

    public double getBateriaV() {
        return bateriaV;
    }

    public double getOleoBar() {
        return oleoBar;
    }

    public double getLambda() {
        return lambda;
    }
}
