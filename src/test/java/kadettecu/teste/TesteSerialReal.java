package kadettecu.teste;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import kadettecu.comunicacao.ConexaoECU;
import kadettecu.comunicacao.ErroProtocolo;
import kadettecu.comunicacao.PortaJSerialComm;
import kadettecu.modelo.Calibracao;
import kadettecu.modelo.CampoParametro;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.InfoECU;
import kadettecu.modelo.Telemetria;
import kadettecu.modelo.TipoMapa;
import kadettecu.protocolo.Protocolo;

/**
 * Teste da porta serial <b>real</b> (a classe {@link PortaJSerialComm}, que usa a biblioteca jSerialComm), sem placa.
 *
 * <p>Os outros testes ligam o aplicativo a uma porta simulada ou a um processo (stdin/stdout). Aqui o aplicativo abre uma
 * porta serial de verdade, aquela que o sistema operacional chama de dispositivo (por exemplo, {@code /dev/pts/3}): um
 * <i>pseudo-terminal</i> criado por {@code firmware/host/ponte_pty.py} e ligado ao {@code ecu_host}, o firmware em C
 * rodando no PC. Assim passam pelo caminho completo, sem nenhum "atalho": a biblioteca nativa do jSerialComm, a
 * configuração da porta (115200 8N1, tempos de leitura e escrita), a abertura, a leitura e a escrita em bytes, o
 * fechamento e a queda do "cabo" (a ponte é encerrada).
 *
 * <p>Só roda em Linux e macOS (pseudo-terminais) e precisa de Python 3 e do {@code ecu_host}. Sem isso, ou se o sistema não
 * deixar o jSerialComm abrir o pseudo-terminal, o teste é IGNORADO (código de saída 0) com uma mensagem. Ele <b>não</b>
 * prova que uma placa real funciona: ela tem reinício automático ao abrir a porta, ruído, cabo etc. (veja
 * {@code docs/HARDWARE.md}, "O que ainda precisa ser validado").
 */
public class TesteSerialReal {

    private static final int BAUD = 115200;

    private static int falhas = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        String so = System.getProperty("os.name", "").toLowerCase();
        if (so.contains("win")) {
            ignorado("pseudo-terminais só existem em Linux e macOS (no Windows, teste com a placa ou com um par de portas virtuais)");
            return;
        }
        Path ecuHost = localizarEcuHost(args.length > 0 ? args[0] : null);
        if (ecuHost == null) {
            ignorado("não achei o firmware compilado para PC (make -C firmware/host all)");
            return;
        }
        Path ponte = Paths.get("firmware/host/ponte_pty.py");
        if (!Files.isRegularFile(ponte)) {
            ignorado("não achei " + ponte);
            return;
        }
        String python = localizarPython();
        if (python == null) {
            ignorado("não achei o Python 3 (python3) no PATH");
            return;
        }
        System.out.println("Firmware sob teste: " + ecuHost);

        Boolean abriu = motorGirando(python, ponte, ecuHost);
        if (abriu == null) {
            return; // ignorado: o sistema não deixou o jSerialComm abrir o pseudo-terminal
        }
        motorParadoEQuedaDoCabo(python, ponte, ecuHost);

