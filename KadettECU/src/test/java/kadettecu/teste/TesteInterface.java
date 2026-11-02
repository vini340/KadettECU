package kadettecu.teste;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import kadettecu.app.SessaoECU;
import kadettecu.modelo.CampoParametro;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.TipoMapa;
import kadettecu.protocolo.Protocolo;
import kadettecu.ui.JanelaBancada;
import kadettecu.ui.JanelaECU;
import kadettecu.ui.PainelParametros;
import kadettecu.ui.PainelRegistro;
import kadettecu.ui.TabelaMapa;
import kadettecu.ui.Tema;

/**
 * Teste de fumaça da interface: abre a janela de verdade, conecta ao simulador pelo botão e mexe nos
 * controles como o usuário faria (editar uma célula do mapa, um parâmetro, as chaves, a bancada,
 * reconectar com alterações pendentes), conferindo o resultado na sessão e na telemetria.
 *
 * <p>Precisa de ambiente gráfico. Sem tela, rode com {@code xvfb-run -a java ...}; se não houver nenhum,
 * o teste se declara ignorado e termina com sucesso. Sem JUnit: é só rodar o main. Termina com código 1
 * se alguma verificação falhar.
 */
public class TesteInterface {

    private static final long ESPERA_MS = 10_000;

    private static int falhas = 0;
    private static int total = 0;
    private static final List<String> errosDaTela = new CopyOnWriteArrayList<>();

