package kadettecu;

import javax.swing.SwingUtilities;
import kadettecu.analise.ControladorECU;
import kadettecu.analise.InterfaceConsole;
import kadettecu.analise.JanelaPrincipal;
import kadettecu.app.SessaoECU;
import kadettecu.ui.JanelaECU;
import kadettecu.ui.Tema;

/**
 * Ponto de entrada do KadettECU (era o {@code inicio.java}). Só cria os objetos e liga uns aos outros.
 *
 * <p>Sem argumentos abre o painel novo (instrumentos, mapas, parâmetros...). Os modos antigos continuam
 * disponíveis: {@code --legado} abre exatamente o que o programa fazia antes (a janela do analisador de
 * binário mais o menu no console) e {@code --console} usa só o console.
 */
public final class Inicio {

    public static final String VERSAO = JanelaECU.VERSAO;

    private Inicio() {
    }

    public static void main(String[] args) {
        System.out.println("KadettECU");
        System.out.println("Versão " + VERSAO);

        String modo = args.length > 0 ? args[0] : "";
        switch (modo) {
            case "":
                iniciarPainel(false);
                break;
            case "--simulador":
                iniciarPainel(true);
                break;
            case "--legado":
                iniciarLegado();
                break;
            case "--console":
                iniciarConsole();
                break;
            default:
                imprimirAjuda();
                break;
        }
    }

    /** O programa novo: janela com instrumentos, editor de mapas, parâmetros, analisador de binário e registro. */
    private static void iniciarPainel(boolean conectarAoSimulador) {
        SwingUtilities.invokeLater(() -> {
            Tema.instalar();
            SessaoECU sessao = new SessaoECU(SwingUtilities::invokeLater);
            // Se alguém fechar o programa de outro jeito (Ctrl+C no terminal, por exemplo), a porta ainda é liberada.
            Runtime.getRuntime().addShutdownHook(new Thread(sessao::fechar, "ecu-gancho"));
            JanelaECU janela = new JanelaECU(sessao, () -> System.exit(0));
            janela.setVisible(true);
            if (conectarAoSimulador) {
                janela.conectarSimulador();
            }
        });
    }

    /** O comportamento de antes da interface nova: janela do analisador + menu no console. */
    private static void iniciarLegado() {
        ControladorECU controlador = new ControladorECU();
        JanelaPrincipal janela = new JanelaPrincipal(controlador);
        InterfaceConsole console = new InterfaceConsole(controlador);

        controlador.adicionarExibidor(janela);
        controlador.adicionarExibidor(console);

        janela.setVisible(true);
        console.iniciar();
    }

    private static void iniciarConsole() {
        ControladorECU controlador = new ControladorECU();
        InterfaceConsole console = new InterfaceConsole(controlador);
        controlador.adicionarExibidor(console);
        console.iniciar();
    }

    private static void imprimirAjuda() {
        System.out.println();
        System.out.println("Uso: java -cp ... kadettecu.Inicio [opção]");
        System.out.println("  (sem opção)   abre o painel completo (instrumentos, mapas, parâmetros, binário, registro)");
        System.out.println("  --simulador   abre o painel já conectado ao simulador em Java (não precisa de placa)");
        System.out.println("  --legado      abre o analisador de binário original (janela + console)");
        System.out.println("  --console     usa só o analisador de binário no console");
        System.out.println("  --ajuda       mostra esta mensagem");
        System.out.println();
        System.out.println("Para usar o firmware em C no PC: compile firmware/host (make) ou informe o caminho com");
        System.out.println("  -Dkadettecu.ecuHost=/caminho/para/ecu_host");
    }
}
