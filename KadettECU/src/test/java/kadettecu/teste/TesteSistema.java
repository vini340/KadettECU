package kadettecu.teste;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import kadettecu.comunicacao.ConexaoECU;
import kadettecu.comunicacao.ErroProtocolo;
import kadettecu.comunicacao.OuvinteConexao;
import kadettecu.comunicacao.PortaSerial;
import kadettecu.comunicacao.PortaSimulada;
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
import kadettecu.protocolo.Crc16;
import kadettecu.protocolo.DecodificadorPacotes;
import kadettecu.protocolo.Pacote;
import kadettecu.protocolo.Protocolo;
import kadettecu.simulador.SimuladorECU;

/**
 * Testes do sistema (protocolo, modelo, simulador e conexão com threads), sem hardware e sem
 * JUnit: é só rodar o main. Termina com código 1 se alguma verificação falhar.
 */
public class TesteSistema {

    private static int falhas = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        testarCrcEPacotes();
        testarDecodificador();
        testarModelo();
        testarSimuladorProtocolo();
        testarSimuladorLogica();
        testarConexaoNormal();
        testarFalhas();
        testarConcorrencia();
        testarThreadsEncerradas();

        System.out.println();
        if (falhas == 0) {
            System.out.println("Todos os testes do sistema passaram (" + total + " verificações).");
        } else {
            System.out.println("Testes do sistema com falha: " + falhas + " de " + total);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ protocolo

    private static void testarCrcEPacotes() {
        titulo("CRC-16 e quadros");
        verificar("CRC-16/CCITT-FALSE de \"123456789\" é 0x29B1",
                Crc16.calcular("123456789".getBytes(), 0, 9) == 0x29B1);

        Pacote p = new Pacote(Protocolo.CMD_LER_LINHA, 7, new byte[] {1, 2});
        byte[] bytes = p.codificar();
        verificar("quadro tem cabeçalho + payload + CRC", bytes.length == 5 + 2 + 2);
        verificar("começa com A5 5A", (bytes[0] & 0xFF) == 0xA5 && (bytes[1] & 0xFF) == 0x5A);
        DecodificadorPacotes d = new DecodificadorPacotes();
        List<Pacote> lidos = d.alimentar(bytes, bytes.length);
        verificar("decodifica o que codificou", lidos.size() == 1 && lidos.get(0).getTipo() == Protocolo.CMD_LER_LINHA
                && lidos.get(0).getSeq() == 7 && Arrays.equals(lidos.get(0).getPayload(), new byte[] {1, 2}));

        verificar("payload de 240 bytes é aceito", new Pacote(1, 0, new byte[240]).codificar().length == 247);
        verificar("payload de 241 bytes é recusado", lanca(() -> new Pacote(1, 0, new byte[241])));
        verificar("tipo fora de 0..255 é recusado", lanca(() -> new Pacote(256, 0, null)));
        verificar("payload é copiado (encapsulamento)", copiaProtegida());
    }

    private static boolean copiaProtegida() {
        byte[] original = {1, 2, 3};
        Pacote p = new Pacote(1, 0, original);
        original[0] = 99;
        byte[] devolvido = p.getPayload();
        devolvido[1] = 77;
        return p.getPayload()[0] == 1 && p.getPayload()[1] == 2;
    }

    private static void testarDecodificador() {
        titulo("Decodificador (ruído, quadros partidos e colados)");
        Pacote a = new Pacote(0xF0, 1, new byte[36]);
        Pacote b = new Pacote(Protocolo.RSP_ACK, 2, new byte[0]);
        Pacote c = new Pacote(Protocolo.RSP_PARAMS, 3, new byte[47]);
        byte[] ba = a.codificar();
        byte[] bb = b.codificar();
        byte[] bc = c.codificar();

        byte[] colados = concat(ba, bb, bc);
        DecodificadorPacotes d1 = new DecodificadorPacotes();
        verificar("três quadros colados saem na ordem",
                tipos(d1.alimentar(colados, colados.length)).equals(Arrays.asList(0xF0, 0x80, 0x90)));

        DecodificadorPacotes d2 = new DecodificadorPacotes();
        List<Pacote> um = new ArrayList<>();
        for (byte x : colados) {
            um.addAll(d2.alimentar(new byte[] {x}, 1));
        }
        verificar("entregando 1 byte por vez dá o mesmo resultado", tipos(um).equals(Arrays.asList(0xF0, 0x80, 0x90)));

        byte[] comLixo = concat(new byte[] {1, 2, 3, (byte) 0xA5, 9, 9}, bb, new byte[] {(byte) 0xA5}, bc);
        DecodificadorPacotes d3 = new DecodificadorPacotes();
        verificar("lixo (inclusive um A5 solto) antes e entre quadros é ignorado",
                tipos(d3.alimentar(comLixo, comLixo.length)).equals(Arrays.asList(0x80, 0x90)));
        verificar("bytes descartados são contados", d3.getBytesDescartados() >= 7);

        byte[] corrompido = bb.clone();
        corrompido[corrompido.length - 1] ^= 0x01;
        byte[] sequencia = concat(corrompido, bc);
        DecodificadorPacotes d4 = new DecodificadorPacotes();
        verificar("quadro com CRC errado é descartado e o seguinte é aproveitado",
                tipos(d4.alimentar(sequencia, sequencia.length)).equals(Arrays.asList(0x90)));
        verificar("erro de CRC é contado", d4.getErrosCrc() == 1);

        // A5 5A com LEN absurdo não pode "engolir" o quadro verdadeiro que vem logo depois
        byte[] lenInvalido = concat(new byte[] {(byte) 0xA5, 0x5A, (byte) 0xFF, 0x01, 0x00}, bb);
        DecodificadorPacotes d5 = new DecodificadorPacotes();
        verificar("LEN inválido (> 240) é descartado sem perder o quadro seguinte",
                tipos(d5.alimentar(lenInvalido, lenInvalido.length)).equals(Arrays.asList(0x80)));

        // quadro cujo payload contém A5 5A: não pode confundir o decodificador
        byte[] armadilha = new byte[40];
        for (int i = 0; i + 1 < armadilha.length; i += 2) {
            armadilha[i] = (byte) 0xA5;
            armadilha[i + 1] = 0x5A;
        }
        byte[] qa = new Pacote(0x90, 9, armadilha).codificar();
        DecodificadorPacotes d6 = new DecodificadorPacotes();
        List<Pacote> r6 = d6.alimentar(qa, qa.length);
        verificar("payload cheio de A5 5A sai inteiro", r6.size() == 1 && Arrays.equals(r6.get(0).getPayload(), armadilha));

        // estresse: 2000 quadros aleatórios com ruído e fragmentação aleatória
        Random sorteio = new Random(1234);
        List<Pacote> enviados = new ArrayList<>();
        java.io.ByteArrayOutputStream fluxo = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 2000; i++) {
            byte[] payload = new byte[sorteio.nextInt(60)];
            sorteio.nextBytes(payload);
            Pacote p = new Pacote(0x80 + sorteio.nextInt(0x70), sorteio.nextInt(256), payload);
            enviados.add(p);
            if (sorteio.nextInt(4) == 0) {
                byte[] ruido = new byte[1 + sorteio.nextInt(4)];
                for (int k = 0; k < ruido.length; k++) {
                    ruido[k] = (byte) (sorteio.nextInt(0xA0)); // ruído sem 0xA5 para não formar falso SOF
                }
                fluxo.write(ruido, 0, ruido.length);
            }
            byte[] q = p.codificar();
            fluxo.write(q, 0, q.length);
        }
        byte[] tudo = fluxo.toByteArray();
        DecodificadorPacotes d7 = new DecodificadorPacotes();
        List<Pacote> recebidos = new ArrayList<>();
        int pos = 0;
        while (pos < tudo.length) {
            int n = Math.min(1 + sorteio.nextInt(40), tudo.length - pos);
            recebidos.addAll(d7.alimentar(Arrays.copyOfRange(tudo, pos, pos + n), n));
            pos += n;
        }
        boolean iguais = recebidos.size() == enviados.size();
        for (int i = 0; iguais && i < enviados.size(); i++) {
            Pacote e = enviados.get(i);
            Pacote r = recebidos.get(i);
            iguais = e.getTipo() == r.getTipo() && e.getSeq() == r.getSeq()
                    && Arrays.equals(e.getPayload(), r.getPayload());
        }
        verificar("2000 quadros aleatórios, fragmentados e com ruído: todos recuperados na ordem", iguais);
    }

