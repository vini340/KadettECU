package kadettecu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import kadettecu.modelo.TipoMapa;

/**
 * Legenda de cores num quadrinho claro: o bloco laranja-vermelho (valores altos) e o bloco
 * azul-verde (valores baixos), como o pequeno painel de cores da tela de referência.
 */
public final class LegendaCalor extends JComponent {

    private static final long serialVersionUID = 1L;

    private String textoAlto = "ALTO";
    private String textoBaixo = "BAIXO";

    public LegendaCalor() {
        setOpaque(false);
        setPreferredSize(new Dimension(150, 96));
        setMinimumSize(new Dimension(110, 70));
    }

    public void setTextos(String alto, String baixo) {
        this.textoAlto = alto;
        this.textoBaixo = baixo;
        repaint();
    }

    /** Escolhe os textos das pontas conforme a tabela mostrada (valor alto de VE = mistura mais rica, e assim por diante). */
    public void setTipo(TipoMapa tipo) {
        switch (tipo) {
            case COMBUSTIVEL:
                setTextos("RICO", "POBRE");
                break;
            case IGNICAO:
                setTextos("AVANÇADO", "RETARDADO");
                break;
            default:
                setTextos("POBRE", "RICO");
                break;
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(0xEAF1F8), 0, h, new Color(0xC9D8E6)));
        g.fillRect(0, 0, w, h);
        g.setColor(Tema.MOLDURA);
        g.drawRect(0, 0, w - 1, h - 1);

        int margem = 10;
        int meio = w / 2;
        int topo = 10;
        int alturaBloco = h - topo - 24;
        int larguraBloco = meio - margem - 6;
        g.setPaint(new GradientPaint(0, topo, PaletaCalor.quenteInicio(), 0, topo + alturaBloco, PaletaCalor.quenteFim()));
        g.fillRect(margem, topo, larguraBloco, alturaBloco);
        g.setPaint(new GradientPaint(0, topo, PaletaCalor.friaFim(), 0, topo + alturaBloco, PaletaCalor.friaInicio()));
        g.fillRect(meio + 6, topo, larguraBloco, alturaBloco);
        g.setColor(new Color(0x2B3E52));
        g.drawRect(margem, topo, larguraBloco, alturaBloco);
        g.drawRect(meio + 6, topo, larguraBloco, alturaBloco);

        g.setFont(Tema.fonteTitulo(9f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0x2B3E52));
        g.drawString(textoAlto, margem + (larguraBloco - fm.stringWidth(textoAlto)) / 2, h - 8);
        g.drawString(textoBaixo, meio + 6 + (larguraBloco - fm.stringWidth(textoBaixo)) / 2, h - 8);
        g.dispose();
    }
}
