package kadettecu.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import kadettecu.app.SessaoECU;
import kadettecu.comunicacao.ErroProtocolo;
import kadettecu.modelo.EstadoConexao;
import kadettecu.protocolo.Protocolo;

/**
 * Os botões que movem a calibração inteira (parâmetros, eixos e as três tabelas) entre a tela, a ECU
 * e arquivos: LER DA ECU, ENVIAR TUDO, GRAVAR NA MEMÓRIA, PADRÕES DE FÁBRICA, ABRIR e SALVAR .KECU.
 *
 * <p>Regras de segurança que esta barra aplica: nunca descarta alterações da tela sem perguntar, e
 * avisa que GRAVAR NA MEMÓRIA grava o que a ECU tem agora (não o que está só na tela). Durante uma
 * operação os botões ficam bloqueados, para duas operações não se atropelarem na porta serial.
 */
public final class BarraCalibracao extends JPanel implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    private static final String EXTENSAO = "kecu";

    private final transient SessaoECU sessao;
    private final JButton botaoLer;
    private final JButton botaoEnviar;
    private final JButton botaoGravar;
    private final JButton botaoPadroes;
    private final JButton botaoAbrir;
    private final JButton botaoSalvar;
    private final Lampada lampIgual = new Lampada("IGUAL À ECU", Tema.VERDE);
    private final Lampada lampAlterada = new Lampada("ALTERADO", Tema.AMBAR);
    private final Timer pisca = new Timer(200, e -> lampAlterada.repaint());
    private boolean ocupado;
    private transient File ultimaPasta;

    public BarraCalibracao(SessaoECU sessao) {
        super(new GridBagLayout());
        this.sessao = sessao;
        setBackground(Tema.FUNDO_PAINEL);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.MOLDURA_ESCURA),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        botaoLer = Controles.botao("LER DA ECU", "Traz a calibração inteira da ECU para a tela", this::lerDaEcu);
        botaoEnviar = Controles.botao("ENVIAR TUDO", "Manda parâmetros, eixos e tabelas para a RAM da ECU (não grava na memória)", this::enviarTudo);
        botaoGravar = Controles.botao("GRAVAR NA MEMÓRIA", "Grava na memória permanente da placa o que a ECU tem na RAM (só com o motor parado)", this::gravar);
        botaoPadroes = Controles.botao("PADRÕES DE FÁBRICA", "Carrega na ECU os valores de fábrica (descarta o que está na tela)", this::padroes);
        botaoAbrir = Controles.botao("ABRIR .KECU", "Abre uma calibração salva em arquivo", this::abrirArquivo);
        botaoSalvar = Controles.botao("SALVAR .KECU", "Salva a calibração da tela em arquivo", this::salvarArquivo);
        lampIgual.setToolTipText("A calibração da tela é igual à que está na RAM da ECU");
        lampAlterada.setToolTipText("A calibração da tela tem alterações que ainda não foram enviadas à ECU");

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; // o padrão é RELATIVE (-1): sem isto o segundo componente cairia na mesma coluna do primeiro
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 8);
        adicionar(botaoLer, c, 0);
        adicionar(botaoEnviar, c, 0);
        adicionar(botaoGravar, c, 0);
        adicionar(botaoPadroes, c, 0);
        adicionar(separador(), c, 0);
        adicionar(botaoAbrir, c, 0);
        adicionar(botaoSalvar, c, 1);
        c.insets = new Insets(0, 0, 0, 4);
        adicionar(lampIgual, c, 0);
        adicionar(lampAlterada, c, 0);

        atualizar();
        sessao.adicionarOuvinte(new OuvinteNaTela(this));
    }

    private void adicionar(Component componente, GridBagConstraints c, double pesoX) {
        c.weightx = pesoX;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        add(componente, c);
        c.gridx++;
    }

    private static JSeparator separador() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(2, 26));
        return s;
    }

    // ---------------------------------------------------------------- operações com a ECU

    private boolean conectado() {
        return sessao.getEstado() == EstadoConexao.CONECTADO;
    }

    private void lerDaEcu() {
        if (sessao.isAlterada() && !Controles.confirmar(this, "Ler da ECU",
                "A calibração na tela tem alterações que ainda não foram enviadas à ECU. Ler da ECU descarta essas alterações.",
                "LER E DESCARTAR", "CANCELAR")) {
            return;
        }
        executar("Ler da ECU", sessao.lerDaEcu(), "Calibração lida da ECU.");
    }

    private void enviarTudo() {
        executar("Enviar à ECU", sessao.enviarTudo(), "Calibração enviada à ECU (RAM). Use GRAVAR NA MEMÓRIA para mantê-la ao desligar.");
    }

    private void gravar() {
        if (sessao.isAlterada()) {
            int escolha = Controles.escolher(this, "Gravar na memória",
                    "A calibração na tela tem alterações que NÃO foram enviadas. GRAVAR NA MEMÓRIA grava somente o que a ECU "
                    + "tem agora, não o que está só na tela.", 2, "ENVIAR E GRAVAR", "SÓ GRAVAR", "CANCELAR");
            if (escolha == 0) {
                executar("Enviar e gravar", sessao.enviarTudo().thenCompose(v -> sessao.gravarNaMemoria()),
                        "Calibração enviada e gravada na memória da placa.");
            } else if (escolha == 1) {
                executar("Gravar na memória", sessao.gravarNaMemoria(), "Memória da placa atualizada (o que a ECU tinha na RAM).");
            }
            return;
        }
        executar("Gravar na memória", sessao.gravarNaMemoria(), "Calibração gravada na memória da placa.");
    }

    private void padroes() {
        if (!Controles.confirmar(this, "Padrões de fábrica",
                "Isto carrega na ECU os mapas e parâmetros de fábrica e descarta o que está na tela. Não grava na memória "
                + "permanente (use GRAVAR NA MEMÓRIA depois, se quiser).", "CARREGAR PADRÕES", "CANCELAR")) {
            return;
        }
        executar("Padrões de fábrica", sessao.restaurarPadroes(), "Valores de fábrica carregados na ECU e na tela.");
    }

    // ---------------------------------------------------------------- arquivos

    private JFileChooser seletor() {
        JFileChooser seletor = new JFileChooser(ultimaPasta);
        seletor.setFileFilter(new FileNameExtensionFilter("Calibração KadettECU (*.kecu)", EXTENSAO));
        return seletor;
    }

    private void abrirArquivo() {
        if (sessao.isAlterada() && !Controles.confirmar(this, "Abrir calibração",
                "A calibração na tela tem alterações que ainda não foram enviadas nem salvas. Abrir um arquivo as substitui.",
                "ABRIR E DESCARTAR", "CANCELAR")) {
            return;
        }
        JFileChooser seletor = seletor();
        if (seletor.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File arquivo = seletor.getSelectedFile();
        ultimaPasta = arquivo.getParentFile();
        executar("Abrir calibração", sessao.carregarArquivo(arquivo.toPath()),
                "Calibração aberta de " + arquivo.getName() + ". Use ENVIAR TUDO para mandá-la à ECU.");
    }

    private void salvarArquivo() {
        JFileChooser seletor = seletor();
        seletor.setSelectedFile(new File(ultimaPasta, "calibracao." + EXTENSAO));
        if (seletor.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File escolhido = seletor.getSelectedFile();
        if (!escolhido.getName().toLowerCase().endsWith("." + EXTENSAO)) {
            escolhido = new File(escolhido.getParentFile(), escolhido.getName() + "." + EXTENSAO);
        }
        Path destino = escolhido.toPath();
        if (Files.exists(destino) && !Controles.confirmar(this, "Salvar calibração",
                "O arquivo " + escolhido.getName() + " já existe. Substituir?", "SUBSTITUIR", "CANCELAR")) {
            return;
        }
        ultimaPasta = escolhido.getParentFile();
        final String nome = escolhido.getName();
        executar("Salvar calibração", sessao.salvarArquivo(destino), "Calibração salva em " + nome + ".");
    }

    // ---------------------------------------------------------------- execução e estado

    /** Roda uma operação assíncrona: bloqueia os botões, registra o resultado e mostra o erro, se houver. */
    private void executar(String nome, CompletableFuture<?> operacao, String mensagemDeSucesso) {
        definirOcupado(true);
        Tarefa.aoTerminarOk(operacao, () -> {
            definirOcupado(false);
            sessao.registrar(mensagemDeSucesso);
        }, erro -> {
            definirOcupado(false);
            String texto = descrever(erro);
            sessao.registrar(nome + " falhou: " + texto);
            Controles.mostrarAviso(this, nome, texto, JOptionPane.ERROR_MESSAGE);
        });
    }

    private static String descrever(Throwable erro) {
        String texto = SessaoECU.descreverErro(erro);
        Throwable causa = Tarefa.causaReal(erro);
        if (causa instanceof ErroProtocolo && ((ErroProtocolo) causa).getCodigo() == Protocolo.ERRO_MOTOR_GIRANDO) {
            texto += "\n\nPare o motor (rotação zero) e tente de novo.";
        }
        return texto;
    }

    private void definirOcupado(boolean valor) {
        ocupado = valor;
        atualizar();
    }

    @Override
    public void aoMudarEstado(EstadoConexao estado) {
        atualizar();
    }

    @Override
    public void aoMudarSituacaoCalibracao(boolean alterada) {
        atualizar();
    }

    @Override
    public void aoSubstituirCalibracao() {
        atualizar();
    }

    private void atualizar() {
        boolean conectado = conectado();
        botaoLer.setEnabled(conectado && !ocupado);
        botaoEnviar.setEnabled(conectado && !ocupado);
        botaoGravar.setEnabled(conectado && !ocupado);
        botaoPadroes.setEnabled(conectado && !ocupado);
        botaoAbrir.setEnabled(!ocupado);
        botaoSalvar.setEnabled(!ocupado);
        boolean alterada = sessao.isAlterada();
        lampIgual.setAcesa(conectado && !alterada);
        lampAlterada.setModo(!alterada ? Lampada.Modo.APAGADA : (conectado ? Lampada.Modo.PISCANDO : Lampada.Modo.ACESA));
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
}
