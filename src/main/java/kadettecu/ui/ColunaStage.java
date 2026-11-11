package kadettecu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * Uma coluna "STAGE" no estilo da tela NOS PROGRAM: aba azul-clara no topo, caixa de situação
 * (READY em verde...) e uma pilha de células azul-claras com listras verdes que vão acendendo de
 * baixo para cima conforme a rotação se aproxima do valor deste estágio.
 *
 * <p>No KadettECU os três estágios são: 1 = launch control, 2 = two-step e 3 = limitador de giro.
 */
public final class ColunaStage extends JComponent {

    private static final long serialVersionUID = 1L;

    public enum Situacao {
        OFFLINE("OFFLINE"), DESLIGADO("OFF"), PRONTO("READY"), ATIVO("ATIVO"), CORTANDO("CORTE");

        private final String texto;

        Situacao(String texto) {
            this.texto = texto;
        }

        public String getTexto() {
            return texto;
        }
    }

    private final String titulo;
    private final String subtitulo;
    private Situacao situacao = Situacao.OFFLINE;
    private int rpmAlvo;
    private double alvoProgresso;
    private double progresso;

    public ColunaStage(String titulo, String subtitulo) {
        this.titulo = titulo;
        this.subtitulo = subtitulo;
        setOpaque(false);
        setPreferredSize(new Dimension(116, 300));
        setMinimumSize(new Dimension(96, 220));
    }

    /**
     * @param situacao  estado a mostrar na caixa
     * @param rpmAlvo   rotação configurada para este estágio
     * @param rpmAtual  rotação do motor agora (define quantas células acendem)
     */
    public void atualizar(Situacao situacao, int rpmAlvo, double rpmAtual) {
        this.situacao = situacao;
        this.rpmAlvo = rpmAlvo;
        this.alvoProgresso = situacao == Situacao.OFFLINE || rpmAlvo <= 0 ? 0 : Math.max(0, Math.min(1, rpmAtual / rpmAlvo));
    }

    public void avancar(double fracao) {
        double d = alvoProgresso - progresso;
        progresso = Math.abs(d) < 0.002 ? alvoProgresso : progresso + d * fracao;
    }