    // ------------------------------------------------------------------ modelo

    private static void testarModelo() throws Exception {
        titulo("Modelo de dados");

        Telemetria t = new Telemetria.Construtor().tempoMs(123456).rpm(3250).velocidade(87.3).ect(88.5).iat(31.2)
                .map(55.5).oleo(3.4).tps(42.1).lambda(0.953).bateria(13.87).avanco(-3.5).pwUs(4321).ve(88).ae(12)
                .iac(40).posicao(5.5, 9.25).flags(Protocolo.FLAG_SYNC | Protocolo.FLAG_CORTE).erros(Protocolo.ERR_MAP)
                .construir();
        byte[] bytes = t.paraBytes();
        verificar("telemetria ocupa 36 bytes", bytes.length == Telemetria.TAMANHO && Telemetria.TAMANHO == 36);
        Telemetria r = Telemetria.deBytes(bytes);
        verificar("telemetria: ida e volta preserva os campos",
                r.getTempoMs() == 123456 && r.getRpm() == 3250 && Math.abs(r.getVelocidadeKmh() - 87.3) < 1e-9
                        && Math.abs(r.getEctC() - 88.5) < 1e-9 && Math.abs(r.getMapKpa() - 55.5) < 1e-9
                        && Math.abs(r.getLambda() - 0.953) < 1e-9 && Math.abs(r.getBateriaV() - 13.87) < 1e-9
                        && Math.abs(r.getAvancoGraus() + 3.5) < 1e-9 && r.getPwUs() == 4321 && r.getVePct() == 88
                        && Math.abs(r.getPosRpm() - 5.5) < 1e-9 && Math.abs(r.getPosCarga() - 9.25) < 1e-9
                        && r.temFlag(Protocolo.FLAG_CORTE) && !r.temFlag(Protocolo.FLAG_LAUNCH)
                        && r.temErro(Protocolo.ERR_MAP));
        verificar("temperatura negativa passa pelo int16 com sinal",
                Math.abs(Telemetria.deBytes(new Telemetria.Construtor().ect(-12.3).construir().paraBytes()).getEctC()
                        + 12.3) < 1e-9);
        verificar("AFR = lambda x estequiométrico", Math.abs(r.getAfr(13.0) - 0.953 * 13.0) < 1e-9);
        verificar("tamanho errado é recusado", lanca(() -> Telemetria.deBytes(new byte[35])));

        InfoECU info = new InfoECU(1, 1, 2, 1, 16, 3, InfoECU.CAP_SAIDAS_REAIS);
        InfoECU info2 = InfoECU.deBytes(info.paraBytes());
        verificar("InfoECU: ida e volta", info2.getFirmware().equals("1.2") && info2.temSaidasReais()
                && info2.getNomePlaca().equals("ESP32") && info2.getNumMapas() == 3);

        ParametrosMotor p = ParametrosMotor.padrao();
        verificar("parâmetros: 32 campos e 47 bytes", CampoParametro.values().length == 32
                && ParametrosMotor.TAMANHO == 47 && p.paraBytes().length == 47);
        p.set(CampoParametro.REV_LIMITE_RPM, 7000);
        p.setBool(CampoParametro.HAB_LAUNCH, true);
        verificar("parâmetros: ida e volta", ParametrosMotor.deBytes(p.paraBytes()).equals(p));
        verificar("parâmetro fora da faixa é recusado", lanca(() -> p.set(CampoParametro.CILINDROS, 9)));
        verificar("padrão do AFR estequiométrico é 13,0 (gasolina E27-E30)",
                ParametrosMotor.padrao().getFisico(CampoParametro.AFR_ESTEQ_X10) == 13.0);
        boolean todosPadroesValidos = true;
        for (CampoParametro c : CampoParametro.values()) {
            todosPadroesValidos &= c.dentroDaFaixa(c.getPadrao());
        }
        verificar("todos os padrões estão dentro da própria faixa", todosPadroesValidos);

        EixosMapa eixos = EixosMapa.padrao();
        verificar("eixos: ida e volta", EixosMapa.deBytes(eixos.paraBytes()).equals(eixos));
        int[] rpmRuim = eixos.getRpmArray();
        rpmRuim[5] = rpmRuim[4];
        verificar("eixo não crescente é recusado", lanca(() -> EixosMapa.deValores(rpmRuim, eixos.getCargaArray())));
        verificar("posição no eixo: nó exato e ponto médio",
                eixos.posicaoRpm(1500) == 3.0 && Math.abs(eixos.posicaoRpm(1750) - 3.5) < 1e-9
                        && eixos.posicaoRpm(100) == 0 && eixos.posicaoRpm(99999) == 15);

        MapaECU ve = MapaECU.padrao(TipoMapa.COMBUSTIVEL);
        verificar("mapa: interpolação em nó é o valor do nó", ve.interpolarRaw(4, 6) == ve.getRaw(6, 4));
        double meio = (ve.getRaw(6, 4) + ve.getRaw(6, 5)) / 2.0;
        verificar("mapa: interpolação no meio entre duas colunas", Math.abs(ve.interpolarRaw(4.5, 6) - meio) < 1e-9);
        verificar("mapa: interpolação nos cantos não estoura o índice",
                !Double.isNaN(ve.interpolarRaw(15, 15)) && !Double.isNaN(ve.interpolarRaw(0, 0)));
        verificar("conversões: avanço raw 60 = 20,0° e volta",
                Math.abs(TipoMapa.IGNICAO.rawParaFisico(60) - 20.0) < 1e-9 && TipoMapa.IGNICAO.fisicoParaRaw(20.0) == 60);
        verificar("conversões: lambda raw 80 = 1,000", Math.abs(TipoMapa.ALVO_LAMBDA.rawParaFisico(80) - 1.0) < 1e-9);
        MapaECU copia = ve.copia();
        copia.setRaw(0, 0, 200);
        verificar("copia do mapa é independente", ve.getRaw(0, 0) != 200);
        verificar("setRaw limita em 0..255", limitado());

        Calibracao cal = Calibracao.padrao();
        cal.getParametros().set(CampoParametro.REV_LIMITE_RPM, 7123);
        cal.getMapa(TipoMapa.IGNICAO).setRaw(3, 4, 99);
        cal.getEixos().setRpm(15, 8000);
        Path arquivo = Files.createTempFile("calibracao", ".kecu");
        cal.salvar(arquivo);
        Calibracao lida = Calibracao.carregar(arquivo);
        boolean igual = lida.getParametros().equals(cal.getParametros()) && lida.getEixos().equals(cal.getEixos());
        for (TipoMapa tipo : TipoMapa.values()) {
            igual &= lida.getMapa(tipo).equals(cal.getMapa(tipo));
        }
        verificar("arquivo .kecu: salvar e abrir devolve a mesma calibração", igual);
        Files.write(arquivo, Arrays.asList("parametro.CILINDROS=99"));
        verificar("arquivo .kecu com valor fora da faixa gera IOException", lancaIO(() -> Calibracao.carregar(arquivo)));
        Files.delete(arquivo);
    }

