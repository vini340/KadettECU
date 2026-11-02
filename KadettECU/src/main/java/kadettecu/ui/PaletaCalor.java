package kadettecu.ui;

import java.awt.Color;

/**
 * Escala de cores "mapa de calor" usada nas tabelas e no gráfico 3D: azul (valor baixo),
 * ciano, verde, amarelo, laranja e vermelho (valor alto). As metades correspondem aos dois
 * quadradinhos de legenda das telas de referência (azul-verde e laranja-vermelho).
 */
public final class PaletaCalor {

    private static final Color[] PARADAS = {
        new Color(0x2146D9), new Color(0x1FA8E8), new Color(0x2FD08A), new Color(0xB8E23B),
        new Color(0xFFC21F), new Color(0xFF7A1A), new Color(0xE5322D)
    };
    private static final int PASSOS = 256;
    private static final Color[] TABELA = new Color[PASSOS];

    static {
        for (int i = 0; i < PASSOS; i++) {
            double t = i / (PASSOS - 1.0) * (PARADAS.length - 1);
            int a = Math.min((int) Math.floor(t), PARADAS.length - 2);
            TABELA[i] = Tema.misturar(PARADAS[a], PARADAS[a + 1], t - a);
        }
    }

    private PaletaCalor() {
    }

    /** Cor para t entre 0 (valor mais baixo) e 1 (mais alto); fora disso, fica nas pontas. */
    public static Color cor(double t) {
        double k = Math.max(0.0, Math.min(1.0, t));
        return TABELA[(int) Math.round(k * (PASSOS - 1))];
    }

    /** A mesma cor, misturada com o azul-claro das células, para fundos de tabela legíveis. */
    public static Color corSuave(double t, double forca) {
        return Tema.misturar(Tema.CELULA_CLARA, cor(t), forca);
    }

    /** Cor inicial e final da metade "fria" (azul para verde), como o quadradinho direito da referência. */
    public static Color friaInicio() {
        return PARADAS[0];
    }

    public static Color friaFim() {
        return PARADAS[2];
    }

    /** Cor inicial e final da metade "quente" (laranja para vermelho), como o quadradinho esquerdo da referência. */
    public static Color quenteInicio() {
        return PARADAS[5];
    }

    public static Color quenteFim() {
        return PARADAS[6];
    }
}
