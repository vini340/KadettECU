package kadettecu.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kadettecu.comunicacao.PortaJSerialComm;
import kadettecu.comunicacao.PortaProcesso;
import kadettecu.comunicacao.PortaSimulada;
import kadettecu.simulador.SimuladorECU;

/**
 * Uma das maneiras de falar com a ECU que aparecem na lista de portas da tela: o simulador em Java,
 * o firmware em C rodando no PC, ou uma porta USB/serial de verdade. Cada tipo é uma subclasse que
 * sabe se abrir ({@link #abrir(int)}); a tela só trata todos como "uma opção".
 */
public abstract class OpcaoPorta {

    private final String rotulo;

    private OpcaoPorta(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }

    /** true se o tipo de ligação usa velocidade (baud): só a porta serial real. */
    public boolean usaBaud() {
        return false;
    }

    /** Cria a porta (ainda fechada) e a bancada de testes correspondente, se houver. */
    public abstract Ligacao abrir(int baud) throws IOException;

    /** O texto da lista suspensa. */
    @Override
    public String toString() {
        return rotulo;
    }

    // ------------------------------------------------------------------ fábricas

    public static OpcaoPorta simulador() {
        return new Simulador();
    }

    public static OpcaoPorta firmwarePc(Path binario) {
        return new FirmwarePc(binario);
    }

    public static OpcaoPorta serial(String nomeSistema, String descricao) {
        return new Serial(nomeSistema, descricao);
    }

    /**
     * Monta a lista de opções disponíveis agora. Demora um pouco (a biblioteca de serial precisa
     * consultar o sistema), então a tela chama isto fora da thread do Swing.
     */
    public static List<OpcaoPorta> listar() {
        List<OpcaoPorta> lista = new ArrayList<>();
        lista.add(simulador());
        Optional<Path> firmware = localizarFirmwarePc();
        if (firmware.isPresent()) {
            lista.add(firmwarePc(firmware.get()));
        }
        try {
            for (PortaJSerialComm.PortaEncontrada p : PortaJSerialComm.listarPortas()) {
                lista.add(serial(p.getNomeSistema(), p.getDescricao()));
            }
        } catch (RuntimeException | LinkageError e) {
            // a biblioteca nativa de serial não carregou neste computador: ficam só as opções virtuais
        }
        return lista;
    }

    /** Procura o programa {@code ecu_host} (o firmware compilado para o PC), se o usuário o tiver compilado. */
    public static Optional<Path> localizarFirmwarePc() {
        List<String> candidatos = new ArrayList<>();
        String indicado = System.getProperty("kadettecu.ecuHost");
        if (indicado != null && !indicado.isEmpty()) {
            candidatos.add(indicado);
        }
        for (String base : new String[] {"", "../", "../../"}) {
            candidatos.add(base + "firmware/host/build/ecu_host");
            candidatos.add(base + "firmware/host/build/ecu_host.exe");
        }
        for (String c : candidatos) {
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ tipos

    private static final class Simulador extends OpcaoPorta {

        Simulador() {
            super(PortaSimulada.NOME + " (ECU virtual em Java)");
        }

        @Override
        public Ligacao abrir(int baud) {
            SimuladorECU simulador = new SimuladorECU();
            return new Ligacao(new PortaSimulada(simulador), new BancadaSimulador(simulador.getCarro()));
        }
    }

    private static final class FirmwarePc extends OpcaoPorta {

        private final Path binario;

        FirmwarePc(Path binario) {
            super("FIRMWARE C (PC) - o mesmo código da placa");
            this.binario = binario;
        }

        @Override
        public Ligacao abrir(int baud) throws IOException {
            Path pasta = Path.of(System.getProperty("user.home"), ".kadettecu");
            Files.createDirectories(pasta);
            Path controle = Files.createTempFile("kadettecu-bancada-", ".txt");
            Bancada bancada = new BancadaArquivo(controle);
            List<String> comando = List.of(binario.toString(), "--ctl", controle.toString(), "--nvm",
                    pasta.resolve("ecu_host.nvm").toString());
            return new Ligacao(new PortaProcesso("FIRMWARE C (PC)", comando), bancada);
        }
    }

    private static final class Serial extends OpcaoPorta {

        private final String nomeSistema;

        Serial(String nomeSistema, String descricao) {
            super(nomeSistema + " - " + descricao);
            this.nomeSistema = nomeSistema;
        }

        @Override
        public boolean usaBaud() {
            return true;
        }

        @Override
        public Ligacao abrir(int baud) {
            return new Ligacao(new PortaJSerialComm(nomeSistema, baud), null);
        }
    }
}
