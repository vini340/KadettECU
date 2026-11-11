package kadettecu.teste;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import kadettecu.comunicacao.ConexaoECU;
import kadettecu.comunicacao.ErroProtocolo;
import kadettecu.comunicacao.PortaProcesso;
import kadettecu.modelo.Calibracao;
import kadettecu.modelo.CampoParametro;
import kadettecu.modelo.EixosMapa;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.InfoECU;
import kadettecu.modelo.MapaECU;
import kadettecu.modelo.ParametrosMotor;
import kadettecu.modelo.Telemetria;
import kadettecu.modelo.TipoMapa;
import kadettecu.protocolo.BufferLE;
import kadettecu.protocolo.DecodificadorPacotes;
import kadettecu.protocolo.Pacote;
import kadettecu.protocolo.Protocolo;
import kadettecu.simulador.SimuladorECU;

/**
 * Teste de integração: o aplicativo Java conversando com o <b>firmware de verdade</b>.
 *
 * <p>O núcleo em C (o mesmo código que roda na placa) é compilado para o PC como um programa,
 * {@code firmware/host/build/ecu_host}, que gera os dentes da roda fônica e os sensores e fala o
 * protocolo pela entrada/saída padrão. Aqui o Java o liga por uma {@link PortaProcesso} e confere:
 * <ul>
 *   <li>os valores padrão, as faixas dos parâmetros e as regras de cada comando são idênticos nos
 *       dois lados (tabelas, eixos, NACKs);</li>
 *   <li>o simulador Java e o firmware C respondem <i>exatamente igual</i> a centenas de comandos
 *       válidos e inválidos (teste diferencial);</li>
 *   <li>gravação na memória, restauração dos padrões e memória corrompida;</li>
 *   <li>a telemetria bate com as entradas, e o simulador calcula o mesmo pulso e avanço que o firmware;</li>
 *   <li>o limitador de giro tem a mesma histerese nos dois;</li>
 *   <li>a conexão detecta quando o programa morre.</li>
 * </ul>
 *
 * <p>Uso: {@code java ... TesteIntegracaoFirmware [caminho/do/ecu_host]}. Sem argumento, procura
 * {@code firmware/host/build/ecu_host} (e tenta compilar com {@code make} se não existir). Se o
 * programa não puder ser obtido, o teste é IGNORADO (código de saída 0) com uma mensagem.
 */
public class TesteIntegracaoFirmware {

    private static int falhas = 0;
    private static int total = 0;
    private static Path executavel;
    private static Path pasta;
    private static int contadorArquivos = 0;

    public static void main(String[] args) throws Exception {
        executavel = localizarExecutavel(args.length > 0 ? args[0] : null);
        if (executavel == null) {
            System.out.println("Teste de integração IGNORADO: não achei o firmware compilado para PC.");
            System.out.println("Compile com:  make -C firmware/host all   (precisa de gcc ou clang)");
            return;
        }
        System.out.println("Firmware sob teste: " + executavel);
        pasta = Files.createTempDirectory("kecu-integracao");
        try {
            testarIdentificacaoEPadroes();
            testarFaixasDosParametros();
            testarSemanticaParadoEGirando();
            testarDiferencialSimuladorFirmware();
            testarPersistencia();
            testarConexaoCompleta();
            testarParidadeDeCalculo();
            testarHistereseDoLimitador();
            testarQuedaDoProcesso();
        } finally {
            apagar(pasta);
        }

        System.out.println();
        if (falhas == 0) {
            System.out.println("Todos os testes de integração passaram (" + total + " verificações).");
        } else {
            System.out.println("Testes de integração com falha: " + falhas + " de " + total);
            System.exit(1);
        }
    }

    // ================================================================== infraestrutura

