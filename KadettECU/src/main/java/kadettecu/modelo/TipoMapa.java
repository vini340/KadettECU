package kadettecu.modelo;

import java.util.Locale;

/**
 * Tipos de tabela 16x16. Os valores trafegam como byte (0..255); aqui ficam as regras
 * de conversão para a grandeza física: fisico = deslocamento + raw * escala.
 */
public enum TipoMapa {

    COMBUSTIVEL(0, "COMBUSTÍVEL (VE)", "%", 0.0, 1.0, 0, 20, 110),
    IGNICAO(1, "IGNIÇÃO (AVANÇO)", "°", -10.0, 0.5, 1, 5, 40),
    ALVO_LAMBDA(2, "ALVO LAMBDA", "λ", 0.60, 0.005, 2, 0.80, 1.10);

    private final int id;
    private final String rotulo;
    private final String unidade;
    private final double deslocamento;
    private final double escala;
    private final int casasDecimais;
    private final double corMinimo;
    private final double corMaximo;

    TipoMapa(int id, String rotulo, String unidade, double deslocamento, double escala, int casasDecimais,
             double corMinimo, double corMaximo) {
        this.id = id;
        this.rotulo = rotulo;
        this.unidade = unidade;
        this.deslocamento = deslocamento;
        this.escala = escala;
        this.casasDecimais = casasDecimais;
        this.corMinimo = corMinimo;
        this.corMaximo = corMaximo;
    }

    public static TipoMapa porId(int id) {
        for (TipoMapa t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        throw new IllegalArgumentException("Mapa desconhecido: " + id);
    }

    public int getId() {
        return id;
    }

    public String getRotulo() {
        return rotulo;
    }

    public String getUnidade() {
        return unidade;
    }

    /** Menor passo editável na grandeza física (1 unidade de raw). */
    public double getPasso() {
        return escala;
    }

    public double rawParaFisico(double raw) {
        return deslocamento + raw * escala;
    }

    public int fisicoParaRaw(double fisico) {
        long raw = Math.round((fisico - deslocamento) / escala);
        return (int) Math.max(0, Math.min(255, raw));
    }

    public String formatar(int raw) {
        return String.format(Locale.ROOT, "%." + casasDecimais + "f", rawParaFisico(raw));
    }

    /** Posição de 0 a 1 do valor dentro da faixa típica (para escala de cores e para o 3D). */
    public double normalizar(double raw) {
        double fisico = rawParaFisico(raw);
        double t = (fisico - corMinimo) / (corMaximo - corMinimo);
        return Math.max(0.0, Math.min(1.0, t));
    }
}
