package kadettecu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import javax.swing.JComponent;

/** Luz de aviso com texto (SYNC, CORTE, ARMADO...). Pode ficar apagada, acesa ou piscando. */
public final class Lampada extends JComponent {

    private static final long serialVersionUID = 1L;

    public enum Modo { APAGADA, ACESA, PISCANDO }

    private final String rotulo;
    private final Color cor;
    private Modo modo = Modo.APAGADA;

    public Lampada(String rotulo, Color cor) {
        this.rotulo = rotulo;
        this.cor = cor;
        setOpaque(false);
        setFont(Tema.fonteTitulo(10.5f));
        FontMetrics fm = getFontMetrics(getFont());
        setPreferredSize(new Dimension(fm.stringWidth(rotulo) + 30, 22));
        setMinimumSize(getPreferredSize());
    }

    public void setModo(Modo modo) {
        if (modo != this.modo) {
            this.modo = modo;
            repaint();
        }
    }

    public void setAcesa(boolean acesa) {
        setModo(acesa ? Modo.ACESA : Modo.APAGADA);
    }

    public Modo getModo() {
        return modo;
    }

    private boolean luzLigada() {
        if (modo == Modo.PISCANDO) {
            return ((System.currentTimeMillis() / 220) & 1) == 0;
        }
        return modo == Modo.ACESA;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int h = getHeight();
        double d = 12;
        double x = 4;
        double y = (h - d) / 2.0;
        boolean ligada = luzLigada();
        if (ligada) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x + d / 2, y + d / 2), (float) (d * 1.5),
                    new float[] {0f, 1f}, new Color[] {Tema.comAlfa(cor, 120), Tema.comAlfa(cor, 0)}));
            g.fill(new Ellipse2D.Double(x - d, y - d, d * 3, d * 3));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x + d * 0.4, y + d * 0.35), (float) d,
                    new float[] {0f, 1f}, new Color[] {Tema.clarear(cor, 0.6), cor}));
        } else {
            g.setColor(Tema.misturar(Tema.FUNDO, cor, 0.14));
        }
        g.fill(new Ellipse2D.Double(x, y, d, d));
        g.setColor(ligada ? Tema.clarear(cor, 0.3) : Tema.MOLDURA_ESCURA);
        g.draw(new Ellipse2D.Double(x, y, d, d));
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        g.setColor(ligada ? Tema.TEXTO : Tema.TEXTO_FRACO);
        g.drawString(rotulo, (float) (x + d + 7), (h + fm.getAscent() - fm.getDescent()) / 2f);
        g.dispose();
    }
}
