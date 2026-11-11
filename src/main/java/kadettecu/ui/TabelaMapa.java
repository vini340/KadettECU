package kadettecu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.DefaultCellEditor;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.LineBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import kadettecu.modelo.EixosMapa;
import kadettecu.modelo.MapaECU;
import kadettecu.modelo.TipoMapa;

/**
 * Editor de uma tabela 16x16: a primeira coluna mostra a carga (MAP) de cada linha, o cabeçalho
 * mostra a rotação de cada coluna e as outras células são editáveis. As células ficam coloridas
 * pela escala de calor (ou todas azul-claras, como na referência), a célula onde o motor está
 * trabalhando ganha uma moldura verde neon e as que foram alteradas e ainda não foram enviadas
 * à ECU ganham um cantinho âmbar.
 *
 * <p>Edição: digite um número numa célula, use as teclas + e -, ou as operações em bloco
 * ({@link #somar}, {@link #multiplicar}, {@link #definir}, {@link #suavizar}, {@link #interpolar}).
 * Ctrl+C / Ctrl+V copiam e colam texto separado por tabulação (compatível com planilhas).
 */
public final class TabelaMapa extends JTable {

    private static final long serialVersionUID = 1L;
    private static final int N = MapaECU.TAMANHO;

    /** Quem quer saber das alterações feitas na tabela (o painel de mapas). */
    public interface Ouvinte {
        /** Chamado antes de qualquer alteração, para guardar o estado e permitir "desfazer". */
        void antesDeAlterar();

        /** Chamado depois de alterar; informa quais linhas mudaram (para enviar à ECU se o modo "ao vivo" estiver ligado). */
        void aoAlterar(Set<Integer> linhasAlteradas);

        void aoMudarSelecao(int linha, int coluna);
    }

    private interface OperacaoCelula {
        int novoValor(int linha, int coluna, int raw);
    }

    private transient MapaECU mapa;
    private transient MapaECU referencia;
    private transient EixosMapa eixos;
    private transient Ouvinte ouvinte;
    private boolean modoCalor = true;
    private boolean temPonto;
    private int vivoLinha = -1;
    private int vivoColuna = -1;
    private double pontoRpm;
    private double pontoCarga;
    private final ModeloMapa modelo = new ModeloMapa();

