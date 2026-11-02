package kadettecu.ui;

import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;

/**
 * Borda de painel no estilo da referência: um retângulo fino cor de aço e, saindo da borda
 * superior esquerda, uma "aba" azul-clara com o título em letras escuras (como os botões
 * STAGE 1, STAGE 2 e STAGE 3).
 */
public final class MolduraTech extends AbstractBorder {

    private static final long serialVersionUID = 1L;
    private static final int ALTURA_TITULO = 18;

    private final String titulo;

    public MolduraTech(String titulo) {
        this.titulo = titulo == null ? "" : titulo;
    }

    @Override
    public void paintBorder(Component c, Graphics g0, int x, int y, int largura, int altura) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int topo = titulo.isEmpty() ? 0 : ALTURA_TITULO / 2;
        g.setColor(Tema.MOLDURA);
        g.drawRect(x + 1, y + topo, largura - 3, altura - topo - 2);
        g.setColor(Tema.comAlfa(Tema.MOLDURA_CLARA, 70));
        g.drawLine(x + 2, y + topo + 1, x + largura - 4, y + topo + 1);
        if (!titulo.isEmpty()) {
            g.setFont(Tema.fonteTitulo(10.5f));
            FontMetrics fm = g.getFontMetrics();
            int larguraAba = fm.stringWidth(titulo) + 18;
            g.setColor(Tema.CELULA);
            g.fillRect(x + 10, y, larguraAba, ALTURA_TITULO);
            g.setColor(Tema.comAlfa(Tema.FUNDO, 90));
            g.drawRect(x + 10, y, larguraAba - 1, ALTURA_TITULO - 1);
            g.setColor(Tema.FUNDO);
            g.drawString(titulo, x + 19, y + (ALTURA_TITULO + fm.getAscent() - fm.getDescent()) / 2f);
        }
        g.dispose();
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(titulo.isEmpty() ? 6 : ALTURA_TITULO + 5, 9, 9, 9);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        Insets base = getBorderInsets(c);
        insets.set(base.top, base.left, base.bottom, base.right);
        return insets;
    }
}
