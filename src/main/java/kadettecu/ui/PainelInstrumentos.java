package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import kadettecu.app.SessaoECU;
import kadettecu.modelo.CampoParametro;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.ParametrosMotor;
import kadettecu.modelo.Telemetria;
import kadettecu.modelo.TipoMapa;
import kadettecu.protocolo.Protocolo;

/**
 * A aba PAINEL: conta-giros, velocímetro, barras dos sensores, as três colunas STAGE (launch,
 * two-step e limitador), a superfície 3D ao vivo, o indicador de mistura, o histórico em
 * gráfico e as luzes de estado e de erro.
 *
 * <p>Atualização: a thread de leitura da porta só guarda a última telemetria (uma referência
 * atômica). A cada 33 ms um {@link Timer} do Swing, que roda na thread da tela, lê essa referência
 * e atualiza os componentes. Assim o desenho nunca espera pela porta serial, e a porta nunca espera
 * pelo desenho.
 */
public final class PainelInstrumentos extends JPanel implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    private static final int PERIODO_TELA_MS = 33;

    private final transient SessaoECU sessao;

    private final Mostrador tacometro = new Mostrador("RPM x1000", "RPM", 0, 8000, 1000, 2, 1, 1000, 0, Tema.CIANO);
    private final Mostrador velocimetro = new Mostrador("VELOCIDADE", "km/h", 0, 240, 20, 2, 2, 1, 0, Tema.VERDE);
    private final BarraSegmentada barraEct = new BarraSegmentada("ECT", "°C", -10, 130, 1)
            .zona(-10, Tema.AZUL).zona(60, Tema.VERDE).zona(100, Tema.AMBAR).zona(110, Tema.VERMELHO);
    private final BarraSegmentada barraIat = new BarraSegmentada("IAT", "°C", -10, 90, 1)
            .zona(-10, Tema.AZUL).zona(10, Tema.VERDE).zona(55, Tema.AMBAR).zona(70, Tema.VERMELHO);
    private final BarraSegmentada barraOleo = new BarraSegmentada("ÓLEO", "bar", 0, 8, 1)
            .zona(0, Tema.VERMELHO).zona(0.8, Tema.AMBAR).zona(1.5, Tema.VERDE).zona(6.5, Tema.AMBAR).zona(7.5, Tema.VERMELHO);
    private final BarraSegmentada barraMap = new BarraSegmentada("MAP", "kPa", 0, 250, 0)
            .zona(0, Tema.CIANO).zona(40, Tema.VERDE).zona(105, Tema.AMBAR).zona(170, Tema.LARANJA);
    private final BarraSegmentada barraTps = new BarraSegmentada("TPS", "%", 0, 100, 1)
            .zona(0, Tema.VERDE).zona(70, Tema.AMBAR).zona(92, Tema.LARANJA);
    private final BarraSegmentada barraBateria = new BarraSegmentada("BAT", "V", 8, 16, 2)
            .zona(8, Tema.VERMELHO).zona(11, Tema.AMBAR).zona(12.4, Tema.VERDE).zona(14.9, Tema.AMBAR).zona(15.6, Tema.VERMELHO);
    private final BarraSegmentada barraLambda = new BarraSegmentada("λ", "", 0.7, 1.3, 3)
            .zona(0.7, Tema.VERMELHO).zona(0.8, Tema.AMBAR).zona(0.92, Tema.VERDE).zona(1.08, Tema.AMBAR).zona(1.2, Tema.VERMELHO);

    private final CaixaInfo infoAvanco = new CaixaInfo("AVANÇO", 6);
    private final CaixaInfo infoPulso = new CaixaInfo("PULSO", 8);
    private final CaixaInfo infoVe = new CaixaInfo("VE", 5);
    private final CaixaInfo infoAe = new CaixaInfo("AE", 5);
    private final CaixaInfo infoIac = new CaixaInfo("IAC", 5);
    private final CaixaInfo infoAfr = new CaixaInfo("AFR", 5);

    private final ColunaStage stage1 = new ColunaStage("STAGE 1", "LAUNCH");
    private final ColunaStage stage2 = new ColunaStage("STAGE 2", "TWO-STEP");
    private final ColunaStage stage3 = new ColunaStage("STAGE 3", "LIMITADOR");

    private final Superficie3D superficie = new Superficie3D(true);
    private final LegendaCalor legenda = new LegendaCalor();
    private final IndicadorHud hud = new IndicadorHud();
    private final GraficoHistorico historico = new GraficoHistorico(20, 30);

    private final Lampada lampSync = new Lampada("SYNC", Tema.VERDE);
    private final Lampada lampMotor = new Lampada("MOTOR", Tema.VERDE);
    private final Lampada lampCorte = new Lampada("CORTE", Tema.VERMELHO);
    private final Lampada lampTwoStep = new Lampada("TWO-STEP", Tema.AMBAR);
    private final Lampada lampLaunch = new Lampada("LAUNCH", Tema.CIANO);
    private final Lampada lampAe = new Lampada("ACEL.", Tema.LARANJA);
    private final Lampada lampMarchaLenta = new Lampada("M.LENTA", Tema.VERDE);
    private final Lampada lampArmado = new Lampada("SAÍDAS ARMADAS", Tema.VERMELHO);
    private final Lampada lampSombra = new Lampada("MODO SOMBRA", Tema.CIANO);
    private final Lampada lampSimulador = new Lampada("SIMULADOR", Tema.AZUL);
    private final Lampada[] lampErros = {
        new Lampada("ECT", Tema.VERMELHO), new Lampada("IAT", Tema.VERMELHO), new Lampada("MAP", Tema.VERMELHO),
        new Lampada("TPS", Tema.VERMELHO), new Lampada("BATERIA", Tema.VERMELHO), new Lampada("ROTAÇÃO", Tema.VERMELHO),
        new Lampada("MEMÓRIA", Tema.VERMELHO), new Lampada("LAMBDA", Tema.VERMELHO)
    };
    private static final int[] MASCARAS_ERRO = {
        Protocolo.ERR_ECT, Protocolo.ERR_IAT, Protocolo.ERR_MAP, Protocolo.ERR_TPS, Protocolo.ERR_BATERIA,
        Protocolo.ERR_SEM_ROTACAO, Protocolo.ERR_NVM, Protocolo.ERR_LAMBDA
    };

    private final JLabel aviso = new JLabel(" ", SwingConstants.CENTER);
    private final Timer relogio = new Timer(PERIODO_TELA_MS, e -> aoTique());
    private TipoMapa tipoMiniatura = TipoMapa.COMBUSTIVEL;
    private int marcaLimite = -1;
    private int marcaTwoStep = -1;
    private int marcaLaunch = -1;
    private boolean twoStepHabilitado;
    private boolean launchHabilitado;

    public PainelInstrumentos(SessaoECU sessao) {
        super(new BorderLayout(0, 6));
        this.sessao = sessao;
        setBackground(Tema.FUNDO_PAINEL);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        aviso.setFont(Tema.fonteTitulo(11.5f));
        aviso.setPreferredSize(new Dimension(10, 22));
        add(aviso, BorderLayout.NORTH);

        JPanel centro = new JPanel(new GridBagLayout());
        centro.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 6, 8);

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0.60;
        c.weighty = 0.68;
        centro.add(montarBlocoEsquerdo(), c);

        c.gridx = 1;
        c.weightx = 0.40;
        c.insets = new Insets(0, 0, 6, 0);
        centro.add(montarBlocoDireito(), c);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 0.32;
        c.insets = new Insets(0, 0, 0, 0);
        centro.add(montarHistorico(), c);
        add(centro, BorderLayout.CENTER);

        add(montarLampadas(), BorderLayout.SOUTH);
        configurarMiniatura();
        sessao.adicionarOuvinte(new OuvinteNaTela(this));
    }

    // ---------------------------------------------------------------- montagem

    private JPanel montarBlocoEsquerdo() {
        JPanel bloco = new JPanel(new GridBagLayout());
        bloco.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 0;
        c.weightx = 1;

        JPanel mostradores = new JPanel(new GridLayout(1, 2, 10, 0));
        mostradores.setOpaque(false);
        mostradores.add(tacometro);
        mostradores.add(velocimetro);
        c.gridy = 0;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 8, 0);
        bloco.add(mostradores, c);

        JPanel sensores = new JPanel(new BorderLayout(0, 6));
        sensores.setBorder(Tema.moldura("SENSORES"));
        sensores.setOpaque(false);
        JPanel barras = new JPanel(new GridLayout(4, 2, 18, 2));
        barras.setOpaque(false);
        barras.add(barraEct);
        barras.add(barraTps);
        barras.add(barraIat);
        barras.add(barraBateria);
        barras.add(barraOleo);
        barras.add(barraLambda);
        barras.add(barraMap);
        barras.add(new JLabel());
        sensores.add(barras, BorderLayout.CENTER);
        JPanel numeros = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        numeros.setOpaque(false);
        numeros.add(infoAvanco);
        numeros.add(infoPulso);
        numeros.add(infoVe);
        numeros.add(infoAe);
        numeros.add(infoIac);
        numeros.add(infoAfr);
        sensores.add(numeros, BorderLayout.SOUTH);
        c.gridy = 1;
        c.weighty = 0;
        c.insets = new Insets(0, 0, 0, 0);
        bloco.add(sensores, c);
        return bloco;
    }

    private JPanel montarBlocoDireito() {
        JPanel bloco = new JPanel(new BorderLayout(8, 0));
        bloco.setOpaque(false);

        JPanel estagios = new JPanel(new GridLayout(1, 3, 6, 0));
        estagios.setBorder(Tema.moldura("ESTÁGIOS DE CORTE"));
        estagios.setOpaque(false);
        estagios.add(stage1);
        estagios.add(stage2);
        estagios.add(stage3);
        bloco.add(estagios, BorderLayout.CENTER);

        JPanel lateral = new JPanel(new GridBagLayout());
        lateral.setOpaque(false);
        lateral.setPreferredSize(new Dimension(250, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 0;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);

        JPanel mapaVivo = new JPanel(new BorderLayout(0, 4));
        mapaVivo.setBorder(Tema.moldura("MAPA AO VIVO"));
        mapaVivo.setOpaque(false);
        mapaVivo.add(superficie, BorderLayout.CENTER);
        mapaVivo.add(montarSeletorMiniatura(), BorderLayout.SOUTH);
        c.gridy = 0;
        c.weighty = 1;
        lateral.add(mapaVivo, c);

        c.gridy = 1;
        c.weighty = 0;
        lateral.add(legenda, c);
        c.gridy = 2;
        c.insets = new Insets(0, 0, 0, 0);
        lateral.add(hud, c);
        bloco.add(lateral, BorderLayout.EAST);
        return bloco;
    }

    private JPanel montarSeletorMiniatura() {
        JPanel linha = new JPanel(new GridLayout(1, 3, 4, 0));
        linha.setOpaque(false);
        ButtonGroup grupo = new ButtonGroup();
        for (TipoMapa tipo : TipoMapa.values()) {
            JToggleButton botao = new JToggleButton(abreviar(tipo), tipo == tipoMiniatura);
            botao.setFont(Tema.fonteTitulo(9.5f));
            botao.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
            botao.setToolTipText(tipo.getRotulo());
            botao.addActionListener(e -> {
                tipoMiniatura = tipo;
                configurarMiniatura();
            });
            grupo.add(botao);
            linha.add(botao);
        }
        return linha;
    }

    private static String abreviar(TipoMapa tipo) {
        switch (tipo) {
            case COMBUSTIVEL:
                return "COMB.";
            case IGNICAO:
                return "IGN.";
            default:
                return "λ ALVO";
        }
    }

    private void configurarMiniatura() {
        superficie.setMapa(sessao.getCalibracao().getMapa(tipoMiniatura));
        legenda.setTipo(tipoMiniatura);
        superficie.repaint();
    }

    private JPanel montarHistorico() {
        JPanel moldura = new JPanel(new BorderLayout());
        moldura.setBorder(Tema.moldura("HISTÓRICO (20 s)"));
        moldura.setOpaque(false);
        historico.adicionarSerie("RPM", "", Tema.CIANO, 0, 8000, 0);
        historico.adicionarSerie("TPS", "%", Tema.VERDE, 0, 100, 1);
        historico.adicionarSerie("MAP", "kPa", Tema.AMBAR, 0, 250, 0);
        historico.adicionarSerie("λ", "", Tema.LARANJA, 0.7, 1.3, 3);
        historico.adicionarSerie("AVANÇO", "°", Tema.misturar(Tema.CIANO, Color.WHITE, 0.55), -10, 50, 1);
        moldura.add(historico, BorderLayout.CENTER);
        return moldura;
    }

    /** Duas fileiras de luzes: o estado da ECU em cima e os sensores com defeito embaixo. */
    private JPanel montarLampadas() {
        JPanel estado = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        estado.setOpaque(false);
        for (Lampada l : new Lampada[] {lampSync, lampMotor, lampCorte, lampTwoStep, lampLaunch, lampAe,
            lampMarchaLenta, lampArmado, lampSombra, lampSimulador}) {
            estado.add(l);
        }
        JPanel erros = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        erros.setOpaque(false);
        JLabel titulo = new JLabel("ERROS DE SENSOR:");
        titulo.setFont(Tema.fonteTitulo(10f));
        titulo.setForeground(Tema.TEXTO_FRACO);
        titulo.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 8));
        erros.add(titulo);
        for (Lampada l : lampErros) {
            erros.add(l);
        }
        JPanel linhas = new JPanel(new GridLayout(2, 1, 0, 0));
        linhas.setOpaque(false);
        linhas.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.MOLDURA_ESCURA));
        linhas.add(estado);
        linhas.add(erros);
        return linhas;
    }

    // ---------------------------------------------------------------- ciclo de vida

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

    /** Chamado quando a calibração local foi trocada: a miniatura 3D precisa apontar para os dados novos. */
    @Override
    public void aoSubstituirCalibracao() {
        configurarMiniatura();
    }

    // ---------------------------------------------------------------- atualização

    private void aoTique() {
        if (!isShowing()) {
            return;
        }
        EstadoConexao estado = sessao.getEstado();
        boolean vivo = estado == EstadoConexao.CONECTADO;
        Telemetria t = sessao.getConexao().getUltimaTelemetria();
        ParametrosMotor p = sessao.getCalibracao().getParametros();
        atualizarMarcas(p);
        atualizarAviso(estado);

        boolean rodando = vivo && t.temFlag(Protocolo.FLAG_MOTOR_GIRANDO);
        tacometro.setAtivo(vivo);
        velocimetro.setAtivo(vivo);
        tacometro.setAlvo(vivo ? t.getRpm() : 0);
        velocimetro.setAlvo(vivo ? t.getVelocidadeKmh() : 0);
        tacometro.avancar(0.35);
        velocimetro.avancar(0.30);

        atualizarBarra(barraEct, vivo, t.getEctC(), t.temErro(Protocolo.ERR_ECT));
        atualizarBarra(barraIat, vivo, t.getIatC(), t.temErro(Protocolo.ERR_IAT));
        atualizarBarra(barraOleo, vivo, t.getOleoBar(), false);
        atualizarBarra(barraMap, vivo, t.getMapKpa(), t.temErro(Protocolo.ERR_MAP));
        atualizarBarra(barraTps, vivo, t.getTpsPct(), t.temErro(Protocolo.ERR_TPS));
        atualizarBarra(barraBateria, vivo, t.getBateriaV(), t.temErro(Protocolo.ERR_BATERIA));
        atualizarBarra(barraLambda, vivo, t.getLambda(), t.temErro(Protocolo.ERR_LAMBDA));

        double afr = t.getAfr(p.getFisico(CampoParametro.AFR_ESTEQ_X10));
        infoAvanco.setValor(vivo ? String.format(Locale.ROOT, "%.1f°", t.getAvancoGraus()) : "--");
        infoPulso.setValor(vivo ? String.format(Locale.ROOT, "%.2f ms", t.getPwUs() / 1000.0) : "--");
        infoVe.setValor(vivo ? t.getVePct() + "%" : "--");
        infoAe.setValor(vivo ? "+" + t.getAePct() + "%" : "--");
        infoIac.setValor(vivo ? t.getIacPct() + "%" : "--");
        infoAfr.setValor(vivo ? String.format(Locale.ROOT, "%.1f", afr) : "--");

        hud.atualizar(vivo, t.getLambda(), afr);
        hud.avancar(0.25);

        atualizarEstagios(vivo, t, p);

        superficie.setPonto(rodando, t.getPosRpm(), t.getPosCarga());
        if (vivo) {
            historico.adicionar(t.getRpm(), t.getTpsPct(), t.getMapKpa(), t.getLambda(), t.getAvancoGraus());
        }
        atualizarLampadas(vivo, t);
        repaint();
    }

    private static void atualizarBarra(BarraSegmentada barra, boolean vivo, double valor, boolean erro) {
        barra.setAtivo(vivo);
        barra.setAlerta(vivo && erro);
        barra.setAlvo(valor);
        barra.avancar(0.35);
    }

    private void atualizarMarcas(ParametrosMotor p) {
        int limite = p.get(CampoParametro.REV_LIMITE_RPM);
        int twoStep = p.get(CampoParametro.TWO_STEP_RPM);
        int launch = p.get(CampoParametro.LAUNCH_RPM);
        boolean habTwo = p.getBool(CampoParametro.HAB_TWO_STEP);
        boolean habLaunch = p.getBool(CampoParametro.HAB_LAUNCH);
        if (limite == marcaLimite && twoStep == marcaTwoStep && launch == marcaLaunch && habTwo == twoStepHabilitado
                && habLaunch == launchHabilitado) {
            return;
        }
        marcaLimite = limite;
        marcaTwoStep = twoStep;
        marcaLaunch = launch;
        twoStepHabilitado = habTwo;
        launchHabilitado = habLaunch;
        tacometro.setInicioZonaVermelha(limite);
        tacometro.limparMarcas();
        tacometro.adicionarMarca(limite, Tema.VERMELHO);
        if (habTwo) {
            tacometro.adicionarMarca(twoStep, Tema.AMBAR);
        }
        if (habLaunch) {
            tacometro.adicionarMarca(launch, Tema.CIANO);
        }
    }

    private void atualizarAviso(EstadoConexao estado) {
        switch (estado) {
            case CONECTADO:
                aviso.setText(" ");
                break;
            case CONECTANDO:
                aviso.setForeground(Tema.CIANO);
                aviso.setText("CONECTANDO À ECU...");
                break;
            case SEM_SINAL:
                aviso.setForeground(Tema.AMBAR);
                aviso.setText("SEM SINAL — a ECU parou de enviar telemetria. Verifique o cabo USB e a alimentação da placa.");
                break;
            case ERRO:
                aviso.setForeground(Tema.VERMELHO);
                aviso.setText("A COMUNICAÇÃO CAIU — veja a aba REGISTRO. Clique em CONECTAR para tentar de novo.");
                break;
            default:
                aviso.setForeground(Tema.TEXTO_FRACO);
                aviso.setText("SEM CONEXÃO — escolha uma porta e clique em CONECTAR (o SIMULADOR funciona sem a placa).");
                break;
        }
    }

    private void atualizarEstagios(boolean vivo, Telemetria t, ParametrosMotor p) {
        boolean twoAtivo = vivo && t.temFlag(Protocolo.FLAG_TWO_STEP);
        boolean launchAtivo = vivo && t.temFlag(Protocolo.FLAG_LAUNCH);
        boolean corte = vivo && t.temFlag(Protocolo.FLAG_CORTE);
        int limite = p.get(CampoParametro.REV_LIMITE_RPM);
        int rpmTwo = p.get(CampoParametro.TWO_STEP_RPM);
        int rpmLaunch = p.get(CampoParametro.LAUNCH_RPM);
        // qual estágio define o limite em vigor agora (o menor entre os que estão ativos)
        int efetivo = 3;
        int limiteEfetivo = limite;
        if (twoAtivo && rpmTwo < limiteEfetivo) {
            limiteEfetivo = rpmTwo;
            efetivo = 2;
        }
        if (launchAtivo && rpmLaunch < limiteEfetivo) {
            efetivo = 1;
        }
        ColunaStage.Situacao s1;
        ColunaStage.Situacao s2;
        ColunaStage.Situacao s3;
        if (!vivo) {
            s1 = ColunaStage.Situacao.OFFLINE;
            s2 = ColunaStage.Situacao.OFFLINE;
            s3 = ColunaStage.Situacao.OFFLINE;
        } else {
            s1 = !p.getBool(CampoParametro.HAB_LAUNCH) ? ColunaStage.Situacao.DESLIGADO
                    : (launchAtivo ? (corte && efetivo == 1 ? ColunaStage.Situacao.CORTANDO : ColunaStage.Situacao.ATIVO)
                    : ColunaStage.Situacao.PRONTO);
            s2 = !p.getBool(CampoParametro.HAB_TWO_STEP) ? ColunaStage.Situacao.DESLIGADO
                    : (twoAtivo ? (corte && efetivo == 2 ? ColunaStage.Situacao.CORTANDO : ColunaStage.Situacao.ATIVO)
                    : ColunaStage.Situacao.PRONTO);
            s3 = corte && efetivo == 3 ? ColunaStage.Situacao.CORTANDO : ColunaStage.Situacao.PRONTO;
        }
        stage1.atualizar(s1, rpmLaunch, t.getRpm());
        stage2.atualizar(s2, rpmTwo, t.getRpm());
        stage3.atualizar(s3, limite, t.getRpm());
        stage1.avancar(0.3);
        stage2.avancar(0.3);
        stage3.avancar(0.3);
    }

    private void atualizarLampadas(boolean vivo, Telemetria t) {
        lampSync.setAcesa(vivo && t.temFlag(Protocolo.FLAG_SYNC));
        lampMotor.setAcesa(vivo && t.temFlag(Protocolo.FLAG_MOTOR_GIRANDO));
        lampCorte.setModo(vivo && t.temFlag(Protocolo.FLAG_CORTE) ? Lampada.Modo.PISCANDO : Lampada.Modo.APAGADA);
        lampTwoStep.setAcesa(vivo && t.temFlag(Protocolo.FLAG_TWO_STEP));
        lampLaunch.setAcesa(vivo && t.temFlag(Protocolo.FLAG_LAUNCH));
        lampAe.setAcesa(vivo && t.temFlag(Protocolo.FLAG_AE));
        lampMarchaLenta.setAcesa(vivo && t.temFlag(Protocolo.FLAG_MARCHA_LENTA));
        lampArmado.setModo(vivo && t.temFlag(Protocolo.FLAG_ARMADO) ? Lampada.Modo.PISCANDO : Lampada.Modo.APAGADA);
        lampSombra.setAcesa(vivo && t.temFlag(Protocolo.FLAG_MODO_SOMBRA));
        lampSimulador.setAcesa(vivo && t.temFlag(Protocolo.FLAG_SIMULADOR));
        for (int i = 0; i < lampErros.length; i++) {
            lampErros[i].setAcesa(vivo && t.temErro(MASCARAS_ERRO[i]));
        }
    }
}
