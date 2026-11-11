package kadettecu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * Mostrador analógico redondo (conta-giros e velocímetro), desenhado com Java2D.
 *
 * <p>O ponteiro não "pula" para o valor novo: a cada quadro da tela ele anda uma fração do caminho
 * ({@link #avancar(double)}), o que dá o movimento suave de um instrumento de verdade mesmo que a
 * telemetria chegue em intervalos irregulares. O mostrador só sabe desenhar; quem lê a telemetria
 * e chama {@link #setAlvo(double)} é o painel que o contém.
 */
public final class Mostrador extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final double ANGULO_INICIAL = 225.0;
    private static final double ANGULO_TOTAL = 270.0;

    private static final class Marca {
        private final double valor;
        private final Color cor;

        Marca(double valor, Color cor) {
            this.valor = valor;
            this.cor = cor;
        }
    }

    private final String titulo;
    private final String unidade;
    private final double minimo;
    private final double maximo;
    private final double passoMaior;
    private final int subdivisoes;
    private final int rotuloACada;
    private final double divisorRotulo;
    private final int casasDigital;
    private final Color corPonteiro;
    private final transient List<Marca> marcas = new ArrayList<>();
    private double inicioZonaVermelha = Double.NaN;
    private double alvo;
    private double exibido;
    private boolean ativo = true;

    /**
     * @param passoMaior    distância entre os traços grandes (por exemplo, 1000 rpm)
     * @param subdivisoes   quantos intervalos entre dois traços grandes (traços pequenos)
     * @param rotuloACada   escreve o número a cada N traços grandes
     * @param divisorRotulo os números da escala são o valor dividido por isto (1000 para "x1000 rpm")
     * @param casasDigital  casas decimais do visor digital
     */
    public Mostrador(String titulo, String unidade, double minimo, double maximo, double passoMaior,
                     int subdivisoes, int rotuloACada, double divisorRotulo, int casasDigital, Color corPonteiro) {
        this.titulo = titulo;
        this.unidade = unidade;
        this.minimo = minimo;
        this.maximo = maximo;
        this.passoMaior = passoMaior;
        this.subdivisoes = Math.max(1, subdivisoes);
        this.rotuloACada = Math.max(1, rotuloACada);
        this.divisorRotulo = divisorRotulo;
        this.casasDigital = casasDigital;
        this.corPonteiro = corPonteiro;
        this.exibido = minimo;
        this.alvo = minimo;
        setOpaque(false);
        setPreferredSize(new Dimension(300, 300));
        setMinimumSize(new Dimension(170, 170));
    }

    // ---------------------------------------------------------------- controle

    public void setAlvo(double valor) {
        this.alvo = Math.max(minimo, Math.min(maximo, valor));
    }

    /** Aproxima o ponteiro do alvo: fracao 0.3 anda 30 % da distância restante a cada chamada. */
    public void avancar(double fracao) {
        double distancia = alvo - exibido;
        exibido = Math.abs(distancia) < (maximo - minimo) * 0.0004 ? alvo : exibido + distancia * fracao;
    }

    public double getExibido() {
        return exibido;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public void setInicioZonaVermelha(double valor) {
        this.inicioZonaVermelha = valor;
    }

    public void limparMarcas() {
        marcas.clear();
    }

    public void adicionarMarca(double valor, Color cor) {
        marcas.add(new Marca(valor, cor));
    }

    // ---------------------------------------------------------------- desenho

    /** Ângulo (graus, anti-horário a partir das 3 horas) correspondente a um valor da escala. */
    private double angulo(double valor) {
        double f = (valor - minimo) / (maximo - minimo);
        return ANGULO_INICIAL - ANGULO_TOTAL * Math.max(0, Math.min(1, f));
    }

    private static Point2D.Double ponto(double cx, double cy, double raio, double anguloGraus) {
        double a = Math.toRadians(anguloGraus);
        return new Point2D.Double(cx + raio * Math.cos(a), cy - raio * Math.sin(a));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        double d = Math.min(w, h) - 6.0;
        double r = d / 2.0;
        double cx = w / 2.0;
        double cy = h / 2.0;
        if (r < 45) {
            g.dispose();
            return;
        }

        // aro e fundo
        g.setPaint(new GradientPaint((float) (cx - r), (float) (cy - r), new Color(0x3A5C82),
                (float) (cx + r), (float) (cy + r), new Color(0x0A1424)));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));
        double r2 = r * 0.955;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy - r * 0.25), (float) r2,
                new float[] {0f, 1f}, new Color[] {new Color(0x12305A), new Color(0x03070F)}));
        g.fill(new Ellipse2D.Double(cx - r2, cy - r2, 2 * r2, 2 * r2));
        g.setColor(Tema.comAlfa(Tema.CIANO, 60));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new Ellipse2D.Double(cx - r2, cy - r2, 2 * r2, 2 * r2));

        // faixa vermelha, na borda externa da escala
        double raioFaixa = r * 0.905;
        if (!Double.isNaN(inicioZonaVermelha)) {
            double a0 = angulo(inicioZonaVermelha);
            double a1 = angulo(maximo);
            Arc2D arco = new Arc2D.Double(cx - raioFaixa, cy - raioFaixa, 2 * raioFaixa, 2 * raioFaixa, a0, a1 - a0,
                    Arc2D.OPEN);
            Tema.brilho(g, arco, Tema.VERMELHO, (float) (r * 0.028));
        }

        // traços e números
        int grandes = (int) Math.round((maximo - minimo) / passoMaior);
        g.setFont(Tema.fonteMono(java.awt.Font.BOLD, (float) Math.max(10, r * 0.105)));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i <= grandes; i++) {
            double v = minimo + i * passoMaior;
            boolean vermelho = !Double.isNaN(inicioZonaVermelha) && v >= inicioZonaVermelha - 1e-9;
            double a = angulo(v);
            Point2D.Double p0 = ponto(cx, cy, r * 0.855, a);
            Point2D.Double p1 = ponto(cx, cy, r * 0.735, a);
            g.setColor(vermelho ? Tema.VERMELHO : Tema.TEXTO);
            g.setStroke(new BasicStroke((float) Math.max(1.6, r * 0.014), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(new Line2D.Double(p0, p1));
            if (i % rotuloACada == 0) {
                String rotulo = String.format(Locale.ROOT, "%d", Math.round(v / divisorRotulo));
                Point2D.Double pt = ponto(cx, cy, r * 0.625, a);
                g.setColor(vermelho ? new Color(0xFF8A80) : Tema.TEXTO);
                g.drawString(rotulo, (float) (pt.x - fm.stringWidth(rotulo) / 2.0),
                        (float) (pt.y + (fm.getAscent() - fm.getDescent()) / 2.0));
            }
            if (i < grandes) {
                for (int s = 1; s < subdivisoes; s++) {
                    double vs = v + s * passoMaior / subdivisoes;
                    boolean verm = !Double.isNaN(inicioZonaVermelha) && vs >= inicioZonaVermelha - 1e-9;
                    double as = angulo(vs);
                    g.setColor(verm ? new Color(0xB3281F) : Tema.TEXTO_FRACO);
                    g.setStroke(new BasicStroke((float) Math.max(1.0, r * 0.007)));
                    g.draw(new Line2D.Double(ponto(cx, cy, r * 0.855, as), ponto(cx, cy, r * 0.80, as)));
                }
            }
        }

        // marcas (limites configurados)
        for (Marca m : marcas) {
            if (m.valor < minimo || m.valor > maximo) {
                continue;
            }
            double a = angulo(m.valor);
            Tema.brilho(g, new Line2D.Double(ponto(cx, cy, r * 0.70, a), ponto(cx, cy, r * 0.93, a)), m.cor,
                    (float) Math.max(1.8, r * 0.012));
        }

        // título
        g.setFont(Tema.fonteTitulo((float) Math.max(9, r * 0.085)));
        fm = g.getFontMetrics();
        g.setColor(Tema.TEXTO_FRACO);
        g.drawString(titulo, (float) (cx - fm.stringWidth(titulo) / 2.0), (float) (cy - r * 0.22));

        // visor digital
        double larguraVisor = r * 0.60;
        double alturaVisor = r * 0.25;
        double yVisor = cy + r * 0.47;
        RoundRectangle2D.Double visor = new RoundRectangle2D.Double(cx - larguraVisor / 2, yVisor, larguraVisor,
                alturaVisor, 8, 8);
        g.setColor(new Color(0x02050B));
        g.fill(visor);
        g.setColor(Tema.MOLDURA);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(visor);
        String texto = ativo ? String.format(Locale.ROOT, "%." + casasDigital + "f", exibido) : "----";
        g.setFont(Tema.fonteMono(java.awt.Font.BOLD, (float) Math.max(12, r * 0.17)));
        fm = g.getFontMetrics();
        g.setColor(ativo ? Tema.VERDE : Tema.DESABILITADO);
        g.drawString(texto, (float) (cx - fm.stringWidth(texto) / 2.0),
                (float) (yVisor + alturaVisor / 2 + (fm.getAscent() - fm.getDescent()) / 2.0));
        g.setFont(Tema.fonteTitulo((float) Math.max(8.5, r * 0.075)));
        fm = g.getFontMetrics();
        g.setColor(Tema.TEXTO_FRACO);
        g.drawString(unidade, (float) (cx - fm.stringWidth(unidade) / 2.0), (float) (yVisor + alturaVisor + fm.getAscent() + 3));

        // ponteiro
        double a = angulo(exibido);
        Point2D.Double ponta = ponto(cx, cy, r * 0.80, a);
        Point2D.Double cauda = ponto(cx, cy, -r * 0.13, a);
        Path2D.Double ponteiro = new Path2D.Double();
        ponteiro.moveTo(cauda.x, cauda.y);
        ponteiro.lineTo(ponta.x, ponta.y);
        boolean emAlerta = !Double.isNaN(inicioZonaVermelha) && exibido >= inicioZonaVermelha;
        Tema.brilho(g, ponteiro, emAlerta ? Tema.VERMELHO : corPonteiro, (float) Math.max(2.2, r * 0.02));
        double hub = r * 0.075;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - hub * 0.3, cy - hub * 0.3), (float) hub,
                new float[] {0f, 1f}, new Color[] {Tema.CELULA_CLARA, new Color(0x2A4A6C)}));
        g.fill(new Ellipse2D.Double(cx - hub, cy - hub, 2 * hub, 2 * hub));
        g.setColor(Tema.FUNDO);
        g.fill(new Ellipse2D.Double(cx - hub * 0.35, cy - hub * 0.35, hub * 0.7, hub * 0.7));

        if (!ativo) {
            g.setColor(new Color(2, 6, 12, 110));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));
        }
        g.dispose();
    }
}