    private static boolean limitado() {
        MapaECU m = MapaECU.padrao(TipoMapa.IGNICAO);
        m.setRaw(0, 0, 999);
        m.setRaw(0, 1, -5);
        return m.getRaw(0, 0) == 255 && m.getRaw(0, 1) == 0;
    }

    // ------------------------------------------------------------------ simulador (protocolo, sem threads)

    private static Pacote perguntar(SimuladorECU sim, int comando, byte... payload) {
        byte[] pedido = new Pacote(comando, 42, payload).codificar();
        byte[] saida = sim.processar(pedido, pedido.length);
        List<Pacote> respostas = new DecodificadorPacotes().alimentar(saida, saida.length);
        return respostas.isEmpty() ? null : respostas.get(0);
    }

    private static int codigoNack(Pacote p) {
        return p != null && p.getTipo() == Protocolo.RSP_NACK ? p.getPayload()[0] & 0xFF : -1;
    }

    private static boolean ack(Pacote p) {
        return p != null && p.getTipo() == Protocolo.RSP_ACK && p.getSeq() == 42;
    }

    private static void rodar(SimuladorECU sim, double segundos) {
        for (int i = 0; i < segundos / 0.01; i++) {
            sim.avancar(0.01);
        }
    }

    private static byte[] u16(int v) {
        return BufferLE.paraEscrita(2).escreverU16(v).bytes();
    }

