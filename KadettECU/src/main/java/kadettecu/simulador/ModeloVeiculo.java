package kadettecu.simulador;

import java.util.Random;

/**
 * Um carro virtual (motor + câmbio + motorista) usado para testar o aplicativo sem a placa.
 * Ele só conhece física: não sabe nada de protocolo nem de mapas. Quem decide o corte de giro
 * é a ECU (veja {@link SimuladorECU}), que avisa este modelo a cada passo.
 *
 * <p>Os números (relações de marcha, curva de torque) são ilustrativos, escolhidos para o
 * painel parecer com o de um carro 2.0 de rua; não representam medições do Kadett.
 */
public final class ModeloVeiculo {

    private static final double[] RELACAO = {0.0, 3.72, 2.16, 1.37, 1.00, 0.80};
    private static final double RELACAO_FINAL = 3.90;
    private static final double RAIO_PNEU_M = 0.30;
    private static final double[] CURVA_RPM = {0, 800, 1500, 2500, 3500, 4500, 5500, 6500, 7500, 12000};
    private static final double[] CURVA_TORQUE = {0.00, 0.35, 0.60, 0.85, 1.00, 0.97, 0.88, 0.72, 0.50, 0.20};
    private static final double GANHO_ACELERACAO = 1.75;
    private static final double ROTACAO_LIVRE_MAX = 7800;
    private static final double TEMPO_EMBREAGEM_S = 0.8;
    private static final double ROTACAO_TROCA = 6100;
    private static final double VELOCIDADE_FIM_ACELERACAO_KMH = 110;

    private enum Fase { PARTIDA, MARCHA_LENTA, ACELERADA, SEGURA, LARGADA, ACELERA, TROCA, DESACELERA }

    private final Random aleatorio;

    // motorista
    private Fase fase = Fase.PARTIDA;
    private double tempoFase;
    private boolean automatico = true;
    private double pedalManual;
    private double pedalAlvo;
    private double freio;
    private int proximaMarcha = 1;

    // carro
    private double rpm;
    private double velMs;
    private double tps;
    private int marcha;
    private double rpmLiberacao;
    private double tempoEmbreagem;
    private double ectC = 62;
    private double iatC = 30;
    private double mapKpa = 90;
    private double oleoBar;
    private double bateriaV = 12.6;
    private boolean girandoPartida = true;
    private boolean motorLigado = true;

    // modo bancada: as entradas são fixadas à mão (pelos controles da tela ou por um teste), sem física nem motorista
    private boolean bancada;
    private double bancadaVelKmh;
    private double bancadaTps;
    private double bancadaMap = 100;
    private double bancadaEct = 85;
    private double bancadaIat = 30;
    private double bancadaBateria = 13.8;
    private double bancadaOleo;

    public ModeloVeiculo(long semente) {
        this.aleatorio = new Random(semente);
    }

    /**
     * Liga o modo bancada e fixa todas as entradas do "motor" nos valores dados: é como girar o
     * motor num suporte e mexer em cada sensor à mão. Serve para ver o corte de giro, o two-step,
     * o launch e o cálculo do pulso reagirem sem depender da física do carro virtual.
     */
    public synchronized void definirBancada(double rpm, double velKmh, double tps, double mapKpa, double ectC,
                                            double iatC, double bateriaV, double oleoBar) {
        bancada = true;
        this.rpm = Math.max(0, rpm);
        bancadaVelKmh = Math.max(0, velKmh);
        bancadaTps = Math.max(0, Math.min(100, tps));
        bancadaMap = mapKpa;
        bancadaEct = ectC;
        bancadaIat = iatC;
        bancadaBateria = bateriaV;
        bancadaOleo = Math.max(0, oleoBar);
        aplicarBancada();
    }

    /** Desliga o modo bancada: o carro virtual volta a andar a partir do estado em que as entradas ficaram. */
    public synchronized void encerrarBancada() {
        bancada = false;
    }

    public synchronized boolean isBancada() {
        return bancada;
    }

    private void aplicarBancada() {
        velMs = bancadaVelKmh / 3.6;
        tps = bancadaTps;
        pedalAlvo = bancadaTps;
        mapKpa = bancadaMap;
        ectC = bancadaEct;
        iatC = bancadaIat;
        bateriaV = bancadaBateria;
        oleoBar = bancadaOleo;
        girandoPartida = false;
        marcha = 0;
        motorLigado = true;
    }

    /** Pedal (acelerador) comandado pelo usuário; desliga o ciclo automático e deixa o carro parado (ponto morto). */
    public synchronized void setPedalManual(double pct) {
        automatico = false;
        pedalManual = Math.max(0, Math.min(100, pct));
    }

    public synchronized void setAutomatico(boolean ligado) {
        if (ligado && !automatico) {
            fase = Fase.MARCHA_LENTA;
            tempoFase = 0;
        }
        automatico = ligado;
    }

