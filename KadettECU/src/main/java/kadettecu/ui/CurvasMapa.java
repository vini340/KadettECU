package kadettecu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.Locale;
import javax.swing.JComponent;
import kadettecu.modelo.EixosMapa;
import kadettecu.modelo.MapaECU;

/**
 * A coluna de curvas verdes da tela de referência: quatro gráficos pequenos, cada um mostrando
 * como o valor da tabela varia com a rotação para uma determinada carga (MAP). O gráfico da
 * carga onde o motor está trabalhando fica com a moldura destacada e um ponto marca a rotação atual.
 */
public final class CurvasMapa extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int[] LINHAS = {3, 7, 11, 15};

    private transient MapaECU mapa;
    private transient EixosMapa eixos;
    private boolean temPonto;
    private double pontoRpm;
    private double pontoCarga;
    private int colunaSelecionada = -1;

    public CurvasMapa() {
        setOpaque(false);
        setPreferredSize(new Dimension(200, 300));
        setMinimumSize(new Dimension(120, 160));
    }

    public void setMapa(MapaECU mapa, EixosMapa eixos) {
        this.mapa = mapa;
        this.eixos = eixos;
    }

    public void setPonto(boolean visivel, double posRpm, double posCarga) {
        this.temPonto = visivel;
        this.pontoRpm = posRpm;
        this.pontoCarga = posCarga;
    }

    public void setColunaSelecionada(int coluna) {
        this.colunaSelecionada = coluna;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        int espaco = 6;
        int altura = (h - espaco * (LINHAS.length - 1)) / LINHAS.length;
        // linha da tabela mais próxima da carga atual: é a curva que fica em destaque
        int linhaViva = temPonto ? (int) Math.round(Math.max(0, Math.min(MapaECU.TAMANHO - 1, pontoCarga))) : -1;
        int destaque = -1;
        double menor = Double.MAX_VALUE;
        for (int i = 0; i < LINHAS.length; i++) {
            double d = Math.abs(LINHAS[i] - linhaViva);
            if (temPonto && d < menor) {
                menor = d;
                destaque = i;
            }
        }
        for (int i = 0; i < LINHAS.length; i++) {
            desenharGrafico(g, 0, i * (altura + espaco), w, altura, LINHAS[i], i == destaque);
        }
        g.dispose();
    }

    private void desenharGrafico(Graphics2D g, int x, int y, int w, int h, int linha, boolean destaque) {
        g.setColor(new Color(0x03070D));
        g.fillRect(x, y, w, h);
        g.setColor(destaque ? Tema.MOLDURA_CLARA : Tema.MOLDURA);
        g.drawRect(x, y, w - 1, h - 1);
        if (mapa == null || eixos == null || w < 40 || h < 30) {
            return;
        }
        int ex = x + 8;
        int ew = w - 16;
        int topo = y + 16;
        int base = y + h - 6;
        double ah = base - topo;
        g.setColor(Tema.comAlfa(Tema.MOLDURA, 55));
        g.setStroke(new BasicStroke(1f));
        for (int k = 0; k <= 2; k++) {
            double yy = topo + ah * k / 2.0;
            g.draw(new Line2D.Double(ex, yy, ex + ew, yy));
        }
        for (int k = 0; k <= 4; k++) {
            double xx = ex + ew * k / 4.0;
            g.draw(new Line2D.Double(xx, topo, xx, base));
        }
        if (colunaSelecionada >= 0) {
            double xs = ex + ew * colunaSelecionada / (MapaECU.TAMANHO - 1.0);
            g.setColor(Tema.comAlfa(Tema.CIANO, 130));
            g.draw(new Line2D.Double(xs, topo, xs, base));
        }
        Path2D.Double curva = new Path2D.Double();
        for (int c = 0; c < MapaECU.TAMANHO; c++) {
            double t = mapa.getTipo().normalizar(mapa.getRaw(linha, c));
            double px = ex + ew * c / (MapaECU.TAMANHO - 1.0);
            double py = base - t * ah;
            if (c == 0) {
                curva.moveTo(px, py);
            } else {
                curva.lineTo(px, py);
            }
        }
        Tema.brilho(g, curva, destaque ? Tema.VERDE : new Color(0x2FBF55), 1.6f);
        if (destaque && temPonto) {
            double col = Math.max(0, Math.min(MapaECU.TAMANHO - 1, pontoRpm));
            double t = mapa.getTipo().normalizar(interpolarNaLinha(linha, col));
            double px = ex + ew * col / (MapaECU.TAMANHO - 1.0);
            double py = base - t * ah;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(px - 3.5, py - 3.5, 7, 7));
            g.setColor(Tema.VERDE);
            g.draw(new Ellipse2D.Double(px - 5, py - 5, 10, 10));
        }
        g.setFont(Tema.fonteTitulo(9f));
        g.setColor(destaque ? Tema.TEXTO : Tema.TEXTO_FRACO);
        g.drawString(String.format(Locale.ROOT, "CARGA %d kPa", eixos.getCarga(linha)), x + 8, y + 12);
    }

    private double interpolarNaLinha(int linha, double coluna) {
        int c0 = (int) Math.min(Math.floor(coluna), MapaECU.TAMANHO - 2);
        double f = coluna - c0;
        return mapa.getRaw(linha, c0) * (1 - f) + mapa.getRaw(linha, c0 + 1) * f;
    }
}
