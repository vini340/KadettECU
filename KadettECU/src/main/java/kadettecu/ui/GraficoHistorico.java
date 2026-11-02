package kadettecu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * Gráfico de linhas que rola da direita para a esquerda (como um osciloscópio): guarda as últimas
 * amostras de cada grandeza num buffer circular e desenha todas juntas, cada uma na sua própria
 * escala vertical (assim rotação e lambda cabem no mesmo quadro).
 *
 * <p>Só a thread do Swing mexe neste objeto: o painel chama {@link #adicionar(double...)} a cada
 * quadro da tela, então não é preciso sincronização.
 */
public final class GraficoHistorico extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final class Serie {
        private final String nome;
        private final String unidade;
        private final Color cor;
        private final double minimo;
        private final double maximo;
        private final int casas;
        private final double[] valores;

        Serie(String nome, String unidade, Color cor, double minimo, double maximo, int casas, int capacidade) {
            this.nome = nome;
            this.unidade = unidade;
            this.cor = cor;
            this.minimo = minimo;
            this.maximo = maximo;
            this.casas = casas;
            this.valores = new double[capacidade];
        }
    }

    private final int capacidade;
    private final int amostrasPorSegundo;
    private final transient List<Serie> series = new ArrayList<>();
    private int proximo;
    private int total;
    private long absoluto;

    public GraficoHistorico(int segundos, int amostrasPorSegundo) {
        this.amostrasPorSegundo = amostrasPorSegundo;
        this.capacidade = Math.max(10, segundos * amostrasPorSegundo);
        setOpaque(false);
        setPreferredSize(new Dimension(600, 160));
        setMinimumSize(new Dimension(240, 90));
    }

    public void adicionarSerie(String nome, String unidade, Color cor, double minimo, double maximo, int casas) {
        series.add(new Serie(nome, unidade, cor, minimo, maximo, casas, capacidade));
    }

    /** Acrescenta uma amostra de cada série, na ordem em que foram adicionadas. */
    public void adicionar(double... valores) {
        for (int i = 0; i < series.size() && i < valores.length; i++) {
            series.get(i).valores[proximo] = valores[i];
        }
        proximo = (proximo + 1) % capacidade;
        total = Math.min(capacidade, total + 1);
        absoluto++;
    }

    public void limpar() {
        proximo = 0;
        total = 0;
        absoluto = 0;
    }

    private double valorMaisRecente(Serie s) {
        return total == 0 ? 0 : s.valores[(proximo - 1 + capacidade) % capacidade];
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        g.setColor(new Color(0x03070D));
        g.fillRect(0, 0, w, h);
        g.setColor(Tema.MOLDURA);
        g.drawRect(0, 0, w - 1, h - 1);

        int esquerda = 8;
        int direita = w - 8;
        int topo = 28;
        int base = h - 8;
        double largura = direita - esquerda;
        double altura = base - topo;
        if (largura < 20 || altura < 20) {
            g.dispose();
            return;
        }

        // grade: linhas horizontais fixas e verticais que rolam junto com os dados
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            double y = topo + altura * i / 4.0;
            g.setColor(i == 0 || i == 4 ? Tema.comAlfa(Tema.MOLDURA, 150) : Tema.comAlfa(Tema.MOLDURA, 60));
            g.draw(new Line2D.Double(esquerda, y, direita, y));
        }
        double dx = largura / (capacidade - 1.0);
        int passoGrade = Math.max(1, amostrasPorSegundo * 2);
        long idade0 = absoluto % passoGrade;
        g.setColor(Tema.comAlfa(Tema.MOLDURA, 55));
        for (long idade = idade0; idade < capacidade; idade += passoGrade) {
            double x = direita - idade * dx;
            if (x >= esquerda) {
                g.draw(new Line2D.Double(x, topo, x, base));
            }
        }

        // curvas
        for (Serie s : series) {
            if (total < 2) {
                continue;
            }
            Path2D.Double caminho = new Path2D.Double();
            boolean primeiro = true;
            for (int k = 0; k < total; k++) {
                int indice = ((proximo - total + k) % capacidade + capacidade) % capacidade;
                double v = (s.valores[indice] - s.minimo) / (s.maximo - s.minimo);
                v = Math.max(-0.02, Math.min(1.02, v));
                double x = direita - (total - 1 - k) * dx;
                double y = base - v * altura;
                if (primeiro) {
                    caminho.moveTo(x, y);
                    primeiro = false;
                } else {
                    caminho.lineTo(x, y);
                }
            }
            Tema.brilho(g, caminho, s.cor, 1.6f);
        }

        // legenda com o valor atual de cada grandeza
        g.setFont(Tema.fonteTitulo(10.5f));
        FontMetrics fmNome = g.getFontMetrics();
        int x = esquerda + 2;
        for (Serie s : series) {
            g.setColor(s.cor);
            g.fillRect(x, 10, 12, 4);
            x += 17;
            g.setColor(Tema.TEXTO_FRACO);
            g.drawString(s.nome, x, 17);
            x += fmNome.stringWidth(s.nome) + 6;
            String valor = String.format(Locale.ROOT, "%." + s.casas + "f", valorMaisRecente(s)) + (s.unidade.isEmpty() ? "" : " " + s.unidade);
            g.setFont(Tema.fonteMono(java.awt.Font.BOLD, 11.5f));
            g.setColor(Tema.TEXTO);
            g.drawString(valor, x, 17);
            x += g.getFontMetrics().stringWidth(valor) + 22;
            g.setFont(Tema.fonteTitulo(10.5f));
        }
        g.dispose();
    }
}
