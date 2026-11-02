package kadettecu.ui;

import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import kadettecu.app.Bancada;
import kadettecu.app.SessaoECU;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.InfoECU;
import kadettecu.modelo.Telemetria;
import kadettecu.protocolo.Protocolo;

/**
 * Os comandos de operação da ECU, sempre à mão no topo da janela: as chaves virtuais de TWO-STEP e
 * LAUNCH, o botão de armar as saídas (bicos e bobina de verdade) e o atalho da bancada de testes.
 *
 * <p>As chaves e o botão de armar mostram o estado REAL da ECU: um {@link Timer} da tela lê as flags
 * da telemetria a cada 250 ms e ajusta os botões (isso também acerta o botão quando a placa já estava
 * com a chave ligada antes de conectarmos). Logo depois de um clique, a leitura da telemetria é
 * ignorada por 700 ms, para o botão não "piscar de volta" antes de a ECU confirmar.
 */
public final class ComandosECU extends JPanel implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    private static final int PERIODO_SINCRONIA_MS = 250;
    private static final long TRAVA_APOS_CLIQUE_MS = 700;
    private static final String TEXTO_ARMAR = "ARMAR SAÍDAS";
    private static final String TEXTO_ARMADO = "SAÍDAS ARMADAS";

    private final transient SessaoECU sessao;
    private final JToggleButton chaveTwoStep;
    private final JToggleButton chaveLaunch;
    private final JToggleButton botaoArmar;
    private final JButton botaoBancada;
    private final Timer sincronia = new Timer(PERIODO_SINCRONIA_MS, e -> sincronizar());
    private long cliqueChaves;
    private long cliqueArmar;
    private JanelaBancada janelaBancada;

    public ComandosECU(SessaoECU sessao) {
        super(new FlowLayout(FlowLayout.RIGHT, 8, 11));
        this.sessao = sessao;
        setOpaque(false);

        chaveTwoStep = Controles.alternar("TWO-STEP", "Chave virtual do two-step (a mesma entrada do botão físico)", false,
                Tema.AMBAR, ligado -> aoClicarChaves());
        chaveLaunch = Controles.alternar("LAUNCH", "Chave virtual do launch control (a mesma entrada do botão físico)", false,
                Tema.CIANO, ligado -> aoClicarChaves());
        botaoArmar = Controles.alternar(TEXTO_ARMAR, "Liga os bicos e a bobina de verdade (pede confirmação)", false,
                Tema.VERMELHO, ligado -> aoClicarArmar());
        botaoBancada = Controles.botao("BANCADA...", "Abre a bancada de testes: define à mão rotação, acelerador e sensores",
                this::abrirBancada);
        add(chaveTwoStep);
        add(chaveLaunch);
        add(botaoArmar);
        add(botaoBancada);

        atualizarEstado(sessao.getEstado());
        sessao.adicionarOuvinte(new OuvinteNaTela(this));
    }

    /** Chamado quando a conexão foi aberta: o firmware do PC só "anda" com a bancada, então ela já abre. */
    public void aoConectar() {
        Bancada bancada = sessao.getBancada();
        if (bancada != null && !bancada.temCicloAutomatico()) {
            abrirBancada();
        }
    }

    // ---------------------------------------------------------------- ações

    private void aoClicarChaves() {
        cliqueChaves = System.currentTimeMillis();
        boolean twoStep = chaveTwoStep.isSelected();
        boolean launch = chaveLaunch.isSelected();
        Tarefa.aoTerminarOk(sessao.definirChaves(twoStep, launch), () -> { }, erro -> {
            cliqueChaves = 0; // a próxima leitura da telemetria devolve o botão ao estado verdadeiro
            Controles.mostrarErro(this, "Chaves", erro);
        });
    }

    private void aoClicarArmar() {
        boolean armar = botaoArmar.isSelected();
        cliqueArmar = System.currentTimeMillis();
        if (armar) {
            InfoECU info = sessao.getConexao().getInfo();
            if (info != null && !info.temSaidasReais()) {
                botaoArmar.setSelected(false);
                Controles.mostrarAviso(this, "Armar saídas",
                        "Este firmware está em MODO SOMBRA: as saídas (bicos e bobina) foram desabilitadas na compilação, "
                        + "então a ECU calcula tudo mas não aciona nada.\n\nPara ligá-las é preciso recompilar o firmware com "
                        + "KECU_ENABLE_OUTPUTS=1 depois de montar e revisar o hardware (veja docs/HARDWARE.md).",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            boolean confirmado = Controles.confirmar(this, "Armar saídas",
                    "ATENÇÃO: armar as saídas faz a ECU acionar de verdade os bicos injetores e a bobina de ignição.\n\n"
                    + "Continue somente se o hardware estiver montado e revisado (veja docs/HARDWARE.md), sem vazamento de "
                    + "combustível, com ventilação e um extintor por perto.\n\nArmar as saídas agora?", "ARMAR", "CANCELAR");
            if (!confirmado) {
                botaoArmar.setSelected(false);
                return;
            }
        }
        Tarefa.aoTerminarOk(sessao.armarSaidas(armar), () -> {
            botaoArmar.setText(armar ? TEXTO_ARMADO : TEXTO_ARMAR);
            sessao.registrar(armar ? "Saídas ARMADAS pelo usuário." : "Saídas desarmadas pelo usuário.");
        }, erro -> {
            cliqueArmar = 0;
            Controles.mostrarErro(this, "Armar saídas", erro);
        });
    }

    private void abrirBancada() {
        if (sessao.getBancada() == null) {
            return;
        }
        if (janelaBancada == null || !janelaBancada.isDisplayable()) {
            janelaBancada = new JanelaBancada(SwingUtilities.getWindowAncestor(this), sessao);
        }
        janelaBancada.setVisible(true);
        janelaBancada.toFront();
    }

    // ---------------------------------------------------------------- estado

    @Override
    public void aoMudarEstado(EstadoConexao estado) {
        atualizarEstado(estado);
    }

    private void atualizarEstado(EstadoConexao estado) {
        boolean conectado = estado == EstadoConexao.CONECTADO;
        chaveTwoStep.setEnabled(conectado);
        chaveLaunch.setEnabled(conectado);
        botaoArmar.setEnabled(conectado);
        botaoBancada.setEnabled(conectado && sessao.getBancada() != null);
        if (estado != EstadoConexao.CONECTADO && estado != EstadoConexao.SEM_SINAL) {
            chaveTwoStep.setSelected(false);
            chaveLaunch.setSelected(false);
            botaoArmar.setSelected(false);
            botaoArmar.setText(TEXTO_ARMAR);
        }
    }

    // ---------------------------------------------------------------- sincronia com a telemetria

    @Override
    public void addNotify() {
        super.addNotify();
        sincronia.start();
    }

    @Override
    public void removeNotify() {
        sincronia.stop();
        super.removeNotify();
    }

    private void sincronizar() {
        if (sessao.getEstado() != EstadoConexao.CONECTADO) {
            return;
        }
        long agora = System.currentTimeMillis();
        Telemetria t = sessao.getConexao().getUltimaTelemetria();
        if (agora - cliqueChaves > TRAVA_APOS_CLIQUE_MS) {
            chaveTwoStep.setSelected(t.temFlag(Protocolo.FLAG_CHAVE_TWO_STEP));
            chaveLaunch.setSelected(t.temFlag(Protocolo.FLAG_CHAVE_LAUNCH));
        }
        if (agora - cliqueArmar > TRAVA_APOS_CLIQUE_MS) {
            boolean armado = t.temFlag(Protocolo.FLAG_ARMADO);
            botaoArmar.setSelected(armado);
            botaoArmar.setText(armado ? TEXTO_ARMADO : TEXTO_ARMAR);
        }
    }
}