    private static boolean piscaLento() {
        return ((System.currentTimeMillis() / 200) & 1) == 0;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth();
        int h = getHeight();
        boolean corte = situacao == Situacao.CORTANDO;
        boolean apagada = situacao == Situacao.OFFLINE || situacao == Situacao.DESLIGADO;

        // aba do título
        int alturaAba = 22;
        Polygon aba = new Polygon();
        aba.addPoint(4, alturaAba);
        aba.addPoint(4, 5);
        aba.addPoint(9, 0);
        aba.addPoint(w - 10, 0);
        aba.addPoint(w - 5, 5);
        aba.addPoint(w - 5, alturaAba);
        g.setPaint(new GradientPaint(0, 0, Tema.CELULA_CLARA, 0, alturaAba, Tema.CELULA));
        g.fill(aba);
        g.setColor(Tema.FUNDO);
        g.setFont(Tema.fonteTitulo(11.5f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(titulo, (w - fm.stringWidth(titulo)) / 2f, (alturaAba + fm.getAscent() - fm.getDescent()) / 2f);

        // caixa de situação
        int yCaixa = alturaAba + 7;
        int alturaCaixa = 22;
        Color fundoCaixa;
        Color textoCaixa = Tema.FUNDO;
        switch (situacao) {
            case PRONTO:
                fundoCaixa = Tema.VERDE_PRONTO;
                break;
            case ATIVO:
                fundoCaixa = Tema.CIANO;
                break;
            case CORTANDO:
                fundoCaixa = piscaLento() ? Tema.VERMELHO : new Color(0x7A1D18);
                textoCaixa = Color.WHITE;
                break;
            case DESLIGADO:
                fundoCaixa = new Color(0x1B2B3D);
                textoCaixa = Tema.TEXTO_FRACO;
                break;
            default:
                fundoCaixa = new Color(0x121E2C);
                textoCaixa = Tema.DESABILITADO;
                break;
        }
        RoundRectangle2D.Double caixa = new RoundRectangle2D.Double(8, yCaixa, w - 16, alturaCaixa, 4, 4);
        g.setColor(fundoCaixa);
        g.fill(caixa);
        g.setColor(Tema.comAlfa(Color.WHITE, 60));
        g.draw(caixa);
        g.setColor(textoCaixa);
        g.setFont(Tema.fonteTitulo(12f));
        fm = g.getFontMetrics();
        String texto = situacao.getTexto();
        g.drawString(texto, (w - fm.stringWidth(texto)) / 2f, yCaixa + (alturaCaixa + fm.getAscent() - fm.getDescent()) / 2f);

        // legenda
        int yTexto = yCaixa + alturaCaixa + 6;
        g.setFont(Tema.fonteTitulo(9.5f));
        fm = g.getFontMetrics();
        g.setColor(Tema.TEXTO_FRACO);
        g.drawString(subtitulo, (w - fm.stringWidth(subtitulo)) / 2f, yTexto + fm.getAscent());
        yTexto += fm.getHeight();
        g.setFont(Tema.fonteMono(java.awt.Font.BOLD, 12.5f));
        fm = g.getFontMetrics();
        String alvo = rpmAlvo > 0 ? String.format(Locale.ROOT, "%d rpm", rpmAlvo) : "-- rpm";
        g.setColor(apagada ? Tema.DESABILITADO : Tema.TEXTO);
        g.drawString(alvo, (w - fm.stringWidth(alvo)) / 2f, yTexto + fm.getAscent());
        yTexto += fm.getHeight() + 4;

        // moldura e células
        int topoGrade = yTexto + 4;
        int alturaGrade = h - topoGrade - 6;
        if (alturaGrade < 40) {
            g.dispose();
            return;
        }
        g.setColor(corte && piscaLento() ? Tema.VERMELHO : Tema.MOLDURA);
        g.drawRect(2, topoGrade - 4, w - 5, alturaGrade + 8);
        int linhas = Math.max(6, Math.min(32, alturaGrade / 14));
        double passo = alturaGrade / (double) linhas;
        double alturaCelula = passo - 3;
        double margem = 8;
        double larguraGrade = w - 2 * margem;
        double espaco = 3;
        double lateral = (larguraGrade - 2 * espaco) * 0.26;
        double centro = larguraGrade - 2 * espaco - 2 * lateral;
        for (int r = 0; r < linhas; r++) {
            int daBase = linhas - 1 - r;
            double luz = apagada ? 0 : Math.max(0, Math.min(1, progresso * linhas - daBase));
            double y = topoGrade + r * passo;
            Color pale = Tema.misturar(Tema.FUNDO_PAINEL, Tema.CELULA, 0.40 + 0.60 * luz);
            g.setColor(pale);
            g.fill(new RoundRectangle2D.Double(margem, y, lateral, alturaCelula, 2, 2));
            g.fill(new RoundRectangle2D.Double(margem + lateral + espaco + centro + espaco, y, lateral, alturaCelula, 2, 2));
            double xc = margem + lateral + espaco;
            g.setColor(Tema.misturar(Tema.FUNDO_PAINEL, Tema.CELULA_ESCURA, 0.45 + 0.55 * luz));
            g.fill(new RoundRectangle2D.Double(xc, y, centro, alturaCelula, 2, 2));
            double posicao = (daBase + 1.0) / linhas;
            Color listra = posicao > 0.95 ? Tema.VERMELHO : (posicao > 0.82 ? Tema.AMBAR : Tema.VERDE);
            Color listraApagada = Tema.misturar(Tema.CELULA_ESCURA, Tema.VERDE_ESCURO, 0.55);
            Color cor = Tema.misturar(listraApagada, listra, luz);
            g.setColor(cor);
            double larguraListra = Math.max(2, centro * 0.12);
            for (int k = 1; k <= 3; k++) {
                double xl = xc + centro * k / 4.0 - larguraListra / 2;
                g.fill(new java.awt.geom.Rectangle2D.Double(xl, y + 1, larguraListra, alturaCelula - 2));
            }
        }
        g.dispose();
    }
}