    private static void testarSimuladorProtocolo() {
        titulo("ECU simulada: comandos do protocolo");
        SimuladorECU sim = new SimuladorECU();

        Pacote hello = perguntar(sim, Protocolo.CMD_HELLO);
        InfoECU info = InfoECU.deBytes(hello.getPayload());
        verificar("HELLO responde INFO com o mesmo SEQ", hello.getTipo() == Protocolo.RSP_INFO && hello.getSeq() == 42
                && info.getVersaoProtocolo() == Protocolo.VERSAO && !info.temSaidasReais());

        verificar("comando desconhecido: NACK 1", codigoNack(perguntar(sim, 0x7E)) == Protocolo.ERRO_COMANDO_DESCONHECIDO);
        verificar("STREAM com tamanho errado: NACK 2", codigoNack(perguntar(sim, Protocolo.CMD_STREAM, (byte) 1))
                == Protocolo.ERRO_TAMANHO);
        verificar("STREAM com período 3 ms: NACK 3", codigoNack(perguntar(sim, Protocolo.CMD_STREAM, u16(3)))
                == Protocolo.ERRO_FAIXA);
        verificar("STREAM com período 2000 ms: NACK 3", codigoNack(perguntar(sim, Protocolo.CMD_STREAM, u16(2000)))
                == Protocolo.ERRO_FAIXA);
        verificar("sem STREAM não há telemetria", sim.avancar(1.0).length == 0);
        verificar("STREAM 20 ms: ACK", ack(perguntar(sim, Protocolo.CMD_STREAM, u16(20))));
        byte[] quadros = sim.avancar(1.0);
        List<Pacote> lidos = new DecodificadorPacotes().alimentar(quadros, quadros.length);
        verificar("1 s de telemetria a 20 ms = 50 quadros (±1)", Math.abs(lidos.size() - 50) <= 1);
        verificar("quadros são telemetria de 36 bytes", lidos.get(0).getTipo() == Protocolo.TELEMETRIA
                && lidos.get(0).getTamanhoPayload() == 36);
        verificar("STREAM 0 desliga", ack(perguntar(sim, Protocolo.CMD_STREAM, u16(0))) && sim.avancar(1.0).length == 0);

        Pacote params = perguntar(sim, Protocolo.CMD_LER_PARAMS);
        verificar("LER_PARAMS devolve 47 bytes iguais ao padrão", params.getTipo() == Protocolo.RSP_PARAMS
                && ParametrosMotor.deBytes(params.getPayload()).equals(ParametrosMotor.padrao()));
        ParametrosMotor novos = ParametrosMotor.padrao();
        novos.set(CampoParametro.REV_LIMITE_RPM, 6200);
        novos.setBool(CampoParametro.HAB_TWO_STEP, true);
        verificar("ESCREVER_PARAMS: ACK", ack(perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS, novos.paraBytes())));
        verificar("ESCREVER_PARAMS: valor aparece na releitura", ParametrosMotor.deBytes(
                perguntar(sim, Protocolo.CMD_LER_PARAMS).getPayload()).get(CampoParametro.REV_LIMITE_RPM) == 6200);
        byte[] ruim = novos.paraBytes();
        ruim[CampoParametro.REV_LIMITE_RPM.ordinal()] = 0; // mexe em byte de outro campo: gera valor inválido em algum ponto
        byte[] foraDaFaixa = novos.paraBytes();
        int posCilindros = posicaoNoPayload(CampoParametro.CILINDROS);
        foraDaFaixa[posCilindros] = 9;
        verificar("ESCREVER_PARAMS com campo fora da faixa: NACK 3 e nada é aplicado",
                codigoNack(perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS, foraDaFaixa)) == Protocolo.ERRO_FAIXA
                        && sim.getCalibracaoRam().getParametros().get(CampoParametro.CILINDROS) == 4);
        verificar("ESCREVER_PARAMS com tamanho errado: NACK 2", codigoNack(perguntar(sim,
                Protocolo.CMD_ESCREVER_PARAMS, new byte[10])) == Protocolo.ERRO_TAMANHO);

        Pacote linha = perguntar(sim, Protocolo.CMD_LER_LINHA, (byte) 1, (byte) 4);
        byte[] esperada = MapaECU.padrao(TipoMapa.IGNICAO).linhaParaBytes(4);
        verificar("LER_LINHA devolve [mapa, linha, 16 valores]", linha.getTipo() == Protocolo.RSP_LINHA
                && linha.getPayload()[0] == 1 && linha.getPayload()[1] == 4
                && Arrays.equals(Arrays.copyOfRange(linha.getPayload(), 2, 18), esperada));
        verificar("LER_LINHA com mapa 3: NACK 3", codigoNack(perguntar(sim, Protocolo.CMD_LER_LINHA, (byte) 3, (byte) 0))
                == Protocolo.ERRO_FAIXA);
        verificar("LER_LINHA com linha 16: NACK 3", codigoNack(perguntar(sim, Protocolo.CMD_LER_LINHA, (byte) 0, (byte) 16))
                == Protocolo.ERRO_FAIXA);
        byte[] escrita = new byte[18];
        escrita[0] = 0;
        escrita[1] = 2;
        for (int i = 0; i < 16; i++) {
            escrita[2 + i] = (byte) (100 + i);
        }
        verificar("ESCREVER_LINHA: ACK", ack(perguntar(sim, Protocolo.CMD_ESCREVER_LINHA, escrita)));
        verificar("ESCREVER_LINHA: releitura devolve o que foi escrito",
                sim.getCalibracaoRam().getMapa(TipoMapa.COMBUSTIVEL).getRaw(2, 5) == 105);
        verificar("ESCREVER_LINHA com tamanho errado: NACK 2", codigoNack(perguntar(sim, Protocolo.CMD_ESCREVER_LINHA,
                new byte[5])) == Protocolo.ERRO_TAMANHO);

        Pacote eixos = perguntar(sim, Protocolo.CMD_LER_EIXOS);
        verificar("LER_EIXOS devolve 64 bytes", eixos.getTipo() == Protocolo.RSP_EIXOS && eixos.getTamanhoPayload() == 64);
        EixosMapa novosEixos = EixosMapa.padrao();
        novosEixos.setRpm(15, 8000);
        verificar("ESCREVER_EIXOS: ACK", ack(perguntar(sim, Protocolo.CMD_ESCREVER_EIXOS, novosEixos.paraBytes())));
        byte[] eixosRuins = EixosMapa.padrao().paraBytes();
        eixosRuins[4] = eixosRuins[2];
        eixosRuins[5] = eixosRuins[3]; // colunas 2 e 2 iguais -> não crescente
        verificar("ESCREVER_EIXOS não crescente: NACK 3", codigoNack(perguntar(sim, Protocolo.CMD_ESCREVER_EIXOS, eixosRuins))
                == Protocolo.ERRO_FAIXA);

        verificar("CHAVES: ACK e vira flag", ack(perguntar(sim, Protocolo.CMD_CHAVES, (byte) 3)) && sim.getChaves() == 3);
        verificar("ARMAR(1) no simulador: NACK 6", codigoNack(perguntar(sim, Protocolo.CMD_ARMAR, (byte) 1))
                == Protocolo.ERRO_SAIDAS_DESABILITADAS);
        verificar("ARMAR(0): ACK", ack(perguntar(sim, Protocolo.CMD_ARMAR, (byte) 0)));

        rodar(sim, 3);
        verificar("motor girando: GRAVAR é recusado (NACK 4)", codigoNack(perguntar(sim, Protocolo.CMD_GRAVAR))
                == Protocolo.ERRO_MOTOR_GIRANDO);
        verificar("motor girando: PADROES é recusado (NACK 4)", codigoNack(perguntar(sim, Protocolo.CMD_PADROES))
                == Protocolo.ERRO_MOTOR_GIRANDO);
        ParametrosMotor trocaSinal = sim.getCalibracaoRam().getParametros();
        trocaSinal.set(CampoParametro.DENTES_RODA, 36);
        verificar("motor girando: mudar dentes da roda é recusado (NACK 4)", codigoNack(perguntar(sim,
                Protocolo.CMD_ESCREVER_PARAMS, trocaSinal.paraBytes())) == Protocolo.ERRO_MOTOR_GIRANDO);
        ParametrosMotor trocaVss = sim.getCalibracaoRam().getParametros();
        trocaVss.set(CampoParametro.VSS_PULSOS_KM, 5000);
        verificar("motor girando: mudar pulsos do VSS é permitido", ack(perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS,
                trocaVss.paraBytes())));

        sim.getCarro().setMotorLigado(false);
        rodar(sim, 4);
        verificar("motor desligado: rpm chega a zero", sim.getUltimaTelemetria().getRpm() == 0);
        verificar("motor parado: GRAVAR é aceito", ack(perguntar(sim, Protocolo.CMD_GRAVAR)));
        verificar("GRAVAR copia a RAM para a memória não volátil",
                sim.getCalibracaoNvm().getMapa(TipoMapa.COMBUSTIVEL).getRaw(2, 5) == 105
                        && sim.getCalibracaoNvm().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6200);
        ParametrosMotor temporarios = sim.getCalibracaoRam().getParametros();
        temporarios.set(CampoParametro.REV_LIMITE_RPM, 5000);
        perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS, temporarios.paraBytes());
        sim.reiniciarPlaca();
        verificar("reiniciar a placa descarta o que não foi gravado",
                sim.getCalibracaoRam().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6200);
        verificar("motor parado: mudar dentes da roda é permitido", ack(perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS,
                trocaSinal.paraBytes())));
        verificar("PADROES carrega os valores de fábrica na RAM (sem gravar)", ack(perguntar(sim, Protocolo.CMD_PADROES))
                && sim.getCalibracaoRam().getParametros().equals(ParametrosMotor.padrao())
                && sim.getCalibracaoNvm().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6200);
    }

    private static int posicaoNoPayload(CampoParametro alvo) {
        int pos = 0;
        for (CampoParametro c : CampoParametro.values()) {
            if (c == alvo) {
                return pos;
            }
            pos += c.getTipo().getBytes();
        }
        throw new IllegalStateException();
    }

    // ------------------------------------------------------------------ simulador (lógica de controle)

    private static void testarSimuladorLogica() {
        titulo("ECU simulada: limitador, two-step, launch, enriquecimento e marcha lenta");

        // marcha lenta
        SimuladorECU sim = new SimuladorECU();
        sim.getCarro().setPedalManual(0);
        rodar(sim, 6);
        Telemetria t = sim.getUltimaTelemetria();
        verificar("marcha lenta: rotação perto do alvo (motor morno)", t.getRpm() > 900 && t.getRpm() < 1050);
        verificar("marcha lenta: flag e válvula IAC ativas", t.temFlag(Protocolo.FLAG_MARCHA_LENTA) && t.getIacPct() > 0);
        verificar("sem pedal não há corte", !t.temFlag(Protocolo.FLAG_CORTE));

        // limitador com histerese
        ParametrosMotor p = sim.getCalibracaoRam().getParametros();
        p.set(CampoParametro.REV_LIMITE_RPM, 5000);
        p.set(CampoParametro.REV_HISTERESE_RPM, 200);
        perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS, p.paraBytes());
        sim.getCarro().setPedalManual(100);
        rodar(sim, 2);
        int max = 0;
        int min = Integer.MAX_VALUE;
        int ligadas = 0;
        boolean anterior = false;
        for (int i = 0; i < 300; i++) {
            sim.avancar(0.005);
            Telemetria x = sim.getUltimaTelemetria();
            max = Math.max(max, x.getRpm());
            min = Math.min(min, x.getRpm());
            boolean corte = x.temFlag(Protocolo.FLAG_CORTE);
            if (corte && !anterior) {
                ligadas++;
            }
            anterior = corte;
        }
        verificar("limitador: rotação nunca passa muito de 5000 (máx " + max + ")", max <= 5120);
        verificar("limitador: rotação chega perto de 5000 (máx " + max + ")", max >= 4990);
        verificar("limitador: histerese de 200 rpm (mín " + min + ")", min >= 4650 && min <= 4850);
        verificar("limitador: o corte liga e desliga várias vezes (" + ligadas + ")", ligadas >= 5);
        Telemetria durante = null;
        for (int i = 0; i < 400 && durante == null; i++) {
            sim.avancar(0.005);
            if (sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_CORTE)) {
                durante = sim.getUltimaTelemetria();
            }
        }
        verificar("durante o corte de combustível o pulso do bico é zero", durante != null && durante.getPwUs() == 0);

        // corte só de ignição: o bico continua pulsando
        p.set(CampoParametro.TIPO_CORTE, 1);
        perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS, p.paraBytes());
        Telemetria soIgn = null;
        for (int i = 0; i < 800 && soIgn == null; i++) {
            sim.avancar(0.005);
            if (sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_CORTE)) {
                soIgn = sim.getUltimaTelemetria();
            }
        }
        verificar("corte só de ignição: o pulso do bico continua", soIgn != null && soIgn.getPwUs() > 0);
        p.set(CampoParametro.TIPO_CORTE, 2);

        // two-step
        p.set(CampoParametro.REV_LIMITE_RPM, 6500);
        p.set(CampoParametro.REV_HISTERESE_RPM, 150);
        p.setBool(CampoParametro.HAB_TWO_STEP, true);
        p.set(CampoParametro.TWO_STEP_RPM, 4000);
        perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS, p.paraBytes());
        perguntar(sim, Protocolo.CMD_CHAVES, (byte) Protocolo.CHAVE_TWO_STEP);
        rodar(sim, 2);
        max = 0;
        for (int i = 0; i < 300; i++) {
            sim.avancar(0.005);
            max = Math.max(max, sim.getUltimaTelemetria().getRpm());
        }
        verificar("two-step: segura a rotação perto de 4000 (máx " + max + ")", max >= 3950 && max <= 4120);
        verificar("two-step: flags de chave e de modo", sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_TWO_STEP)
                && sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_CHAVE_TWO_STEP));
        perguntar(sim, Protocolo.CMD_CHAVES, (byte) 0);
        rodar(sim, 2);
        verificar("soltando a chave o limite volta a ser o normal", sim.getUltimaTelemetria().getRpm() > 6000);

        // launch (carro parado, TPS alto)
        p.setBool(CampoParametro.HAB_LAUNCH, true);
        p.set(CampoParametro.LAUNCH_RPM, 3000);
        p.set(CampoParametro.LAUNCH_RETARDO, 8);
        perguntar(sim, Protocolo.CMD_ESCREVER_PARAMS, p.paraBytes());
        perguntar(sim, Protocolo.CMD_CHAVES, (byte) Protocolo.CHAVE_LAUNCH);
        rodar(sim, 2);
        max = 0;
        boolean avancoCerto = true;
        for (int i = 0; i < 300; i++) {
            sim.avancar(0.005);
            Telemetria x = sim.getUltimaTelemetria();
            max = Math.max(max, x.getRpm());
            Calibracao cal = sim.getCalibracaoRam();
            double sem = cal.getMapa(TipoMapa.IGNICAO).interpolarFisico(x.getPosRpm(), x.getPosCarga());
            avancoCerto &= Math.abs(x.getAvancoGraus() - (sem - 8)) < 1e-6 || !x.temFlag(Protocolo.FLAG_LAUNCH);
        }
        verificar("launch: segura a rotação perto de 3000 (máx " + max + ")", max >= 2950 && max <= 3120);
        verificar("launch: flag ligada", sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_LAUNCH));
        verificar("launch: avanço = valor do mapa - retardo (8°)", avancoCerto);
        sim.getCarro().setPedalManual(50);
        rodar(sim, 1);
        verificar("launch só arma com TPS alto: com 50% a flag cai", !sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_LAUNCH));
        perguntar(sim, Protocolo.CMD_CHAVES, (byte) 0);

        // enriquecimento de aceleração
        sim.getCarro().setPedalManual(0);
        rodar(sim, 3);
        sim.getCarro().setPedalManual(100);
        boolean viuAe = false;
        double aeMax = 0;
        for (int i = 0; i < 40; i++) {
            sim.avancar(0.005);
            Telemetria x = sim.getUltimaTelemetria();
            viuAe |= x.temFlag(Protocolo.FLAG_AE);
            aeMax = Math.max(aeMax, x.getAePct());
        }
        verificar("enriquecimento: dispara com o pedal batendo no fundo", viuAe && aeMax > 0 && aeMax <= 30);
        sim.getCarro().setPedalManual(0);
        rodar(sim, 1);
        sim.getCarro().setPedalManual(40);
        rodar(sim, 1.5);
        verificar("enriquecimento: apaga depois da duração (400 ms) com o pedal parado",
                !sim.getUltimaTelemetria().temFlag(Protocolo.FLAG_AE));

        // pulso do injetor sobe com a carga
        sim.getCarro().setPedalManual(0);
        rodar(sim, 3);
        int pwLenta = sim.getUltimaTelemetria().getPwUs();
        sim.getCarro().setPedalManual(60);
        rodar(sim, 2);
        verificar("pulso do injetor cresce com a carga (" + pwLenta + " -> " + sim.getUltimaTelemetria().getPwUs() + " µs)",
                sim.getUltimaTelemetria().getPwUs() > 2 * pwLenta);

        // ciclo automático: sai do lugar e troca de marcha
        SimuladorECU auto = new SimuladorECU(7);
        double velMax = 0;
        int rpmMax = 0;
        for (int i = 0; i < 2500; i++) {
            auto.avancar(0.01);
            velMax = Math.max(velMax, auto.getUltimaTelemetria().getVelocidadeKmh());
            rpmMax = Math.max(rpmMax, auto.getUltimaTelemetria().getRpm());
        }
        verificar("ciclo automático: passa de 100 km/h em 25 s (máx " + (int) velMax + ")", velMax > 100);
        verificar("ciclo automático: rotação nunca passa do limitador (máx " + rpmMax + ")", rpmMax <= 6600);
    }

    // ------------------------------------------------------------------ conexão (threads) com a porta simulada

    private static class Registro implements OuvinteConexao {
        final List<EstadoConexao> estados = new CopyOnWriteArrayList<>();
        final List<String> mensagens = new CopyOnWriteArrayList<>();

        @Override
        public void aoMudarEstado(EstadoConexao estado) {
            estados.add(estado);
        }

        @Override
        public void aoRegistrar(String mensagem) {
            mensagens.add(mensagem);
        }
    }

    /**
     * Porta que só entrega o comando GRAVAR à ECU simulada depois de um atraso: imita o Arduino Mega, cuja EEPROM leva
     * cerca de 3 s para gravar um bloco novo (889 bytes a ~3,4 ms cada) e que só responde ao fim da gravação.
     */
    private static final class PortaGravaLenta implements PortaSerial {
        private final PortaSimulada destino;
        private final long atrasoMs;

        PortaGravaLenta(PortaSimulada destino, long atrasoMs) {
            this.destino = destino;
            this.atrasoMs = atrasoMs;
        }

        @Override
        public String getNome() {
            return destino.getNome();
        }

        @Override
        public void abrir() throws IOException {
            destino.abrir();
        }

        @Override
        public boolean estaAberta() {
            return destino.estaAberta();
        }

        @Override
        public int ler(byte[] buffer) throws IOException {
            return destino.ler(buffer);
        }

        @Override
        public void escrever(byte[] dados) throws IOException {
            if (dados.length >= 5 && (dados[3] & 0xFF) == Protocolo.CMD_GRAVAR) {
                final byte[] quadro = dados.clone();
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(atrasoMs);
                        destino.escrever(quadro);
                    } catch (InterruptedException | IOException e) {
                        // a porta foi fechada antes do fim da "gravação": nada a entregar
                    }
                }, "teste-gravacao-lenta");
                t.setDaemon(true);
                t.start();
                return;
            }
            destino.escrever(dados);
        }

        @Override
        public void fechar() {
            destino.fechar();
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

    private static void testarConexaoNormal() throws Exception {
        titulo("Conexão: porta simulada, threads e comandos");
        ConexaoECU conexao = new ConexaoECU();
        Registro registro = new Registro();
        conexao.adicionarOuvinte(registro);
        PortaSimulada porta = new PortaSimulada();
        porta.getSimulador().getCarro().setPedalManual(0);

        verificar("começa DESCONECTADO", conexao.getEstado() == EstadoConexao.DESCONECTADO);
        verificar("comando sem conexão gera ErroProtocolo", lancaErroProtocolo(conexao::lerParametros));

        InfoECU info = conexao.conectar(porta);
        verificar("conectar devolve a identificação da placa", info.getNomePlaca().equals("Simulador / PC")
                && info.getVersaoProtocolo() == Protocolo.VERSAO);
        verificar("estado CONECTADO e sequência CONECTANDO -> CONECTADO",
                conexao.getEstado() == EstadoConexao.CONECTADO
                        && registro.estados.equals(Arrays.asList(EstadoConexao.CONECTANDO, EstadoConexao.CONECTADO)));
        verificar("a telemetria começa a chegar", esperar(() -> conexao.getTelemetriasRecebidas() > 5, 1500));
        Thread.sleep(1300);
        verificar("taxa de telemetria perto de 50 Hz (" + String.format("%.1f", conexao.getTaxaTelemetria()) + ")",
                conexao.getTaxaTelemetria() > 40 && conexao.getTaxaTelemetria() < 60);
        verificar("sem erros de CRC na porta limpa", conexao.getErrosCrc() == 0 && conexao.getPacotesValidos() > 50);
        verificar("telemetria tem marcha lenta e motor girando", conexao.getUltimaTelemetria().getRpm() > 300
                && conexao.getUltimaTelemetria().temFlag(Protocolo.FLAG_SIMULADOR));

        // ler calibração inteira, com andamento
        AtomicInteger passos = new AtomicInteger();
        AtomicInteger ultimoTotal = new AtomicInteger();
        long inicio = System.nanoTime();
        Calibracao lida = conexao.lerCalibracao((feitos, tot) -> {
            passos.incrementAndGet();
            ultimoTotal.set(tot);
        });
        long ms = (System.nanoTime() - inicio) / 1_000_000L;
        verificar("lerCalibracao: 50 requisições com andamento (" + ms + " ms)", passos.get() == 50 && ultimoTotal.get() == 50);
        boolean igualPadrao = lida.getParametros().equals(ParametrosMotor.padrao())
                && lida.getEixos().equals(EixosMapa.padrao());
        for (TipoMapa tipo : TipoMapa.values()) {
            igualPadrao &= lida.getMapa(tipo).equals(MapaECU.padrao(tipo));
        }
        verificar("calibração lida é igual à de fábrica", igualPadrao);

        // escrever e reler
        lida.getParametros().set(CampoParametro.REV_LIMITE_RPM, 6800);
        lida.getMapa(TipoMapa.COMBUSTIVEL).setRaw(7, 9, 123);
        lida.getEixos().setRpm(15, 8000);
        conexao.escreverCalibracao(lida, null);
        Calibracao relida = conexao.lerCalibracao(null);
        verificar("escrever e reler devolve exatamente o que foi enviado", relida.getParametros().equals(lida.getParametros())
                && relida.getEixos().equals(lida.getEixos())
                && relida.getMapa(TipoMapa.COMBUSTIVEL).equals(lida.getMapa(TipoMapa.COMBUSTIVEL)));
        verificar("a ECU simulada recebeu os dados na RAM", porta.getSimulador().getCalibracaoRam().getMapa(
                TipoMapa.COMBUSTIVEL).getRaw(7, 9) == 123);

        // mudança linha a linha (ao vivo) reflete na telemetria
        MapaECU ve = relida.getMapa(TipoMapa.COMBUSTIVEL);
        for (int c = 0; c < 16; c++) {
            ve.setRaw(2, c, 110);
        }
        conexao.escreverLinha(ve, 2);
        verificar("escreverLinha: a linha mudou na ECU", porta.getSimulador().getCalibracaoRam().getMapa(
                TipoMapa.COMBUSTIVEL).getRaw(2, 0) == 110);
        MapaECU conferir = MapaECU.padrao(TipoMapa.COMBUSTIVEL);
        conexao.lerLinha(conferir, 2);
        verificar("lerLinha traz a linha alterada", conferir.getRaw(2, 15) == 110);

        // erros do protocolo chegam como ErroProtocolo com o código do NACK
        int codigoArmar = codigoErro(() -> conexao.armarSaidas(true));
        verificar("armar saídas no simulador: ErroProtocolo com código 6", codigoArmar == Protocolo.ERRO_SAIDAS_DESABILITADAS);
        int codigoGravar = codigoErro(conexao::gravarNaMemoria);
        verificar("gravar com o motor girando: ErroProtocolo com código 4", codigoGravar == Protocolo.ERRO_MOTOR_GIRANDO);
        conexao.definirChaves(Protocolo.CHAVE_TWO_STEP);
        verificar("chaves virtuais aparecem na telemetria", esperar(() -> conexao.getUltimaTelemetria()
                .temFlag(Protocolo.FLAG_CHAVE_TWO_STEP), 1000));
        conexao.definirChaves(0);
        conexao.definirPeriodoTelemetria(50);
        Thread.sleep(2300);
        verificar("período de 50 ms muda a taxa para ~20 Hz (" + String.format("%.1f", conexao.getTaxaTelemetria()) + ")",
                conexao.getTaxaTelemetria() > 15 && conexao.getTaxaTelemetria() < 25);
        conexao.definirPeriodoTelemetria(20);

        // tarefa em segundo plano
        CompletableFuture<String> futuro = conexao.emSegundoPlano(() -> Thread.currentThread().getName());
        verificar("emSegundoPlano roda na thread ecu-tarefas", "ecu-tarefas".equals(futuro.get(2, TimeUnit.SECONDS)));
        CompletableFuture<Calibracao> longa = conexao.emSegundoPlano(() -> conexao.lerCalibracao(null));
        verificar("o resultado do futuro é a calibração", longa.get(5, TimeUnit.SECONDS).getEixos().getRpm(15) == 8000);
        long antes = System.nanoTime();
        CompletableFuture<Object> lenta = conexao.emSegundoPlano(() -> {
            Thread.sleep(300);
            return "pronto";
        });
        long retornoMs = (System.nanoTime() - antes) / 1_000_000L;
        verificar("emSegundoPlano devolve o controle na hora (" + retornoMs + " ms); o futuro termina depois",
                retornoMs < 100 && !lenta.isDone() && "pronto".equals(lenta.get(2, TimeUnit.SECONDS)));
        CompletableFuture<Object> comErro = conexao.emSegundoPlano(() -> {
            throw new IllegalStateException("falha de teste");
        });
        verificar("exceção da tarefa chega no futuro", causaDe(comErro) instanceof IllegalStateException);

        // desligar o motor simulado e gravar
        porta.getSimulador().getCarro().setMotorLigado(false);
        verificar("motor simulado desligado: telemetria mostra rpm 0", esperar(() -> conexao.getUltimaTelemetria().getRpm() == 0, 4000));
        conexao.gravarNaMemoria();
        verificar("com o motor parado, gravar funciona", porta.getSimulador().getCalibracaoNvm().getMapa(TipoMapa.COMBUSTIVEL)
                .getRaw(7, 9) == 123);
        conexao.restaurarPadroes();
        verificar("restaurar padrões muda a RAM, não a memória gravada", porta.getSimulador().getCalibracaoRam()
                .getParametros().equals(ParametrosMotor.padrao())
                && porta.getSimulador().getCalibracaoNvm().getParametros().get(CampoParametro.REV_LIMITE_RPM) == 6800);

        conexao.desconectar();
        verificar("desconectar: estado DESCONECTADO", conexao.getEstado() == EstadoConexao.DESCONECTADO);
        verificar("desconectar: a porta foi fechada", !porta.estaAberta());
        verificar("depois de desconectar os comandos falham", lancaErroProtocolo(conexao::lerParametros));
        conexao.desconectar();
        verificar("desconectar duas vezes não dá erro", conexao.getEstado() == EstadoConexao.DESCONECTADO);

        PortaSimulada outra = new PortaSimulada();
        conexao.conectar(outra);
        verificar("reconectar com outra porta funciona", conexao.getEstado() == EstadoConexao.CONECTADO
                && esperar(() -> conexao.getTelemetriasRecebidas() > 5, 1500));
        conexao.desconectar();
    }

    private static void testarFalhas() throws Exception {
        titulo("Conexão: falhas (silêncio, queda de cabo, reinício da placa, ruído)");

        // placa muda: conectar falha, estado volta a DESCONECTADO e nada fica pendurado
        ConexaoECU c1 = new ConexaoECU();
        PortaSimulada muda = new PortaSimulada();
        muda.getSimulador().setMudo(true);
        long t0 = System.nanoTime();
        boolean falhou = false;
        try {
            c1.conectar(muda);
        } catch (ErroProtocolo e) {
            falhou = true;
        }
        long gasto = (System.nanoTime() - t0) / 1_000_000L;
        verificar("placa que não responde: conectar falha com ErroProtocolo (" + gasto + " ms)", falhou && gasto < 5000);
        verificar("depois da falha o estado é DESCONECTADO e a porta está fechada",
                c1.getEstado() == EstadoConexao.DESCONECTADO && !muda.estaAberta());

        // porta que nem abre
        ConexaoECU c2 = new ConexaoECU();
        PortaSimulada quebrada = new PortaSimulada();
        quebrada.abrir();
        boolean io = lancaIO(() -> c2.conectar(quebrada));
        quebrada.fechar();
        verificar("porta que não abre: IOException e estado DESCONECTADO", io && c2.getEstado() == EstadoConexao.DESCONECTADO);

        // SEM_SINAL e recuperação
        ConexaoECU c3 = new ConexaoECU();
        Registro r3 = new Registro();
        c3.adicionarOuvinte(r3);
        PortaSimulada p3 = new PortaSimulada();
        p3.getSimulador().getCarro().setPedalManual(0);
        c3.conectar(p3);
        esperar(() -> c3.getTelemetriasRecebidas() > 10, 2000);
        p3.getSimulador().setTelemetriaPausada(true);
        verificar("telemetria pausada: vai para SEM_SINAL em ~1 s", esperar(() -> c3.getEstado() == EstadoConexao.SEM_SINAL, 2500));
        verificar("em SEM_SINAL os comandos ainda funcionam", c3.lerParametros().equals(ParametrosMotor.padrao()));
        p3.getSimulador().setTelemetriaPausada(false);
        verificar("telemetria volta: estado volta a CONECTADO", esperar(() -> c3.getEstado() == EstadoConexao.CONECTADO, 2500));

        // placa reiniciou (envio desligado): o vigia religa sozinho
        p3.getSimulador().reiniciarPlaca();
        verificar("placa reiniciada: SEM_SINAL", esperar(() -> c3.getEstado() == EstadoConexao.SEM_SINAL, 2500));
        verificar("o aplicativo pede a telemetria de novo e volta a CONECTADO", esperar(() -> c3.getEstado()
                == EstadoConexao.CONECTADO, 4000));

        // ruído e quadros partidos
        p3.setRuido(true);
        long crcAntes = c3.getErrosCrc();
        Calibracao comRuido = c3.lerCalibracao(null);
        verificar("com ruído na linha a leitura da calibração ainda dá certo (tentativas)", comRuido.getEixos().getRpm(0) == 500);
        Thread.sleep(1500);
        verificar("com ruído: o CRC pega quadros estragados (" + (c3.getErrosCrc() - crcAntes) + ") e a conexão segue",
                c3.getErrosCrc() > crcAntes && c3.getEstado() == EstadoConexao.CONECTADO);
        p3.setRuido(false);

        // cabo puxado
        p3.simularQuedaDoCabo();
        verificar("cabo puxado: estado ERRO", esperar(() -> c3.getEstado() == EstadoConexao.ERRO, 2000));
        verificar("cabo puxado: comandos falham com ErroProtocolo", lancaErroProtocolo(c3::lerParametros));
        verificar("cabo puxado: o ouvinte foi avisado", r3.estados.contains(EstadoConexao.ERRO)
                && r3.mensagens.stream().anyMatch(m -> m.contains("fechada") || m.contains("removido")));
        c3.desconectar();
        verificar("desconectar depois do erro volta a DESCONECTADO", c3.getEstado() == EstadoConexao.DESCONECTADO);
        PortaSimulada nova = new PortaSimulada();
        c3.conectar(nova);
        verificar("depois da queda dá para conectar de novo", c3.getEstado() == EstadoConexao.CONECTADO);
        c3.desconectar();

        // conectar() estando em ERRO (sem desconectar antes) não vaza nada
        ConexaoECU c4 = new ConexaoECU();
        PortaSimulada p4 = new PortaSimulada();
        c4.conectar(p4);
        p4.simularQuedaDoCabo();
        esperar(() -> c4.getEstado() == EstadoConexao.ERRO, 2000);
        PortaSimulada p5 = new PortaSimulada();
        c4.conectar(p5);
        verificar("conectar direto a partir do estado ERRO funciona", c4.getEstado() == EstadoConexao.CONECTADO);
        c4.desconectar();

        // GRAVAR demorado (EEPROM do Mega): o "ok" chega depois de 2,6 s, mais do que as 3 tentativas de 700 ms dos outros
        // comandos esperariam (2,1 s). A gravação precisa ser dada como feita, e não como "a ECU não respondeu".
        ConexaoECU c6 = new ConexaoECU();
        PortaSimulada p6 = new PortaSimulada();
        p6.getSimulador().getCarro().setPedalManual(0);
        c6.conectar(new PortaGravaLenta(p6, 2600));
        p6.getSimulador().getCarro().setMotorLigado(false);
        verificar("GRAVAR lento: motor simulado parado", esperar(() -> c6.getUltimaTelemetria().getRpm() == 0, 4000));
        EixosMapa eixosGravar = EixosMapa.padrao();
        eixosGravar.setRpm(15, 7900);
        c6.escreverEixos(eixosGravar);
        long inicioGravar = System.nanoTime();
        boolean gravou;
        try {
            c6.gravarNaMemoria();
            gravou = true;
        } catch (ErroProtocolo e) {
            gravou = false;
        }
        long msGravar = (System.nanoTime() - inicioGravar) / 1_000_000L;
        verificar("GRAVAR que demora 2,6 s (como a EEPROM do Mega) dá certo e espera o ok (" + msGravar + " ms)",
                gravou && msGravar >= 2500 && msGravar < 6000);
        verificar("GRAVAR lento: a memória da ECU recebeu a calibração", p6.getSimulador().getCalibracaoNvm().getEixos()
                .getRpm(15) == 7900);
        c6.desconectar();
    }

    private static void testarConcorrencia() throws Exception {
        titulo("Conexão: vários comandos ao mesmo tempo com a telemetria rodando");
        ConexaoECU conexao = new ConexaoECU();
        PortaSimulada porta = new PortaSimulada();
        conexao.conectar(porta);
        int linhasPorThread = 300;
        List<Throwable> erros = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            final int id = n;
            Thread t = new Thread(() -> {
                try {
                    MapaECU mapa = MapaECU.padrao(TipoMapa.values()[id % 3]);
                    for (int i = 0; i < linhasPorThread; i++) {
                        int linha = (i + id) % 16;
                        for (int c = 0; c < 16; c++) {
                            mapa.setRaw(linha, c, (id * 50 + i + c) & 0xFF);
                        }
                        conexao.escreverLinha(mapa, linha);
                        MapaECU lido = MapaECU.padrao(mapa.getTipo());
                        conexao.lerLinha(lido, linha);
                        // outra thread pode ter escrito na mesma linha do mesmo mapa; só conferimos que veio uma linha completa
                        if (lido.linhaParaBytes(linha).length != 16) {
                            throw new IllegalStateException("linha incompleta");
                        }
                        conexao.lerParametros();
                    }
                } catch (Throwable e) {
                    erros.add(e);
                }
            }, "teste-cliente-" + n);
            threads.add(t);
        }
        Thread.sleep(300);
        long quadrosAntes = conexao.getTelemetriasRecebidas();
        long t0 = System.nanoTime();
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(30000);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long quadrosDurante = conexao.getTelemetriasRecebidas() - quadrosAntes;
        boolean terminou = threads.stream().noneMatch(Thread::isAlive);
        verificar("4 threads x " + linhasPorThread + " linhas x 3 comandos terminam sem travar (" + ms + " ms)", terminou);
        verificar("nenhum erro nas threads de teste " + erros, erros.isEmpty());
        verificar("a telemetria continuou chegando durante a carga (" + quadrosDurante + " quadros em " + ms + " ms)",
                conexao.getEstado() == EstadoConexao.CONECTADO && quadrosDurante >= (ms / 20) * 0.6);
        conexao.desconectar();
    }

    private static void testarThreadsEncerradas() throws Exception {
        titulo("Limpeza de threads");
        boolean semThreads = esperar(() -> contarThreads("ecu-") == 0, 3000);
        verificar("nenhuma thread ecu-* sobrou depois de tudo desconectado (restam: " + nomesThreads("ecu-") + ")", semThreads);
    }

    private static int contarThreads(String prefixo) {
        return nomesThreads(prefixo).size();
    }

    private static List<String> nomesThreads(String prefixo) {
        List<String> nomes = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(prefixo)) {
                nomes.add(t.getName());
            }
        }
        return nomes;
    }

    // ------------------------------------------------------------------ utilitários

    private interface Acao {
        void executar() throws Exception;
    }

    private static boolean lanca(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean lancaIO(Acao a) {
        try {
            a.executar();
            return false;
        } catch (IOException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean lancaErroProtocolo(Runnable r) {
        try {
            r.run();
            return false;
        } catch (ErroProtocolo e) {
            return true;
        }
    }

    private static int codigoErro(Runnable r) {
        try {
            r.run();
            return -1;
        } catch (ErroProtocolo e) {
            return e.getCodigo();
        }
    }

    private static Throwable causaDe(CompletableFuture<?> f) throws InterruptedException {
        try {
            f.get(2, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (java.util.concurrent.TimeoutException e) {
            return e;
        }
    }

    private static List<Integer> tipos(List<Pacote> pacotes) {
        List<Integer> t = new ArrayList<>();
        for (Pacote p : pacotes) {
            t.add(p.getTipo());
        }
        return t;
    }

    private static byte[] concat(byte[]... partes) {
        int n = 0;
        for (byte[] p : partes) {
            n += p.length;
        }
        byte[] r = new byte[n];
        int pos = 0;
        for (byte[] p : partes) {
            System.arraycopy(p, 0, r, pos, p.length);
            pos += p.length;
        }
        return r;
    }

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
