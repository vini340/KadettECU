package kadettecu.simulador;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import kadettecu.modelo.Calibracao;
import kadettecu.modelo.CampoParametro;
import kadettecu.modelo.EixosMapa;
import kadettecu.modelo.InfoECU;
import kadettecu.modelo.MapaECU;
import kadettecu.modelo.ParametrosMotor;
import kadettecu.modelo.Telemetria;
import kadettecu.modelo.TipoMapa;
import kadettecu.protocolo.BufferLE;
import kadettecu.protocolo.DecodificadorPacotes;
import kadettecu.protocolo.Pacote;
import kadettecu.protocolo.Protocolo;

/**
 * Uma ECU virtual: fala o mesmo protocolo da placa e roda a mesma lógica (corte de giro com
 * histerese, two-step, launch, enriquecimento, marcha lenta, cálculo de pulso e avanço) sobre
 * um {@link ModeloVeiculo}. Serve para testar e demonstrar o aplicativo sem hardware.
 *
 * <p>Não tem threads próprias: quem a usa chama {@link #processar} com os bytes recebidos do
 * PC e {@link #avancar} a cada passo de tempo (veja {@code PortaSimulada}). Todos os métodos
 * públicos são sincronizados.
 */
public final class SimuladorECU {

    private static final byte[] VAZIO = new byte[0];
    private static final double PASSO_MAXIMO_S = 0.005;
    /** Abaixo disto, com o motor girando, é a partida (mesmo valor de KECU_RPM_PARTIDA_MAX no firmware). */
    private static final double PARTIDA_RPM_MAX = 400;

    private final ModeloVeiculo carro;
    private final DecodificadorPacotes decodificador = new DecodificadorPacotes();
    private final InfoECU info = new InfoECU(Protocolo.VERSAO, 1, 0, 0, Protocolo.TAMANHO_MAPA,
            Protocolo.NUM_MAPAS, InfoECU.CAP_MEMORIA_NVM);

    private Calibracao ram = Calibracao.padrao();
    private Calibracao nvm = Calibracao.padrao();
    private int periodoMs;
    private double acumuladoMs;
    private int chaves;
    private boolean telemetriaPausada;
    private boolean mudo;
    private int seqTelemetria;
    private double tempoMs;
    private long comandosRecebidos;

    // estado da lógica de controle
    private boolean corte;
    private double aeAteMs;
    private double tpsAnterior;
    private double integralMarchaLenta;
    private Telemetria ultima = Telemetria.vazia();

    public SimuladorECU() {
        this(42L);
    }

    public SimuladorECU(long semente) {
        this.carro = new ModeloVeiculo(semente);
    }

    public ModeloVeiculo getCarro() {
        return carro;
    }

    // ------------------------------------------------------------------ protocolo

