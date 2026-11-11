package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import kadettecu.app.SessaoECU;
import kadettecu.comunicacao.ErroProtocolo;
import kadettecu.modelo.CampoParametro;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.ParametrosMotor;
import kadettecu.modelo.Telemetria;
import kadettecu.protocolo.Protocolo;

/**
 * A aba PARÂMETROS: limitador de giro, two-step, launch control, enriquecimento em aceleração,
 * marcha lenta, injetores/ignição e sinal de rotação.
 *
 * <p>A tela inteira é gerada a partir do {@link CampoParametro}: para cada constante do enum se cria
 * o editor certo (caixa de marcar, lista de opções ou número com setinhas) já com a faixa, a unidade
 * e o valor de fábrica. Acrescentar um parâmetro novo no firmware e no enum faz ele aparecer aqui
 * sozinho, sem mexer nesta classe.
 *
 * <p>Um rótulo fica âmbar quando o valor na tela é diferente do que está na ECU. Os campos do sinal
 * de rotação ficam bloqueados com o motor girando, que é quando a ECU também os recusaria.
 */
public final class PainelParametros extends JPanel implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    private static final int ATRASO_AO_VIVO_MS = 250;
    private static final int PERIODO_ESTADO_MS = 400;
    private static final int COLUNAS_MAXIMAS = 3;
    private static final int ESPACO_GRUPOS = 12;
    private static final String DICA_PADRAO = "Os rótulos em âmbar têm valor diferente do que está na ECU. Envie com o botão ao lado "
            + "ou ligue o AO VIVO.";

    private final transient SessaoECU sessao;
    private final transient Map<CampoParametro, Editor> editores = new EnumMap<>(CampoParametro.class);
    private final JCheckBox aoVivo = new JCheckBox("AO VIVO");
    private final JLabel aviso = new JLabel(DICA_PADRAO);
    private final Timer temporizadorEnvio = new Timer(ATRASO_AO_VIVO_MS, e -> enviar());
    private final Timer relogio = new Timer(PERIODO_ESTADO_MS, e -> aoTique());
    private final Grade grade = new Grade();
    private final transient List<JPanel> quadros = new ArrayList<>();
    private final int[] largurasDosGrupos = new int[CampoParametro.Grupo.values().length];
    private int colunas;
    private boolean carregando;
    private boolean motorGirando;

    public PainelParametros(SessaoECU sessao) {
        super(new BorderLayout(0, 8));
        this.sessao = sessao;
        setBackground(Tema.FUNDO_PAINEL);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        temporizadorEnvio.setRepeats(false);

        CampoParametro.Grupo[] grupos = CampoParametro.Grupo.values();
        for (int i = 0; i < grupos.length; i++) {
            JPanel quadro = montarGrupo(grupos[i]);
            largurasDosGrupos[i] = quadro.getPreferredSize().width;
            quadros.add(quadro);
        }
        organizarGrupos(COLUNAS_MAXIMAS);

        JScrollPane rolagem = new JScrollPane(grade);
        rolagem.setBorder(BorderFactory.createEmptyBorder());
        rolagem.getViewport().setOpaque(false);
        rolagem.setOpaque(false);
        rolagem.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                ajustarColunas(rolagem.getViewport().getWidth());
            }
        });
        add(rolagem, BorderLayout.CENTER);
        add(montarRodape(), BorderLayout.SOUTH);

        carregarTodos();
        aoMudarEstado(sessao.getEstado());
        sessao.adicionarOuvinte(new OuvinteNaTela(this));
    }

    // ---------------------------------------------------------------- montagem

    /**
     * A grade dos grupos dentro da rolagem. Ela acompanha a largura da janela quando há espaço (os grupos
     * se esticam e nada fica cortado); quando a janela estreita, o painel passa de três para duas ou uma
     * coluna (veja {@link #ajustarColunas}) e a rolagem fica só na vertical.
     */
    private static final class Grade extends JPanel implements Scrollable {

        private static final long serialVersionUID = 1L;
        private static final int PASSO_ROLAGEM = 18;

        Grade() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visivel, int orientacao, int direcao) {
            return PASSO_ROLAGEM;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visivel, int orientacao, int direcao) {
            int total = orientacao == SwingConstants.VERTICAL ? visivel.height : visivel.width;
            return Math.max(PASSO_ROLAGEM, total - PASSO_ROLAGEM);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            Container pai = getParent();
            return pai instanceof JViewport && pai.getWidth() >= getPreferredSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container pai = getParent();
            return pai instanceof JViewport && pai.getHeight() >= getPreferredSize().height;
        }
    }

    /** Escolhe quantas colunas cabem na largura da janela (três, duas ou uma) e reorganiza se mudou. */
    private void ajustarColunas(int larguraDisponivel) {
        int novas = 1;
        for (int n = COLUNAS_MAXIMAS; n > 1; n--) {
            if (larguraNecessaria(n) <= larguraDisponivel) {
                novas = n;
                break;
            }
        }
        if (novas != colunas) {
            organizarGrupos(novas);
        }
    }

    /** Largura que a grade pede com {@code n} colunas: a soma do grupo mais largo de cada coluna e dos espaços. */
    private int larguraNecessaria(int n) {
        int total = ESPACO_GRUPOS * (n - 1);
        for (int coluna = 0; coluna < n; coluna++) {
            int maior = 0;
            for (int i = coluna; i < largurasDosGrupos.length; i += n) {
                maior = Math.max(maior, largurasDosGrupos[i]);
            }
            total += maior;
        }
        return total;
    }

    /** Distribui os quadros dos grupos em {@code novasColunas} colunas, na ordem de leitura. */
    private void organizarGrupos(int novasColunas) {
        colunas = novasColunas;
        grade.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.anchor = GridBagConstraints.NORTH;
        for (int i = 0; i < quadros.size(); i++) {
            c.gridx = i % colunas;
            c.gridy = i / colunas;
            c.insets = new Insets(0, 0, ESPACO_GRUPOS, c.gridx == colunas - 1 ? 0 : ESPACO_GRUPOS);
            grade.add(quadros.get(i), c);
        }
        // a última linha é elástica: se sobrar altura, o espaço fica embaixo e os quadros não se esticam
        c.gridx = 0;
        c.gridy = (quadros.size() + colunas - 1) / colunas;
        c.gridwidth = colunas;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        JPanel folga = new JPanel();
        folga.setOpaque(false);
        grade.add(folga, c);
        grade.revalidate();
        grade.repaint();
    }

    private JPanel montarGrupo(CampoParametro.Grupo grupo) {
        JPanel quadro = new JPanel(new GridBagLayout());
        quadro.setOpaque(false);
        quadro.setBorder(Tema.moldura(grupo.getTitulo()));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(4, 5, 4, 5);
        for (CampoParametro campo : CampoParametro.values()) {
            if (campo.getGrupo() != grupo) {
                continue;
            }
            Editor editor = criarEditor(campo);
            editores.put(campo, editor);
            c.gridx = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            quadro.add(new RotuloCampo(campo), c);
            c.gridx = 1;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            quadro.add(editor.controle, c);
            c.gridx = 2;
            c.anchor = GridBagConstraints.WEST;
            JLabel unidade = new JLabel(campo.getUnidade());
            unidade.setFont(Tema.fonteUI(java.awt.Font.PLAIN, 11f));
            unidade.setForeground(Tema.TEXTO_FRACO);
            quadro.add(unidade, c);
            c.gridy++;
        }
        // Uma última linha elástica empurra os campos para cima: o quadro pode ficar mais alto do que o
        // conteúdo (os quadros de uma mesma fileira têm a mesma altura) e os campos não flutuam no meio.
        JPanel folga = new JPanel();
        folga.setOpaque(false);
        c.gridx = 0;
        c.gridwidth = 3;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        quadro.add(folga, c);
        return quadro;
    }

    private JPanel montarRodape() {
        JPanel rodape = new JPanel(new BorderLayout(12, 0));
        rodape.setOpaque(false);
        aoVivo.setOpaque(false);
        aoVivo.setFont(Tema.fonteTitulo(11f));
        aoVivo.setToolTipText("<html>Cada alteração é enviada à ECU na hora (só para a RAM da placa).<br>"
                + "Use GRAVAR NA MEMÓRIA para que ela sobreviva ao desligar.</html>");
        aoVivo.addActionListener(e -> {
            if (aoVivo.isSelected()) {
                mostrarAviso("AO VIVO ligado: as próximas alterações vão direto para a ECU.", Tema.AMBAR);
                temporizadorEnvio.restart();
            } else {
                temporizadorEnvio.stop();
                mostrarAviso(DICA_PADRAO, Tema.TEXTO_FRACO);
            }
        });
        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        botoes.setOpaque(false);
        botoes.add(Controles.botao("ENVIAR PARÂMETROS", "Manda só os parâmetros (não as tabelas) para a RAM da ECU", this::enviar));
        botoes.add(aoVivo);
        rodape.add(botoes, BorderLayout.WEST);
        aviso.setFont(Tema.fonteUI(java.awt.Font.PLAIN, 11.5f));
        aviso.setForeground(Tema.TEXTO_FRACO);
        rodape.add(aviso, BorderLayout.CENTER);
        return rodape;
    }

    // ---------------------------------------------------------------- editores (um tipo para cada forma de campo)

    /** Rótulo que muda de cor sozinho quando o valor na tela difere do que a ECU tem. */
    private final class RotuloCampo extends JLabel {

        private static final long serialVersionUID = 1L;
        private final CampoParametro campo;

        RotuloCampo(CampoParametro campo) {
            super(campo.getRotulo());
            this.campo = campo;
            setFont(Tema.fonteUI(java.awt.Font.PLAIN, 12f));
        }

        @Override
        public Color getForeground() {
            if (campo != null && sessao != null && diferenteDaEcu(campo)) {
                return Tema.AMBAR;
            }
            return Tema.TEXTO;
        }
    }

    private boolean diferenteDaEcu(CampoParametro campo) {
        return sessao.getCalibracao().getParametros().get(campo) != sessao.getCalibracaoEcu().getParametros().get(campo);
    }

    /** O que todo editor sabe fazer, qualquer que seja o controle que ele usa. */
    private abstract class Editor {
        final CampoParametro campo;
        final JComponent controle;

        Editor(CampoParametro campo, JComponent controle) {
            this.campo = campo;
            this.controle = controle;
            controle.setToolTipText(descricaoDaFaixa(campo));
        }

        /** Mostra o valor no controle sem tratar isso como uma edição do usuário. */
        abstract void mostrar(int valor);

        /** Valor (já na unidade inteira do protocolo) que o controle mostra agora. */
        abstract int ler();
    }

    private final class EditorBool extends Editor {

        EditorBool(CampoParametro campo) {
            super(campo, new JCheckBox());
            JCheckBox caixa = (JCheckBox) controle;
            caixa.setOpaque(false);
            caixa.setFont(Tema.fonteTitulo(10.5f));
            caixa.setHorizontalAlignment(JCheckBox.LEFT);
            caixa.setPreferredSize(new Dimension(110, 24));
            caixa.addActionListener(e -> {
                atualizarTexto(caixa);
                aoEditar(this);
            });
        }

        @Override
        void mostrar(int valor) {
            JCheckBox c = (JCheckBox) controle;
            c.setSelected(valor != 0);
            atualizarTexto(c);
        }

        @Override
        int ler() {
            return ((JCheckBox) controle).isSelected() ? 1 : 0;
        }
    }

    private final class EditorOpcoes extends Editor {

        @SuppressWarnings("unchecked")
        EditorOpcoes(CampoParametro campo) {
            super(campo, new JComboBox<>(campo.getOpcoes()));
            JComboBox<String> lista = (JComboBox<String>) controle;
            FontMetrics fm = lista.getFontMetrics(lista.getFont());
            int maior = 0;
            for (String opcao : campo.getOpcoes()) {
                maior = Math.max(maior, fm.stringWidth(opcao));
            }
            lista.setPreferredSize(new Dimension(maior + 44, 26));
            lista.addActionListener(e -> aoEditar(this));
        }

        @Override
        void mostrar(int valor) {
            @SuppressWarnings("unchecked")
            JComboBox<String> lista = (JComboBox<String>) controle;
            lista.setSelectedIndex(Math.max(0, Math.min(lista.getItemCount() - 1, valor - campo.getMin())));
        }

        @Override
        int ler() {
            @SuppressWarnings("unchecked")
            JComboBox<String> lista = (JComboBox<String>) controle;
            return campo.getMin() + lista.getSelectedIndex();
        }
    }

    private final class EditorNumero extends Editor {

        private final double divisor;

        EditorNumero(CampoParametro campo) {
            super(campo, criarSpinner(campo));
            this.divisor = campo.getDivisor();
            ((JSpinner) controle).addChangeListener(e -> aoEditar(this));
        }

        @Override
        void mostrar(int valor) {
            ((JSpinner) controle).setValue(valor / divisor);
        }

        @Override
        int ler() {
            return (int) Math.round(((Number) ((JSpinner) controle).getValue()).doubleValue() * divisor);
        }
    }

    private static void atualizarTexto(JCheckBox caixa) {
        caixa.setText(caixa.isSelected() ? "LIGADO" : "DESLIGADO");
        caixa.setForeground(caixa.isSelected() ? Tema.VERDE : Tema.TEXTO_FRACO);
    }

    private static JSpinner criarSpinner(CampoParametro campo) {
        int divisor = campo.getDivisor();
        double passo = Math.max(1, passoBruto(campo)) / (double) divisor;
        SpinnerNumberModel modelo = new SpinnerNumberModel(campo.getPadrao() / (double) divisor,
                campo.getMin() / (double) divisor, campo.getMax() / (double) divisor, passo);
        int casas = divisor >= 100 ? 2 : (divisor >= 10 ? 1 : 0);
        StringBuilder formato = new StringBuilder("0");
        if (casas > 0) {
            formato.append('.');
            for (int i = 0; i < casas; i++) {
                formato.append('0');
            }
        }
        JSpinner spinner = Controles.numero(modelo, formato.toString(), 6);
        spinner.setPreferredSize(new Dimension(100, 26));
        return spinner;
    }

    /** Tamanho do passo das setinhas, na unidade inteira do protocolo, proporcional à faixa do campo. */
    private static int passoBruto(CampoParametro campo) {
        int faixa = campo.getMax() - campo.getMin();
        if (faixa >= 10000) {
            return 100;
        }
        if (faixa >= 2000) {
            return 50;
        }
        if (faixa >= 500) {
            return 10;
        }
        return 1;
    }

    private static String descricaoDaFaixa(CampoParametro campo) {
        if (campo.getTipo() == CampoParametro.Tipo.BOOL) {
            return "Liga ou desliga (padrão: " + (campo.getPadrao() != 0 ? "ligado" : "desligado") + ")";
        }
        if (campo.temOpcoes()) {
            return "Padrão: " + campo.getOpcoes()[campo.getPadrao() - campo.getMin()];
        }
        double d = campo.getDivisor();
        String unidade = campo.getUnidade().isEmpty() ? "" : " " + campo.getUnidade();
        String texto = String.format(Locale.ROOT, "Faixa: %s a %s%s (padrão %s)", numero(campo.getMin() / d),
                numero(campo.getMax() / d), unidade, numero(campo.getPadrao() / d));
        return campo.exigeMotorParado() ? texto + ". Só pode mudar com o motor parado." : texto;
    }

    private static String numero(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private Editor criarEditor(CampoParametro campo) {
        if (campo.getTipo() == CampoParametro.Tipo.BOOL) {
            return new EditorBool(campo);
        }
        if (campo.temOpcoes()) {
            return new EditorOpcoes(campo);
        }
        return new EditorNumero(campo);
    }

    // ---------------------------------------------------------------- edição

    /** Um controle foi mexido pelo usuário: grava no modelo local e (se AO VIVO) agenda o envio. */
    private void aoEditar(Editor editor) {
        if (carregando) {
            return;
        }
        ParametrosMotor parametros = sessao.getCalibracao().getParametros();
        try {
            parametros.set(editor.campo, editor.ler());
        } catch (IllegalArgumentException e) {
            mostrarAviso(e.getMessage(), Tema.VERMELHO);
            editor.mostrar(parametros.get(editor.campo));
            return;
        }
        sessao.notificarEdicao();
        repaint();
        if (aoVivo.isSelected() && sessao.getEstado() == EstadoConexao.CONECTADO) {
            temporizadorEnvio.restart();
        }
    }

    /** Copia todos os valores do modelo local para os controles. */
    private void carregarTodos() {
        ParametrosMotor parametros = sessao.getCalibracao().getParametros();
        carregando = true;
        try {
            for (Editor editor : editores.values()) {
                editor.mostrar(parametros.get(editor.campo));
            }
        } finally {
            carregando = false;
        }
        repaint();
    }

    private void enviar() {
        if (sessao.getEstado() != EstadoConexao.CONECTADO) {
            mostrarAviso("Conecte-se à ECU para enviar os parâmetros.", Tema.AMBAR);
            return;
        }
        Tarefa.aoTerminarOk(sessao.enviarParametros(), () -> {
            repaint();
            mostrarAviso("Parâmetros enviados à ECU.", Tema.VERDE);
        }, this::falhaAoEnviar);
    }

    private void falhaAoEnviar(Throwable erro) {
        Throwable causa = Tarefa.causaReal(erro);
        String texto = SessaoECU.descreverErro(causa);
        if (causa instanceof ErroProtocolo && ((ErroProtocolo) causa).getCodigo() == Protocolo.ERRO_MOTOR_GIRANDO) {
            texto = "A ECU recusou: o motor está girando. Os campos de SINAL (cilindros, dentes, offset do PMS) só "
                    + "mudam com o motor parado. Pare o motor e envie de novo.";
        }
        if (aoVivo.isSelected()) {
            aoVivo.setSelected(false);
            temporizadorEnvio.stop();
            texto = "AO VIVO desligado. " + texto;
        }
        mostrarAviso(texto, Tema.VERMELHO);
        sessao.registrar("Envio de parâmetros falhou: " + texto);
    }

    private void mostrarAviso(String texto, Color cor) {
        aviso.setForeground(cor);
        aviso.setText(texto);
    }

    // ---------------------------------------------------------------- avisos da sessão e relógio

    @Override
    public void aoMudarEstado(EstadoConexao estado) {
        boolean conectado = estado == EstadoConexao.CONECTADO;
        aoVivo.setEnabled(conectado);
        if (!conectado) {
            motorGirando = false;
            if (aoVivo.isSelected()) {
                aoVivo.setSelected(false);
                temporizadorEnvio.stop();
            }
            atualizarBloqueios();
        }
    }

    @Override
    public void aoSubstituirCalibracao() {
        carregarTodos();
        mostrarAviso(DICA_PADRAO, Tema.TEXTO_FRACO);
    }

    @Override
    public void aoMudarSituacaoCalibracao(boolean alterada) {
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        relogio.start();
    }

    @Override
    public void removeNotify() {
        relogio.stop();
        super.removeNotify();
    }

    private void aoTique() {
        if (!isShowing()) {
            return;
        }
        Telemetria t = sessao.getConexao().getUltimaTelemetria();
        boolean girando = sessao.getEstado() == EstadoConexao.CONECTADO && t.temFlag(Protocolo.FLAG_MOTOR_GIRANDO);
        if (girando != motorGirando) {
            motorGirando = girando;
            atualizarBloqueios();
        }
    }

    /** Com o motor girando, os campos do sinal de rotação ficam bloqueados (a ECU os recusaria). */
    private void atualizarBloqueios() {
        for (Editor editor : editores.values()) {
            if (editor.campo.exigeMotorParado()) {
                editor.controle.setEnabled(!motorGirando);
            }
        }
    }
}