    public synchronized boolean isAutomatico() {
        return automatico;
    }

    /** "Chave de ignição" virtual: desligada, o motor para; ao religar, o carro faz a partida de novo. */
    public synchronized void setMotorLigado(boolean ligado) {
        if (ligado && !motorLigado) {
            fase = Fase.PARTIDA;
            tempoFase = 0;
            rpm = 0;
            marcha = 0;
            velMs = 0;
        }
        motorLigado = ligado;
    }

    public synchronized boolean isMotorLigado() {
        return motorLigado;
    }

    /**
     * Avança a simulação.
     *
     * @param dt             segundos desde o passo anterior
     * @param corte          true se a ECU está cortando (limitador de giro): o motor não gera torque
     * @param rpmMarchaLenta rotação que a ECU tenta manter em marcha lenta
     */
    public synchronized void avancar(double dt, boolean corte, double rpmMarchaLenta) {
        if (dt <= 0) {
            return;
        }
        if (bancada) {
            aplicarBancada(); // tudo fica exatamente como o usuário definiu (e a rotação não "cai" sozinha)
            return;
        }
        if (!motorLigado) {
            motorDesligado(dt);
            return;
        }
        motorista(dt);

        // acelerador (borboleta) segue o pedal com um pequeno atraso
        tps += (pedalAlvo - tps) * (1 - Math.exp(-dt / 0.05));
        double tpsFrac = Math.max(0, Math.min(1, tps / 100.0));

        // rotação
        double rpmRodas = marcha > 0 ? velMs * 3.6 * rpmPorKmh(marcha) : 0;
        if (fase == Fase.PARTIDA && tempoFase < 0.9) {
            girandoPartida = true;
            double x = tempoFase / 0.9;
            rpm = 240 * x * x * (3 - 2 * x);
        } else {
            girandoPartida = false;
            if (marcha == 0) {
                double alvo = corte ? rpmMarchaLenta * 0.9
                        : rpmMarchaLenta + tpsFrac * (ROTACAO_LIVRE_MAX - rpmMarchaLenta);
                double tau = alvo > rpm ? 0.22 : 0.30;
                rpm += (alvo - rpm) * (1 - Math.exp(-dt / tau));
            } else {
                // embreagem soltando: a rotação "arrastada" cai de rpmLiberacao até ~55% em 0,8 s e depois acompanha as rodas
                tempoEmbreagem += dt;
                double patinando = tempoEmbreagem >= TEMPO_EMBREAGEM_S ? 0.0
                        : rpmLiberacao * (1 - 0.45 * tempoEmbreagem / TEMPO_EMBREAGEM_S);
                double alvo = Math.max(Math.max(rpmRodas, patinando), rpmMarchaLenta * 0.85);
                rpm += (alvo - rpm) * (1 - Math.exp(-dt / 0.04));
            }
        }

        // velocidade
        double aResistencia = 0.15 + 0.00035 * velMs * velMs;
        double a;
        if (marcha > 0 && !girandoPartida) {
            double atrito = 0.06 + rpm / 40000.0;
            double torque = corte ? -atrito : curvaTorque(rpm) * tpsFrac - atrito * (1 - tpsFrac);
            a = GANHO_ACELERACAO * torque * RELACAO[marcha];
        } else {
            a = 0;
        }
        if (velMs > 0 || a > 0) {
            velMs = Math.max(0, velMs + (a - aResistencia - freio) * dt);
        }

        // temperaturas, bateria e demais sensores
        double alvoEct = 88 + 6 * tpsFrac;
        ectC += (alvoEct - ectC) * dt / 90.0;
        iatC = 30 + 0.12 * (ectC - 60) + 0.15 * aleatorio.nextGaussian() * 0.1;
        double descida = (marcha > 0 && tps < 2) ? 10 * Math.min(1.0, rpm / 6500.0) : 0;
        double mapBase = girandoPartida ? 88 : 32 + 66 * Math.pow(tpsFrac, 0.75) - descida;
        mapKpa = Math.max(18, Math.min(100, mapBase)) + 0.4 * aleatorio.nextGaussian();
        oleoBar = rpm < 150 ? 0 : Math.min(5.5, 0.9 + 0.5 * rpm / 1000.0) * (1.15 - 0.002 * ectC)
                + 0.03 * aleatorio.nextGaussian();
        bateriaV = girandoPartida ? 10.9 + 0.05 * aleatorio.nextGaussian()
                : 13.7 + 0.4 * Math.min(1.0, rpm / 6000.0) + 0.03 * aleatorio.nextGaussian();
    }

