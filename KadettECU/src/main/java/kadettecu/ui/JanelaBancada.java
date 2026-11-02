package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import kadettecu.app.Bancada;
import kadettecu.app.EntradasBancada;
import kadettecu.app.SessaoECU;
import kadettecu.modelo.EstadoConexao;

/**
 * Bancada de testes: uma janelinha com controles deslizantes que definem à mão o que a ECU "sente"
 * (rotação, acelerador, MAP, temperaturas, bateria...). Serve para ensaiar o limitador, o two-step,
 * o launch e o enriquecimento sem carro e sem placa, e funciona com o simulador em Java e com o
 * firmware em C rodando no PC. Com uma ECU de verdade ela não existe: as entradas vêm dos sensores.
 *
 * <p>Os valores não são enviados direto da thread da tela: o envio pode ser uma escrita em arquivo e
 * a tela nunca deve esperar por disco. Cada movimento de controle deixa o valor mais recente numa
 * {@link AtomicReference} e pede a uma thread própria ("ecu-bancada") para gravá-lo; se o usuário
 * mexer rápido, os valores intermediários são descartados e só o último é gravado.
 */
public final class JanelaBancada extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient SessaoECU sessao;
    private final transient Bancada bancada;
    private final transient ExecutorService escritor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ecu-bancada");
        t.setDaemon(true);
        return t;
    });
    private final transient AtomicReference<EntradasBancada> pendente = new AtomicReference<>();
    private final transient OuvinteNaTela ouvinte;
    private final JToggleButton botaoManual;
    private boolean ajustando;

    private final transient Entrada rpm = new Entrada("ROTAÇÃO", "rpm", 0, 8000, 0, 1, 0);
    private final transient Entrada tps = new Entrada("ACELERADOR (TPS)", "%", 0, 100, 0, 2, 1);
    private final transient Entrada velocidade = new Entrada("VELOCIDADE", "km/h", 0, 240, 0, 1, 0);
    private final transient Entrada map = new Entrada("PRESSÃO NO COLETOR (MAP)", "kPa", 10, 250, 100, 1, 0);
    private final transient Entrada ect = new Entrada("ÁGUA (ECT)", "°C", -10, 130, 85, 1, 0);
    private final transient Entrada iat = new Entrada("AR (IAT)", "°C", -10, 90, 30, 1, 0);
    private final transient Entrada bateria = new Entrada("BATERIA", "V", 8, 16, 13.8, 10, 1);
    private final transient Entrada oleo = new Entrada("PRESSÃO DO ÓLEO", "bar", 0, 8, 0, 10, 1);
    private final transient Entrada lambda = new Entrada("LAMBDA (SONDA)", "λ", 0.70, 1.30, 1.0, 100, 2);
    private final transient Entrada[] todas = {rpm, tps, velocidade, map, ect, iat, bateria, oleo, lambda};

    public JanelaBancada(Window dono, SessaoECU sessao) {
        super(dono, "BANCADA DE TESTES", ModalityType.MODELESS);
        this.sessao = sessao;
        this.bancada = sessao.getBancada();
        if (sessao.getOpcaoAtual() != null) {
            setTitle("BANCADA DE TESTES — " + sessao.getOpcaoAtual().getRotulo());
        }
        getContentPane().setBackground(Tema.FUNDO_PAINEL);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        botaoManual = Controles.alternar(bancada != null && bancada.temCicloAutomatico() ? "MODO MANUAL" : "MOTOR LIGADO",
                "Liga: a ECU passa a usar os valores destes controles. Desliga: volta ao normal.", false,
                ligado -> aoAlternarManual(ligado));

        lambda.slider.setEnabled(bancada == null || bancada.controlaLambda());
        if (!lambda.slider.isEnabled()) {
            lambda.slider.setToolTipText("O simulador em Java calcula o lambda dele sozinho.");
        }

        JPanel conteudo = new JPanel(new BorderLayout(0, 10));
        conteudo.setOpaque(false);
        conteudo.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        conteudo.add(montarTopo(), BorderLayout.NORTH);
        conteudo.add(montarControles(), BorderLayout.CENTER);
        conteudo.add(montarPredefinicoes(), BorderLayout.SOUTH);
        setContentPane(conteudo);
        pack();
        setMinimumSize(new Dimension(560, getHeight()));
        setLocationRelativeTo(dono);

        if (bancada != null && !bancada.temCicloAutomatico()) {
            // o firmware do PC só anda com as entradas definidas aqui: começa já "ligado" em marcha lenta
            aplicar(Predefinicao.MARCHA_LENTA);
            botaoManual.setSelected(true);
            enviar();
        } else if (bancada != null && bancada.estaManual()) {
            botaoManual.setSelected(true);
        }

        ouvinte = new OuvinteNaTela(new SessaoECU.Ouvinte() {
            @Override
            public void aoMudarEstado(EstadoConexao estado) {
                if (estado == EstadoConexao.DESCONECTADO || estado == EstadoConexao.ERRO) {
                    dispose();
                }
            }
        });
        sessao.adicionarOuvinte(ouvinte);
    }

    @Override
    public void dispose() {
        if (ouvinte != null) {
            sessao.removerOuvinte(ouvinte);
        }
        escritor.shutdownNow();
        super.dispose();
    }

    // ---------------------------------------------------------------- montagem

    private JPanel montarTopo() {
        JPanel topo = new JPanel(new BorderLayout(10, 6));
        topo.setOpaque(false);
        boolean automatico = bancada != null && bancada.temCicloAutomatico();
        JTextArea explicacao = new JTextArea(automatico
                ? "O simulador faz sozinho um ciclo de aceleração. Ligue o MODO MANUAL para fixar as entradas com os controles abaixo."
                : "O firmware em C do PC só \"anda\" com as entradas definidas aqui. Desligue o MOTOR para zerar a rotação.");
        explicacao.setEditable(false);
        explicacao.setOpaque(false);
        explicacao.setLineWrap(true);
        explicacao.setWrapStyleWord(true);
        explicacao.setFont(Tema.fonteUI(Font.PLAIN, 12f));
        explicacao.setForeground(Tema.TEXTO_FRACO);
        explicacao.setBorder(BorderFactory.createEmptyBorder());
        topo.add(botaoManual, BorderLayout.WEST);
        topo.add(explicacao, BorderLayout.CENTER);
        return topo;
    }

    private JPanel montarControles() {
        JPanel quadro = new JPanel(new GridBagLayout());
        quadro.setOpaque(false);
        quadro.setBorder(Tema.moldura("ENTRADAS"));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(3, 8, 3, 8);
        for (Entrada e : todas) {
            c.gridx = 0;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.WEST;
            quadro.add(e.rotulo, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            quadro.add(e.slider, c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            quadro.add(e.valor, c);
            c.gridy++;
        }
        return quadro;
    }

    private JPanel montarPredefinicoes() {
        JPanel linha = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        linha.setOpaque(false);
        linha.add(Controles.rotulo("PREDEFINIÇÕES"));
        for (Predefinicao p : Predefinicao.values()) {
            linha.add(Controles.botao(p.rotulo, p.dica, () -> {
                aplicar(p);
                botaoManual.setSelected(true);
                enviar();
            }));
        }
        return linha;
    }

    // ---------------------------------------------------------------- uma entrada (rótulo + controle + valor)

    private final class Entrada {

        final JLabel rotulo;
        final JSlider slider;
        final JLabel valor = new JLabel("", SwingConstants.RIGHT);
        private final double escala;
        private final int casas;
        private final String unidade;

        Entrada(String nome, String unidade, double minimo, double maximo, double inicial, double escala, int casas) {
            this.escala = escala;
            this.casas = casas;
            this.unidade = unidade;
            rotulo = new JLabel(nome);
            rotulo.setFont(Tema.fonteTitulo(10.5f));
            rotulo.setForeground(Tema.TEXTO_FRACO);
            rotulo.setPreferredSize(new Dimension(200, 22));
            slider = new JSlider((int) Math.round(minimo * escala), (int) Math.round(maximo * escala),
                    (int) Math.round(inicial * escala));
            slider.setOpaque(false);
            valor.setFont(Tema.fonteMono(Font.BOLD, 12.5f));
            valor.setForeground(Tema.VERDE);
            valor.setPreferredSize(new Dimension(96, 22));
            slider.addChangeListener(e -> {
                atualizarTexto();
                if (!ajustando && botaoManual != null && botaoManual.isSelected()) {
                    enviar();
                }
            });
            atualizarTexto();
        }

        double get() {
            return slider.getValue() / escala;
        }

        void set(double v) {
            slider.setValue((int) Math.round(v * escala));
        }

        private void atualizarTexto() {
            valor.setText(String.format(Locale.ROOT, "%." + casas + "f %s", get(), unidade));
        }
    }

    // ---------------------------------------------------------------- predefinições

    private enum Predefinicao {
        PARADO("PARADO", "Chave ligada, motor parado", 0, 0, 0, 100, 85, 30, 12.6, 0, 1.0),
        MARCHA_LENTA("MARCHA LENTA", "Motor quente em marcha lenta", 850, 2, 0, 38, 88, 30, 13.8, 1.5, 1.0),
        CRUZEIRO("CRUZEIRO", "3000 rpm, carga leve", 3000, 22, 90, 55, 90, 32, 14.0, 3.5, 1.0),
        PLENA_CARGA("PLENA CARGA", "5500 rpm, acelerador todo", 5500, 100, 150, 98, 92, 35, 14.0, 5.0, 0.88),
        LARGADA("LARGADA", "Parado, 3500 rpm, acelerador todo (teste do launch)", 3500, 100, 0, 95, 90, 32, 14.0, 3.5, 0.90);

        private final String rotulo;
        private final String dica;
        private final double[] valores;

        Predefinicao(String rotulo, String dica, double rpm, double tps, double velocidade, double map, double ect,
                     double iat, double bateria, double oleo, double lambda) {
            this.rotulo = rotulo;
            this.dica = dica;
            this.valores = new double[] {rpm, tps, velocidade, map, ect, iat, bateria, oleo, lambda};
        }
    }

    private void aplicar(Predefinicao p) {
        ajustando = true;
        try {
            for (int i = 0; i < todas.length; i++) {
                todas[i].set(p.valores[i]);
            }
        } finally {
            ajustando = false;
        }
    }

    // ---------------------------------------------------------------- envio

    private void aoAlternarManual(boolean ligado) {
        if (ligado) {
            enviar();
        } else {
            pendente.set(null);
            escritor.execute(() -> {
                try {
                    bancada.desligar();
                } catch (IOException e) {
                    sessao.registrar("Bancada: falha ao desligar o modo manual: " + e.getMessage());
                }
            });
        }
    }

    private EntradasBancada lerEntradas() {
        return new EntradasBancada(rpm.get(), velocidade.get(), tps.get(), map.get(), ect.get(), iat.get(),
                bateria.get(), oleo.get(), lambda.get());
    }

    /** Guarda os valores atuais e pede para a thread da bancada gravá-los (só o mais recente vale). */
    private void enviar() {
        if (bancada == null) {
            return;
        }
        pendente.set(lerEntradas());
        escritor.execute(this::gravarPendente);
    }

    private void gravarPendente() {
        EntradasBancada entradas = pendente.getAndSet(null);
        if (entradas == null) {
            return; // um envio mais novo já levou este valor
        }
        try {
            bancada.definir(entradas);
        } catch (IOException e) {
            sessao.registrar("Bancada: falha ao gravar as entradas: " + e.getMessage());
        }
    }
}
