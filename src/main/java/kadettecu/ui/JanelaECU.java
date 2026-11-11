package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GradientPaint;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import kadettecu.app.SessaoECU;
import kadettecu.comunicacao.ConexaoECU;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.InfoECU;

/**
 * A janela principal do KadettECU: cabeçalho com a identificação da ECU conectada, a barra de conexão,
 * a barra de calibração, as abas (PAINEL, MAPAS, PARÂMETROS, BINÁRIO / HEX e REGISTRO) e a barra de
 * status com o andamento das operações longas.
 *
 * <p>A janela só monta as peças e liga uma na outra; quem faz o trabalho é cada painel e a
 * {@link SessaoECU}. Cada painel assina os avisos da sessão por conta própria, e esta classe
 * assina só o que é dela: a mensagem e a barra de progresso da barra de status.
 */
public final class JanelaECU extends JFrame implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    public static final String VERSAO = "1.0";

    private final transient SessaoECU sessao;
    private final transient Runnable aoSair;
    private final BarraConexao barraConexao;
    private final ComandosECU comandos;
    private final JTabbedPane abas = new JTabbedPane();
    private final JLabel mensagem = new JLabel("Pronto.");
    private final JProgressBar progresso = new JProgressBar(0, 100);
    private final CaixaInfo infoFirmware = new CaixaInfo("FW", 4);
    private final CaixaInfo infoProtocolo = new CaixaInfo("PROTO", 2);
    private final CaixaInfo infoPlaca = new CaixaInfo("PLACA", 14);
    private final CaixaInfo infoTaxa = new CaixaInfo("TAXA", 6);
    private final CaixaInfo infoPacotes = new CaixaInfo("PACOTES", 8);
    private final CaixaInfo infoCrc = new CaixaInfo("CRC", 4);
    private final Timer relogio = new Timer(500, e -> atualizarIdentificacao());

    /**
     * @param sessao a sessão (o "cérebro" que a janela usa)
     * @param aoSair o que fazer depois de fechar a sessão ao sair; o programa passa {@code System.exit(0)}
     */
    public JanelaECU(SessaoECU sessao, Runnable aoSair) {
        super("KadettECU — injeção eletrônica Kadett GL 2.0");
        this.sessao = sessao;
        this.aoSair = aoSair;
        getContentPane().setBackground(Tema.FUNDO_PAINEL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                tentarFechar();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // o foco inicial vai para as abas, para nenhum botão da barra parecer "pressionado" ao abrir
                abas.requestFocusInWindow();
            }
        });

        barraConexao = new BarraConexao(sessao);
        barraConexao.setAoConectar(this::aoConectar);
        comandos = new ComandosECU(sessao);
        BarraCalibracao barraCalibracao = new BarraCalibracao(sessao);

        JPanel faixaSuperior = new JPanel(new BorderLayout());
        faixaSuperior.setOpaque(false);
        faixaSuperior.add(new Cabecalho(comandos), BorderLayout.NORTH);
        JPanel barras = new JPanel(new BorderLayout());
        barras.setOpaque(false);
        barras.add(barraConexao, BorderLayout.NORTH);
        barras.add(barraCalibracao, BorderLayout.SOUTH);
        faixaSuperior.add(barras, BorderLayout.SOUTH);

        abas.addTab("PAINEL", new PainelInstrumentos(sessao));
        abas.addTab("MAPAS", new PainelMapas(sessao));
        abas.addTab("PARÂMETROS", new PainelParametros(sessao));
        abas.addTab("BINÁRIO / HEX", new PainelBinario(sessao));
        abas.addTab("REGISTRO", new PainelRegistro(sessao));
        JPanel centro = new JPanel(new BorderLayout());
        centro.setOpaque(false);
        centro.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        centro.add(abas, BorderLayout.CENTER);

        getContentPane().add(faixaSuperior, BorderLayout.NORTH);
        getContentPane().add(centro, BorderLayout.CENTER);
        getContentPane().add(montarStatus(), BorderLayout.SOUTH);

        Rectangle tela = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(1160, 700));
        setSize(Math.min(1340, tela.width - 20), Math.min(840, tela.height - 20));
        setLocationRelativeTo(null);

        sessao.adicionarOuvinte(new OuvinteNaTela(this));
        atualizarIdentificacao();
    }

    // ---------------------------------------------------------------- montagem

    private JPanel montarStatus() {
        JPanel barra = new JPanel(new BorderLayout(10, 0));
        barra.setBackground(Tema.FUNDO);
        barra.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.MOLDURA_ESCURA),
                BorderFactory.createEmptyBorder(3, 12, 3, 12)));
        mensagem.setFont(Tema.fonteUI(java.awt.Font.PLAIN, 12f));
        mensagem.setForeground(Tema.TEXTO_FRACO);
        progresso.setStringPainted(true);
        progresso.setPreferredSize(new Dimension(260, 16));
        progresso.setVisible(false);
        JLabel versao = new JLabel("KadettECU " + VERSAO);
        versao.setFont(Tema.fonteTitulo(9.5f));
        versao.setForeground(Tema.DESABILITADO);
        JPanel direita = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        direita.setOpaque(false);
        direita.add(progresso);
        direita.add(infoFirmware);
        direita.add(infoProtocolo);
        direita.add(infoPlaca);
        direita.add(infoTaxa);
        direita.add(infoPacotes);
        direita.add(infoCrc);
        direita.add(Controles.espaco(6));
        direita.add(versao);
        barra.add(mensagem, BorderLayout.CENTER);
        barra.add(direita, BorderLayout.EAST);
        return barra;
    }

    /** O cabeçalho: faixa azul-clara com o nome do programa e, à direita, os comandos de operação da ECU. */
    private static final class Cabecalho extends JPanel {

        private static final long serialVersionUID = 1L;

        Cabecalho(ComandosECU comandos) {
            super(new BorderLayout());
            setBackground(Tema.FUNDO);
            setPreferredSize(new Dimension(10, 50));
            add(comandos, BorderLayout.CENTER);
            add(new Titulo(), BorderLayout.WEST);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(Tema.MOLDURA_ESCURA);
            g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g.dispose();
        }
    }

    /** O título em faixa azul-clara com a ponta cortada em diagonal e as listras verdes (estilo da tela de referência). */
    private static final class Titulo extends JComponent {

        private static final long serialVersionUID = 1L;

        Titulo() {
            setPreferredSize(new Dimension(500, 50));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth();
            int h = getHeight();
            Path2D faixa = new Path2D.Double();
            faixa.moveTo(0, 6);
            faixa.lineTo(w - 44, 6);
            faixa.lineTo(w - 14, h - 5);
            faixa.lineTo(0, h - 5);
            faixa.closePath();
            g.setPaint(new GradientPaint(0, 6, Tema.CELULA_CLARA, 0, h - 5, Tema.CELULA_ESCURA));
            g.fill(faixa);
            g.setColor(Tema.MOLDURA_CLARA);
            g.draw(faixa);

            g.setColor(Tema.comAlfa(Tema.VERDE, 235));
            for (int i = 0; i < 2; i++) {
                Path2D listra = new Path2D.Double();
                double x = w - 6 - i * 9;
                listra.moveTo(x + 2, 6);
                listra.lineTo(x + 6, 6);
                listra.lineTo(x - 22 + 6 + 8, h - 5);
                listra.lineTo(x - 22 + 8, h - 5);
                listra.closePath();
                g.fill(listra);
            }

            g.setColor(new Color(0x08203A));
            g.setFont(Tema.fonteTitulo(21f));
            g.drawString("KADETT ECU PROGRAM " + VERSAO, 14, 29);
            g.setFont(Tema.fonteTitulo(9.5f));
            g.setColor(new Color(0x1F4A73));
            g.drawString("GL 2.0 · 1996 · INJEÇÃO ELETRÔNICA PRÓPRIA", 15, 43);
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- identificação da ECU (cabeçalho)

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

    private void atualizarIdentificacao() {
        ConexaoECU conexao = sessao.getConexao();
        InfoECU info = conexao.getInfo();
        boolean ligado = info != null && (sessao.getEstado() == EstadoConexao.CONECTADO
                || sessao.getEstado() == EstadoConexao.SEM_SINAL);
        infoFirmware.setValor(ligado ? info.getFirmware() : "--");
        infoProtocolo.setValor(ligado ? String.valueOf(info.getVersaoProtocolo()) : "--");
        infoPlaca.setValor(ligado ? info.getNomePlaca() : "--");
        infoTaxa.setValor(ligado ? String.format(Locale.ROOT, "%.0fHz", conexao.getTaxaTelemetria()) : "--");
        infoPacotes.setValor(ligado ? String.valueOf(conexao.getPacotesValidos()) : "--");
        long errosCrc = conexao.getErrosCrc();
        infoCrc.setValor(ligado ? String.valueOf(errosCrc) : "--");
        infoCrc.setCorValor(ligado && errosCrc > 0 ? Tema.VERMELHO : Tema.TEXTO);
    }

    // ---------------------------------------------------------------- ações da janela

    /** Escolhe uma aba pelo número (0 = PAINEL). */
    public void selecionarAba(int indice) {
        abas.setSelectedIndex(indice);
    }

    /** Conecta direto ao simulador em Java, como se o usuário o escolhesse na lista e clicasse em CONECTAR. */
    public void conectarSimulador() {
        barraConexao.conectarSimulador();
    }

    /** Depois de conectar, traz a calibração da ECU para a tela (perguntando antes, se houver alterações na tela). */
    private void aoConectar(InfoECU info) {
        comandos.aoConectar(); // o firmware em C do PC só "anda" com a bancada, então ela já abre
        if (sessao.isAlterada()) {
            int escolha = Controles.escolher(this, "Conectado",
                    "A calibração na tela tem alterações que ainda não foram enviadas à ECU. Ler a calibração da ECU agora "
                    + "descartaria essas alterações.", 1, "LER DA ECU E DESCARTAR", "MANTER MINHAS ALTERAÇÕES");
            if (escolha != 0) {
                // A tela fica como está, mas o aviso "alterado" precisa comparar com o que a ECU tem de verdade agora.
                Tarefa.aoTerminarOk(sessao.lerReferenciaDaEcu(),
                        () -> sessao.registrar("Conectado. As alterações da tela foram mantidas: use ENVIAR TUDO para mandá-las à ECU."),
                        erro -> sessao.registrar("Não foi possível ler a calibração da ECU para comparar: "
                                + SessaoECU.descreverErro(erro)));
                return;
            }
        }
        Tarefa.aoTerminarOk(sessao.lerDaEcu(), () -> sessao.registrar("Calibração lida da ECU."), erro -> {
            sessao.registrar("Não foi possível ler a calibração da ECU: " + SessaoECU.descreverErro(erro));
            Controles.mostrarErro(this, "Ler da ECU", erro);
        });
    }

    private void tentarFechar() {
        if (sessao.isAlterada()) {
            int escolha = Controles.escolher(this, "Sair",
                    "A calibração na tela tem alterações que não foram enviadas à ECU nem salvas em arquivo. Sair assim mesmo?",
                    1, "SAIR", "CONTINUAR");
            if (escolha != 0) {
                return;
            }
        }
        setVisible(false);
        dispose();
        // Fechar a porta pode demorar um pouco; fazemos fora da tela e só então encerramos o programa.
        Thread fechamento = new Thread(() -> {
            sessao.fechar();
            aoSair.run();
        }, "ecu-fechar");
        fechamento.start();
    }

    // ---------------------------------------------------------------- avisos da sessão

    @Override
    public void aoRegistrar(String texto) {
        mensagem.setText(texto);
    }

    @Override
    public void aoProgredir(String operacao, int feitos, int total) {
        if (total <= 0 || feitos >= total) {
            progresso.setVisible(false);
            return;
        }
        progresso.setVisible(true);
        progresso.setMaximum(total);
        progresso.setValue(feitos);
        progresso.setString(operacao + " " + feitos + "/" + total);
    }

    @Override
    public void aoMudarEstado(EstadoConexao estado) {
        if (estado != EstadoConexao.CONECTADO) {
            progresso.setVisible(false);
        }
        atualizarIdentificacao();
    }
}