    private void motorDesligado(double dt) {
        pedalAlvo = 0;
        tps += (0 - tps) * (1 - Math.exp(-dt / 0.05));
        rpm *= Math.exp(-dt / 0.15);
        if (rpm < 20) {
            rpm = 0;
        }
        marcha = 0;
        velMs = Math.max(0, velMs - (0.3 + 0.00035 * velMs * velMs + 3.0) * dt);
        girandoPartida = false;
        mapKpa = 98;
        oleoBar = 0;
        bateriaV = 12.45 + 0.01 * aleatorio.nextGaussian();
        ectC += (25 - ectC) * dt / 900.0;
    }

    private void motorista(double dt) {
        tempoFase += dt;
        if (!automatico) {
            pedalAlvo = pedalManual;
            marcha = 0;
            freio = velMs > 0 ? 1.5 : 0;
            if (fase == Fase.PARTIDA && tempoFase >= 1.3) {
                fase = Fase.MARCHA_LENTA;
            }
            return;
        }
        switch (fase) {
            case PARTIDA:
                pedalAlvo = 0;
                marcha = 0;
                if (tempoFase >= 1.3) {
                    mudarFase(Fase.MARCHA_LENTA);
                }
                break;
            case MARCHA_LENTA:
                pedalAlvo = 0;
                marcha = 0;
                freio = 0;
                if (tempoFase >= 3.0) {
                    mudarFase(Fase.ACELERADA);
                }
                break;
            case ACELERADA:
                pedalAlvo = (tempoFase % 1.4) < 0.55 ? 70 : 0;
                if (tempoFase >= 2.8) {
                    mudarFase(Fase.SEGURA);
                }
                break;
            case SEGURA:
                pedalAlvo = 100;
                if (tempoFase >= 2.6) {
                    mudarFase(Fase.LARGADA);
                }
                break;
            case LARGADA:
                engatar(1);
                mudarFase(Fase.ACELERA);
                break;
            case ACELERA:
                pedalAlvo = 100;
                if (velMs * 3.6 >= VELOCIDADE_FIM_ACELERACAO_KMH) {
                    mudarFase(Fase.DESACELERA);
                } else if (marcha > 0 && marcha < 3 && velMs * 3.6 * rpmPorKmh(marcha) >= ROTACAO_TROCA) {
                    // a troca é decidida pela rotação das rodas, não pela do motor (que pode estar patinando a embreagem)
                    proximaMarcha = marcha + 1;
                    marcha = 0;
                    mudarFase(Fase.TROCA);
                }
                break;
            case TROCA:
                pedalAlvo = 0;
                marcha = 0;
                if (tempoFase >= 0.30) {
                    engatar(proximaMarcha);
                    mudarFase(Fase.ACELERA);
                }
                break;
            case DESACELERA:
            default:
                pedalAlvo = 0;
                freio = 3.2;
                if (velMs * 3.6 < 12) {
                    marcha = 0;
                }
                if (velMs * 3.6 < 0.5) {
                    velMs = 0;
                    freio = 0;
                    mudarFase(Fase.MARCHA_LENTA);
                }
                break;
        }
        if (fase == Fase.ACELERA || fase == Fase.TROCA) {
            freio = 0;
        }
    }

    private void engatar(int nova) {
        marcha = nova;
        rpmLiberacao = rpm;
        tempoEmbreagem = 0;
    }

    private void mudarFase(Fase nova) {
        fase = nova;
        tempoFase = 0;
    }

    private static double rpmPorKmh(int m) {
        return (1000.0 / 3600.0) / (2 * Math.PI * RAIO_PNEU_M) * 60.0 * RELACAO[m] * RELACAO_FINAL;
    }

    private static double curvaTorque(double r) {
        for (int i = 1; i < CURVA_RPM.length; i++) {
            if (r <= CURVA_RPM[i]) {
                double t = (r - CURVA_RPM[i - 1]) / (CURVA_RPM[i] - CURVA_RPM[i - 1]);
                return CURVA_TORQUE[i - 1] + t * (CURVA_TORQUE[i] - CURVA_TORQUE[i - 1]);
            }
        }
        return CURVA_TORQUE[CURVA_TORQUE.length - 1];
    }

    public synchronized double getRpm() {
        return rpm;
    }

    public synchronized double getVelocidadeKmh() {
        return velMs * 3.6;
    }

    public synchronized double getTps() {
        if (bancada) {
            return tps;
        }
        return Math.max(0, Math.min(100, tps + 0.1 * aleatorio.nextGaussian()));
    }

    public synchronized int getMarcha() {
        return marcha;
    }

    public synchronized double getEctC() {
        return ectC;
    }

    public synchronized double getIatC() {
        return iatC;
    }

    public synchronized double getMapKpa() {
        return mapKpa;
    }

    public synchronized double getOleoBar() {
        return Math.max(0, oleoBar);
    }

    public synchronized double getBateriaV() {
        return bateriaV;
    }

    public synchronized boolean estaNaPartida() {
        return girandoPartida;
    }
}
