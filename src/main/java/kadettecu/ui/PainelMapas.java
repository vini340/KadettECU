package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import kadettecu.app.SessaoECU;
import kadettecu.modelo.Calibracao;
import kadettecu.modelo.EixosMapa;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.MapaECU;
import kadettecu.modelo.Telemetria;
import kadettecu.modelo.TipoMapa;
import kadettecu.protocolo.Protocolo;

/**
 * A aba MAPAS: edita as três tabelas 16x16 (combustível, ignição e alvo de lambda). À esquerda a
 * tabela editável com as ferramentas de ajuste; à direita a superfície 3D e as curvas da mesma tabela.
 * Com o motor girando, o ponto de operação (a célula que a ECU está lendo agora) aparece nas três
 * visões.
 *
 * <p>Modo AO VIVO: cada alteração é enviada à ECU depois de uma pausa de 150 ms sem novas edições
 * (um {@link Timer} de disparo único que é reiniciado a cada edição). Assim, segurar a tecla "+"
 * manda uma rajada de edições vira poucos envios, e só as linhas que mudaram trafegam.
 *
 * <p>Desfazer: antes de cada alteração a tabela avisa (veja {@link TabelaMapa.Ouvinte}) e guardamos uma
 * cópia; há uma pilha por tabela, limitada a 60 passos.
 */
