package kadettecu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * Barra de LEDs (como as colunas de células da referência): o rótulo à esquerda, os segmentos no
 * meio, que acendem da esquerda para a direita, e o valor numérico à direita. A cor da barra
 * depende da faixa em que o valor está (por exemplo, temperatura baixa = azul, normal = verde).
 */
public final class BarraSegmentada extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int LARGURA_ROTULO = 50;
    private static final int LARGURA_VALOR = 86;

    private static final class Zona {
        private final double aPartirDe;
        private final Color cor;

        Zona(double aPartirDe, Color cor) {
            this.aPartirDe = aPartirDe;
            this.cor = cor;
        }
    }

    private final String rotulo;
    private final String unidade;
    private final double minimo;
    private final double maximo;
    private final int casas;
    private final transient List<Zona> zonas = new ArrayList<>();
    private double alvo;
    private double exibido;
    private boolean ativo = true;
    private boolean alerta;

    public BarraSegmentada(String rotulo, String unidade, double minimo, double maximo, int casas) {
        this.rotulo = rotulo;
        this.unidade = unidade;
        this.minimo = minimo;
        this.maximo = maximo;
        this.casas = casas;
        this.alvo = minimo;
        this.exibido = minimo;
        setOpaque(false);
        setPreferredSize(new Dimension(300, 24));
        setMinimumSize(new Dimension(180, 20));
    }

    /** Define a cor dos segmentos a partir deste valor (as zonas devem ser dadas em ordem crescente). */
    public BarraSegmentada zona(double aPartirDe, Color cor) {
        zonas.add(new Zona(aPartirDe, cor));
        return this;
    }

    public void setAlvo(double valor) {
        this.alvo = Math.max(minimo, Math.min(maximo, valor));
    }

    public void avancar(double fracao) {
        double distancia = alvo - exibido;
        exibido = Math.abs(distancia) < (maximo - minimo) * 0.001 ? alvo : exibido + distancia * fracao;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    /** Destaca o rótulo em vermelho (sensor com defeito, por exemplo). */
    public void setAlerta(boolean alerta) {
        this.alerta = alerta;
    }

    private Color corDaZona(double valor) {
        Color cor = Tema.VERDE;
        for (Zona z : zonas) {
            if (valor >= z.aPartirDe) {
                cor = z.cor;
            }
        }
        return cor;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        g.setFont(Tema.fonteTitulo(10.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(alerta ? Tema.VERMELHO : Tema.TEXTO_FRACO);
        g.drawString(rotulo, 2, (h + fm.getAscent() - fm.getDescent()) / 2f);

        int x0 = LARGURA_ROTULO;
        int larguraBarra = Math.max(20, w - LARGURA_ROTULO - LARGURA_VALOR);
        int alturaSeg = Math.max(6, Math.min(14, h - 8));
        int y0 = (h - alturaSeg) / 2;
        int n = Math.max(10, larguraBarra / 8);
        double gap = 2.0;
        double larguraSeg = (larguraBarra - (n - 1) * gap) / n;
        double fracao = (exibido - minimo) / (maximo - minimo);
        double acesos = ativo ? fracao * n : 0;
        // Os segmentos apagados mostram, bem fracos, onde ficam as faixas; os acesos usam todos a cor da faixa
        // em que o valor está agora (tudo verde = normal, tudo vermelho = alerta), em vez de um arco-íris.
        Color corAtual = corDaZona(exibido);
        for (int i = 0; i < n; i++) {
            double valorSeg = minimo + (i + 0.5) / n * (maximo - minimo);
            Color cor = corDaZona(valorSeg);
            double x = x0 + i * (larguraSeg + gap);
            RoundRectangle2D.Double seg = new RoundRectangle2D.Double(x, y0, larguraSeg, alturaSeg, 2, 2);
            g.setColor(Tema.misturar(Tema.FUNDO, cor, 0.16));
            g.fill(seg);
            double luz = Math.max(0, Math.min(1, acesos - i));
            if (luz > 0) {
                g.setColor(Tema.comAlfa(corAtual, (int) (255 * luz)));
                g.fill(seg);
                if (luz >= 1) {
                    g.setColor(Tema.comAlfa(Color.WHITE, 40));
                    g.fill(new RoundRectangle2D.Double(x, y0, larguraSeg, alturaSeg / 2.5, 2, 2));
                }
            }
        }

        String valor = ativo ? String.format(Locale.ROOT, "%." + casas + "f", exibido) : "--";
        g.setFont(Tema.fonteMono(java.awt.Font.BOLD, 12.5f));
        fm = g.getFontMetrics();
        int xv = w - LARGURA_VALOR + 8;
        g.setColor(ativo ? Tema.TEXTO : Tema.DESABILITADO);
        g.drawString(valor, xv, (h + fm.getAscent() - fm.getDescent()) / 2f);
        g.setFont(Tema.fonteUI(java.awt.Font.PLAIN, 10.5f));
        g.setColor(Tema.TEXTO_FRACO);
        g.drawString(unidade, xv + fm.stringWidth(valor) + 4, (h + fm.getAscent() - fm.getDescent()) / 2f);
        g.dispose();
    }
}
