package kadettecu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;

/**
 * Caixinha numérica da barra de cima (como a fileira de campos pequenos da referência):
 * um rótulo apagado e, ao lado, o valor em fonte de largura fixa.
 */
public final class CaixaInfo extends JComponent {

    private static final long serialVersionUID = 1L;

    private final String rotulo;
    private final int largura;
    private String valor = "--";
    private Color corValor = Tema.TEXTO;

    public CaixaInfo(String rotulo, int caracteresDoValor) {
        this.rotulo = rotulo;
        setOpaque(false);
        FontMetrics fmRotulo = getFontMetrics(Tema.fonteTitulo(9.5f));
        FontMetrics fmValor = getFontMetrics(Tema.fonteMono(java.awt.Font.BOLD, 11.5f));
        this.largura = fmRotulo.stringWidth(rotulo) + fmValor.charWidth('0') * caracteresDoValor + 22;
        setPreferredSize(new Dimension(largura, 22));
        setMinimumSize(getPreferredSize());
    }

    public void setValor(String valor) {
        if (!valor.equals(this.valor)) {
            this.valor = valor;
            repaint();
        }
    }

    public void setCorValor(Color cor) {
        if (!cor.equals(this.corValor)) {
            this.corValor = cor;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        g.setColor(Tema.FUNDO_CAMPO);
        g.fillRect(0, 2, w - 1, h - 4);
        g.setColor(Tema.MOLDURA);
        g.drawRect(0, 2, w - 2, h - 5);
        g.setFont(Tema.fonteTitulo(9.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Tema.TEXTO_FRACO);
        float base = (h + fm.getAscent() - fm.getDescent()) / 2f;
        g.drawString(rotulo, 6, base);
        int xv = 6 + fm.stringWidth(rotulo) + 6;
        g.setFont(Tema.fonteMono(java.awt.Font.BOLD, 11.5f));
        g.setColor(corValor);
        g.drawString(valor, xv, (h + g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2f);
        g.dispose();
    }
}