    public TabelaMapa(MapaECU mapa, MapaECU referencia, EixosMapa eixos) {
        this.mapa = mapa;
        this.referencia = referencia;
        this.eixos = eixos;
        setModel(modelo);
        setRowHeight(24);
        setShowGrid(false);
        setIntercellSpacing(new Dimension(2, 2));
        setBackground(Tema.FUNDO);
        setCellSelectionEnabled(true);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setAutoResizeMode(AUTO_RESIZE_ALL_COLUMNS);
        setFillsViewportHeight(true);
        putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        RenderizadorCelula celulas = new RenderizadorCelula();
        setDefaultRenderer(Integer.class, celulas);
        setDefaultEditor(Integer.class, new EditorCelula());
        JTableHeader cabecalho = getTableHeader();
        cabecalho.setReorderingAllowed(false);
        cabecalho.setResizingAllowed(false);
        cabecalho.setDefaultRenderer(new RenderizadorCabecalho());
        cabecalho.setPreferredSize(new Dimension(10, 26));
        getColumnModel().getColumn(0).setCellRenderer(new RenderizadorLinha());
        getColumnModel().getColumn(0).setPreferredWidth(56);
        getColumnModel().getColumn(0).setMaxWidth(70);
        for (int c = 1; c <= N; c++) {
            getColumnModel().getColumn(c).setPreferredWidth(50);
        }

        getSelectionModel().addListSelectionListener(e -> notificarSelecao());
        getColumnModel().getSelectionModel().addListSelectionListener(e -> notificarSelecao());

        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke('+'), "kecu-mais");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke('-'), "kecu-menos");
        getActionMap().put("kecu-mais", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                somar(mapa().getTipo().getPasso());
            }
        });
        getActionMap().put("kecu-menos", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                somar(-mapa().getTipo().getPasso());
            }
        });
        getActionMap().put("copy", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                copiar();
            }
        });
        getActionMap().put("paste", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                colar();
            }
        });
    }

    private MapaECU mapa() {
        return mapa;
    }

    // ---------------------------------------------------------------- dados

    public void setOuvinte(Ouvinte ouvinte) {
        this.ouvinte = ouvinte;
    }

    public void setMapa(MapaECU mapa, MapaECU referencia, EixosMapa eixos) {
        this.mapa = mapa;
        this.referencia = referencia;
        this.eixos = eixos;
        refrescar();
    }

    public MapaECU getMapa() {
        return mapa;
    }

    /** Redesenha tudo (depois de trocar valores ou eixos por fora, por exemplo ao ler da ECU). */
    public void refrescar() {
        if (isEditing()) {
            getCellEditor().cancelCellEditing();
        }
        modelo.fireTableRowsUpdated(0, N - 1);
        getTableHeader().repaint();
    }

    public void setModoCalor(boolean calor) {
        this.modoCalor = calor;
        repaint();
    }

    /** Informa onde o motor está trabalhando na tabela (índices fracionários, 0 a 15). */
    public void setPontoOperacao(boolean visivel, double posRpm, double posCarga) {
        int linha = visivel ? (int) Math.round(Math.max(0, Math.min(N - 1, posCarga))) : -1;
        int coluna = visivel ? (int) Math.round(Math.max(0, Math.min(N - 1, posRpm))) : -1;
        boolean mudou = visivel != temPonto || linha != vivoLinha || coluna != vivoColuna
                || (int) Math.floor(posRpm) != (int) Math.floor(pontoRpm) || (int) Math.floor(posCarga) != (int) Math.floor(pontoCarga);
        this.temPonto = visivel;
        this.vivoLinha = linha;
        this.vivoColuna = coluna;
        this.pontoRpm = posRpm;
        this.pontoCarga = posCarga;
        if (mudou) {
            repaint();
        }
    }

    private void notificarSelecao() {
        if (ouvinte == null) {
            return;
        }
        int linha = getSelectionModel().getLeadSelectionIndex();
        int coluna = getColumnModel().getSelectionModel().getLeadSelectionIndex() - 1;
        ouvinte.aoMudarSelecao(linha, coluna);
    }

    /** A coluna 0 só mostra a carga: o cursor nunca entra nela. */
    @Override
    public void changeSelection(int linha, int coluna, boolean alternar, boolean estender) {
        super.changeSelection(linha, Math.max(1, coluna), alternar, estender);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int linha = rowAtPoint(e.getPoint());
        int coluna = columnAtPoint(e.getPoint());
        if (linha < 0 || coluna < 1) {
            return null;
        }
        int raw = mapa.getRaw(linha, coluna - 1);
        return String.format(Locale.ROOT, "%d rpm, %d kPa: %s %s (valor bruto %d)", eixos.getRpm(coluna - 1),
                eixos.getCarga(linha), mapa.getTipo().formatar(raw), mapa.getTipo().getUnidade(), raw);
    }

    // ---------------------------------------------------------------- operações sobre a seleção

    private List<int[]> celulasSelecionadas() {
        List<int[]> lista = new ArrayList<>();
        for (int linha : getSelectedRows()) {
            for (int coluna : getSelectedColumns()) {
                if (coluna >= 1) {
                    lista.add(new int[] {linha, coluna - 1});
                }
            }
        }
        return lista;
    }

    public boolean temSelecao() {
        return !celulasSelecionadas().isEmpty();
    }

    private void aplicar(OperacaoCelula operacao) {
        List<int[]> celulas = celulasSelecionadas();
        if (celulas.isEmpty()) {
            return;
        }
        if (isEditing()) {
            getCellEditor().cancelCellEditing();
        }
        if (ouvinte != null) {
            ouvinte.antesDeAlterar();
        }
        Set<Integer> linhas = new LinkedHashSet<>();
        for (int[] c : celulas) {
            int antigo = mapa.getRaw(c[0], c[1]);
            int novo = Math.max(0, Math.min(255, operacao.novoValor(c[0], c[1], antigo)));
            if (novo != antigo) {
                mapa.setRaw(c[0], c[1], novo);
                linhas.add(c[0]);
            }
        }
        concluirAlteracao(linhas);
    }

    private void concluirAlteracao(Set<Integer> linhas) {
        modelo.fireTableRowsUpdated(0, N - 1);
        if (ouvinte != null && !linhas.isEmpty()) {
            ouvinte.aoAlterar(linhas);
        }
    }

    /** Soma (ou subtrai, se negativo) um valor, na unidade física da tabela, às células selecionadas. */
    public void somar(double deltaFisico) {
        TipoMapa tipo = mapa.getTipo();
        int deltaRaw = (int) Math.round(deltaFisico / tipo.getPasso());
        if (deltaRaw == 0 && deltaFisico != 0) {
            deltaRaw = deltaFisico > 0 ? 1 : -1;
        }
        final int d = deltaRaw;
        aplicar((l, c, raw) -> raw + d);
    }

    /** Multiplica o valor físico das células selecionadas (1.05 = +5 %). */
    public void multiplicar(double fator) {
        TipoMapa tipo = mapa.getTipo();
        aplicar((l, c, raw) -> tipo.fisicoParaRaw(tipo.rawParaFisico(raw) * fator));
    }

    /** Põe o mesmo valor (na unidade física da tabela) em todas as células selecionadas. */
    public void definir(double fisico) {
        final int raw = mapa.getTipo().fisicoParaRaw(fisico);
        aplicar((l, c, antigo) -> raw);
    }

    /** Suaviza: cada célula selecionada vira uma média ponderada dela com as vizinhas (centro pesa mais). */
    public void suavizar() {
        final MapaECU antes = mapa.copia();
        aplicar((l, c, raw) -> {
            double soma = 0;
            double pesos = 0;
            for (int dl = -1; dl <= 1; dl++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int ll = l + dl;
                    int cc = c + dc;
                    if (ll < 0 || cc < 0 || ll >= N || cc >= N) {
                        continue;
                    }
                    double peso = dl == 0 && dc == 0 ? 4 : (dl == 0 || dc == 0 ? 2 : 1);
                    soma += peso * antes.getRaw(ll, cc);
                    pesos += peso;
                }
            }
            return (int) Math.round(soma / pesos);
        });
    }

    /** Preenche o retângulo selecionado por interpolação linear entre os quatro cantos. */
    public void interpolar() {
        List<int[]> celulas = celulasSelecionadas();
        if (celulas.size() < 4) {
            return;
        }
        int l0 = N;
        int l1 = -1;
        int c0 = N;
        int c1 = -1;
        for (int[] c : celulas) {
            l0 = Math.min(l0, c[0]);
            l1 = Math.max(l1, c[0]);
            c0 = Math.min(c0, c[1]);
            c1 = Math.max(c1, c[1]);
        }
        if (l1 == l0 || c1 == c0) {
            return;
        }
        final int fl0 = l0;
        final int fl1 = l1;
        final int fc0 = c0;
        final int fc1 = c1;
        final double a = mapa.getRaw(fl0, fc0);
        final double b = mapa.getRaw(fl0, fc1);
        final double cc = mapa.getRaw(fl1, fc0);
        final double d = mapa.getRaw(fl1, fc1);
        aplicar((l, c, raw) -> {
            double u = (c - fc0) / (double) (fc1 - fc0);
            double w = (l - fl0) / (double) (fl1 - fl0);
            double topo = a * (1 - u) + b * u;
            double base = cc * (1 - u) + d * u;
            return (int) Math.round(topo * (1 - w) + base * w);
        });
    }

    // ---------------------------------------------------------------- copiar e colar

    private void copiar() {
        List<int[]> celulas = celulasSelecionadas();
        if (celulas.isEmpty()) {
            return;
        }
        int[] linhas = getSelectedRows();
        int[] colunas = getSelectedColumns();
        StringBuilder texto = new StringBuilder();
        for (int linha : linhas) {
            boolean primeiro = true;
            for (int coluna : colunas) {
                if (coluna < 1) {
                    continue;
                }
                if (!primeiro) {
                    texto.append('\t');
                }
                texto.append(mapa.getTipo().formatar(mapa.getRaw(linha, coluna - 1)));
                primeiro = false;
            }
            texto.append('\n');
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(texto.toString()), null);
        } catch (IllegalStateException e) {
            // a área de transferência pode estar ocupada por outro programa: simplesmente não copia
        }
    }

    private void colar() {
        String texto;
        try {
            Object dado = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            texto = String.valueOf(dado);
        } catch (java.io.IOException | java.awt.datatransfer.UnsupportedFlavorException | IllegalStateException e) {
            return;
        }
        int linha0 = Math.max(0, getSelectionModel().getMinSelectionIndex());
        int coluna0 = Math.max(0, getColumnModel().getSelectionModel().getMinSelectionIndex() - 1);
        List<double[]> valores = new ArrayList<>();
        for (String linha : texto.split("\\r?\\n")) {
            if (linha.trim().isEmpty()) {
                continue;
            }
            String[] partes = linha.split("[\\t;]");
            double[] v = new double[partes.length];
            for (int i = 0; i < partes.length; i++) {
                try {
                    v[i] = Double.parseDouble(partes[i].trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    return; // texto que não é uma tabela de números: ignora tudo em vez de colar pela metade
                }
            }
            valores.add(v);
        }
        if (valores.isEmpty()) {
            return;
        }
        if (ouvinte != null) {
            ouvinte.antesDeAlterar();
        }
        Set<Integer> alteradas = new LinkedHashSet<>();
        for (int i = 0; i < valores.size(); i++) {
            for (int j = 0; j < valores.get(i).length; j++) {
                int l = linha0 + i;
                int c = coluna0 + j;
                if (l >= N || c >= N) {
                    continue;
                }
                int novo = mapa.getTipo().fisicoParaRaw(valores.get(i)[j]);
                if (novo != mapa.getRaw(l, c)) {
                    mapa.setRaw(l, c, novo);
                    alteradas.add(l);
                }
            }
        }
        concluirAlteracao(alteradas);
    }

    // ---------------------------------------------------------------- modelo

    private final class ModeloMapa extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        @Override
        public int getRowCount() {
            return N;
        }

        @Override
        public int getColumnCount() {
            return N + 1;
        }

        @Override
        public Class<?> getColumnClass(int coluna) {
            return Integer.class;
        }

        @Override
        public Object getValueAt(int linha, int coluna) {
            return coluna == 0 ? Integer.valueOf(eixos.getCarga(linha)) : Integer.valueOf(mapa.getRaw(linha, coluna - 1));
        }

        @Override
        public boolean isCellEditable(int linha, int coluna) {
            return coluna >= 1;
        }

        @Override
        public void setValueAt(Object valor, int linha, int coluna) {
            if (coluna < 1) {
                return;
            }
            double fisico;
            try {
                fisico = Double.parseDouble(String.valueOf(valor).trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return; // texto inválido: a célula volta ao valor anterior
            }
            if (Double.isNaN(fisico) || Double.isInfinite(fisico)) {
                return;
            }
            int novo = mapa.getTipo().fisicoParaRaw(fisico);
            if (novo == mapa.getRaw(linha, coluna - 1)) {
                return;
            }
            if (ouvinte != null) {
                ouvinte.antesDeAlterar();
            }
            mapa.setRaw(linha, coluna - 1, novo);
            fireTableRowsUpdated(0, N - 1);
            if (ouvinte != null) {
                Set<Integer> linhas = new LinkedHashSet<>();
                linhas.add(linha);
                ouvinte.aoAlterar(linhas);
            }
        }
    }

    // ---------------------------------------------------------------- desenho das células

    private final class RenderizadorCelula extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;
        private boolean selecionada;
        private boolean vivo;
        private boolean vizinho;
        private boolean alterada;

        @Override
        public Component getTableCellRendererComponent(JTable tabela, Object valor, boolean sel, boolean foco,
                                                       int linha, int coluna) {
            super.getTableCellRendererComponent(tabela, "", false, false, linha, coluna);
            int c = coluna - 1;
            int raw = mapa.getRaw(linha, c);
            TipoMapa tipo = mapa.getTipo();
            setText(tipo.formatar(raw));
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(Tema.fonteMono(Font.BOLD, 11.5f));
            setOpaque(true);
            setBorder(null);
            setBackground(modoCalor ? PaletaCalor.corSuave(tipo.normalizar(raw), 0.82) : Tema.CELULA);
            setForeground(new Color(0x07121F));
            selecionada = sel;
            vivo = temPonto && linha == vivoLinha && c == vivoColuna;
            vizinho = temPonto && !vivo && linha >= (int) Math.floor(pontoCarga) && linha <= (int) Math.floor(pontoCarga) + 1
                    && c >= (int) Math.floor(pontoRpm) && c <= (int) Math.floor(pontoRpm) + 1;
            alterada = referencia != null && referencia.getRaw(linha, c) != raw;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth();
            int h = getHeight();
            if (selecionada) {
                g.setColor(new Color(255, 255, 255, 70));
                g.fillRect(0, 0, w, h);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawRect(1, 1, w - 3, h - 3);
            }
            if (vizinho) {
                g.setColor(Tema.comAlfa(new Color(0x0E8A35), 190));
                g.setStroke(new BasicStroke(1.2f));
                g.drawRect(1, 1, w - 3, h - 3);
            }
            if (vivo) {
                g.setColor(Tema.comAlfa(Tema.VERDE, 90));
                g.setStroke(new BasicStroke(5f));
                g.drawRect(2, 2, w - 5, h - 5);
                g.setColor(new Color(0x0E7A2E));
                g.setStroke(new BasicStroke(2.2f));
                g.drawRect(1, 1, w - 3, h - 3);
            }
            if (alterada) {
                Path2D.Double canto = new Path2D.Double();
                canto.moveTo(w - 9, 0);
                canto.lineTo(w, 0);
                canto.lineTo(w, 9);
                canto.closePath();
                g.setColor(Tema.AMBAR);
                g.fill(canto);
            }
            g.dispose();
        }
    }

    /** Primeira coluna: a carga de cada linha, num rótulo azul-claro, com um ponto âmbar se a linha difere da ECU. */
    private final class RenderizadorLinha implements TableCellRenderer {

        private final LinhaRotulo rotulo = new LinhaRotulo();

        @Override
        public Component getTableCellRendererComponent(JTable tabela, Object valor, boolean sel, boolean foco,
                                                       int linha, int coluna) {
            rotulo.configurar(String.valueOf(valor), referencia != null && mapa.linhaDiferente(linha, referencia),
                    temPonto && linha == vivoLinha);
            return rotulo;
        }
    }

    private static final class LinhaRotulo extends JLabel {

        private static final long serialVersionUID = 1L;
        private boolean diferente;
        private boolean viva;

        LinhaRotulo() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(Tema.fonteMono(Font.BOLD, 11.5f));
            setOpaque(false);
        }

        void configurar(String texto, boolean diferente, boolean viva) {
            setText(texto);
            this.diferente = diferente;
            this.viva = viva;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth();
            int h = getHeight();
            g.setPaint(new GradientPaint(0, 0, viva ? Tema.VERDE : Tema.CELULA_CLARA, 0, h, viva ? Tema.VERDE_PRONTO : Tema.CELULA));
            g.fillRect(0, 0, w, h);
            g.setColor(Tema.FUNDO);
            g.setFont(getFont());
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), (w - fm.stringWidth(getText())) / 2f, (h + fm.getAscent() - fm.getDescent()) / 2f);
            if (diferente) {
                g.setColor(Tema.AMBAR);
                g.fillOval(4, h / 2 - 3, 6, 6);
                g.setColor(Tema.FUNDO);
                g.drawOval(4, h / 2 - 3, 6, 6);
            }
            g.dispose();
        }
    }

    /** Cabeçalho das colunas: o valor de RPM num rótulo azul-claro (o mesmo estilo das abas STAGE). */
    private final class RenderizadorCabecalho implements TableCellRenderer {

        private final CabecalhoRotulo rotulo = new CabecalhoRotulo();

        @Override
        public Component getTableCellRendererComponent(JTable tabela, Object valor, boolean sel, boolean foco,
                                                       int linha, int coluna) {
            if (coluna == 0) {
                rotulo.configurar("kPa\\rpm", true);
            } else {
                boolean viva = temPonto && coluna - 1 == vivoColuna;
                rotulo.configurar(String.valueOf(eixos.getRpm(coluna - 1)), false);
                rotulo.viva = viva;
            }
            return rotulo;
        }
    }

    private static final class CabecalhoRotulo extends JLabel {

        private static final long serialVersionUID = 1L;
        private boolean pequeno;
        private boolean viva;

        CabecalhoRotulo() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(false);
        }

        void configurar(String texto, boolean pequeno) {
            setText(texto);
            this.pequeno = pequeno;
            this.viva = false;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth();
            int h = getHeight();
            g.setPaint(new GradientPaint(0, 0, viva ? Tema.VERDE : Tema.CELULA_CLARA, 0, h, viva ? Tema.VERDE_PRONTO : Tema.CELULA));
            g.fillRect(0, 2, w, h - 2);
            g.setColor(Tema.FUNDO);
            g.setFont(pequeno ? Tema.fonteTitulo(9f) : Tema.fonteMono(Font.BOLD, 11f));
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), (w - fm.stringWidth(getText())) / 2f, (h + 2 + fm.getAscent() - fm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** Editor de célula: mostra o valor na unidade física (não o byte bruto) e seleciona tudo ao começar. */
    private final class EditorCelula extends DefaultCellEditor {

        private static final long serialVersionUID = 1L;

        EditorCelula() {
            super(new JTextField());
        }

        @Override
        public Component getTableCellEditorComponent(JTable tabela, Object valor, boolean sel, int linha, int coluna) {
            JTextField campo = (JTextField) super.getTableCellEditorComponent(tabela, valor, sel, linha, coluna);
            campo.setText(mapa.getTipo().formatar(mapa.getRaw(linha, coluna - 1)));
            campo.setHorizontalAlignment(SwingConstants.CENTER);
            campo.setFont(Tema.fonteMono(Font.BOLD, 11.5f));
            campo.setBackground(Tema.FUNDO);
            campo.setForeground(Tema.VERDE);
            campo.setCaretColor(Tema.VERDE);
            campo.setBorder(new LineBorder(Tema.VERDE, 2));
            campo.selectAll();
            return campo;
        }
    }
}
