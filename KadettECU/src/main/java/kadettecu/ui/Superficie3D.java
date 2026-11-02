package kadettecu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.Arrays;
import javax.swing.JComponent;
import kadettecu.modelo.MapaECU;

/**
 * Superfície 3D de uma tabela 16x16 dentro de uma caixa de arame sobre painel claro, como na
 * tela de referência. A projeção é feita à mão com Java2D (sem bibliotecas 3D): cada vértice é
 * girado, projetado em perspectiva suave e os 225 quadrados são pintados do fundo para a
 * frente (algoritmo do pintor), com um sombreamento simples para dar volume.
 *
 * <p>Com o mouse: arrastar gira, a roda aproxima e afasta, duplo clique volta à posição inicial.
 * Também desenha o ponto de operação do motor (de onde a ECU está lendo a tabela agora) e a célula
 * selecionada no editor.
 */
public final class Superficie3D extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int N = MapaECU.TAMANHO;
    private static final double DISTANCIA_CAMERA = 4.6;
    private static final double GUINADA_INICIAL = Math.toRadians(-38);
    private static final double ARFAGEM_INICIAL = Math.toRadians(31);

    private transient MapaECU mapa;
    private double guinada = GUINADA_INICIAL;
    private double arfagem = ARFAGEM_INICIAL;
    private double zoom = 1.0;
    private boolean temPonto;
    private double pontoRpm;
    private double pontoCarga;
    private int selecaoLinha = -1;
    private int selecaoColuna = -1;
    private int arrastoX;
    private int arrastoY;

    /** Valores de seno e cosseno da vista, calculados uma vez por quadro. */
    private static final class Vista {
        private final double cg;
        private final double sg;
        private final double cp;
        private final double sp;
        private final double escala;
        private final double cx;
        private final double cy;

        Vista(double guinada, double arfagem, double escala, double cx, double cy) {
            this.cg = Math.cos(guinada);
            this.sg = Math.sin(guinada);
            this.cp = Math.cos(arfagem);
            this.sp = Math.sin(arfagem);
            this.escala = escala;
            this.cx = cx;
            this.cy = cy;
        }

        /** Projeta (x, y, z) do mundo para a tela: saída = {x da tela, y da tela, profundidade}. */
        void projetar(double x, double y, double z, double[] saida) {
            double x1 = x * cg - y * sg;
            double y1 = x * sg + y * cg;
            double zc = z - 0.5;
            double y2 = y1 * cp - zc * sp;
            double z2 = y1 * sp + zc * cp;
            double perspectiva = DISTANCIA_CAMERA / (DISTANCIA_CAMERA + y2);
            saida[0] = cx + escala * x1 * perspectiva;
            saida[1] = cy - escala * z2 * perspectiva;
            saida[2] = y2;
        }

        /** Profundidade da normal de uma face: maior que zero = a face aponta para longe do observador. */
        double profundidadeNormal(double nx, double ny, double nz) {
            double y1 = nx * sg + ny * cg;
            return y1 * cp - nz * sp;
        }
    }

    public Superficie3D(boolean interativo) {
        setOpaque(false);
        setPreferredSize(new Dimension(440, 330));
        setMinimumSize(new Dimension(160, 120));
        if (interativo) {
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            setToolTipText("Arraste para girar • roda do mouse aproxima • duplo clique restaura");
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    arrastoX = e.getX();
                    arrastoY = e.getY();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    guinada += (e.getX() - arrastoX) * 0.011;
                    arfagem = Math.max(0.12, Math.min(1.45, arfagem + (e.getY() - arrastoY) * 0.009));
                    arrastoX = e.getX();
                    arrastoY = e.getY();
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        guinada = GUINADA_INICIAL;
                        arfagem = ARFAGEM_INICIAL;
                        zoom = 1.0;
                        repaint();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    zoom = Math.max(0.6, Math.min(2.4, zoom * Math.exp(-0.1 * e.getWheelRotation())));
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }
    }

    public void setMapa(MapaECU mapa) {
        this.mapa = mapa;
    }

    /** Ponto de operação do motor em índices fracionários (0 a 15) da tabela. */
    public void setPonto(boolean visivel, double posRpm, double posCarga) {
        this.temPonto = visivel;
        this.pontoRpm = posRpm;
        this.pontoCarga = posCarga;
    }

    public void setSelecao(int linha, int coluna) {
        this.selecaoLinha = linha;
        this.selecaoColuna = coluna;
    }

    private static double mundo(double indice) {
        return indice / ((N - 1) / 2.0) - 1.0;
    }

    private double altura(int linha, int coluna) {
        return 0.04 + 0.86 * mapa.getTipo().normalizar(mapa.getRaw(linha, coluna));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(0xEEF4FA), 0, h, new Color(0xC3D3E3)));
        g.fillRect(0, 0, w, h);
        g.setColor(Tema.MOLDURA);
        g.drawRect(0, 0, w - 1, h - 1);

        Vista v = new Vista(guinada, arfagem, Math.min(w, h * 1.18) * 0.30 * zoom, w / 2.0, h / 2.0 + h * 0.02);
        double[] p = new double[3];

        desenharParedes(g, v, p);
        if (mapa != null) {
            desenharSuperficie(g, v, p);
        }
        desenharArestas(g, v, p);
        desenharRotulosDosEixos(g, v, p);
        if (mapa != null) {
            desenharMarcadores(g, v, p);
        }
        g.dispose();
    }

    // ---------------------------------------------------------------- caixa de arame

    private static final double[][][] FACES = {
        // chão (z = 0)
        {{-1, -1, 0}, {1, -1, 0}, {1, 1, 0}, {-1, 1, 0}},
        // paredes x = -1 e x = +1
        {{-1, -1, 0}, {-1, 1, 0}, {-1, 1, 1}, {-1, -1, 1}},
        {{1, -1, 0}, {1, 1, 0}, {1, 1, 1}, {1, -1, 1}},
        // paredes y = -1 e y = +1
        {{-1, -1, 0}, {1, -1, 0}, {1, -1, 1}, {-1, -1, 1}},
        {{-1, 1, 0}, {1, 1, 0}, {1, 1, 1}, {-1, 1, 1}}
    };
    private static final double[][] NORMAIS = {{0, 0, -1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};

    private void desenharParedes(Graphics2D g, Vista v, double[] p) {
        final int divisoes = 8;
        for (int f = 0; f < FACES.length; f++) {
            double[] n = NORMAIS[f];
            if (v.profundidadeNormal(n[0], n[1], n[2]) <= 0) {
                continue; // face virada para o observador: não desenhamos (ficaria na frente da superfície)
            }
            double[][] c = FACES[f];
            double[][] pontos = new double[4][3];
            Path2D.Double poligono = new Path2D.Double();
            for (int i = 0; i < 4; i++) {
                v.projetar(c[i][0], c[i][1], c[i][2], p);
                pontos[i][0] = p[0];
                pontos[i][1] = p[1];
                if (i == 0) {
                    poligono.moveTo(p[0], p[1]);
                } else {
                    poligono.lineTo(p[0], p[1]);
                }
            }
            poligono.closePath();
            g.setColor(new Color(0xA9BFD4 | 0x66000000, true));
            g.fill(poligono);
            g.setColor(new Color(0x7F97AF));
            g.setStroke(new BasicStroke(0.8f));
            for (int k = 1; k < divisoes; k++) {
                double t = k / (double) divisoes;
                g.draw(new Line2D.Double(
                        pontos[0][0] + (pontos[1][0] - pontos[0][0]) * t, pontos[0][1] + (pontos[1][1] - pontos[0][1]) * t,
                        pontos[3][0] + (pontos[2][0] - pontos[3][0]) * t, pontos[3][1] + (pontos[2][1] - pontos[3][1]) * t));
                g.draw(new Line2D.Double(
                        pontos[0][0] + (pontos[3][0] - pontos[0][0]) * t, pontos[0][1] + (pontos[3][1] - pontos[0][1]) * t,
                        pontos[1][0] + (pontos[2][0] - pontos[1][0]) * t, pontos[1][1] + (pontos[2][1] - pontos[1][1]) * t));
            }
        }
    }

    private void desenharArestas(Graphics2D g, Vista v, double[] p) {
        double[][] cantos = new double[8][3];
        for (int i = 0; i < 8; i++) {
            v.projetar((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? 0 : 1, p);
            cantos[i][0] = p[0];
            cantos[i][1] = p[1];
        }
        g.setColor(new Color(0x5F7A95));
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) == 0) {
                    int j = i | bit;
                    g.draw(new Line2D.Double(cantos[i][0], cantos[i][1], cantos[j][0], cantos[j][1]));
                }
            }
        }
    }

    private void desenharRotulosDosEixos(Graphics2D g, Vista v, double[] p) {
        g.setFont(Tema.fonteTitulo(10f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0x2B3E52));
        // aresta do chão mais próxima do observador em cada direção
        v.projetar(0, -1, 0, p);
        double profNegY = p[2];
        v.projetar(0, 1, 0, p);
        double ySinal = profNegY <= p[2] ? -1 : 1;
        v.projetar(0, ySinal * 1.28, 0, p);
        g.drawString("RPM →", (float) (p[0] - fm.stringWidth("RPM →") / 2.0), (float) p[1] + 4);
        v.projetar(-1, 0, 0, p);
        double profNegX = p[2];
        v.projetar(1, 0, 0, p);
        double xSinal = profNegX <= p[2] ? -1 : 1;
        v.projetar(xSinal * 1.28, 0, 0, p);
        g.drawString("CARGA", (float) (p[0] - fm.stringWidth("CARGA") / 2.0), (float) p[1] + 4);
    }

    // ---------------------------------------------------------------- superfície

    private void desenharSuperficie(Graphics2D g, Vista v, double[] p) {
        double[][] tela = new double[N * N][3];
        double[] z = new double[N * N];
        for (int l = 0; l < N; l++) {
            for (int c = 0; c < N; c++) {
                int i = l * N + c;
                z[i] = altura(l, c);
                v.projetar(mundo(c), mundo(l), z[i], p);
                tela[i][0] = p[0];
                tela[i][1] = p[1];
                tela[i][2] = p[2];
            }
        }
        final int n = N - 1;
        final double[] profundidade = new double[n * n];
        Integer[] ordem = new Integer[n * n];
        for (int l = 0; l < n; l++) {
            for (int c = 0; c < n; c++) {
                int q = l * n + c;
                ordem[q] = q;
                profundidade[q] = (tela[l * N + c][2] + tela[l * N + c + 1][2] + tela[(l + 1) * N + c][2]
                        + tela[(l + 1) * N + c + 1][2]) / 4.0;
            }
        }
        Arrays.sort(ordem, (a, b) -> Double.compare(profundidade[b], profundidade[a])); // do mais longe para o mais perto

        g.setStroke(new BasicStroke(0.7f));
        for (int q : ordem) {
            int l = q / n;
            int c = q % n;
            int i00 = l * N + c;
            int i10 = l * N + c + 1;
            int i11 = (l + 1) * N + c + 1;
            int i01 = (l + 1) * N + c;
            // normal da superfície no mundo (vetores ao longo de x e de y)
            double ax = mundo(c + 1) - mundo(c);
            double az = z[i10] - z[i00];
            double by = mundo(l + 1) - mundo(l);
            double bz = z[i01] - z[i00];
            // produto vetorial (ax,0,az) x (0,by,bz) = (-az*by, -ax*bz, ax*by)
            double nx = -az * by;
            double ny = -ax * bz;
            double nz = ax * by;
            double norma = Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= norma;
            ny /= norma;
            nz /= norma;
            // para o espaço da vista: só importa a componente em relação à luz (fixa em relação ao observador)
            double x1 = nx * Math.cos(guinada) - ny * Math.sin(guinada);
            double y1 = nx * Math.sin(guinada) + ny * Math.cos(guinada);
            double y2 = y1 * Math.cos(arfagem) - nz * Math.sin(arfagem);
            double z2 = y1 * Math.sin(arfagem) + nz * Math.cos(arfagem);
            double luz = -0.35 * x1 - 0.55 * y2 + 0.75 * z2;
            double sombra = 0.60 + 0.40 * Math.max(0, Math.min(1, luz / 0.98));

            double t = (mapa.getTipo().normalizar(mapa.getRaw(l, c)) + mapa.getTipo().normalizar(mapa.getRaw(l, c + 1))
                    + mapa.getTipo().normalizar(mapa.getRaw(l + 1, c)) + mapa.getTipo().normalizar(mapa.getRaw(l + 1, c + 1))) / 4.0;
            Color base = PaletaCalor.cor(t);
            Color cor = new Color(Math.min(255, (int) (base.getRed() * sombra + 22 * (sombra - 0.6))),
                    Math.min(255, (int) (base.getGreen() * sombra + 22 * (sombra - 0.6))),
                    Math.min(255, (int) (base.getBlue() * sombra + 22 * (sombra - 0.6))));
            Path2D.Double quad = new Path2D.Double();
            quad.moveTo(tela[i00][0], tela[i00][1]);
            quad.lineTo(tela[i10][0], tela[i10][1]);
            quad.lineTo(tela[i11][0], tela[i11][1]);
            quad.lineTo(tela[i01][0], tela[i01][1]);
            quad.closePath();
            g.setColor(cor);
            g.fill(quad);
            g.setColor(new Color(0, 0, 0, 55));
            g.draw(quad);
        }
    }

    // ---------------------------------------------------------------- marcadores

    private void desenharMarcadores(Graphics2D g, Vista v, double[] p) {
        if (selecaoLinha >= 0 && selecaoColuna >= 0) {
            double z = altura(selecaoLinha, selecaoColuna);
            double[] topo = new double[3];
            v.projetar(mundo(selecaoColuna), mundo(selecaoLinha), z, topo);
            v.projetar(mundo(selecaoColuna), mundo(selecaoLinha), 0, p);
            g.setColor(new Color(0x1F3A57));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3f, 3f}, 0f));
            g.draw(new Line2D.Double(p[0], p[1], topo[0], topo[1]));
            g.setStroke(new BasicStroke(2f));
            g.setColor(Color.WHITE);
            g.draw(new Ellipse2D.Double(topo[0] - 6, topo[1] - 6, 12, 12));
            g.setColor(new Color(0x1F3A57));
            g.draw(new Ellipse2D.Double(topo[0] - 7.5, topo[1] - 7.5, 15, 15));
        }
        if (temPonto) {
            double px = Math.max(0, Math.min(N - 1, pontoRpm));
            double py = Math.max(0, Math.min(N - 1, pontoCarga));
            double t = mapa.getTipo().normalizar(mapa.interpolarRaw(px, py));
            double z = 0.04 + 0.86 * t;
            double[] topo = new double[3];
            v.projetar(mundo(px), mundo(py), z, topo);
            v.projetar(mundo(px), mundo(py), 0, p);
            g.setStroke(new BasicStroke(1.4f));
            g.setColor(Tema.comAlfa(new Color(0x0E6B2A), 200));
            g.draw(new Line2D.Double(p[0], p[1], topo[0], topo[1]));
            g.setColor(new Color(0, 0, 0, 50));
            g.fill(new Ellipse2D.Double(p[0] - 4, p[1] - 2, 8, 4));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(topo[0], topo[1]), 13f, new float[] {0f, 1f},
                    new Color[] {Tema.comAlfa(Tema.VERDE, 190), Tema.comAlfa(Tema.VERDE, 0)}));
            g.fill(new Ellipse2D.Double(topo[0] - 13, topo[1] - 13, 26, 26));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(topo[0] - 3.5, topo[1] - 3.5, 7, 7));
            g.setColor(new Color(0x0E6B2A));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new Ellipse2D.Double(topo[0] - 5, topo[1] - 5, 10, 10));
        }
    }
}