    private static SessaoECU sessao;
    private static JanelaECU janela;

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Teste da interface ignorado: não há ambiente gráfico (rode com xvfb-run -a java ...).");
            return;
        }
        ByteArrayOutputStream saidaDeErro = espiarSaidaDeErro();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> errosDaTela.add(t.getName() + ": " + e));

        try {
            montarJanela();
            testarJanelaDesconectada();
            testarConexaoPeloBotao();
            testarEdicaoDeMapa();
            testarParametros();
            testarChaves();
            testarArmarEmModoSombra();
            testarBancadaDoSimulador();
            testarRedimensionamento();
            testarReconexaoComAlteracoes();
            testarDesconexao();
            testarFirmwareDoPc();
        } catch (Throwable erro) {
            System.out.println("  FALHOU  o teste parou por um erro inesperado: " + erro);
            erro.printStackTrace(System.out);
            falhas++;
            total++;
        }
        testarEncerramento(saidaDeErro);

        System.out.println();
        if (falhas == 0) {
            System.out.println("Todos os testes da interface passaram (" + total + " verificações).");
            System.exit(0);
        } else {
            System.out.println("Testes da interface com falha: " + falhas + " de " + total);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ cenários

    private static void montarJanela() throws Exception {
        naTela(() -> {
            Tema.instalar();
            sessao = new SessaoECU(SwingUtilities::invokeLater);
            janela = new JanelaECU(sessao, () -> { });
            janela.setVisible(true);
            return null;
        });
    }

    private static void testarJanelaDesconectada() throws Exception {
        titulo("Janela: montagem e estado desconectado");
        final JTabbedPane abas = naTela(() -> unico(janela, JTabbedPane.class));
        final String[] titulos = naTela(() -> {
            String[] t = new String[abas.getTabCount()];
            for (int i = 0; i < t.length; i++) {
                t[i] = abas.getTitleAt(i);
            }
            return t;
        });
        verificar("a janela tem as cinco abas, na ordem certa",
                Arrays.equals(new String[] {"PAINEL", "MAPAS", "PARÂMETROS", "BINÁRIO / HEX", "REGISTRO"}, titulos));
        verificar("sem conexão: TWO-STEP, LAUNCH e ARMAR SAÍDAS bloqueados",
                !habilitado("TWO-STEP") && !habilitado("LAUNCH") && !habilitado("ARMAR SAÍDAS"));
        verificar("sem conexão: LER DA ECU, ENVIAR TUDO e GRAVAR NA MEMÓRIA bloqueados",
                !habilitado("LER DA ECU") && !habilitado("ENVIAR TUDO") && !habilitado("GRAVAR NA MEMÓRIA"));
        verificar("sem conexão: ABRIR .KECU e CONECTAR estão disponíveis", habilitado("ABRIR .KECU") && habilitado("CONECTAR"));
        verificar("a sessão começa DESCONECTADA", sessao.getEstado() == EstadoConexao.DESCONECTADO);
    }

    private static void testarConexaoPeloBotao() throws Exception {
        titulo("Conexão com o simulador pelo botão CONECTAR");
        final JComboBox<?> portas = naTela(TesteInterface::comboDePortas);
        verificar("a lista de portas é montada", esperarNaTela(() -> portas.getItemCount() > 0, ESPERA_MS));
        verificar("o simulador é a primeira opção da lista", naTela(() -> String.valueOf(portas.getItemAt(0)).toUpperCase().contains("SIMULADOR")));
        clicar("CONECTAR");
        verificar("conecta ao simulador", esperar(() -> sessao.getEstado() == EstadoConexao.CONECTADO, ESPERA_MS));
        verificar("chegam pacotes de telemetria", esperar(() -> sessao.getConexao().getPacotesValidos() >= 25, ESPERA_MS));
        verificar("o botão de conexão vira DESCONECTAR", existeBotao("DESCONECTAR"));
        verificar("conectado: TWO-STEP, LAUNCH e ARMAR SAÍDAS liberados",
                habilitado("TWO-STEP") && habilitado("LAUNCH") && habilitado("ARMAR SAÍDAS"));
        verificar("conectado: LER DA ECU, ENVIAR TUDO e BANCADA... liberados",
                habilitado("LER DA ECU") && habilitado("ENVIAR TUDO") && habilitado("BANCADA..."));
        verificar("a calibração é lida da ECU ao conectar",
                esperarNaTela(() -> registro().contains("Calibração lida da ECU."), ESPERA_MS));
        verificar("calibração da tela igual à da ECU (nada alterado)", !naTela(sessao::isAlterada));
        verificar("a aba REGISTRO mostra a abertura da conexão", naTela(() -> registro().contains("Conectado:")));
    }

    private static void testarEdicaoDeMapa() throws Exception {
        titulo("Mapas: editar células, enviar à ECU, ler de volta e desfazer");
        janela.selecionarAba(1);
        final TabelaMapa tabela = naTela(() -> unico(janela, TabelaMapa.class));
        final TipoMapa tipo = naTela(() -> tabela.getMapa().getTipo());
        verificar("a aba MAPAS começa pelo mapa de combustível", tipo == TipoMapa.COMBUSTIVEL);

        final int antes = naTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(5, 6));
        final int novo = antes + 4 <= 255 ? antes + 4 : antes - 4;
        naTela(() -> {
            tabela.getModel().setValueAt(Double.valueOf(tipo.rawParaFisico(novo)), 5, 7);
            return null;
        });
        verificar("editar a célula muda a calibração da tela", naTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(5, 6)) == novo);
        verificar("a sessão avisa que há alterações não enviadas", naTela(sessao::isAlterada));

        clicar("ENVIAR TUDO");
        verificar("ENVIAR TUDO conclui", esperarNaTela(() -> registro().contains("Calibração enviada à ECU"), ESPERA_MS));
        verificar("depois de enviar, a tela e a ECU estão iguais", esperarNaTela(() -> !sessao.isAlterada(), ESPERA_MS));

        final int leiturasAntes = naTela(() -> contar(registro(), "Calibração lida da ECU."));
        clicar("LER DA ECU");
        verificar("LER DA ECU conclui",
                esperarNaTela(() -> contar(registro(), "Calibração lida da ECU.") > leiturasAntes, ESPERA_MS));
        verificar("a ECU confirma o valor enviado ao ler de volta",
                naTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(5, 6)) == novo && !naTela(sessao::isAlterada));

        final int antes2 = naTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(2, 2));
        final int novo2 = antes2 + 3 <= 255 ? antes2 + 3 : antes2 - 3;
        naTela(() -> {
            tabela.getModel().setValueAt(Double.valueOf(tipo.rawParaFisico(novo2)), 2, 3);
            return null;
        });
        verificar("segunda edição aplicada", naTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(2, 2)) == novo2);
        clicar("DESFAZER");
        verificar("DESFAZER devolve o valor anterior", naTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(2, 2)) == antes2);
        verificar("e a situação volta a 'igual à ECU'", esperarNaTela(() -> !sessao.isAlterada(), ESPERA_MS));
    }

    private static void testarParametros() throws Exception {
        titulo("Parâmetros: editar um campo e enviar");
        janela.selecionarAba(2);
        final String rotulo = CampoParametro.REV_LIMITE_RPM.getRotulo();
        final JSpinner limite = naTela(() -> spinnerDoCampo(rotulo));
        final int atual = naTela(() -> sessao.getCalibracao().getParametros().get(CampoParametro.REV_LIMITE_RPM));
        verificar("o campo mostra o valor da calibração", naTela(() -> ((Number) limite.getValue()).intValue()) == atual);
        naTela(() -> {
            limite.setValue(Double.valueOf(7300));
            return null;
        });
        verificar("editar o campo altera a calibração da tela",
                naTela(() -> sessao.getCalibracao().getParametros().get(CampoParametro.REV_LIMITE_RPM)) == 7300);
        verificar("e marca alterações pendentes", naTela(sessao::isAlterada));
        clicar("ENVIAR PARÂMETROS");
        verificar("ENVIAR PARÂMETROS atualiza a ECU",
                esperarNaTela(() -> sessao.getCalibracaoEcu().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 7300, ESPERA_MS));
        verificar("e a situação volta a 'igual à ECU'", esperarNaTela(() -> !sessao.isAlterada(), ESPERA_MS));
    }

    private static void testarChaves() throws Exception {
        titulo("Chaves virtuais: TWO-STEP e LAUNCH");
        clicar("TWO-STEP");
        verificar("a ECU liga a chave de two-step", esperar(() -> temFlag(Protocolo.FLAG_CHAVE_TWO_STEP), ESPERA_MS));
        Thread.sleep(800); // o botão sincroniza com a telemetria a cada 250 ms: não pode "desligar sozinho"
        verificar("o botão TWO-STEP continua ligado", naTela(() -> botao(janela, "TWO-STEP").isSelected()));
        clicar("LAUNCH");
        verificar("a ECU liga a chave de launch", esperar(() -> temFlag(Protocolo.FLAG_CHAVE_LAUNCH), ESPERA_MS));
        verificar("as duas chaves ficam ligadas juntas", temFlag(Protocolo.FLAG_CHAVE_TWO_STEP));
        clicar("TWO-STEP");
        clicar("LAUNCH");
        verificar("desligar os botões desliga as chaves na ECU",
                esperar(() -> !temFlag(Protocolo.FLAG_CHAVE_TWO_STEP) && !temFlag(Protocolo.FLAG_CHAVE_LAUNCH), ESPERA_MS));
        Thread.sleep(500);
        verificar("os botões acompanham: ambos desligados",
                !naTela(() -> botao(janela, "TWO-STEP").isSelected()) && !naTela(() -> botao(janela, "LAUNCH").isSelected()));
    }

    private static void testarArmarEmModoSombra() throws Exception {
        titulo("ARMAR SAÍDAS em modo sombra");
        final AbstractButton armar = naTela(() -> botao(janela, "ARMAR SAÍDAS"));
        SwingUtilities.invokeLater(armar::doClick); // abre um diálogo modal: não pode bloquear o teste
        verificar("o aviso de modo sombra aparece", esperar(() -> dialogoVisivel("Armar saídas") != null, ESPERA_MS));
        JDialog aviso = dialogoVisivel("Armar saídas");
        if (aviso != null) {
            naTela(() -> {
                aviso.dispose();
                return null;
            });
        }
        verificar("o botão volta a ficar desarmado", esperarNaTela(() -> !armar.isSelected(), ESPERA_MS));
        verificar("a ECU continua com as saídas desarmadas", !temFlag(Protocolo.FLAG_ARMADO));
        verificar("o simulador está em modo sombra", temFlag(Protocolo.FLAG_MODO_SOMBRA));
    }

    private static void testarBancadaDoSimulador() throws Exception {
        titulo("Bancada de testes do simulador");
        clicar("BANCADA...");
        verificar("a janela da bancada abre", esperar(() -> bancadaVisivel() != null, ESPERA_MS));
        final JanelaBancada bancada = bancadaVisivel();
        final AbstractButton manual = naTela(() -> botao(bancada, "MODO MANUAL"));
        naTela(() -> {
            manual.doClick(0);
            return null;
        });
        verificar("MODO MANUAL liga a bancada na sessão", esperar(() -> sessao.getBancada() != null && sessao.getBancada().estaManual(), ESPERA_MS));
        final JSlider rpm = naTela(() -> todos(bancada, JSlider.class).get(0));
        naTela(() -> {
            rpm.setValue(3000);
            return null;
        });
        verificar("a ECU 'sente' os 3000 rpm do controle",
                esperar(() -> Math.abs(sessao.getConexao().getUltimaTelemetria().getRpm() - 3000) <= 200, ESPERA_MS));
        naTela(() -> {
            rpm.setValue(7700);
            return null;
        });
        verificar("acima do limite (7300 rpm) o corte atua", esperar(() -> temFlag(Protocolo.FLAG_CORTE), ESPERA_MS));
        naTela(() -> {
            rpm.setValue(0);
            manual.doClick(0);
            return null;
        });
        verificar("desligar o MODO MANUAL devolve o simulador ao ciclo automático",
                esperar(() -> sessao.getBancada() != null && !sessao.getBancada().estaManual(), ESPERA_MS));
        naTela(() -> {
            bancada.setVisible(false);
            return null;
        });
    }

    private static void testarRedimensionamento() throws Exception {
        titulo("Janela: redimensionamento (aba PARÂMETROS reorganiza as colunas)");
        janela.selecionarAba(2);
        final PainelParametros painel = naTela(() -> unico(janela, PainelParametros.class));
        final JScrollPane rolagem = naTela(() -> unico(painel, JScrollPane.class));

        naTela(() -> {
            janela.setSize(1160, 700);
            return null;
        });
        Thread.sleep(700);
        int alturaEstreita = naTela(() -> rolagem.getViewport().getView().getPreferredSize().height);
        verificar("com 1160 px não há rolagem horizontal", !naTela(() -> rolagem.getHorizontalScrollBar().isVisible()));

        naTela(() -> {
            janela.setSize(1500, 900);
            return null;
        });
        Thread.sleep(700);
        int alturaLarga = naTela(() -> rolagem.getViewport().getView().getPreferredSize().height);
        verificar("com 1500 px não há rolagem horizontal", !naTela(() -> rolagem.getHorizontalScrollBar().isVisible()));
        verificar("na janela larga os grupos ocupam menos linhas (mais colunas)", alturaLarga < alturaEstreita);

        naTela(() -> {
            janela.setSize(1340, 840);
            return null;
        });
        Thread.sleep(300);
        for (int aba = 0; aba < 5; aba++) {
            final int indice = aba;
            naTela(() -> {
                janela.selecionarAba(indice);
                return null;
            });
            Thread.sleep(150);
        }
        verificar("percorrer todas as abas em dois tamanhos não gera erro na tela", errosDaTela.isEmpty());
        janela.selecionarAba(0);
    }

    /** Reconectar com alterações pendentes: a pergunta aparece e as duas respostas funcionam. */
    private static void testarReconexaoComAlteracoes() throws Exception {
        titulo("Reconectar com alterações pendentes");
        final TabelaMapa tabela = naTela(() -> unico(janela, TabelaMapa.class));
        final TipoMapa tipo = naTela(() -> tabela.getMapa().getTipo());

        // 1) "MANTER MINHAS ALTERAÇÕES"
        final int antes = naTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(3, 3));
        final int novo = antes + 2 <= 255 ? antes + 2 : antes - 2;
        naTela(() -> {
            tabela.getModel().setValueAt(Double.valueOf(tipo.rawParaFisico(novo)), 3, 4);
            return null;
        });
        verificar("alteração local pendente antes de desconectar", naTela(sessao::isAlterada));
        clicar("DESCONECTAR");
        verificar("desconecta", esperar(() -> sessao.getEstado() == EstadoConexao.DESCONECTADO, ESPERA_MS));
        verificar("a alteração pendente continua na tela depois de desconectar", naTela(sessao::isAlterada));

        clicar("CONECTAR");
        verificar("ao reconectar, a pergunta sobre as alterações aparece", esperar(() -> dialogoVisivel("Conectado") != null, ESPERA_MS));
        final JDialog perguntaManter = dialogoVisivel("Conectado");
        if (perguntaManter != null) {
            naTela(() -> {
                botao(perguntaManter, "MANTER MINHAS ALTERAÇÕES").doClick(0);
                return null;
            });
        }
        verificar("MANTER: a calibração da tela continua com o valor editado",
                esperarNaTela(() -> sessao.getCalibracao().getMapa(tipo).getRaw(3, 3) == novo, ESPERA_MS));
        verificar("MANTER: a referência passa a ser o que a ECU tem de verdade (limite de fábrica de volta a 6500)",
                esperarNaTela(() -> sessao.getCalibracaoEcu().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6500, ESPERA_MS));
        verificar("MANTER: o aviso de alterações continua aceso (a tela difere da ECU)", naTela(sessao::isAlterada));
        clicar("ENVIAR TUDO");
        verificar("ENVIAR TUDO iguala a ECU à tela",
                esperarNaTela(() -> !sessao.isAlterada()
                        && sessao.getCalibracaoEcu().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 7300, ESPERA_MS));

        // 2) "LER DA ECU E DESCARTAR"
        naTela(() -> {
            tabela.getModel().setValueAt(Double.valueOf(tipo.rawParaFisico(antes)), 3, 4);
            return null;
        });
        verificar("nova alteração local pendente", naTela(sessao::isAlterada));
        clicar("DESCONECTAR");
        verificar("desconecta de novo", esperar(() -> sessao.getEstado() == EstadoConexao.DESCONECTADO, ESPERA_MS));
        clicar("CONECTAR");
        verificar("a pergunta aparece outra vez", esperar(() -> dialogoVisivel("Conectado") != null, ESPERA_MS));
        final JDialog perguntaDescartar = dialogoVisivel("Conectado");
        if (perguntaDescartar != null) {
            naTela(() -> {
                botao(perguntaDescartar, "LER DA ECU E DESCARTAR").doClick(0);
                return null;
            });
        }
        verificar("DESCARTAR: a tela passa a ter o que a ECU tem (limite de fábrica 6500)",
                esperarNaTela(() -> sessao.getCalibracao().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6500, ESPERA_MS));
        verificar("DESCARTAR: nada fica alterado", esperarNaTela(() -> !sessao.isAlterada(), ESPERA_MS));
    }

    private static void testarDesconexao() throws Exception {
        titulo("Desconectar pelo botão");
        verificar("está conectado antes de desconectar", sessao.getEstado() == EstadoConexao.CONECTADO);
        clicar("BANCADA...");
        verificar("a bancada abre de novo", esperar(() -> bancadaVisivel() != null, ESPERA_MS));
        final JanelaBancada bancada = bancadaVisivel();
        clicar("DESCONECTAR");
        verificar("desconecta", esperar(() -> sessao.getEstado() == EstadoConexao.DESCONECTADO, ESPERA_MS));
        verificar("a janela da bancada é fechada junto", esperarNaTela(() -> bancada == null || !bancada.isDisplayable(), ESPERA_MS));
        verificar("de volta ao estado desconectado: botões de operação bloqueados",
                esperarNaTela(() -> !botao(janela, "TWO-STEP").isEnabled() && !botao(janela, "ENVIAR TUDO").isEnabled(), ESPERA_MS));
        verificar("o botão volta a dizer CONECTAR", existeBotao("CONECTAR"));
    }

    /** Se o firmware em C do PC estiver compilado, ele aparece na lista e a bancada abre sozinha em marcha lenta. */
    private static void testarFirmwareDoPc() throws Exception {
        titulo("Firmware em C rodando no PC (se estiver compilado)");
        final JComboBox<?> portas = naTela(TesteInterface::comboDePortas);
        final int indice = naTela(() -> {
            for (int i = 0; i < portas.getItemCount(); i++) {
                if (String.valueOf(portas.getItemAt(i)).toLowerCase().contains("firmware")) {
                    return i;
                }
            }
            return -1;
        });
        if (indice < 0) {
            System.out.println("  (ignorado: ecu_host não está compilado; rode make em firmware/host)");
            return;
        }
        naTela(() -> {
            portas.setSelectedIndex(indice);
            return null;
        });
        clicar("CONECTAR");
        verificar("conecta ao firmware em C", esperar(() -> sessao.getEstado() == EstadoConexao.CONECTADO, ESPERA_MS));
        verificar("a bancada abre sozinha (o firmware do PC só anda com ela)", esperar(() -> bancadaVisivel() != null, ESPERA_MS));
        verificar("o firmware em C parte em marcha lenta (cerca de 850 rpm)",
                esperar(() -> {
                    int rpm = sessao.getConexao().getUltimaTelemetria().getRpm();
                    return rpm >= 700 && rpm <= 1000;
                }, ESPERA_MS));
        verificar("o firmware em C declara o modo sombra", esperar(() -> temFlag(Protocolo.FLAG_MODO_SOMBRA), ESPERA_MS));
        verificar("a calibração é lida do firmware em C", esperarNaTela(() -> !sessao.isAlterada(), ESPERA_MS));
        clicar("DESCONECTAR");
        verificar("desconecta do firmware em C", esperar(() -> sessao.getEstado() == EstadoConexao.DESCONECTADO, ESPERA_MS));
        verificar("o processo do firmware em C é encerrado", esperar(() -> !existeFilhoEcuHost(), ESPERA_MS));
    }

    private static void testarEncerramento(ByteArrayOutputStream saidaDeErro) throws Exception {
        titulo("Encerramento");
        naTela(() -> {
            for (Window w : Window.getWindows()) {
                w.dispose();
            }
            return null;
        });
        sessao.fechar();
        verificar("nenhuma thread 'ecu-*' sobra depois de fechar tudo", esperar(() -> threadsEcuVivas().isEmpty(), ESPERA_MS));
        if (!threadsEcuVivas().isEmpty()) {
            System.out.println("         sobraram: " + threadsEcuVivas());
        }
        verificar("nenhuma exceção não tratada na thread da tela", errosDaTela.isEmpty());
        if (!errosDaTela.isEmpty()) {
            System.out.println("         " + errosDaTela);
        }
        String erros;
        synchronized (saidaDeErro) {
            erros = new String(saidaDeErro.toByteArray(), StandardCharsets.UTF_8);
        }
        verificar("nada de exceção escrito na saída de erro", !erros.contains("Exception"));
    }

    // ------------------------------------------------------------------ achar componentes

    private static <T extends Component> List<T> todos(Container raiz, Class<T> tipo) {
        List<T> lista = new ArrayList<>();
        coletar(raiz, tipo, lista);
        return lista;
    }

    private static <T extends Component> void coletar(Component componente, Class<T> tipo, List<T> lista) {
        if (tipo.isInstance(componente)) {
            lista.add(tipo.cast(componente));
        }
        if (componente instanceof Container) {
            for (Component filho : ((Container) componente).getComponents()) {
                coletar(filho, tipo, lista);
            }
        }
    }

    private static <T extends Component> T unico(Container raiz, Class<T> tipo) {
        List<T> lista = todos(raiz, tipo);
        if (lista.isEmpty()) {
            throw new IllegalStateException("componente não encontrado: " + tipo.getSimpleName());
        }
        return lista.get(0);
    }

    private static AbstractButton botao(Container raiz, String texto) {
        for (AbstractButton b : todos(raiz, AbstractButton.class)) {
            if (texto.equals(b.getText())) {
                return b;
            }
        }
        throw new IllegalStateException("botão não encontrado: " + texto);
    }

    private static boolean existeBotao(String texto) throws Exception {
        return naTela(() -> {
            for (AbstractButton b : todos(janela, AbstractButton.class)) {
                if (texto.equals(b.getText())) {
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean habilitado(String texto) throws Exception {
        return naTela(() -> botao(janela, texto).isEnabled());
    }

    private static void clicar(String texto) throws Exception {
        naTela(() -> {
            AbstractButton b = botao(janela, texto);
            if (!b.isEnabled()) {
                throw new IllegalStateException("o botão " + texto + " está bloqueado");
            }
            b.doClick(0);
            return null;
        });
    }

    private static JComboBox<?> comboDePortas() {
        for (JComboBox<?> combo : todos(janela, JComboBox.class)) {
            // o outro combo da barra é o da velocidade (números); o das portas tem opções de texto
            if (combo.getItemCount() == 0 || !(combo.getItemAt(0) instanceof Integer)) {
                return combo;
            }
        }
        throw new IllegalStateException("lista de portas não encontrada");
    }

    private static JSpinner spinnerDoCampo(String rotulo) {
        for (JLabel l : todos(janela, JLabel.class)) {
            if (rotulo.equals(l.getText())) {
                Component[] irmaos = l.getParent().getComponents();
                for (int i = 0; i < irmaos.length - 1; i++) {
                    if (irmaos[i] == l && irmaos[i + 1] instanceof JSpinner) {
                        return (JSpinner) irmaos[i + 1];
                    }
                }
            }
        }
        throw new IllegalStateException("campo não encontrado: " + rotulo);
    }

    /** O texto da aba REGISTRO. Só pode ser chamado de dentro da thread da tela (dentro de naTela). */
    private static String registro() {
        return unico(unico(janela, PainelRegistro.class), JTextArea.class).getText();
    }

    private static int contar(String texto, String trecho) {
        int n = 0;
        int pos = texto.indexOf(trecho);
        while (pos >= 0) {
            n++;
            pos = texto.indexOf(trecho, pos + trecho.length());
        }
        return n;
    }

    private static JanelaBancada bancadaVisivel() {
        for (Window w : Window.getWindows()) {
            if (w instanceof JanelaBancada && w.isVisible()) {
                return (JanelaBancada) w;
            }
        }
        return null;
    }

    private static JDialog dialogoVisivel(String titulo) {
        for (Window w : Window.getWindows()) {
            if (w instanceof JDialog && w.isVisible() && titulo.equals(((JDialog) w).getTitle())) {
                return (JDialog) w;
            }
        }
        return null;
    }

    private static boolean temFlag(int mascara) {
        return sessao.getConexao().getUltimaTelemetria().temFlag(mascara);
    }

    private static boolean existeFilhoEcuHost() {
        return ProcessHandle.current().children()
                .anyMatch(p -> p.info().command().map(c -> c.endsWith("ecu_host")).orElse(false));
    }

    private static List<String> threadsEcuVivas() {
        List<String> nomes = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("ecu-") && !t.getName().equals("ecu-fechar")) {
                nomes.add(t.getName());
            }
        }
        return nomes;
    }

    // ------------------------------------------------------------------ thread da tela e espera

    private static <T> T naTela(Callable<T> tarefa) throws Exception {
        final AtomicReference<T> resultado = new AtomicReference<>();
        final AtomicReference<Throwable> erro = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                resultado.set(tarefa.call());
            } catch (Throwable t) {
                erro.set(t);
            }
        });
        if (erro.get() != null) {
            throw new RuntimeException(erro.get());
        }
        return resultado.get();
    }

    private static boolean esperar(BooleanSupplier condicao, long limiteMs) throws InterruptedException {
        long fim = System.currentTimeMillis() + limiteMs;
        while (System.currentTimeMillis() < fim) {
            if (condicao.getAsBoolean()) {
                return true;
            }
            Thread.sleep(40);
        }
        return condicao.getAsBoolean();
    }

    private static boolean esperarNaTela(Callable<Boolean> condicao, long limiteMs) throws Exception {
        long fim = System.currentTimeMillis() + limiteMs;
        while (System.currentTimeMillis() < fim) {
            if (naTela(condicao)) {
                return true;
            }
            Thread.sleep(40);
        }
        return naTela(condicao);
    }

    /** Copia tudo o que for escrito em System.err para um buffer, para o teste conferir no fim. */
    private static ByteArrayOutputStream espiarSaidaDeErro() {
        final ByteArrayOutputStream capturado = new ByteArrayOutputStream();
        final PrintStream original = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                original.write(b);
                synchronized (capturado) {
                    capturado.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                original.write(b, off, len);
                synchronized (capturado) {
                    capturado.write(b, off, len);
                }
            }
        }, true));
        return capturado;
    }

    // ------------------------------------------------------------------ relatório

    private static void titulo(String nome) {
        System.out.println(nome);
    }

    private static void verificar(String nome, boolean condicao) {
        total++;
        System.out.println("  " + (condicao ? "OK     " : "FALHOU ") + nome);
        if (!condicao) {
            falhas++;
        }
    }
}
