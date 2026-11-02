package kadettecu.ui;

import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;

/**
 * Tema escuro para o Metal. O Metal deriva todas as cores de seis tons básicos mais "preto" e
 * "branco"; aqui o "preto" (cor do texto) é o azul-claro e o "branco" (fundo dos campos) é o
 * azul quase preto, ou seja, as duas pontas estão trocadas.
 */
final class TemaMetal extends DefaultMetalTheme {

    private static final ColorUIResource PRIMARIA_1 = new ColorUIResource(Tema.MOLDURA);
    private static final ColorUIResource PRIMARIA_2 = new ColorUIResource(0x1F4E7C);
    private static final ColorUIResource PRIMARIA_3 = new ColorUIResource(0x2B6CA3);
    private static final ColorUIResource SECUNDARIA_1 = new ColorUIResource(Tema.MOLDURA);
    private static final ColorUIResource SECUNDARIA_2 = new ColorUIResource(Tema.MOLDURA_ESCURA);
    private static final ColorUIResource SECUNDARIA_3 = new ColorUIResource(Tema.FUNDO_PAINEL);
    private static final ColorUIResource PRETO = new ColorUIResource(Tema.TEXTO);
    private static final ColorUIResource BRANCO = new ColorUIResource(Tema.FUNDO_CAMPO);

    @Override
    public String getName() {
        return "KadettECU";
    }

    @Override
    protected ColorUIResource getPrimary1() {
        return PRIMARIA_1;
    }

    @Override
    protected ColorUIResource getPrimary2() {
        return PRIMARIA_2;
    }

    @Override
    protected ColorUIResource getPrimary3() {
        return PRIMARIA_3;
    }

    @Override
    protected ColorUIResource getSecondary1() {
        return SECUNDARIA_1;
    }

    @Override
    protected ColorUIResource getSecondary2() {
        return SECUNDARIA_2;
    }

    @Override
    protected ColorUIResource getSecondary3() {
        return SECUNDARIA_3;
    }

    @Override
    protected ColorUIResource getBlack() {
        return PRETO;
    }

    @Override
    protected ColorUIResource getWhite() {
        return BRANCO;
    }

    @Override
    public FontUIResource getControlTextFont() {
        return new FontUIResource(Tema.fonteUI(java.awt.Font.PLAIN, 12.5f));
    }

    @Override
    public FontUIResource getSystemTextFont() {
        return getControlTextFont();
    }

    @Override
    public FontUIResource getUserTextFont() {
        return new FontUIResource(Tema.fonteMono(java.awt.Font.PLAIN, 12.5f));
    }

    @Override
    public FontUIResource getMenuTextFont() {
        return getControlTextFont();
    }

    @Override
    public FontUIResource getWindowTitleFont() {
        return new FontUIResource(Tema.fonteUI(java.awt.Font.BOLD, 12.5f));
    }

    @Override
    public FontUIResource getSubTextFont() {
        return new FontUIResource(Tema.fonteUI(java.awt.Font.PLAIN, 11f));
    }
}