    /** Entrada serial: devolve os bytes das respostas (vazio se não houver). */
    public synchronized byte[] processar(byte[] dados, int tamanho) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        for (Pacote p : decodificador.alimentar(dados, tamanho)) {
            comandosRecebidos++;
            if (mudo) {
                continue;
            }
            byte[] resposta = tratar(p);
            saida.write(resposta, 0, resposta.length);
        }
        return saida.toByteArray();
    }

    private byte[] tratar(Pacote p) {
        int seq = p.getSeq();
        byte[] d = p.getPayload();
        switch (p.getTipo()) {
            case Protocolo.CMD_HELLO:
                return resposta(Protocolo.RSP_INFO, seq, info.paraBytes());

            case Protocolo.CMD_STREAM: {
                if (d.length != 2) {
                    return nack(seq, Protocolo.ERRO_TAMANHO);
                }
                int ms = BufferLE.paraLeitura(d).lerU16();
                if (ms != 0 && (ms < 5 || ms > 1000)) {
                    return nack(seq, Protocolo.ERRO_FAIXA);
                }
                periodoMs = ms;
                acumuladoMs = 0;
                return ack(seq);
            }

            case Protocolo.CMD_LER_PARAMS:
                return resposta(Protocolo.RSP_PARAMS, seq, ram.getParametros().paraBytes());

            case Protocolo.CMD_ESCREVER_PARAMS: {
                if (d.length != ParametrosMotor.TAMANHO) {
                    return nack(seq, Protocolo.ERRO_TAMANHO);
                }
                ParametrosMotor novos;
                try {
                    novos = ParametrosMotor.deBytes(d);
                } catch (IllegalArgumentException e) {
                    return nack(seq, Protocolo.ERRO_FAIXA);
                }
                if (carro.getRpm() > 0) {
                    for (CampoParametro c : CampoParametro.values()) {
                        if (c.exigeMotorParado() && novos.get(c) != ram.getParametros().get(c)) {
                            return nack(seq, Protocolo.ERRO_MOTOR_GIRANDO);
                        }
                    }
                }
                ram.getParametros().copiarDe(novos);
                return ack(seq);
            }

            case Protocolo.CMD_LER_LINHA: {
                if (d.length != 2) {
                    return nack(seq, Protocolo.ERRO_TAMANHO);
                }
                int mapa = d[0] & 0xFF;
                int linha = d[1] & 0xFF;
                if (mapa >= Protocolo.NUM_MAPAS || linha >= Protocolo.TAMANHO_MAPA) {
                    return nack(seq, Protocolo.ERRO_FAIXA);
                }
                byte[] valores = ram.getMapa(TipoMapa.porId(mapa)).linhaParaBytes(linha);
                return resposta(Protocolo.RSP_LINHA, seq,
                        BufferLE.paraEscrita(2 + valores.length).escreverU8(mapa).escreverU8(linha)
                                .escreverBytes(valores).bytes());
            }

            case Protocolo.CMD_ESCREVER_LINHA: {
                if (d.length != 2 + Protocolo.TAMANHO_MAPA) {
                    return nack(seq, Protocolo.ERRO_TAMANHO);
                }
                int mapa = d[0] & 0xFF;
                int linha = d[1] & 0xFF;
                if (mapa >= Protocolo.NUM_MAPAS || linha >= Protocolo.TAMANHO_MAPA) {
                    return nack(seq, Protocolo.ERRO_FAIXA);
                }
                ram.getMapa(TipoMapa.porId(mapa)).linhaDeBytes(linha, Arrays.copyOfRange(d, 2, d.length));
                return ack(seq);
            }

            case Protocolo.CMD_LER_EIXOS:
                return resposta(Protocolo.RSP_EIXOS, seq, ram.getEixos().paraBytes());

            case Protocolo.CMD_ESCREVER_EIXOS: {
                if (d.length != 4 * EixosMapa.TAMANHO) {
                    return nack(seq, Protocolo.ERRO_TAMANHO);
                }
                try {
                    ram.getEixos().copiarDe(EixosMapa.deBytes(d));
                } catch (IllegalArgumentException e) {
                    return nack(seq, Protocolo.ERRO_FAIXA);
                }
                return ack(seq);
            }

            case Protocolo.CMD_GRAVAR:
                if (carro.getRpm() > 0) {
                    return nack(seq, Protocolo.ERRO_MOTOR_GIRANDO);
                }
                nvm = ram.copia();
                return ack(seq);

            case Protocolo.CMD_PADROES:
                if (carro.getRpm() > 0) {
                    return nack(seq, Protocolo.ERRO_MOTOR_GIRANDO);
                }
                ram = Calibracao.padrao();
                return ack(seq);

            case Protocolo.CMD_ARMAR:
                if (d.length != 1) {
                    return nack(seq, Protocolo.ERRO_TAMANHO);
                }
                // o simulador não tem bicos nem bobina: arma-se só o que existe, ou seja, nada
                return (d[0] & 0xFF) == 0 ? ack(seq) : nack(seq, Protocolo.ERRO_SAIDAS_DESABILITADAS);

            case Protocolo.CMD_CHAVES:
                if (d.length != 1) {
                    return nack(seq, Protocolo.ERRO_TAMANHO);
                }
                chaves = d[0] & (Protocolo.CHAVE_TWO_STEP | Protocolo.CHAVE_LAUNCH);
                return ack(seq);

            default:
                return nack(seq, Protocolo.ERRO_COMANDO_DESCONHECIDO);
        }
    }

    private static byte[] resposta(int tipo, int seq, byte[] payload) {
        return new Pacote(tipo, seq, payload).codificar();
    }

    private static byte[] ack(int seq) {
        return resposta(Protocolo.RSP_ACK, seq, new byte[0]);
    }

    private static byte[] nack(int seq, int codigo) {
        return resposta(Protocolo.RSP_NACK, seq, new byte[] {(byte) codigo});
    }

    // ------------------------------------------------------------------ passo de tempo

    /**
     * Avança a simulação de {@code dt} segundos, em passos de no máximo 5 ms (a física e o
     * limitador de giro ficam estáveis mesmo que quem chama use passos grandes).
     *
     * @return os quadros de telemetria que ficaram devidos nesse intervalo (vazio se nenhum)
     */
    public synchronized byte[] avancar(double dt) {
        ByteArrayOutputStream saida = null;
        double restante = dt;
        while (restante > 1e-9) {
            double passo = Math.min(restante, PASSO_MAXIMO_S);
            restante -= passo;
            ParametrosMotor p = ram.getParametros();
            carro.avancar(passo, corte, alvoMarchaLenta(p, carro.getEctC()));
            tempoMs += passo * 1000.0;
            ultima = calcular(p, passo);
            if (telemetriaPausada || mudo || periodoMs == 0) {
                acumuladoMs = 0;
                continue;
            }
            acumuladoMs += passo * 1000.0;
            if (acumuladoMs >= periodoMs) {
                acumuladoMs -= periodoMs;
                seqTelemetria = (seqTelemetria + 1) & 0xFF;
                byte[] quadro = new Pacote(Protocolo.TELEMETRIA, seqTelemetria, ultima.paraBytes()).codificar();
                if (saida == null) {
                    saida = new ByteArrayOutputStream();
                }
                saida.write(quadro, 0, quadro.length);
            }
        }
        return saida == null ? VAZIO : saida.toByteArray();
    }

    private static double alvoMarchaLenta(ParametrosMotor p, double ect) {
        if (!p.getBool(CampoParametro.HAB_MARCHA_LENTA)) {
            return 750;
        }
        double f = Math.max(0, Math.min(1, (ect - 20) / 60.0));
        double frio = p.get(CampoParametro.ML_RPM_FRIO);
        double quente = p.get(CampoParametro.ML_RPM_QUENTE);
        return frio + (quente - frio) * f;
    }

    /** O "cérebro": decide limitador, enriquecimento e calcula pulso/avanço a partir dos mapas. */
    private Telemetria calcular(ParametrosMotor p, double dt) {
        double rpm = carro.getRpm();
        int rpmInt = (int) Math.round(rpm);
        double vel = carro.getVelocidadeKmh();
        double tps = carro.getTps();
        double map = carro.getMapKpa();
        double ect = carro.getEctC();
        double iat = carro.getIatC();
        double bateria = carro.getBateriaV();
        boolean girando = rpmInt > 0;

        // --- limitador de giro (com histerese), two-step e launch
        boolean twoStep = p.getBool(CampoParametro.HAB_TWO_STEP) && (chaves & Protocolo.CHAVE_TWO_STEP) != 0;
        boolean launch = p.getBool(CampoParametro.HAB_LAUNCH) && (chaves & Protocolo.CHAVE_LAUNCH) != 0
                && tps >= p.get(CampoParametro.LAUNCH_TPS_MIN) && vel <= p.get(CampoParametro.LAUNCH_VEL_MAX);
        int limite = p.get(CampoParametro.REV_LIMITE_RPM);
        if (twoStep) {
            limite = Math.min(limite, p.get(CampoParametro.TWO_STEP_RPM));
        }
        if (launch) {
            limite = Math.min(limite, p.get(CampoParametro.LAUNCH_RPM));
        }
        int histerese = Math.min(p.get(CampoParametro.REV_HISTERESE_RPM), limite / 2);
        if (rpmInt >= limite) {
            corte = true;
        } else if (rpmInt <= limite - histerese) {
            corte = false;
        }

        // --- enriquecimento de aceleração: dispara quando o TPS sobe mais rápido que o limiar
        double tpsPorSegundo = dt > 0 ? (tps - tpsAnterior) / dt : 0;
        tpsAnterior = tps;
        if (p.getBool(CampoParametro.HAB_AE) && tpsPorSegundo > p.get(CampoParametro.AE_LIMIAR)) {
            aeAteMs = tempoMs + p.get(CampoParametro.AE_DURACAO_MS);
        }
        double ae = 0;
        int duracaoAe = Math.max(1, p.get(CampoParametro.AE_DURACAO_MS));
        if (tempoMs < aeAteMs) {
            ae = p.get(CampoParametro.AE_ENRIQUECIMENTO) * (aeAteMs - tempoMs) / duracaoAe;
        }

        // --- marcha lenta (PI simples sobre a válvula IAC). Mesma regra do firmware (kecu_control.c):
        // pedal solto e rotação até 500 rpm acima da meta; fora disso a válvula fica na abertura base.
        double alvoMl = alvoMarchaLenta(p, ect);
        boolean marchaLenta = p.getBool(CampoParametro.HAB_MARCHA_LENTA) && girando
                && tps < p.get(CampoParametro.ML_TPS_MAX) && rpm < alvoMl + 500;
        double iac = girando ? p.get(CampoParametro.ML_DUTY_BASE) : 0;
        if (marchaLenta) {
            double erro = alvoMl - rpm;
            double ki = p.get(CampoParametro.ML_KI) / 100.0;
            double kp = p.get(CampoParametro.ML_KP) / 100.0;
            integralMarchaLenta = Math.max(-30, Math.min(30, integralMarchaLenta + ki * erro * dt));
            iac = Math.max(0, Math.min(100, p.get(CampoParametro.ML_DUTY_BASE) + kp * erro + integralMarchaLenta));
        } else {
            integralMarchaLenta = 0;
        }

        // --- leitura das tabelas no ponto de operação
        EixosMapa eixos = ram.getEixos();
        double posRpm = eixos.posicaoRpm(rpm);
        double posCarga = eixos.posicaoCarga(map);
        double ve = ram.getMapa(TipoMapa.COMBUSTIVEL).interpolarFisico(posRpm, posCarga);
        double avanco = ram.getMapa(TipoMapa.IGNICAO).interpolarFisico(posRpm, posCarga);
        double alvoLambda = ram.getMapa(TipoMapa.ALVO_LAMBDA).interpolarFisico(posRpm, posCarga);
        if (launch) {
            avanco -= p.get(CampoParametro.LAUNCH_RETARDO);
        }
        // na partida (girando abaixo de 400 rpm) o avanço é fixo e conservador, como no firmware
        boolean partida = girando && rpm < PARTIDA_RPM_MAX;
        if (partida) {
            avanco = 10;
        }

        // --- pulso do injetor
        double corrAr = 293.15 / (273.15 + iat);
        double aquecimento = 1 + Math.max(0, 80 - ect) / 80.0 * 0.4;
        double pulso = partida
                ? p.get(CampoParametro.REQ_FUEL_US) * 0.4 * aquecimento
                : p.get(CampoParametro.REQ_FUEL_US) * (ve / 100.0) * (map / 100.0) * corrAr / alvoLambda
                        * aquecimento * (1 + ae / 100.0);
        double tempoMorto = Math.max(0, Math.min(5000, p.get(CampoParametro.TEMPO_MORTO_US)
                + p.get(CampoParametro.TEMPO_MORTO_COEF) * (14.0 - bateria)));
        int pw = girando ? (int) Math.min(65535, Math.round(pulso + tempoMorto)) : 0;
        int tipoCorte = p.get(CampoParametro.TIPO_CORTE);
        if (corte && (tipoCorte == 0 || tipoCorte == 2)) {
            pw = 0;
        }

        // --- lambda "medido": segue o alvo, fica pobre durante o corte de combustível
        double lambda = alvoLambda * (1 - 0.001 * ae) + 0.006 * (Math.sin(tempoMs / 37.0) + Math.sin(tempoMs / 91.0));
        if (corte && (tipoCorte == 0 || tipoCorte == 2)) {
            lambda = 1.45;
        }
        if (!girando) {
            lambda = 1.0;
        }

        int flags = Protocolo.FLAG_SIMULADOR | Protocolo.FLAG_MODO_SOMBRA;
        if (girando) {
            flags |= Protocolo.FLAG_SYNC | Protocolo.FLAG_MOTOR_GIRANDO;
        }
        if (corte) {
            flags |= Protocolo.FLAG_CORTE;
        }
        if (twoStep) {
            flags |= Protocolo.FLAG_TWO_STEP;
        }
        if (launch) {
            flags |= Protocolo.FLAG_LAUNCH;
        }
        if (ae > 0.5) {
            flags |= Protocolo.FLAG_AE;
        }
        if (marchaLenta) {
            flags |= Protocolo.FLAG_MARCHA_LENTA;
        }
        if ((chaves & Protocolo.CHAVE_TWO_STEP) != 0) {
            flags |= Protocolo.FLAG_CHAVE_TWO_STEP;
        }
        if ((chaves & Protocolo.CHAVE_LAUNCH) != 0) {
            flags |= Protocolo.FLAG_CHAVE_LAUNCH;
        }

        return new Telemetria.Construtor()
                .tempoMs((long) tempoMs)
                .rpm(rpmInt)
                .velocidade(vel)
                .ect(ect)
                .iat(iat)
                .map(map)
                .oleo(carro.getOleoBar())
                .tps(tps)
                .lambda(Math.max(0.6, Math.min(2.0, lambda)))
                .bateria(bateria)
                .avanco(girando ? avanco : 0)
                .pwUs(pw)
                .ve((int) Math.round(ve))
                .ae((int) Math.round(ae))
                .iac((int) Math.round(iac))
                .posicao(posRpm, posCarga)
                .flags(flags)
                .erros(0)
                .construir();
    }

    // ------------------------------------------------------------------ ganchos de teste e interface

    /** Última telemetria calculada (mesmo que o envio esteja desligado). */
    public synchronized Telemetria getUltimaTelemetria() {
        return ultima;
    }

    /** Cópia da calibração que está na RAM da "placa". */
    public synchronized Calibracao getCalibracaoRam() {
        return ram.copia();
    }

    /** Cópia da calibração gravada na memória não volátil da "placa". */
    public synchronized Calibracao getCalibracaoNvm() {
        return nvm.copia();
    }

    public synchronized long getComandosRecebidos() {
        return comandosRecebidos;
    }

    public synchronized int getChaves() {
        return chaves;
    }

    public synchronized int getPeriodoTelemetriaMs() {
        return periodoMs;
    }

    /** Simula uma placa que trava: continua "viva" mas para de transmitir telemetria. */
    public synchronized void setTelemetriaPausada(boolean pausada) {
        this.telemetriaPausada = pausada;
    }

    /** Simula uma placa que não responde a nada (nem comandos nem telemetria). */
    public synchronized void setMudo(boolean mudo) {
        this.mudo = mudo;
    }

    /** Equivale a tirar e recolocar a alimentação: a RAM volta ao que estava gravado e o envio de telemetria para. */
    public synchronized void reiniciarPlaca() {
        ram = nvm.copia();
        periodoMs = 0;
        acumuladoMs = 0;
        chaves = 0;
        corte = false;
        aeAteMs = 0;
        integralMarchaLenta = 0;
        decodificador.alimentar(new byte[0], 0);
    }
}
