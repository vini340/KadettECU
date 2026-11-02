package kadettecu.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import kadettecu.app.OpcaoPorta;
import kadettecu.app.SessaoECU;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.InfoECU;

/**
 * A barra de conexão: a luz com o estado, a lista de portas (o simulador, o firmware em C do PC e as
 * portas USB/serial que existirem), a velocidade e os botões ATUALIZAR e CONECTAR/DESCONECTAR.
 *
 * <p>A lista de portas é montada fora da thread da tela, porque a biblioteca de serial pode demorar
 * a responder. O resultado de conectar e desconectar chega pelos futuros da {@link SessaoECU}; esta
 * barra não bloqueia a tela esperando por eles.
 */
public final class BarraConexao extends JPanel implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    private static final Integer[] VELOCIDADES = {9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600};

    private final transient SessaoECU sessao;
    private final transient PontoEstado ponto = new PontoEstado();
    private final JLabel rotuloEstado = new JLabel(EstadoConexao.DESCONECTADO.getRotulo());
    private final JComboBox<OpcaoPorta> portas = new JComboBox<>();
    private final JComboBox<Integer> velocidades = new JComboBox<>(VELOCIDADES);
    private final JButton botaoAtualizar;
    private final JButton botaoConectar;
    private final Timer pisca;
    private transient Consumer<InfoECU> aoConectar = info -> { };

    public BarraConexao(SessaoECU sessao) {
        super(new GridBagLayout());
        this.sessao = sessao;
        // repinta o rótulo enquanto o estado é CONECTANDO, para o pontinho piscar
        this.pisca = new Timer(150, e -> {
            if (sessao.getEstado() == EstadoConexao.CONECTANDO) {
                rotuloEstado.repaint();
            }
        });
        setBackground(Tema.FUNDO_PAINEL);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.MOLDURA_ESCURA),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        rotuloEstado.setIcon(ponto);
        rotuloEstado.setIconTextGap(8);
        rotuloEstado.setFont(Tema.fonteTitulo(12f));
        rotuloEstado.setPreferredSize(new Dimension(150, 26));
        rotuloEstado.setMinimumSize(new Dimension(150, 26));

        portas.setPreferredSize(new Dimension(380, 28));
        portas.setMinimumSize(new Dimension(160, 28));
        portas.setToolTipText("Simulador em Java, firmware em C no PC ou uma porta USB/serial com a placa");
        portas.addActionListener(e -> atualizarEstado(sessao.getEstado()));
        velocidades.setSelectedItem(115200);
        velocidades.setPreferredSize(new Dimension(116, 28));
        velocidades.setMinimumSize(new Dimension(116, 28));
        velocidades.setToolTipText("Velocidade da porta serial (só vale para uma placa de verdade). O firmware usa 115200.");
        velocidades.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> lista, Object valor, int indice, boolean selecionado,
                                                          boolean foco) {
                return super.getListCellRendererComponent(lista, valor + " baud", indice, selecionado, foco);
            }
        });

        botaoAtualizar = Controles.botao("ATUALIZAR", "Procura as portas USB/serial de novo", this::carregarPortas);
        botaoConectar = Controles.botao("CONECTAR", "Abre a porta escolhida e fala com a ECU", this::aoClicarConectar);
        botaoConectar.setPreferredSize(new Dimension(150, 28));

        montar();
        carregarPortas();
        atualizarEstado(sessao.getEstado());
        sessao.adicionarOuvinte(new OuvinteNaTela(this));
    }

    /** O que fazer depois que a conexão foi aberta e a ECU respondeu (a janela principal lê a calibração aqui). */
    public void setAoConectar(Consumer<InfoECU> acao) {
        this.aoConectar = acao;
    }

    // ---------------------------------------------------------------- montagem

    private void montar() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; // o padrão é RELATIVE (-1): sem isto o segundo componente cairia na mesma coluna do primeiro
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 8);
        c.anchor = GridBagConstraints.WEST;
        adicionar(rotuloEstado, c, 0);
        adicionar(Controles.rotulo("PORTA"), c, 0);
        adicionar(portas, c, 1);
        adicionar(velocidades, c, 0);
        adicionar(botaoAtualizar, c, 0);
        c.insets = new Insets(0, 0, 0, 0);
        adicionar(botaoConectar, c, 0);
    }

    private void adicionar(Component componente, GridBagConstraints c, double pesoX) {
        c.weightx = pesoX;
        c.fill = pesoX > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        add(componente, c);
        c.gridx++;
    }

    // ---------------------------------------------------------------- portas

    /** Procura as portas fora da thread da tela (a biblioteca de serial pode demorar a responder). */
    public void carregarPortas() {
        botaoAtualizar.setEnabled(false);
        portas.setEnabled(false);
        CompletableFuture<List<OpcaoPorta>> busca = CompletableFuture.supplyAsync(OpcaoPorta::listar);
        Tarefa.aoTerminar(busca, this::preencherPortas, erro -> {
            sessao.registrar("Não foi possível listar as portas: " + SessaoECU.descreverErro(erro));
            preencherPortas(List.of(OpcaoPorta.simulador()));
        });
    }

    private void preencherPortas(List<OpcaoPorta> lista) {
        OpcaoPorta anterior = (OpcaoPorta) portas.getSelectedItem();
        DefaultComboBoxModel<OpcaoPorta> modelo = new DefaultComboBoxModel<>();
        OpcaoPorta escolhida = null;
        for (OpcaoPorta opcao : lista) {
            modelo.addElement(opcao);
            if (anterior != null && opcao.getRotulo().equals(anterior.getRotulo())) {
                escolhida = opcao;
            }
        }
        portas.setModel(modelo);
        if (escolhida != null) {
            portas.setSelectedItem(escolhida);
        }
        atualizarEstado(sessao.getEstado());
    }

    // ---------------------------------------------------------------- conectar e desconectar

    private void aoClicarConectar() {
        EstadoConexao estado = sessao.getEstado();
        if (estado == EstadoConexao.CONECTADO || estado == EstadoConexao.SEM_SINAL) {
            Tarefa.aoTerminarOk(sessao.desconectar(), () -> { }, erro -> Controles.mostrarErro(this, "Desconectar", erro));
            return;
        }
        OpcaoPorta opcao = (OpcaoPorta) portas.getSelectedItem();
        if (opcao == null) {
            Controles.mostrarAviso(this, "Conectar", "Escolha uma porta na lista (ou clique em ATUALIZAR).", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        conectarCom(opcao, (Integer) velocidades.getSelectedItem());
    }

    /** Abre a ligação escolhida; usado pelo botão CONECTAR e pelo atalho do simulador. */
    private void conectarCom(OpcaoPorta opcao, int baud) {
        botaoConectar.setEnabled(false);
        Tarefa.aoTerminar(sessao.conectar(opcao, baud), this::aoConectou, erro -> {
            atualizarEstado(sessao.getEstado());
            Controles.mostrarErro(this, "Não foi possível conectar", erro);
        });
    }

    /** Conecta ao simulador em Java (a opção "SIMULADOR" da lista), sem precisar de nenhuma placa. */
    public void conectarSimulador() {
        if (portas.getItemCount() > 0) {
            portas.setSelectedIndex(0); // o simulador é sempre o primeiro item da lista
        }
        conectarCom(OpcaoPorta.simulador(), (Integer) velocidades.getSelectedItem());
    }

    private void aoConectou(InfoECU info) {
        atualizarEstado(sessao.getEstado());
        aoConectar.accept(info);
    }

    // ---------------------------------------------------------------- estado

    @Override
    public void aoMudarEstado(EstadoConexao estado) {
        atualizarEstado(estado);
    }

    private void atualizarEstado(EstadoConexao estado) {
        Color cor = corDoEstado(estado);
        ponto.definir(cor, estado == EstadoConexao.CONECTANDO);
        rotuloEstado.setText(estado.getRotulo());
        rotuloEstado.setForeground(cor);
        boolean ligado = estado == EstadoConexao.CONECTADO || estado == EstadoConexao.SEM_SINAL;
        boolean ocupado = estado == EstadoConexao.CONECTANDO;
        OpcaoPorta opcao = (OpcaoPorta) portas.getSelectedItem();
        botaoConectar.setText(ligado ? "DESCONECTAR" : "CONECTAR");
        botaoConectar.setEnabled(!ocupado);
        portas.setEnabled(!ligado && !ocupado && portas.getItemCount() > 0);
        botaoAtualizar.setEnabled(!ligado && !ocupado);
        velocidades.setEnabled(!ligado && !ocupado && opcao != null && opcao.usaBaud());
    }

    private static Color corDoEstado(EstadoConexao estado) {
        switch (estado) {
            case CONECTADO:
                return Tema.VERDE;
            case CONECTANDO:
                return Tema.CIANO;
            case SEM_SINAL:
                return Tema.AMBAR;
            case ERRO:
                return Tema.VERMELHO;
            default:
                return Tema.TEXTO_FRACO;
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        pisca.start();
    }

    @Override
    public void removeNotify() {
        pisca.stop();
        super.removeNotify();
    }

    // ---------------------------------------------------------------- o pontinho colorido do estado

    /** Bolinha luminosa que pisca enquanto a conexão está sendo aberta. */
    private static final class PontoEstado implements Icon {

        private Color cor = Tema.TEXTO_FRACO;
        private boolean piscando;

        void definir(Color novaCor, boolean novoPiscar) {
            this.cor = novaCor;
            this.piscando = novoPiscar;
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            boolean acesa = !piscando || ((System.currentTimeMillis() / 300) & 1) == 0;
            double d = 12;
            double px = x + 1;
            double py = y + 1;
            if (acesa) {
                g.setPaint(new RadialGradientPaint(new Point2D.Double(px + d / 2, py + d / 2), (float) (d * 1.2),
                        new float[] {0f, 1f}, new Color[] {Tema.comAlfa(cor, 110), Tema.comAlfa(cor, 0)}));
                g.fill(new Ellipse2D.Double(px - d / 2, py - d / 2, d * 2, d * 2));
                g.setPaint(new RadialGradientPaint(new Point2D.Double(px + d * 0.4, py + d * 0.35), (float) d,
                        new float[] {0f, 1f}, new Color[] {Tema.clarear(cor, 0.6), cor}));
            } else {
                g.setColor(Tema.misturar(Tema.FUNDO, cor, 0.18));
            }
            g.fill(new Ellipse2D.Double(px, py, d, d));
            g.setColor(acesa ? Tema.clarear(cor, 0.3) : Tema.MOLDURA_ESCURA);
            g.draw(new Ellipse2D.Double(px, py, d, d));
            g.dispose();
        }
    }
}
