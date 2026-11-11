package kadettecu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * Indicador de mistura em forma de leque, inspirado no "HUD" no canto inferior da tela de
 * referência: nove barras laranja; a do centro é a mistura ideal (lambda 1,00), as da esquerda
 * indicam mistura rica e as da direita, mistura pobre. Acendem do centro até a posição atual.
 */
public final class IndicadorHud extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int BARRAS = 9;

    private double lambdaAlvo = 1.0;
    private double lambda = 1.0;
    private double afr = 14.7;
    private boolean ativo;

    public IndicadorHud() {
        setOpaque(false);
        setPreferredSize(new Dimension(210, 74));
        setMinimumSize(new Dimension(150, 60));
    }

    public void atualizar(boolean ativo, double lambda, double afr) {
        this.ativo = ativo;
        this.lambdaAlvo = lambda;
        this.afr = afr;
    }

    public void avancar(double fracao) {
        lambda += (lambdaAlvo - lambda) * fracao;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();

        // "para-brisa" escuro com cantos cortados
        Path2D.Double casco = new Path2D.Double();
        casco.moveTo(2, h - 3);
        casco.lineTo(2, h * 0.42);
        casco.quadTo(w * 0.5, -h * 0.25, w - 2, h * 0.42);
        casco.lineTo(w - 2, h - 3);
        casco.closePath();
        g.setPaint(new GradientPaint(0, 0, new Color(0x0B1830), 0, h, new Color(0x02050A)));
        g.fill(casco);
        g.setColor(Tema.MOLDURA);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(casco);

        // leque
        double cx = w / 2.0;
        double cy = h * 0.92;
        double raioInterno = h * 0.38;
        double raioExterno = h * 0.74;
        double desvio = Math.max(-1, Math.min(1, (lambda - 1.0) / 0.25));
        int centro = BARRAS / 2;
        double indiceAtual = centro + desvio * centro;
        for (int i = 0; i < BARRAS; i++) {
            double angulo = Math.toRadians(150 - i * (120.0 / (BARRAS - 1)));
            double c = Math.cos(angulo);
            double s = Math.sin(angulo);
            double comprimento = i == centro ? 1.0 : 0.88 - 0.05 * Math.abs(i - centro) / centro;
            Line2D.Double barra = new Line2D.Double(cx + raioInterno * c, cy - raioInterno * s,
                    cx + (raioInterno + (raioExterno - raioInterno) * comprimento) * c,
                    cy - (raioInterno + (raioExterno - raioInterno) * comprimento) * s);
            boolean entre = ativo && (indiceAtual >= centro ? (i >= centro && i <= indiceAtual + 0.5)
                    : (i <= centro && i >= indiceAtual - 0.5));
            Color cor = i == centro ? Tema.CIANO : Tema.LARANJA;
            if (entre) {
                Tema.brilho(g, barra, cor, 4.2f);
            } else {
                g.setStroke(new BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(Tema.misturar(new Color(0x02050A), cor, 0.18));
                g.draw(barra);
            }
        }

        // números
        g.setFont(Tema.fonteTitulo(9.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Tema.TEXTO_FRACO);
        g.drawString("RICA", 10, h - 8);
        g.drawString("POBRE", w - 10 - fm.stringWidth("POBRE"), h - 8);
        g.setFont(Tema.fonteMono(java.awt.Font.BOLD, 12f));
        fm = g.getFontMetrics();
        String l1 = ativo ? String.format(Locale.ROOT, "λ %.3f", lambdaAlvo) : "λ ---";
        String l2 = ativo ? String.format(Locale.ROOT, "AFR %.1f", afr) : "AFR ---";
        g.setColor(ativo ? Tema.TEXTO : Tema.DESABILITADO);
        g.drawString(l1, 12, fm.getAscent() + 14);
        g.drawString(l2, w - 12 - fm.stringWidth(l2), fm.getAscent() + 14);
        g.setColor(Tema.comAlfa(Tema.MOLDURA_CLARA, 40));
        g.fill(new RoundRectangle2D.Double(w / 2.0 - 1, h * 0.12, 2, h * 0.18, 2, 2));
        g.dispose();
    }
}