    private static Path localizarExecutavel(String indicado) {
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

    private static List<String> comando(String... argumentos) {
        List<String> c = new ArrayList<>();
        c.add(executavel.toString());
        c.addAll(Arrays.asList(argumentos));
        return c;
    }

    private static Path novoArquivo(String sufixo) {
        return pasta.resolve("arquivo" + (++contadorArquivos) + sufixo);
    }

    private static void apagar(Path raiz) {
        try (Stream<Path> arvore = Files.walk(raiz)) {
            arvore.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // faxina de melhor esforço
        }
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

    private static String hexa(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    private static String resumo(Pacote p) {
        return String.format("0x%02X[%s]", p.getTipo(), hexa(p.getPayload()));
    }

    private static boolean ehNack(Pacote p, int codigo) {
        return p.getTipo() == Protocolo.RSP_NACK && p.getPayload().length == 1 && (p.getPayload()[0] & 0xFF) == codigo;
    }

    private static boolean ehAck(Pacote p) {
        return p.getTipo() == Protocolo.RSP_ACK && p.getPayload().length == 0;
    }

    /** Arquivo de controle do ecu_host: dá para mudar rotação, TPS etc. com o programa rodando. */
    private static final class Controle {
        private final Path arquivo;
        private final Map<String, Double> valores = new LinkedHashMap<>();
        private long contador;

        Controle(Path arquivo) throws IOException {
            this.arquivo = arquivo;
            gravar();
        }

        void definir(String chave, double valor) throws IOException {
            valores.put(chave, valor);
            gravar();
        }

        private void gravar() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Double> e : valores.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.write(arquivo, sb.toString().getBytes(StandardCharsets.UTF_8));
            // cada gravação ganha uma data de modificação diferente, para o programa perceber a mudança
            Files.setLastModifiedTime(arquivo, FileTime.fromMillis(1_700_000_000_000L + (++contador) * 3000L));
        }

        String caminho() {
            return arquivo.toString();
        }
    }

    /** Alguém que responde a comandos do protocolo: o firmware (por processo) ou o simulador Java. */
    private interface Alvo {
        Pacote pedir(int tipo, byte[] payload) throws IOException;
    }

    /** Cliente "cru": fala o protocolo direto com o programa, sem passar pela ConexaoECU (assim dá para mandar também o que o aplicativo jamais mandaria). */
    private static final class Cru implements Alvo, AutoCloseable {
        final PortaProcesso porta;
        final DecodificadorPacotes decodificador = new DecodificadorPacotes();
        private int seq;
        private Telemetria ultimaTelemetria;

        Cru(String... argumentos) throws IOException {
            porta = new PortaProcesso("ecu_host (cru)", comando(argumentos));
            porta.abrir();
        }

        @Override
        public Pacote pedir(int tipo, byte[] payload) throws IOException {
            int s = ++seq & 0xFF;
            porta.escrever(new Pacote(tipo, s, payload).codificar());
            long fim = System.nanoTime() + 4_000_000_000L;
            byte[] buffer = new byte[1024];
            while (System.nanoTime() < fim) {
                int n = porta.ler(buffer);
                if (n < 0) {
                    throw new IOException("o programa terminou");
                }
                if (n == 0) {
                    continue;
                }
                for (Pacote p : decodificador.alimentar(buffer, n)) {
                    if (p.getTipo() == Protocolo.TELEMETRIA) {
                        ultimaTelemetria = Telemetria.deBytes(p.getPayload());
                    } else if (p.getSeq() == s) {
                        return p;
                    }
                }
            }
            throw new IOException("sem resposta ao comando 0x" + Integer.toHexString(tipo));
        }

        /** Liga a telemetria, espera chegar um quadro que satisfaça a condição e desliga de novo. */
        Telemetria telemetria(java.util.function.Predicate<Telemetria> condicao, long timeoutMs) throws IOException {
            pedir(Protocolo.CMD_STREAM, BufferLE.paraEscrita(2).escreverU16(10).bytes());
            ultimaTelemetria = null;
            long fim = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] buffer = new byte[2048];
            Telemetria achada = null;
            while (System.nanoTime() < fim && achada == null) {
                int n = porta.ler(buffer);
                if (n < 0) {
                    throw new IOException("o programa terminou");
                }
                for (Pacote p : decodificador.alimentar(buffer, Math.max(n, 0))) {
                    if (p.getTipo() == Protocolo.TELEMETRIA) {
                        Telemetria t = Telemetria.deBytes(p.getPayload());
                        if (condicao.test(t)) {
                            achada = t;
                        }
                    }
                }
            }
            pedir(Protocolo.CMD_STREAM, BufferLE.paraEscrita(2).escreverU16(0).bytes());
            return achada;
        }

        @Override
        public void close() {
            porta.fechar();
        }
    }

    /** O simulador Java respondendo aos mesmos comandos, sem threads nem porta. */
    private static final class AlvoSimulador implements Alvo {
        final SimuladorECU simulador;
        final DecodificadorPacotes decodificador = new DecodificadorPacotes();
        private int seq;

        AlvoSimulador(SimuladorECU simulador) {
            this.simulador = simulador;
        }

        @Override
        public Pacote pedir(int tipo, byte[] payload) throws IOException {
            int s = ++seq & 0xFF;
            byte[] quadro = new Pacote(tipo, s, payload).codificar();
            byte[] resposta = simulador.processar(quadro, quadro.length);
            for (Pacote p : decodificador.alimentar(resposta, resposta.length)) {
                if (p.getSeq() == s) {
                    return p;
                }
            }
            throw new IOException("o simulador não respondeu");
        }
    }

    private static byte[] u16(int ms) {
        return BufferLE.paraEscrita(2).escreverU16(ms).bytes();
    }

    /** Bloco de parâmetros a partir de valores brutos, SEM validar faixas (para mandar também o que é inválido). */
    private static byte[] blocoParametros(int[] valores) {
        BufferLE b = BufferLE.paraEscrita(ParametrosMotor.TAMANHO);
        for (CampoParametro c : CampoParametro.values()) {
            if (c.getTipo().getBytes() == 2) {
                b.escreverU16(valores[c.ordinal()]);
            } else {
                b.escreverU8(valores[c.ordinal()]);
            }
        }
        return b.bytes();
    }

    private static int[] valoresPadrao() {
        int[] v = new int[CampoParametro.values().length];
        for (CampoParametro c : CampoParametro.values()) {
            v[c.ordinal()] = c.getPadrao();
        }
        return v;
    }

    private static int maximoDoTipo(CampoParametro c) {
        return c.getTipo().getBytes() == 2 ? 65535 : 255;
    }

    // ================================================================== 1. identificação e padrões

    private static void testarIdentificacaoEPadroes() throws Exception {
        titulo("Identificação e valores padrão: firmware C x modelo Java");
        try (Cru ecu = new Cru("--rpm", "0")) {
            Pacote hello = ecu.pedir(Protocolo.CMD_HELLO, new byte[0]);
            verificar("HELLO devolve INFO (0x81) com 8 bytes", hello.getTipo() == Protocolo.RSP_INFO
                    && hello.getPayload().length == InfoECU.TAMANHO);
            InfoECU info = InfoECU.deBytes(hello.getPayload());
            verificar("versão do protocolo = " + Protocolo.VERSAO, info.getVersaoProtocolo() == Protocolo.VERSAO);
            verificar("mapas 16x16 e 3 tabelas", info.getTamanhoMapa() == Protocolo.TAMANHO_MAPA
                    && info.getNumMapas() == Protocolo.NUM_MAPAS);
            verificar("firmware 1.0 (" + info.getFirmware() + ")", "1.0".equals(info.getFirmware()));
            verificar("compilado em modo sombra: sem saídas reais", !info.temSaidasReais());
            verificar("placa 'Simulador / PC' (" + info.getNomePlaca() + ")", "Simulador / PC".equals(info.getNomePlaca()));
            int caps = (hello.getPayload()[6] & 0xFF) | ((hello.getPayload()[7] & 0xFF) << 8);
            verificar("anuncia memória não volátil", (caps & InfoECU.CAP_MEMORIA_NVM) != 0);

            Calibracao padrao = Calibracao.padrao();
            Pacote p = ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]);
            verificar("parâmetros: " + ParametrosMotor.TAMANHO + " bytes", p.getTipo() == Protocolo.RSP_PARAMS
                    && p.getPayload().length == ParametrosMotor.TAMANHO);
            verificar("parâmetros padrão do firmware = ParametrosMotor.padrao() (byte a byte)",
                    Arrays.equals(p.getPayload(), padrao.getParametros().paraBytes()));
            ParametrosMotor lidos = ParametrosMotor.deBytes(p.getPayload());
            for (CampoParametro c : CampoParametro.values()) {
                verificar("padrão de " + c.name() + " = " + c.getPadrao(), lidos.get(c) == c.getPadrao());
            }

            Pacote eixos = ecu.pedir(Protocolo.CMD_LER_EIXOS, new byte[0]);
            verificar("eixos padrão do firmware = EixosMapa.padrao()",
                    eixos.getTipo() == Protocolo.RSP_EIXOS && Arrays.equals(eixos.getPayload(), padrao.getEixos().paraBytes()));

            int linhasIguais = 0;
            for (TipoMapa t : TipoMapa.values()) {
                for (int l = 0; l < Protocolo.TAMANHO_MAPA; l++) {
                    Pacote r = ecu.pedir(Protocolo.CMD_LER_LINHA, new byte[] {(byte) t.getId(), (byte) l});
                    byte[] esperado = padrao.getMapa(t).linhaParaBytes(l);
                    byte[] dados = r.getPayload();
                    if (r.getTipo() == Protocolo.RSP_LINHA && dados.length == 2 + esperado.length
                            && dados[0] == t.getId() && dados[1] == l
                            && Arrays.equals(Arrays.copyOfRange(dados, 2, dados.length), esperado)) {
                        linhasIguais++;
                    }
                }
            }
            verificar("as 48 linhas dos 3 mapas padrão são idênticas (" + linhasIguais + " de 48)", linhasIguais == 48);
        }
    }

    // ================================================================== 2. faixas dos parâmetros

    private static void testarFaixasDosParametros() throws Exception {
        titulo("Faixas dos parâmetros: o firmware aceita e recusa exatamente como o Java");
        try (Cru ecu = new Cru("--rpm", "0")) {
            int aceitos = 0;
            int recusados = 0;
            int esperadosRecusar = 0;
            boolean semAlteracaoNaRecusa = true;
            for (CampoParametro c : CampoParametro.values()) {
                int[] v = valoresPadrao();

                v[c.ordinal()] = c.getMin();
                Pacote r = ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v));
                boolean ok = ehAck(r) && ParametrosMotor.deBytes(ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0])
                        .getPayload()).get(c) == c.getMin();
                verificar(c.name() + ": mínimo " + c.getMin() + " aceito e lido de volta", ok);
                aceitos += ok ? 1 : 0;

                v[c.ordinal()] = c.getMax();
                r = ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v));
                ok = ehAck(r) && ParametrosMotor.deBytes(ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0])
                        .getPayload()).get(c) == c.getMax();
                verificar(c.name() + ": máximo " + c.getMax() + " aceito e lido de volta", ok);
                aceitos += ok ? 1 : 0;

                byte[] antes = ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload();
                if (c.getMin() > 0) {
                    esperadosRecusar++;
                    v = valoresPadrao();
                    v[c.ordinal()] = c.getMin() - 1;
                    r = ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v));
                    boolean recusou = ehNack(r, Protocolo.ERRO_FAIXA);
                    verificar(c.name() + ": " + (c.getMin() - 1) + " (abaixo do mínimo) recusado com NACK 3", recusou);
                    recusados += recusou ? 1 : 0;
                    semAlteracaoNaRecusa &= Arrays.equals(antes, ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload());
                }
                if (c.getMax() < maximoDoTipo(c)) {
                    esperadosRecusar++;
                    v = valoresPadrao();
                    v[c.ordinal()] = c.getMax() + 1;
                    r = ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v));
                    boolean recusou = ehNack(r, Protocolo.ERRO_FAIXA);
                    verificar(c.name() + ": " + (c.getMax() + 1) + " (acima do máximo) recusado com NACK 3", recusou);
                    recusados += recusou ? 1 : 0;
                    semAlteracaoNaRecusa &= Arrays.equals(antes, ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload());
                }
            }
            verificar("todos os " + (2 * CampoParametro.values().length) + " extremos válidos foram aceitos (" + aceitos + ")",
                    aceitos == 2 * CampoParametro.values().length);
            verificar("todos os " + esperadosRecusar + " valores fora da faixa foram recusados (" + recusados + ")",
                    recusados == esperadosRecusar);
            verificar("um bloco recusado não altera NENHUM campo na placa", semAlteracaoNaRecusa);

            byte[] bom = ParametrosMotor.padrao().paraBytes();
            verificar("bloco curto (46 bytes) recusado com NACK 2", ehNack(ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS,
                    Arrays.copyOf(bom, 46)), Protocolo.ERRO_TAMANHO));
            verificar("bloco longo (48 bytes) recusado com NACK 2", ehNack(ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS,
                    Arrays.copyOf(bom, 48)), Protocolo.ERRO_TAMANHO));
            verificar("bloco vazio recusado com NACK 2", ehNack(ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS, new byte[0]),
                    Protocolo.ERRO_TAMANHO));

            verificar("PADROES com o motor parado: ACK", ehAck(ecu.pedir(Protocolo.CMD_PADROES, new byte[0])));
            verificar("depois de PADROES os parâmetros voltam ao padrão", Arrays.equals(bom,
                    ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload()));
        }
    }

    // ================================================================== 3. semântica: parado x girando

    private static void testarSemanticaParadoEGirando() throws Exception {
        titulo("Regras com o motor parado e girando (firmware real, rotação gerada por dentes de roda)");
        Controle ctl = new Controle(novoArquivo(".ctl"));
        ctl.definir("rpm", 3000);
        ctl.definir("tps", 10);
        ctl.definir("map", 50);
        try (Cru ecu = new Cru("--ctl", ctl.caminho(), "--nvm", novoArquivo(".bin").toString())) {
            Telemetria t = ecu.telemetria(x -> x.temFlag(Protocolo.FLAG_MOTOR_GIRANDO) && x.temFlag(Protocolo.FLAG_SYNC)
                    && Math.abs(x.getRpm() - 3000) < 40, 4000);
            verificar("o firmware sincroniza com a roda e mede ~3000 rpm", t != null);
            if (t != null) {
                perto("rpm medido", t.getRpm(), 3000, 15);
            }

            int[] v = valoresPadrao();
            v[CampoParametro.CILINDROS.ordinal()] = 6;
            verificar("girando: mudar CILINDROS é recusado com NACK 4", ehNack(ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS,
                    blocoParametros(v)), Protocolo.ERRO_MOTOR_GIRANDO));
            for (CampoParametro c : new CampoParametro[] {CampoParametro.DENTES_RODA, CampoParametro.DENTES_FALTANDO,
                    CampoParametro.MODO_SINAL, CampoParametro.PMS_OFFSET_X10}) {
                v = valoresPadrao();
                v[c.ordinal()] = c == CampoParametro.DENTES_RODA ? 36 : (c == CampoParametro.DENTES_FALTANDO ? 1
                        : (c == CampoParametro.MODO_SINAL ? 1 : 300));
                verificar("girando: mudar " + c.name() + " é recusado com NACK 4", ehNack(ecu.pedir(
                        Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v)), Protocolo.ERRO_MOTOR_GIRANDO));
            }
            v = valoresPadrao();
            v[CampoParametro.REV_LIMITE_RPM.ordinal()] = 7000;
            v[CampoParametro.AE_ENRIQUECIMENTO.ordinal()] = 50;
            verificar("girando: parâmetros que não são do sinal podem mudar", ehAck(ecu.pedir(
                    Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v))));
            verificar("girando: reenviar o MESMO valor do sinal é aceito (não é mudança)", ehAck(ecu.pedir(
                    Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v))));
            verificar("girando: GRAVAR recusado com NACK 4", ehNack(ecu.pedir(Protocolo.CMD_GRAVAR, new byte[0]),
                    Protocolo.ERRO_MOTOR_GIRANDO));
            verificar("girando: PADROES recusado com NACK 4", ehNack(ecu.pedir(Protocolo.CMD_PADROES, new byte[0]),
                    Protocolo.ERRO_MOTOR_GIRANDO));

            byte[] linha = new byte[2 + Protocolo.TAMANHO_MAPA];
            linha[0] = (byte) TipoMapa.COMBUSTIVEL.getId();
            linha[1] = 6;
            Arrays.fill(linha, 2, linha.length, (byte) 77);
            verificar("girando: editar linha do mapa vale na hora (ACK)", ehAck(ecu.pedir(Protocolo.CMD_ESCREVER_LINHA, linha)));
            Pacote lida = ecu.pedir(Protocolo.CMD_LER_LINHA, new byte[] {linha[0], linha[1]});
            verificar("a linha lida é a que foi escrita", Arrays.equals(lida.getPayload(), linha));
            verificar("girando: ARMAR(1) recusado em modo sombra com NACK 6", ehNack(ecu.pedir(Protocolo.CMD_ARMAR,
                    new byte[] {1}), Protocolo.ERRO_SAIDAS_DESABILITADAS));
            verificar("ARMAR(0) é sempre aceito", ehAck(ecu.pedir(Protocolo.CMD_ARMAR, new byte[] {0})));

            // para o motor: sem dentes por 250 ms o firmware considera parado e libera GRAVAR e PADROES
            ctl.definir("rpm", 0);
            Telemetria parado = ecu.telemetria(x -> !x.temFlag(Protocolo.FLAG_MOTOR_GIRANDO) && x.getRpm() == 0, 3000);
            verificar("sem dentes, a telemetria mostra motor parado e rotação 0", parado != null);
            v = valoresPadrao();
            v[CampoParametro.CILINDROS.ordinal()] = 6;
            verificar("parado: mudar CILINDROS é aceito", ehAck(ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v))));
            verificar("parado: GRAVAR aceito", ehAck(ecu.pedir(Protocolo.CMD_GRAVAR, new byte[0])));
            verificar("parado: PADROES aceito", ehAck(ecu.pedir(Protocolo.CMD_PADROES, new byte[0])));
        }
    }

    // ================================================================== 4. diferencial: simulador Java x firmware C

    private static final class Passo {
        final int tipo;
        final byte[] payload;
        final String rotulo;

        Passo(int tipo, byte[] payload, String rotulo) {
            this.tipo = tipo;
            this.payload = payload;
            this.rotulo = rotulo;
        }
    }

    private static int sorteio(Random r, int minimo, int maximo) {
        return minimo + r.nextInt(maximo - minimo + 1);
    }

    private static boolean campoDeSinal(CampoParametro c) {
        return c.exigeMotorParado();
    }

    private static List<Passo> gerarSequencia(Random r, boolean motorGirando, int quantidade) {
        List<Passo> lista = new ArrayList<>();
        CampoParametro[] campos = CampoParametro.values();
        int[] tiposDesconhecidos = {0x00, 0x03, 0x12, 0x24, 0x32, 0x42, 0x7F, 0x80, 0xF0, 0xFF};
        for (int i = 0; i < quantidade; i++) {
            switch (r.nextInt(14)) {
                case 0:
                    lista.add(new Passo(Protocolo.CMD_HELLO, new byte[0], "HELLO"));
                    break;
                case 1:
                case 2: {
                    int[] v = valoresPadrao();
                    for (CampoParametro c : campos) {
                        boolean mexer = !campoDeSinal(c) || !motorGirando || r.nextBoolean() && r.nextInt(4) == 0;
                        if (mexer) {
                            v[c.ordinal()] = sorteio(r, c.getMin(), c.getMax());
                        }
                    }
                    lista.add(new Passo(Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v), "ESCREVER_PARAMS válido"));
                    break;
                }
                case 3: {
                    int[] v = valoresPadrao();
                    CampoParametro c = campos[r.nextInt(campos.length)];
                    boolean abaixo = c.getMin() > 0 && (c.getMax() >= maximoDoTipo(c) || r.nextBoolean());
                    if (abaixo || c.getMax() < maximoDoTipo(c)) {
                        v[c.ordinal()] = abaixo ? c.getMin() - 1 - r.nextInt(Math.min(3, c.getMin())) : c.getMax() + 1;
                        lista.add(new Passo(Protocolo.CMD_ESCREVER_PARAMS, blocoParametros(v),
                                "ESCREVER_PARAMS fora da faixa (" + c.name() + ")"));
                    }
                    break;
                }
                case 4: {
                    byte[] b = new byte[r.nextInt(70)];
                    r.nextBytes(b);
                    lista.add(new Passo(Protocolo.CMD_ESCREVER_PARAMS, b, "ESCREVER_PARAMS com " + b.length + " bytes"));
                    break;
                }
                case 5:
                    lista.add(new Passo(Protocolo.CMD_LER_PARAMS, r.nextInt(6) == 0 ? new byte[] {1} : new byte[0], "LER_PARAMS"));
                    break;
                case 6: {
                    byte[] b = new byte[2 + Protocolo.TAMANHO_MAPA];
                    r.nextBytes(b);
                    b[0] = (byte) (r.nextInt(8) == 0 ? sorteio(r, 3, 255) : r.nextInt(3));
                    b[1] = (byte) (r.nextInt(8) == 0 ? sorteio(r, 16, 255) : r.nextInt(16));
                    if (r.nextInt(8) == 0) {
                        b = Arrays.copyOf(b, r.nextInt(25));
                    }
                    lista.add(new Passo(Protocolo.CMD_ESCREVER_LINHA, b, "ESCREVER_LINHA"));
                    break;
                }
                case 7: {
                    byte[] b = {(byte) (r.nextInt(8) == 0 ? sorteio(r, 3, 255) : r.nextInt(3)),
                            (byte) (r.nextInt(8) == 0 ? sorteio(r, 16, 255) : r.nextInt(16))};
                    if (r.nextInt(10) == 0) {
                        b = Arrays.copyOf(b, r.nextInt(4));
                    }
                    lista.add(new Passo(Protocolo.CMD_LER_LINHA, b, "LER_LINHA"));
                    break;
                }
                case 8: {
                    int[] rpm = new int[16];
                    int[] carga = new int[16];
                    int a = sorteio(r, 0, 600);
                    int c = sorteio(r, 0, 40);
                    for (int k = 0; k < 16; k++) {
                        a += sorteio(r, 1, 450);
                        c += sorteio(r, 1, 20);
                        rpm[k] = a;
                        carga[k] = c;
                    }
                    byte[] b = BufferLE.paraEscrita(64).bytes();
                    BufferLE w = BufferLE.paraEscrita(64);
                    int defeito = r.nextInt(3); // 0 = válido, 1 = repetido, 2 = decrescente
                    if (defeito == 1) {
                        int k = sorteio(r, 1, 15);
                        (r.nextBoolean() ? rpm : carga)[k] = (r.nextBoolean() ? rpm : carga)[k - 1];
                        // pode acabar válido de novo se o sorteio escolheu eixos diferentes; o alvo decide
                    } else if (defeito == 2) {
                        int k = sorteio(r, 1, 15);
                        (r.nextBoolean() ? rpm : carga)[k] = 0;
                    }
                    for (int k = 0; k < 16; k++) {
                        w.escreverU16(rpm[k]);
                    }
                    for (int k = 0; k < 16; k++) {
                        w.escreverU16(carga[k]);
                    }
                    b = w.bytes();
                    if (r.nextInt(12) == 0) {
                        b = Arrays.copyOf(b, r.nextInt(70));
                    }
                    lista.add(new Passo(Protocolo.CMD_ESCREVER_EIXOS, b, "ESCREVER_EIXOS"));
                    break;
                }
                case 9:
                    lista.add(new Passo(Protocolo.CMD_LER_EIXOS, new byte[0], "LER_EIXOS"));
                    break;
                case 10:
                    lista.add(new Passo(r.nextBoolean() ? Protocolo.CMD_GRAVAR : Protocolo.CMD_PADROES,
                            r.nextInt(8) == 0 ? new byte[] {1} : new byte[0], "GRAVAR/PADROES"));
                    break;
                case 11: {
                    byte[] b = r.nextInt(8) == 0 ? new byte[r.nextInt(4)] : new byte[] {(byte) r.nextInt(3)};
                    lista.add(new Passo(Protocolo.CMD_ARMAR, b, "ARMAR"));
                    break;
                }
                case 12: {
                    byte[] b = r.nextInt(8) == 0 ? new byte[r.nextInt(4)] : new byte[] {(byte) r.nextInt(256)};
                    lista.add(new Passo(Protocolo.CMD_CHAVES, b, "CHAVES"));
                    break;
                }
                default:
                    if (r.nextBoolean()) {
                        lista.add(new Passo(tiposDesconhecidos[r.nextInt(tiposDesconhecidos.length)],
                                new byte[r.nextInt(5)], "comando desconhecido"));
                    } else {
                        int[] validos = {0, 5, 20, 1000};
                        int[] invalidos = {1, 4, 1001, 65535};
                        int ms = r.nextBoolean() ? validos[r.nextInt(validos.length)] : invalidos[r.nextInt(invalidos.length)];
                        byte[] b = r.nextInt(10) == 0 ? new byte[r.nextInt(4)] : u16(ms);
                        lista.add(new Passo(Protocolo.CMD_STREAM, b, "STREAM " + ms));
                        if (ms != 0 && b.length == 2 && ms >= 5 && ms <= 1000) {
                            lista.add(new Passo(Protocolo.CMD_STREAM, u16(0), "STREAM 0 (desliga)"));
                        }
                    }
                    break;
            }
        }
        // no fim, lê TUDO: o estado completo precisa ser igual nos dois lados
        lista.add(new Passo(Protocolo.CMD_LER_PARAMS, new byte[0], "LER_PARAMS final"));
        lista.add(new Passo(Protocolo.CMD_LER_EIXOS, new byte[0], "LER_EIXOS final"));
        for (int m = 0; m < Protocolo.NUM_MAPAS; m++) {
            for (int l = 0; l < Protocolo.TAMANHO_MAPA; l++) {
                lista.add(new Passo(Protocolo.CMD_LER_LINHA, new byte[] {(byte) m, (byte) l}, "LER_LINHA final"));
            }
        }
        return lista;
    }

    private static void compararSequencia(String nome, List<Passo> passos, Alvo a, Alvo b, Map<String, Integer> contagem)
            throws IOException {
        int diferencas = 0;
        String primeira = null;
        for (int i = 0; i < passos.size(); i++) {
            Passo p = passos.get(i);
            Pacote ra = a.pedir(p.tipo, p.payload);
            Pacote rb = b.pedir(p.tipo, p.payload);
            String sa = resumo(ra);
            String sb = resumo(rb);
            if (!sa.equals(sb)) {
                diferencas++;
                if (primeira == null) {
                    primeira = "passo " + i + " (" + p.rotulo + ", comando 0x" + Integer.toHexString(p.tipo) + "["
                            + hexa(p.payload) + "]): simulador Java=" + sa + " firmware C=" + sb;
                }
            }
            String classe = ra.getTipo() == Protocolo.RSP_NACK ? "NACK " + (ra.getPayload().length > 0 ? ra.getPayload()[0] : -1)
                    : (ra.getTipo() == Protocolo.RSP_ACK ? "ACK" : "dados");
            contagem.merge(classe, 1, Integer::sum);
        }
        verificar(nome + ": " + passos.size() + " comandos, respostas idênticas nos dois lados"
                + (primeira == null ? "" : " -- primeira diferença: " + primeira) + " (" + diferencas + " diferenças)",
                diferencas == 0);
    }

    private static void testarDiferencialSimuladorFirmware() throws Exception {
        titulo("Teste diferencial: simulador Java x firmware C respondem igual aos mesmos comandos");

        // motor parado: tudo é permitido, inclusive mudar o sinal, gravar e restaurar padrões
        Map<String, Integer> contagem = new java.util.TreeMap<>();
        for (int semente = 1; semente <= 3; semente++) {
            SimuladorECU sim = new SimuladorECU();
            try (Cru ecu = new Cru("--rpm", "0", "--nvm", novoArquivo(".bin").toString())) {
                compararSequencia("motor parado, semente " + semente, gerarSequencia(new Random(1000 + semente), false, 900),
                        new AlvoSimulador(sim), ecu, contagem);
            }
        }
        System.out.println("  respostas observadas (motor parado): " + contagem);
        verificar("a sequência exercitou ACK, dados e os NACKs 1, 2, 3 e 6",
                contagem.getOrDefault("ACK", 0) > 100 && contagem.getOrDefault("dados", 0) > 100
                        && contagem.getOrDefault("NACK 1", 0) > 5 && contagem.getOrDefault("NACK 2", 0) > 20
                        && contagem.getOrDefault("NACK 3", 0) > 10 && contagem.getOrDefault("NACK 6", 0) > 3);

        // motor girando: mudar sinal, GRAVAR e PADROES são recusados (NACK 4)
        contagem.clear();
        for (int semente = 1; semente <= 3; semente++) {
            SimuladorECU sim = new SimuladorECU();
            sim.getCarro().definirBancada(3000, 0, 10, 55, 90, 30, 13.8, 3);
            sim.avancar(0.05);
            Controle ctl = new Controle(novoArquivo(".ctl"));
            ctl.definir("rpm", 3000);
            try (Cru ecu = new Cru("--ctl", ctl.caminho(), "--nvm", novoArquivo(".bin").toString())) {
                verificar("motor girando: firmware sincronizado antes de começar (semente " + semente + ")",
                        ecu.telemetria(x -> x.temFlag(Protocolo.FLAG_MOTOR_GIRANDO), 4000) != null);
                compararSequencia("motor girando, semente " + semente, gerarSequencia(new Random(2000 + semente), true, 900),
                        new AlvoSimulador(sim), ecu, contagem);
            }
        }
        System.out.println("  respostas observadas (motor girando): " + contagem);
        verificar("a sequência com motor girando exercitou o NACK 4 e também ACKs",
                contagem.getOrDefault("NACK 4", 0) > 20 && contagem.getOrDefault("ACK", 0) > 100);
    }

    // ================================================================== 5. persistência na memória

    private static void testarPersistencia() throws Exception {
        titulo("Memória não volátil: GRAVAR sobrevive ao reinício; PADROES só mexe na RAM; memória corrompida");
        Path nvm = novoArquivo(".nvm");
        Calibracao editada = Calibracao.padrao();
        editada.getParametros().set(CampoParametro.REV_LIMITE_RPM, 5800);
        editada.getParametros().set(CampoParametro.TIPO_CORTE, 1);
        editada.getParametros().set(CampoParametro.ML_RPM_QUENTE, 900);
        editada.getMapa(TipoMapa.COMBUSTIVEL).setRaw(5, 7, 91);
        editada.getMapa(TipoMapa.IGNICAO).setRaw(3, 2, 77);
        editada.getMapa(TipoMapa.ALVO_LAMBDA).setRaw(15, 15, 66);
        editada.getEixos().setRpm(15, 7800);
        editada.getEixos().setCarga(0, 25);

        try (Cru ecu = new Cru("--rpm", "0", "--nvm", nvm.toString())) {
            verificar("memória virgem: o arquivo ainda não existe e a ECU sobe com os padrões", !Files.exists(nvm)
                    && Arrays.equals(ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload(),
                    ParametrosMotor.padrao().paraBytes()));
            Telemetria t = ecu.telemetria(x -> true, 2000);
            verificar("memória virgem não é erro (flag NVM apagada)", t != null && !t.temErro(Protocolo.ERR_NVM));

            verificar("ESCREVER_PARAMS", ehAck(ecu.pedir(Protocolo.CMD_ESCREVER_PARAMS, editada.getParametros().paraBytes())));
            verificar("ESCREVER_EIXOS", ehAck(ecu.pedir(Protocolo.CMD_ESCREVER_EIXOS, editada.getEixos().paraBytes())));
            for (TipoMapa tipo : TipoMapa.values()) {
                for (int l = 0; l < Protocolo.TAMANHO_MAPA; l++) {
                    byte[] valores = editada.getMapa(tipo).linhaParaBytes(l);
                    byte[] payload = BufferLE.paraEscrita(2 + valores.length).escreverU8(tipo.getId()).escreverU8(l)
                            .escreverBytes(valores).bytes();
                    if (!ehAck(ecu.pedir(Protocolo.CMD_ESCREVER_LINHA, payload))) {
                        verificar("ESCREVER_LINHA " + tipo + "/" + l, false);
                    }
                }
            }
            verificar("GRAVAR aceito (motor parado)", ehAck(ecu.pedir(Protocolo.CMD_GRAVAR, new byte[0])));
        }
        verificar("o arquivo da memória foi criado com o bloco de 889 bytes", Files.exists(nvm) && Files.size(nvm) == 889);
        verificar("o bloco começa com a assinatura 'KECU'", Files.exists(nvm) && Arrays.equals(
                Arrays.copyOf(Files.readAllBytes(nvm), 4), new byte[] {'K', 'E', 'C', 'U'}));

        // reinício: tudo volta como foi gravado
        try (Cru ecu = new Cru("--rpm", "0", "--nvm", nvm.toString())) {
            verificar("depois de reiniciar, os parâmetros gravados voltam", Arrays.equals(
                    ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload(), editada.getParametros().paraBytes()));
            verificar("depois de reiniciar, os eixos gravados voltam", Arrays.equals(
                    ecu.pedir(Protocolo.CMD_LER_EIXOS, new byte[0]).getPayload(), editada.getEixos().paraBytes()));
            boolean mapasIguais = true;
            for (TipoMapa tipo : TipoMapa.values()) {
                for (int l = 0; l < Protocolo.TAMANHO_MAPA; l++) {
                    Pacote r = ecu.pedir(Protocolo.CMD_LER_LINHA, new byte[] {(byte) tipo.getId(), (byte) l});
                    mapasIguais &= Arrays.equals(Arrays.copyOfRange(r.getPayload(), 2, r.getPayload().length),
                            editada.getMapa(tipo).linhaParaBytes(l));
                }
            }
            verificar("depois de reiniciar, as 48 linhas dos mapas gravadas voltam", mapasIguais);

            verificar("PADROES: ACK", ehAck(ecu.pedir(Protocolo.CMD_PADROES, new byte[0])));
            verificar("PADROES muda a RAM para os valores de fábrica", Arrays.equals(
                    ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload(), ParametrosMotor.padrao().paraBytes()));
        }
        // PADROES não gravou: reiniciar traz de volta o que estava na memória
        try (Cru ecu = new Cru("--rpm", "0", "--nvm", nvm.toString())) {
            verificar("PADROES não gravou: a memória ainda tem a calibração editada", Arrays.equals(
                    ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload(), editada.getParametros().paraBytes()));
        }

        // corrompe um byte no meio do bloco: o CRC pega, sobem os padrões e acende o aviso
        byte[] bytes = Files.readAllBytes(nvm);
        bytes[300] ^= 0x40;
        Files.write(nvm, bytes);
        try (Cru ecu = new Cru("--rpm", "0", "--nvm", nvm.toString())) {
            verificar("memória corrompida: a ECU sobe com os padrões", Arrays.equals(
                    ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload(), ParametrosMotor.padrao().paraBytes()));
            Telemetria t = ecu.telemetria(x -> true, 2000);
            verificar("memória corrompida: o aviso ERR_NVM aparece na telemetria", t != null && t.temErro(Protocolo.ERR_NVM));
            verificar("regravar conserta a memória", ehAck(ecu.pedir(Protocolo.CMD_GRAVAR, new byte[0])));
            t = ecu.telemetria(x -> true, 2000);
            verificar("depois de regravar o aviso ERR_NVM some", t != null && !t.temErro(Protocolo.ERR_NVM));
        }
        try (Cru ecu = new Cru("--rpm", "0", "--nvm", nvm.toString())) {
            Telemetria t = ecu.telemetria(x -> true, 2000);
            verificar("memória regravada carrega sem erro", t != null && !t.temErro(Protocolo.ERR_NVM));
        }

        // versão desconhecida do bloco (assinatura certa, versão errada): também cai nos padrões com aviso
        byte[] outro = Files.readAllBytes(nvm);
        outro[4] = 9;
        Files.write(nvm, outro);
        try (Cru ecu = new Cru("--rpm", "0", "--nvm", nvm.toString())) {
            Telemetria t = ecu.telemetria(x -> true, 2000);
            verificar("bloco de versão desconhecida: padrões e aviso", t != null && t.temErro(Protocolo.ERR_NVM)
                    && Arrays.equals(ecu.pedir(Protocolo.CMD_LER_PARAMS, new byte[0]).getPayload(),
                    ParametrosMotor.padrao().paraBytes()));
        }
    }

    // ================================================================== 6. conexão completa (ConexaoECU + firmware)

    private static void testarConexaoCompleta() throws Exception {
        titulo("ConexaoECU (threads, tentativas, telemetria) ligada ao firmware real");
        Controle ctl = new Controle(novoArquivo(".ctl"));
        ctl.definir("rpm", 3000);
        ctl.definir("vel", 60);
        ctl.definir("tps", 20);
        ctl.definir("ect", 90);
        ctl.definir("iat", 25);
        ctl.definir("map", 60);
        ctl.definir("vbat", 13.8);
        ctl.definir("oleo", 3.0);
        ctl.definir("lambda", 1.0);
        PortaProcesso porta = new PortaProcesso("ecu_host", comando("--ctl", ctl.caminho()));
        ConexaoECU con = new ConexaoECU();
        List<EstadoConexao> estados = new java.util.concurrent.CopyOnWriteArrayList<>();
        con.adicionarOuvinte(new kadettecu.comunicacao.OuvinteConexao() {
            @Override
            public void aoMudarEstado(EstadoConexao novo) {
                estados.add(novo);
            }

            @Override
            public void aoRegistrar(String mensagem) {
                // sem log nos testes
            }
        });
        try {
            InfoECU info = con.conectar(porta);
            verificar("conectou e identificou a placa: " + info, info != null && info.getVersaoProtocolo() == Protocolo.VERSAO);
            verificar("estado CONECTADO", con.getEstado() == EstadoConexao.CONECTADO);
            verificar("a telemetria sincronizada chega",
                    esperar(() -> con.getUltimaTelemetria().temFlag(Protocolo.FLAG_SYNC)
                            && Math.abs(con.getUltimaTelemetria().getRpm() - 3000) < 40, 4000));
            Thread.sleep(300);
            Telemetria t = con.getUltimaTelemetria();
            perto("rpm", t.getRpm(), 3000, 15);
            perto("velocidade (km/h)", t.getVelocidadeKmh(), 60, 1.5);
            perto("ECT (°C)", t.getEctC(), 90, 1.0);
            perto("IAT (°C)", t.getIatC(), 25, 1.0);
            perto("MAP (kPa)", t.getMapKpa(), 60, 1.0);
            perto("TPS (%)", t.getTpsPct(), 20, 1.0);
            perto("bateria (V)", t.getBateriaV(), 13.8, 0.1);
            perto("pressão de óleo (bar)", t.getOleoBar(), 3.0, 0.15);
            perto("lambda", t.getLambda(), 1.0, 0.03);
            verificar("nenhum sensor em falha (erros=0x" + Integer.toHexString(t.getErros()) + ")", t.getErros() == 0);
            verificar("flags: girando, sincronizado, modo sombra, não armado",
                    t.temFlag(Protocolo.FLAG_MOTOR_GIRANDO) && t.temFlag(Protocolo.FLAG_SYNC)
                            && t.temFlag(Protocolo.FLAG_MODO_SOMBRA) && !t.temFlag(Protocolo.FLAG_ARMADO));
            verificar("pulso e avanço calculados pelo firmware (pw " + t.getPwUs() + " us, avanço " + t.getAvancoGraus() + ")",
                    t.getPwUs() > 1500 && t.getPwUs() < 12000 && t.getAvancoGraus() > 10 && t.getAvancoGraus() < 40);
            verificar("posição no mapa dentro de 0..15", t.getPosRpm() > 5 && t.getPosRpm() < 7 && t.getPosCarga() > 5
                    && t.getPosCarga() < 9);

            Thread.sleep(2000);
            perto("taxa de telemetria a 20 ms (quadros/s)", con.getTaxaTelemetria(), 50, 8);
            verificar("nenhum erro de CRC no caminho", con.getErrosCrc() == 0);
            con.definirPeriodoTelemetria(100);
            Thread.sleep(2300);
            perto("taxa depois de pedir 100 ms (quadros/s)", con.getTaxaTelemetria(), 10, 2.5);
            con.definirPeriodoTelemetria(ConexaoECU.PERIODO_TELEMETRIA_PADRAO_MS);

            // calibração inteira: ler, editar, escrever, ler de volta
            AtomicInteger passos = new AtomicInteger();
            Calibracao lida = con.lerCalibracao((feitos, tot) -> passos.set(feitos));
            verificar("lerCalibracao: 50 passos de progresso (" + passos.get() + ")", passos.get() == 50);
            verificar("a calibração lida é a de fábrica", paramsIguais(lida, Calibracao.padrao())
                    && lida.getEixos().equals(Calibracao.padrao().getEixos()) && mapasIguais(lida, Calibracao.padrao()));

            Calibracao nova = lida.copia();
            nova.getParametros().set(CampoParametro.REV_LIMITE_RPM, 7200);
            nova.getParametros().set(CampoParametro.HAB_TWO_STEP, 1);
            nova.getParametros().set(CampoParametro.LAUNCH_RPM, 3200);
            nova.getMapa(TipoMapa.COMBUSTIVEL).setRaw(8, 6, 99);
            nova.getMapa(TipoMapa.IGNICAO).setRaw(2, 3, 70);
            nova.getEixos().setRpm(15, 7600);
            con.escreverCalibracao(nova, null);
            Calibracao reler = con.lerCalibracao(null);
            verificar("escrever e ler de volta devolve a calibração editada (motor girando, sem tocar no sinal)",
                    paramsIguais(reler, nova) && reler.getEixos().equals(nova.getEixos()) && mapasIguais(reler, nova));

            // motor girando: o que é recusado chega ao Java como ErroProtocolo com o código certo
            verificar("gravarNaMemoria com o motor girando falha com NACK 4", codigoDoErro(() -> con.gravarNaMemoria())
                    == Protocolo.ERRO_MOTOR_GIRANDO);
            verificar("restaurarPadroes com o motor girando falha com NACK 4", codigoDoErro(() -> con.restaurarPadroes())
                    == Protocolo.ERRO_MOTOR_GIRANDO);
            verificar("armarSaidas(true) em modo sombra falha com NACK 6", codigoDoErro(() -> con.armarSaidas(true))
                    == Protocolo.ERRO_SAIDAS_DESABILITADAS);
            con.armarSaidas(false);

            // limitador de giro (7200 rpm no parâmetro acima): sobe o giro e confere o corte
            ctl.definir("rpm", 7500);
            verificar("acima do limite: flag CORTE e pulso zero na telemetria", esperar(() -> {
                Telemetria x = con.getUltimaTelemetria();
                return x.temFlag(Protocolo.FLAG_CORTE) && x.getPwUs() == 0;
            }, 3000));
            ctl.definir("rpm", 3000);
            verificar("voltando a 3000 rpm: o corte termina e o pulso volta", esperar(() -> {
                Telemetria x = con.getUltimaTelemetria();
                return !x.temFlag(Protocolo.FLAG_CORTE) && x.getPwUs() > 1500;
            }, 3000));

            // two-step por chave virtual: com a função habilitada (parâmetro acima) corta em 4500
            ctl.definir("rpm", 5000);
            Thread.sleep(500);
            verificar("sem a chave o two-step não atua em 5000 rpm", !con.getUltimaTelemetria().temFlag(Protocolo.FLAG_CORTE));
            con.definirChaves(Protocolo.CHAVE_TWO_STEP);
            verificar("com a chave virtual do two-step: corta e sinaliza", esperar(() -> {
                Telemetria x = con.getUltimaTelemetria();
                return x.temFlag(Protocolo.FLAG_CORTE) && x.temFlag(Protocolo.FLAG_TWO_STEP)
                        && x.temFlag(Protocolo.FLAG_CHAVE_TWO_STEP);
            }, 3000));
            con.definirChaves(0);
            ctl.definir("rpm", 3000);

            // emSegundoPlano: a tarefa roda na thread "ecu-tarefas" sem bloquear quem chamou
            String nomeThread = con.emSegundoPlano(() -> Thread.currentThread().getName()).get(3, TimeUnit.SECONDS);
            verificar("emSegundoPlano roda na thread ecu-tarefas (" + nomeThread + ")", "ecu-tarefas".equals(nomeThread));

            // para o motor: agora GRAVAR e PADROES são aceitos pelo firmware
            ctl.definir("rpm", 0);
            ctl.definir("vel", 0);
            verificar("motor parado na telemetria", esperar(() -> !con.getUltimaTelemetria()
                    .temFlag(Protocolo.FLAG_MOTOR_GIRANDO), 3000));
            con.gravarNaMemoria();
            con.restaurarPadroes();
            verificar("depois de parar, gravarNaMemoria e restaurarPadroes funcionam e os padrões voltam",
                    paramsIguais(con.lerCalibracao(null), Calibracao.padrao()));
        } finally {
            con.desconectar();
        }
        verificar("desconectar encerra o programa do firmware", esperar(() -> !porta.processoVivo(), 2000));
        verificar("o ouvinte viu CONECTANDO, CONECTADO e DESCONECTADO",
                estados.contains(EstadoConexao.CONECTANDO) && estados.contains(EstadoConexao.CONECTADO)
                        && estados.get(estados.size() - 1) == EstadoConexao.DESCONECTADO);
    }

    private static boolean paramsIguais(Calibracao a, Calibracao b) {
        return a.getParametros().equals(b.getParametros());
    }

    private static boolean mapasIguais(Calibracao a, Calibracao b) {
        for (TipoMapa t : TipoMapa.values()) {
            if (!a.getMapa(t).equals(b.getMapa(t))) {
                return false;
            }
        }
        return true;
    }

    private static int codigoDoErro(Runnable r) {
        try {
            r.run();
            return -1;
        } catch (ErroProtocolo e) {
            return e.getCodigo();
        }
    }

    // ================================================================== 7. paridade de cálculo: simulador Java x firmware C

    private static final class Ponto {
        final String nome;
        final double rpm;
        final double vel;
        final double tps;
        final double map;
        final double ect;
        final double iat;
        final double bateria;
        /** false onde o PI da marcha lenta ainda está integrando: o valor depende de há quanto tempo cada lado roda. */
        final boolean comparaIac;

        Ponto(String nome, double rpm, double vel, double tps, double map, double ect, double iat, double bateria,
              boolean comparaIac) {
            this.comparaIac = comparaIac;
            this.nome = nome;
            this.rpm = rpm;
            this.vel = vel;
            this.tps = tps;
            this.map = map;
            this.ect = ect;
            this.iat = iat;
            this.bateria = bateria;
        }
    }

    private static void testarParidadeDeCalculo() throws Exception {
        titulo("Paridade de cálculo: o simulador Java e o firmware C chegam ao mesmo pulso, avanço e estado");
        Ponto[] pontos = {
                new Ponto("marcha lenta quente (na meta)", 850, 0, 0, 35, 90, 30, 13.8, true),
                new Ponto("cruzeiro leve", 2500, 60, 12, 48, 88, 32, 14.0, true),
                new Ponto("carga média", 4500, 90, 70, 85, 92, 40, 13.9, true),
                new Ponto("plena carga", 5500, 120, 100, 98, 90, 35, 14.1, true),
                new Ponto("motor frio com pedal (aquecimento)", 1500, 0, 8, 40, 20, 20, 13.5, true),
                new Ponto("ar quente e bateria fraca", 3000, 50, 25, 60, 90, 60, 10.5, true),
                new Ponto("partida (250 rpm)", 250, 0, 0, 90, 60, 25, 11.0, false),
                new Ponto("acima do limitador (7000 rpm)", 7000, 0, 30, 70, 90, 30, 13.8, true),
                new Ponto("torque médio, motor morno", 3500, 70, 40, 72, 75, 28, 13.6, true),
                new Ponto("freio-motor (pedal solto, 4000 rpm)", 4000, 80, 0, 25, 90, 30, 14.0, true),
        };
        double[] maiorDiferencaPulso = {0};
        double[] maiorDiferencaAvanco = {0};
        for (Ponto p : pontos) {
            SimuladorECU sim = new SimuladorECU();
            sim.getCarro().definirBancada(p.rpm, p.vel, p.tps, p.map, p.ect, p.iat, p.bateria, 3.0);
            sim.avancar(1.0); // o degrau de TPS de 0 até o valor do ponto dispara o enriquecimento (400 ms): espera passar
            Telemetria java = sim.getUltimaTelemetria();

            Telemetria c;
            try (Cru ecu = new Cru("--rpm", String.valueOf(p.rpm), "--vel", String.valueOf(p.vel), "--tps",
                    String.valueOf(p.tps), "--map", String.valueOf(p.map), "--ect", String.valueOf(p.ect), "--iat",
                    String.valueOf(p.iat), "--vbat", String.valueOf(p.bateria), "--oleo", "3.0")) {
                c = ecu.telemetria(x -> x.temFlag(Protocolo.FLAG_MOTOR_GIRANDO) && x.temFlag(Protocolo.FLAG_SYNC)
                        && Math.abs(x.getRpm() - p.rpm) < p.rpm * 0.01 && x.getTempoMs() > 400, 8000);
            }
            if (c == null) {
                verificar(p.nome + ": o firmware sincronizou e mediu a rotação", false);
                continue;
            }
            String ctx = p.nome + ": ";
            perto(ctx + "rpm medido pelo firmware", c.getRpm(), p.rpm, p.rpm * 0.006 + 2);
            double tolPw = 0.012 * java.getPwUs() + 20;
            perto(ctx + "pulso (us) C x Java", c.getPwUs(), java.getPwUs(), tolPw);
            perto(ctx + "avanço (graus) C x Java", c.getAvancoGraus(), java.getAvancoGraus(), 0.35);
            if (java.getPwUs() > 0) {
                maiorDiferencaPulso[0] = Math.max(maiorDiferencaPulso[0],
                        Math.abs(c.getPwUs() - java.getPwUs()) * 100.0 / java.getPwUs());
            }
            maiorDiferencaAvanco[0] = Math.max(maiorDiferencaAvanco[0], Math.abs(c.getAvancoGraus() - java.getAvancoGraus()));
            perto(ctx + "VE (%) C x Java", c.getVePct(), java.getVePct(), 1.01);
            if (p.comparaIac) {
                perto(ctx + "IAC (%) C x Java", c.getIacPct(), java.getIacPct(), 2.01);
            }
            perto(ctx + "posição no eixo de rpm", c.getPosRpm(), java.getPosRpm(), 0.08);
            perto(ctx + "posição no eixo de carga", c.getPosCarga(), java.getPosCarga(), 0.08);
            int mascara = Protocolo.FLAG_MOTOR_GIRANDO | Protocolo.FLAG_SYNC | Protocolo.FLAG_CORTE | Protocolo.FLAG_TWO_STEP
                    | Protocolo.FLAG_LAUNCH | Protocolo.FLAG_MARCHA_LENTA | Protocolo.FLAG_MODO_SOMBRA
                    | Protocolo.FLAG_SIMULADOR | Protocolo.FLAG_ARMADO;
            verificar(ctx + String.format("flags C=0x%03X Java=0x%03X", c.getFlags() & mascara, java.getFlags() & mascara),
                    (c.getFlags() & mascara) == (java.getFlags() & mascara));
        }
        System.out.printf("  maior diferença do pulso entre o firmware C e o simulador Java: %.2f %%; do avanço: %.2f graus%n",
                maiorDiferencaPulso[0], maiorDiferencaAvanco[0]);
    }

    // ================================================================== 8. histerese do limitador

    private static void testarHistereseDoLimitador() throws Exception {
        titulo("Limitador de giro: mesma histerese no simulador Java e no firmware C");
        SimuladorECU sim = new SimuladorECU();
        // margens de 30 rpm dos limiares (corta em 6500, volta em 6350): a rotação medida tem ruído de alguns rpm
        double[] sequencia = {6400, 6540, 6400, 6380, 6310, 6460, 6540, 6310, 6380, 6540, 6380};
        Controle ctl = new Controle(novoArquivo(".ctl"));
        ctl.definir("rpm", sequencia[0]);
        ctl.definir("tps", 30);
        ctl.definir("map", 70);
        sim.getCarro().definirBancada(sequencia[0], 0, 30, 70, 85, 30, 13.8, 3.0);
        sim.avancar(0.05);
        try (Cru ecu = new Cru("--ctl", ctl.caminho())) {
            verificar("firmware pronto a 6400 rpm", ecu.telemetria(x -> x.temFlag(Protocolo.FLAG_MOTOR_GIRANDO)
                    && Math.abs(x.getRpm() - 6400) < 40, 5000) != null);
            StringBuilder linha = new StringBuilder();
            for (double rpm : sequencia) {
                sim.getCarro().definirBancada(rpm, 0, 30, 70, 85, 30, 13.8, 3.0);
                sim.avancar(0.05);
                boolean corteJava = sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_CORTE);
                ctl.definir("rpm", rpm);
                final double alvo = rpm;
                Telemetria c = ecu.telemetria(x -> Math.abs(x.getRpm() - alvo) < 25 && x.getTempoMs() > 0, 3000);
                // dá tempo ao passo de controle de 5 ms de decidir com a rotação já estável
                Thread.sleep(80);
                Telemetria c2 = ecu.telemetria(x -> Math.abs(x.getRpm() - alvo) < 25, 3000);
                boolean corteC = c2 != null && c2.temFlag(Protocolo.FLAG_CORTE);
                linha.append(String.format("%.0f:%s/%s ", rpm, corteJava ? "corta" : "livre", corteC ? "corta" : "livre"));
                verificar(String.format("%.0f rpm: Java %s, firmware %s", rpm, corteJava ? "corta" : "livre",
                        corteC ? "corta" : "livre"), c != null && c2 != null && corteJava == corteC);
            }
            System.out.println("  Java/C: " + linha);
        }
    }

    // ================================================================== 9. o programa morre

    private static void testarQuedaDoProcesso() throws Exception {
        titulo("Queda do firmware (processo morto) é percebida pela conexão");
        PortaProcesso porta = new PortaProcesso("ecu_host", comando("--rpm", "2000"));
        ConexaoECU con = new ConexaoECU();
        con.conectar(porta);
        verificar("conectado antes da queda", con.getEstado() == EstadoConexao.CONECTADO
                && esperar(() -> con.getTelemetriasRecebidas() > 5, 3000));
        porta.matar();
        verificar("sem o programa, o estado vai para ERRO", esperar(() -> con.getEstado() == EstadoConexao.ERRO, 3000));
        boolean lancou = false;
        long inicio = System.nanoTime();
        try {
            con.lerParametros();
        } catch (ErroProtocolo e) {
            lancou = true;
        }
        long gastoMs = (System.nanoTime() - inicio) / 1_000_000L;
        verificar("lerParametros depois da queda lança ErroProtocolo", lancou);
        verificar("e falha na hora, sem ficar esperando (" + gastoMs + " ms)", gastoMs < 1500);
        con.desconectar();
        verificar("desconectar depois de uma queda deixa o estado DESCONECTADO", con.getEstado() == EstadoConexao.DESCONECTADO);
    }
}