public final class PainelMapas extends JPanel implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    private static final int PERIODO_PONTO_MS = 50;
    private static final int ATRASO_AO_VIVO_MS = 150;
    private static final int LIMITE_DESFAZER = 60;
    private static final int ALTURA_MIN_LINHA = 20;
    private static final int ALTURA_MAX_LINHA = 40;
    private static final String DICA_PADRAO = "Selecione células (arraste ou Shift+clique). Digite um número, use + e - "
            + "ou os botões abaixo. Ctrl+C / Ctrl+V copia e cola, Ctrl+Z desfaz.";

    private final transient SessaoECU sessao;
    private final TabelaMapa tabela;
    private final Superficie3D superficie = new Superficie3D(true);
    private final CurvasMapa curvas = new CurvasMapa();
    private final LegendaCalor legenda = new LegendaCalor();
    private final JPanel quadroTabela = new JPanel(new BorderLayout(0, 6));
    private final JLabel dica = new JLabel(DICA_PADRAO);

    private final SpinnerNumberModel modeloSoma = new SpinnerNumberModel(1.0, 0.001, 100.0, 1.0);
    private final SpinnerNumberModel modeloFator = new SpinnerNumberModel(5.0, 0.1, 100.0, 1.0);
    private final SpinnerNumberModel modeloValor = new SpinnerNumberModel(80.0, 0.0, 255.0, 1.0);
    private final JSpinner campoSoma = Controles.numero(modeloSoma, "0.###", 4);
    private final JSpinner campoFator = Controles.numero(modeloFator, "0.#", 4);
    private final JSpinner campoValor = Controles.numero(modeloValor, "0.###", 5);
    private final JButton botaoDesfazer = Controles.semFoco(Controles.botao("DESFAZER", "Desfaz a última alteração desta tabela (Ctrl+Z)", this::desfazer));
    private final JCheckBox aoVivo = new JCheckBox("AO VIVO");

    private final transient Map<TipoMapa, Deque<MapaECU>> pilhas = new EnumMap<>(TipoMapa.class);
    private final transient Map<TipoMapa, Set<Integer>> pendentes = new EnumMap<>(TipoMapa.class);
    private final Timer temporizadorEnvio = new Timer(ATRASO_AO_VIVO_MS, e -> enviarPendentes());
    private final Timer relogio = new Timer(PERIODO_PONTO_MS, e -> aoTique());
    private TipoMapa tipo = TipoMapa.COMBUSTIVEL;

    public PainelMapas(SessaoECU sessao) {
        super(new BorderLayout(0, 6));
        this.sessao = sessao;
        setBackground(Tema.FUNDO_PAINEL);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        Calibracao cal = sessao.getCalibracao();
        tabela = new TabelaMapa(cal.getMapa(tipo), sessao.getCalibracaoEcu().getMapa(tipo), cal.getEixos());
        tabela.setOuvinte(new OuvinteTabela());
        temporizadorEnvio.setRepeats(false);

        aoVivo.setOpaque(false);
        aoVivo.setFont(Tema.fonteTitulo(11f));
        aoVivo.setToolTipText("<html>Cada alteração é enviada à ECU na hora (só para a RAM da placa).<br>"
                + "Use GRAVAR NA MEMÓRIA para que ela sobreviva ao desligar.</html>");
        aoVivo.setEnabled(sessao.getEstado() == EstadoConexao.CONECTADO);
        aoVivo.addActionListener(e -> {
            if (aoVivo.isSelected()) {
                mostrarDica("AO VIVO ligado: as próximas alterações vão direto para a ECU.", Tema.AMBAR);
            } else {
                pendentes.clear();
                temporizadorEnvio.stop();
                mostrarDica(DICA_PADRAO, Tema.TEXTO_FRACO);
            }
        });

        dica.setFont(Tema.fonteUI(java.awt.Font.PLAIN, 11.5f));
        dica.setForeground(Tema.TEXTO_FRACO);

        add(montarTopo(), BorderLayout.NORTH);
        JPanel centro = new JPanel(new BorderLayout(10, 0));
        centro.setOpaque(false);
        centro.add(montarEsquerda(), BorderLayout.CENTER);
        centro.add(montarDireita(), BorderLayout.EAST);
        add(centro, BorderLayout.CENTER);
        add(dica, BorderLayout.SOUTH);

        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "kecu-desfazer");
        getActionMap().put("kecu-desfazer", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                desfazer();
            }
        });

        configurarTipo();
        atualizarDesfazer();
        sessao.adicionarOuvinte(new OuvinteNaTela(this));
    }

    // ---------------------------------------------------------------- montagem

    private JPanel montarTopo() {
        JPanel topo = new JPanel(new BorderLayout(10, 0));
        topo.setOpaque(false);

        JPanel seletor = new JPanel(new GridLayout(1, 3, 4, 0));
        seletor.setOpaque(false);
        ButtonGroup grupo = new ButtonGroup();
        for (TipoMapa t : TipoMapa.values()) {
            JToggleButton botao = new JToggleButton(t.getRotulo(), t == tipo);
            botao.setFont(Tema.fonteTitulo(11.5f));
            botao.setFocusable(false);
            botao.addActionListener(e -> trocarTipo(t));
            grupo.add(botao);
            seletor.add(botao);
        }
        topo.add(seletor, BorderLayout.WEST);

        JToggleButton cores = Controles.semFoco(Controles.alternar("CORES", "Liga e desliga a escala de cores da tabela", true,
                tabela::setModoCalor));
        JButton eixos = Controles.semFoco(Controles.botao("EIXOS...", "Edita as rotações (colunas) e as cargas (linhas) das tabelas",
                this::editarEixos));
        JPanel opcoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        opcoes.setOpaque(false);
        opcoes.add(aoVivo);
        opcoes.add(cores);
        opcoes.add(eixos);
        topo.add(opcoes, BorderLayout.EAST);
        return topo;
    }

    private JPanel montarEsquerda() {
        JScrollPane rolagem = new JScrollPane(tabela);
        rolagem.setBorder(BorderFactory.createLineBorder(Tema.MOLDURA_ESCURA));
        rolagem.getViewport().setBackground(Tema.FUNDO);
        rolagem.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                ajustarAlturaDasLinhas(rolagem.getViewport().getHeight());
            }
        });
        quadroTabela.setOpaque(false);
        quadroTabela.add(rolagem, BorderLayout.CENTER);
        quadroTabela.add(montarFerramentas(), BorderLayout.SOUTH);
        return quadroTabela;
    }

    /** Faz as 16 linhas da tabela preencherem a altura disponível: as células crescem junto com a janela. */
    private void ajustarAlturaDasLinhas(int alturaDisponivel) {
        int altura = Math.max(ALTURA_MIN_LINHA, Math.min(ALTURA_MAX_LINHA, alturaDisponivel / MapaECU.TAMANHO));
        if (altura != tabela.getRowHeight()) {
            tabela.setRowHeight(altura);
        }
    }

    private JPanel montarFerramentas() {
        JButton menos =Controles.semFoco(Controles.botao("−", "Subtrai o valor das células selecionadas (tecla -)",
                () -> comSelecao(() -> tabela.somar(-valor(campoSoma)))));
        JButton mais = Controles.semFoco(Controles.botao("+", "Soma o valor às células selecionadas (tecla +)",
                () -> comSelecao(() -> tabela.somar(valor(campoSoma)))));
        JButton menosPorCento = Controles.semFoco(Controles.botao("− %", "Reduz as células selecionadas nessa porcentagem",
                () -> comSelecao(() -> tabela.multiplicar(1 - valor(campoFator) / 100.0))));
        JButton maisPorCento = Controles.semFoco(Controles.botao("+ %", "Aumenta as células selecionadas nessa porcentagem",
                () -> comSelecao(() -> tabela.multiplicar(1 + valor(campoFator) / 100.0))));
        JButton definir = Controles.semFoco(Controles.botao("DEFINIR", "Põe este valor em todas as células selecionadas",
                () -> comSelecao(() -> tabela.definir(valor(campoValor)))));
        JButton suavizar = Controles.semFoco(Controles.botao("SUAVIZAR", "Cada célula selecionada vira a média ponderada com as vizinhas",
                () -> comSelecao(tabela::suavizar)));
        JButton interpolar = Controles.semFoco(Controles.botao("INTERPOLAR", "Preenche o retângulo selecionado a partir dos quatro cantos",
                () -> comSelecao(tabela::interpolar)));

        JPanel linha1 = Controles.linha(Controles.rotulo("SOMAR"), menos, campoSoma, mais, Controles.espaco(10),
                Controles.rotulo("FATOR %"), menosPorCento, campoFator, maisPorCento, Controles.espaco(10),
                Controles.rotulo("VALOR"), campoValor, definir);
        JPanel linha2 = Controles.linha(suavizar, interpolar, botaoDesfazer);
        JPanel caixa = new JPanel(new GridLayout(2, 1, 0, 4));
        caixa.setOpaque(false);
        caixa.add(linha1);
        caixa.add(linha2);
        return caixa;
    }

    private JPanel montarDireita() {
        JPanel direita = new JPanel(new GridBagLayout());
        direita.setOpaque(false);
        direita.setPreferredSize(new Dimension(370, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 0;
        c.weightx = 1;

        JPanel quadro3d = new JPanel(new BorderLayout(0, 4));
        quadro3d.setOpaque(false);
        quadro3d.setBorder(Tema.moldura("SUPERFÍCIE 3D"));
        quadro3d.add(superficie, BorderLayout.CENTER);
        quadro3d.add(legenda, BorderLayout.SOUTH);
        c.gridy = 0;
        c.weighty = 0.64;
        c.insets = new Insets(0, 0, 8, 0);
        direita.add(quadro3d, c);

        JPanel quadroCurvas = new JPanel(new BorderLayout());
        quadroCurvas.setOpaque(false);
        quadroCurvas.setBorder(Tema.moldura("CURVAS POR CARGA"));
        quadroCurvas.add(curvas, BorderLayout.CENTER);
        c.gridy = 1;
        c.weighty = 0.36;
        c.insets = new Insets(0, 0, 0, 0);
        direita.add(quadroCurvas, c);
        return direita;
    }

    // ---------------------------------------------------------------- tabela atual

    private void trocarTipo(TipoMapa novo) {
        if (novo == tipo) {
            return;
        }
        tipo = novo;
        configurarTipo();
        atualizarDesfazer();
    }

    /** Aponta todas as visões para a tabela escolhida e ajusta os campos numéricos à unidade dela. */
    private void configurarTipo() {
        Calibracao cal = sessao.getCalibracao();
        MapaECU mapa = cal.getMapa(tipo);
        tabela.setMapa(mapa, sessao.getCalibracaoEcu().getMapa(tipo), cal.getEixos());
        superficie.setMapa(mapa);
        curvas.setMapa(mapa, cal.getEixos());
        legenda.setTipo(tipo);
        quadroTabela.setBorder(Tema.moldura("TABELA — " + tipo.getRotulo()));

        double minimo = tipo.rawParaFisico(0);
        double maximo = tipo.rawParaFisico(255);
        double passo = tipo.getPasso();
        modeloValor.setMinimum(minimo);
        modeloValor.setMaximum(maximo);
        modeloValor.setStepSize(passo);
        modeloValor.setValue(Math.max(minimo, Math.min(maximo, tipo == TipoMapa.COMBUSTIVEL ? 80.0
                : (tipo == TipoMapa.IGNICAO ? 20.0 : 1.0))));
        double somaPadrao = tipo == TipoMapa.COMBUSTIVEL ? 1.0 : (tipo == TipoMapa.IGNICAO ? 0.5 : 0.01);
        modeloSoma.setMinimum(passo);
        modeloSoma.setMaximum(tipo == TipoMapa.COMBUSTIVEL ? 100.0 : (tipo == TipoMapa.IGNICAO ? 30.0 : 0.5));
        modeloSoma.setStepSize(tipo == TipoMapa.ALVO_LAMBDA ? 0.005 : somaPadrao);
        modeloSoma.setValue(somaPadrao);
        revalidate();
        repaint();
    }

    private Deque<MapaECU> pilha(TipoMapa t) {
        return pilhas.computeIfAbsent(t, k -> new ArrayDeque<>());
    }

    private void atualizarDesfazer() {
        Deque<MapaECU> pilha = pilhas.get(tipo);
        botaoDesfazer.setEnabled(pilha != null && !pilha.isEmpty());
    }

    private static double valor(JSpinner campo) {
        return Controles.valor(campo);
    }

    /** Roda a operação só se houver células selecionadas; senão explica o que fazer. */
    private void comSelecao(Runnable operacao) {
        if (!tabela.temSelecao()) {
            mostrarDica("Selecione uma ou mais células da tabela primeiro.", Tema.AMBAR);
            return;
        }
        operacao.run();
        tabela.requestFocusInWindow();
    }

    private void mostrarDica(String texto, Color cor) {
        dica.setForeground(cor);
        dica.setText(texto);
    }

    // ---------------------------------------------------------------- alterações

    /** Recebe os avisos da tabela: guarda o estado para o "desfazer" e propaga as mudanças. */
    private final class OuvinteTabela implements TabelaMapa.Ouvinte {

        @Override
        public void antesDeAlterar() {
            Deque<MapaECU> pilha = pilha(tipo);
            pilha.addFirst(tabela.getMapa().copia());
            while (pilha.size() > LIMITE_DESFAZER) {
                pilha.removeLast();
            }
            atualizarDesfazer();
        }

        @Override
        public void aoAlterar(Set<Integer> linhasAlteradas) {
            depoisDeAlterar(linhasAlteradas);
        }

        @Override
        public void aoMudarSelecao(int linha, int coluna) {
            boolean valida = linha >= 0 && coluna >= 0 && linha < MapaECU.TAMANHO && coluna < MapaECU.TAMANHO;
            superficie.setSelecao(valida ? linha : -1, valida ? coluna : -1);
            curvas.setColunaSelecionada(valida ? coluna : -1);
            if (valida) {
                modeloValor.setValue(Math.max(tipo.rawParaFisico(0), Math.min(tipo.rawParaFisico(255),
                        tabela.getMapa().getFisico(linha, coluna))));
            }
            superficie.repaint();
            curvas.repaint();
        }
    }

    private void depoisDeAlterar(Set<Integer> linhas) {
        superficie.repaint();
        curvas.repaint();
        sessao.notificarEdicao();
        tabela.repaint();
        atualizarDesfazer();
        agendarEnvio(linhas);
    }

    private void desfazer() {
        MapaECU anterior = pilha(tipo).pollFirst();
        if (anterior == null) {
            mostrarDica("Não há nada para desfazer nesta tabela.", Tema.AMBAR);
            atualizarDesfazer();
            return;
        }
        MapaECU atual = tabela.getMapa();
        Set<Integer> linhas = new TreeSet<>();
        for (int l = 0; l < MapaECU.TAMANHO; l++) {
            if (atual.linhaDiferente(l, anterior)) {
                linhas.add(l);
            }
        }
        atual.copiarDe(anterior);
        tabela.refrescar();
        depoisDeAlterar(linhas);
        mostrarDica("Alteração desfeita.", Tema.TEXTO_FRACO);
        tabela.requestFocusInWindow();
    }

    private void editarEixos() {
        EixosMapa novos = DialogoEixos.mostrar(this, sessao.getCalibracao().getEixos());
        if (novos == null) {
            return;
        }
        sessao.getCalibracao().getEixos().copiarDe(novos);
        sessao.notificarEdicao();
        tabela.refrescar();
        curvas.repaint();
        superficie.repaint();
        if (aoVivo.isSelected() && sessao.getEstado() == EstadoConexao.CONECTADO) {
            Tarefa.aoTerminarOk(sessao.enviarEixos(), () -> {
                tabela.repaint();
                mostrarDica("Eixos enviados à ECU.", Tema.VERDE);
            }, this::falhaAoVivo);
        } else {
            mostrarDica("Eixos alterados. Eles só vão para a ECU em ENVIAR TUDO.", Tema.AMBAR);
        }
    }

    // ---------------------------------------------------------------- modo ao vivo

    private void agendarEnvio(Set<Integer> linhas) {
        if (linhas.isEmpty() || !aoVivo.isSelected() || sessao.getEstado() != EstadoConexao.CONECTADO) {
            return;
        }
        pendentes.computeIfAbsent(tipo, k -> new TreeSet<>()).addAll(linhas);
        temporizadorEnvio.restart();
    }

    private void enviarPendentes() {
        if (sessao.getEstado() != EstadoConexao.CONECTADO) {
            pendentes.clear();
            return;
        }
        for (TipoMapa t : TipoMapa.values()) {
            Set<Integer> linhas = pendentes.remove(t);
            if (linhas == null || linhas.isEmpty()) {
                continue;
            }
            final int quantas = linhas.size();
            Tarefa.aoTerminarOk(sessao.enviarLinhas(t, linhas), () -> {
                tabela.repaint();
                mostrarDica("Enviado à ECU (ao vivo): " + t.getRotulo() + ", " + quantas + " linha(s).", Tema.VERDE);
            }, this::falhaAoVivo);
        }
    }

    private void falhaAoVivo(Throwable erro) {
        aoVivo.setSelected(false);
        pendentes.clear();
        String texto = SessaoECU.descreverErro(erro);
        mostrarDica("AO VIVO desligado: " + texto, Tema.VERMELHO);
        sessao.registrar("Envio ao vivo falhou: " + texto);
    }

    // ---------------------------------------------------------------- avisos da sessão

    @Override
    public void aoMudarEstado(EstadoConexao estado) {
        boolean conectado = estado == EstadoConexao.CONECTADO;
        aoVivo.setEnabled(conectado);
        if (!conectado && aoVivo.isSelected()) {
            aoVivo.setSelected(false);
            pendentes.clear();
            temporizadorEnvio.stop();
        }
    }

    @Override
    public void aoSubstituirCalibracao() {
        pilhas.clear();
        pendentes.clear();
        temporizadorEnvio.stop();
        configurarTipo();
        atualizarDesfazer();
        mostrarDica(DICA_PADRAO, Tema.TEXTO_FRACO);
    }

    // ---------------------------------------------------------------- ponto de operação

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
        boolean vivo = sessao.getEstado() == EstadoConexao.CONECTADO && t.temFlag(Protocolo.FLAG_MOTOR_GIRANDO);
        tabela.setPontoOperacao(vivo, t.getPosRpm(), t.getPosCarga());
        superficie.setPonto(vivo, t.getPosRpm(), t.getPosCarga());
        curvas.setPonto(vivo, t.getPosRpm(), t.getPosCarga());
        if (vivo) {
            superficie.repaint();
            curvas.repaint();
        }
    }
}