        System.out.println();
        if (falhas == 0) {
            System.out.println("Todos os testes da porta serial real passaram (" + total + " verificações).");
        } else {
            System.out.println("Testes da porta serial real com falha: " + falhas + " de " + total);
            System.exit(1);
        }
    }

    // ================================================================== cenários

    /** Motor a 3000 rpm: telemetria contínua, leitura e escrita da calibração e as recusas do firmware. */
    private static Boolean motorGirando(String python, Path ponte, Path ecuHost) throws Exception {
        titulo("porta real, motor girando a 3000 rpm");
        try (Ponte p = new Ponte(python, ponte, ecuHost, "--rpm", "3000", "--tps", "25", "--ect", "85", "--map", "55")) {
            PortaJSerialComm sonda = new PortaJSerialComm(p.dispositivo, BAUD);
            try {
                sonda.abrir();
                sonda.fechar();
            } catch (IOException e) {
                ignorado("o jSerialComm não conseguiu abrir " + p.dispositivo + " (" + e.getMessage() + ")");
                return null;
            }

            PortaJSerialComm porta = new PortaJSerialComm(p.dispositivo, BAUD);
            ConexaoECU con = new ConexaoECU();
            InfoECU info = con.conectar(porta);
            verificar("conectou pela porta real: " + info, info.getVersaoProtocolo() == Protocolo.VERSAO);
            verificar("firmware em modo sombra (sem saídas reais)", !info.temSaidasReais());
            verificar("estado CONECTADO e porta aberta", con.getEstado() == EstadoConexao.CONECTADO && porta.estaAberta());
            verificar("a telemetria sincronizada chega",
                    esperar(() -> con.getUltimaTelemetria().temFlag(Protocolo.FLAG_SYNC)
                            && Math.abs(con.getUltimaTelemetria().getRpm() - 3000) < 40, 4000));
            Thread.sleep(1500);
            Telemetria t = con.getUltimaTelemetria();
            perto("rpm", t.getRpm(), 3000, 20);
            perto("TPS (%)", t.getTpsPct(), 25, 1.0);
            perto("ECT (°C)", t.getEctC(), 85, 1.0);
            perto("MAP (kPa)", t.getMapKpa(), 55, 1.0);
            perto("taxa de telemetria a 20 ms (quadros/s)", con.getTaxaTelemetria(), 50, 15);
            verificar("nenhum erro de CRC (" + con.getErrosCrc() + ")", con.getErrosCrc() == 0);
            verificar("pacotes válidos recebidos (" + con.getPacotesValidos() + ")", con.getPacotesValidos() > 40);

            AtomicInteger passos = new AtomicInteger();
            Calibracao lida = con.lerCalibracao((feitos, tot) -> passos.set(feitos));
            verificar("lerCalibracao: 50 passos de progresso (" + passos.get() + ")", passos.get() == 50);
            verificar("calibração lida é a de fábrica (limite 6500 rpm)",
                    lida.getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6500);
            lida.getParametros().set(CampoParametro.REV_LIMITE_RPM, 7000);
            lida.getMapa(TipoMapa.COMBUSTIVEL).setRaw(3, 4, 77);
            con.escreverCalibracao(lida, null);
            Calibracao reler = con.lerCalibracao(null);
            verificar("escrever e ler de volta pela porta real",
                    reler.getParametros().get(CampoParametro.REV_LIMITE_RPM) == 7000
                            && reler.getMapa(TipoMapa.COMBUSTIVEL).getRaw(3, 4) == 77);

            verificar("GRAVAR com o motor girando é recusado (NACK 4)", recusa(() -> con.gravarNaMemoria(), Protocolo.ERRO_MOTOR_GIRANDO));
            verificar("ARMAR em firmware de modo sombra é recusado (NACK 6)",
                    recusa(() -> con.armarSaidas(true), Protocolo.ERRO_SAIDAS_DESABILITADAS));

            con.definirChaves(3);
            verificar("as chaves enviadas pelo aplicativo aparecem na telemetria",
                    esperar(() -> con.getUltimaTelemetria().temFlag(Protocolo.FLAG_CHAVE_TWO_STEP)
                            && con.getUltimaTelemetria().temFlag(Protocolo.FLAG_CHAVE_LAUNCH), 2000));
            con.definirChaves(0);

            con.definirPeriodoTelemetria(100);
            Thread.sleep(2300);
            perto("taxa depois de pedir 100 ms (quadros/s)", con.getTaxaTelemetria(), 10, 4);
            con.definirPeriodoTelemetria(ConexaoECU.PERIODO_TELEMETRIA_PADRAO_MS);

            con.desconectar();
            verificar("desconectar: estado DESCONECTADO e porta fechada",
                    con.getEstado() == EstadoConexao.DESCONECTADO && !porta.estaAberta());

            // reabrir a mesma porta depois de fechada
            InfoECU de_novo = con.conectar(new PortaJSerialComm(p.dispositivo, BAUD));
            verificar("reconectou na mesma porta: " + de_novo, con.getEstado() == EstadoConexao.CONECTADO);
            con.desconectar();
        }
        return Boolean.TRUE;
    }

    /** Motor parado: o que só pode mudar parado, GRAVAR, restaurar os padrões e o cabo arrancado. */
    private static void motorParadoEQuedaDoCabo(String python, Path ponte, Path ecuHost) throws Exception {
        titulo("porta real, motor parado, e o cabo arrancado");
        try (Ponte p = new Ponte(python, ponte, ecuHost, "--rpm", "0", "--tps", "0", "--ect", "85", "--map", "100")) {
            PortaJSerialComm porta = new PortaJSerialComm(p.dispositivo, BAUD);
            ConexaoECU con = new ConexaoECU();
            con.conectar(porta);
            verificar("conectado com o motor parado", con.getEstado() == EstadoConexao.CONECTADO);
            Thread.sleep(500);
            Telemetria t = con.getUltimaTelemetria();
            verificar("rpm zero e sem a flag de motor girando", t.getRpm() == 0 && !t.temFlag(Protocolo.FLAG_MOTOR_GIRANDO));

            Calibracao cal = con.lerCalibracao(null);
            cal.getParametros().set(CampoParametro.PMS_OFFSET_X10, 900); // campo "só parado"
            cal.getParametros().set(CampoParametro.REV_LIMITE_RPM, 7100);
            con.escreverCalibracao(cal, null);
            Calibracao reler = con.lerCalibracao(null);
            verificar("com o motor parado aceita mudar o sinal da roda (PMS 90,0°)",
                    reler.getParametros().get(CampoParametro.PMS_OFFSET_X10) == 900
                            && reler.getParametros().get(CampoParametro.REV_LIMITE_RPM) == 7100);
            con.gravarNaMemoria();
            con.restaurarPadroes();
            verificar("restaurarPadroes devolve os valores de fábrica",
                    con.lerCalibracao(null).getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6500);

            p.arrancarCabo();
            // No Linux, o fim do pseudo-terminal faz a leitura devolver -1 e a conexão cai logo em ERRO (verificado: ~50 ms).
            // Em outros sistemas a leitura pode só ficar muda, e então vale o "sem sinal" depois de 1 s.
            boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
            long inicioQueda = System.nanoTime();
            boolean percebeu = esperar(() -> con.getEstado() == EstadoConexao.ERRO
                    || (!linux && con.getEstado() == EstadoConexao.SEM_SINAL), 4000);
            long msQueda = (System.nanoTime() - inicioQueda) / 1_000_000L;
            System.out.println("  queda do cabo percebida em " + msQueda + " ms (estado " + con.getEstado() + ")");
            verificar("a queda do cabo é percebida", percebeu);
            if (linux) {
                // A leitura devolve -1 no mesmo instante. Se só a escrita do STREAM (a cada 1 s, depois de 1 s sem telemetria)
                // notasse a queda, isto levaria mais de 1 s: seria sinal de que a leitura deixou de avisar o erro.
                verificar("a leitura avisa a queda na hora, sem esperar o segundo sem telemetria (" + msQueda + " ms)", msQueda < 900);
            }
            con.desconectar();
            verificar("desconectar depois da queda deixa a conexão limpa", con.getEstado() == EstadoConexao.DESCONECTADO);
        }
    }

    // ================================================================== infraestrutura

    /** A ponte com o pseudo-terminal: um processo Python que mostra o nome do dispositivo e liga-o ao ecu_host. */
    private static final class Ponte implements AutoCloseable {
        private final Process processo;
        final String dispositivo;

        Ponte(String python, Path ponte, Path ecuHost, String... argumentosDoEcu) throws IOException {
            List<String> comando = new ArrayList<>();
            comando.add(python);
            comando.add("-I");
            comando.add(ponte.toString());
            comando.add(ecuHost.toString());
            comando.addAll(Arrays.asList(argumentosDoEcu));
            processo = new ProcessBuilder(comando).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            BufferedReader saida = new BufferedReader(new InputStreamReader(processo.getInputStream(), StandardCharsets.UTF_8));
            CompletableFuture<String> primeiraLinha = CompletableFuture.supplyAsync(() -> {
                try {
                    return saida.readLine();
                } catch (IOException e) {
                    return null;
                }
            });
            String nome;
            try {
                nome = primeiraLinha.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                processo.destroyForcibly();
                throw new IOException("a ponte do pseudo-terminal não respondeu", e);
            }
            if (nome == null || nome.trim().isEmpty()) {
                processo.destroyForcibly();
                throw new IOException("a ponte do pseudo-terminal terminou sem informar o dispositivo");
            }
            dispositivo = nome.trim();
        }

        /** Equivale a arrancar o cabo USB: o lado "escravo" passa a dar erro de leitura. */
        void arrancarCabo() throws InterruptedException {
            processo.destroyForcibly();
            processo.waitFor(3, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            try {
                processo.getOutputStream().close(); // entrada fechada = sinal para a ponte terminar
            } catch (IOException e) {
                // já terminou
            }
            try {
                if (!processo.waitFor(3, TimeUnit.SECONDS)) {
                    processo.destroyForcibly();
                }
            } catch (InterruptedException e) {
                processo.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean recusa(Runnable acao, int codigoEsperado) {
        try {
            acao.run();
            return false;
        } catch (ErroProtocolo e) {
            return e.getCodigo() == codigoEsperado;
        }
    }

    private static Path localizarEcuHost(String indicado) {
        // mesma busca do TesteIntegracaoFirmware: argumento, propriedade, pasta padrão e, por último, compilar com make
        String[] candidatos = {indicado, System.getProperty("kecu.ecu_host"), "firmware/host/build/ecu_host"};
        for (String c : candidatos) {
            if (c != null && Files.isExecutable(Paths.get(c))) {
                return Paths.get(c).toAbsolutePath();
            }
        }
        Path dir = Paths.get("firmware/host");
        if (Files.isDirectory(dir)) {
            try {
                Process make = new ProcessBuilder("make", "-C", dir.toString(), "all")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (make.waitFor(120, TimeUnit.SECONDS) && make.exitValue() == 0) {
                    Path exe = dir.resolve("build/ecu_host");
                    if (Files.isExecutable(exe)) {
                        return exe.toAbsolutePath();
                    }
                }
            } catch (IOException | InterruptedException e) {
                // sem make: segue para "ignorado"
            }
        }
        return null;
    }

    private static String localizarPython() {
        for (String nome : new String[] {"python3", "python"}) {
            try {
                Process p = new ProcessBuilder(nome, "-c", "import pty, sys; sys.exit(0 if sys.version_info[0] >= 3 else 1)")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return nome;
                }
            } catch (IOException | InterruptedException e) {
                // este nome não existe: tenta o próximo
            }
        }
        return null;
    }

    private static void ignorado(String motivo) {
        System.out.println("Teste da porta serial real IGNORADO: " + motivo + ".");
    }

    private static void titulo(String texto) {
        System.out.println("[" + texto + "]");
    }

    private static void verificar(String descricao, boolean condicao) {
        total++;
        if (!condicao) {
            falhas++;
            System.out.println("  FALHOU: " + descricao);
        }
    }

    private static void perto(String descricao, double valor, double esperado, double tolerancia) {
        total++;
        if (!(Math.abs(valor - esperado) <= tolerancia)) {
            falhas++;
            System.out.printf("  FALHOU: %s = %.3f, esperado %.3f +- %.3f%n", descricao, valor, esperado, tolerancia);
        }
    }

    private static boolean esperar(BooleanSupplier condicao, long ms) throws InterruptedException {
        long fim = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < fim) {
            if (condicao.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condicao.getAsBoolean();
    }
}
