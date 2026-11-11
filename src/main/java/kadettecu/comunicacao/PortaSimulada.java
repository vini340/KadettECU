package kadettecu.comunicacao;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kadettecu.simulador.SimuladorECU;

/**
 * "Porta serial" ligada a uma ECU simulada em vez de a um cabo USB. Para o resto do programa
 * ela é igual à porta real ({@link PortaSerial}): é o que permite usar e testar o aplicativo
 * sem a placa.
 *
 * <p>Uma thread própria ("ecu-simulada") faz o tempo da simulação andar e coloca os quadros de
 * telemetria numa fila; {@link #ler} tira dessa fila, bloqueando até 100 ms, como a porta real.
 * Os métodos {@code simular...} e {@link #setRuido} existem para os testes provocarem falhas.
 */
public final class PortaSimulada implements PortaSerial {

    public static final String NOME = "SIMULADOR";

    private final SimuladorECU simulador;
    private final LinkedBlockingQueue<byte[]> fila = new LinkedBlockingQueue<>();
    private final Random sorteio = new Random(7);
    private volatile boolean aberta;
    private volatile boolean caiu;
    private volatile boolean ruido;
    private Thread thread;
    private byte[] atual = new byte[0];
    private int posicao;

    public PortaSimulada() {
        this(new SimuladorECU());
    }

    public PortaSimulada(SimuladorECU simulador) {
        this.simulador = simulador;
    }

    public SimuladorECU getSimulador() {
        return simulador;
    }

    @Override
    public String getNome() {
        return NOME;
    }

    @Override
    public synchronized void abrir() throws IOException {
        if (aberta) {
            throw new IOException("Porta já aberta");
        }
        caiu = false;
        fila.clear();
        atual = new byte[0];
        posicao = 0;
        aberta = true;
        thread = new Thread(this::executar, "ecu-simulada");
        thread.setDaemon(true);
        thread.start();
    }

    private void executar() {
        long anterior = System.nanoTime();
        while (aberta) {
            long agora = System.nanoTime();
            double dt = Math.min((agora - anterior) / 1e9, 0.25);
            anterior = agora;
            byte[] quadros = simulador.avancar(dt);
            if (quadros.length > 0) {
                entregar(quadros);
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Põe bytes na fila de leitura; com ruído ligado, estraga e fragmenta alguns quadros. */
    private void entregar(byte[] bytes) {
        if (!ruido) {
            fila.add(bytes);
            return;
        }
        byte[] copia = bytes.clone();
        if (sorteio.nextInt(10) < 3) {
            byte[] lixo = new byte[1 + sorteio.nextInt(5)];
            sorteio.nextBytes(lixo);
            fila.add(lixo);
        }
        if (sorteio.nextInt(10) < 1 && copia.length > 8) {
            copia[sorteio.nextInt(copia.length)] ^= (byte) (1 << sorteio.nextInt(8)); // um bit errado: CRC deve pegar
        }
        if (sorteio.nextInt(10) < 3 && copia.length > 4) {
            int corte = 1 + sorteio.nextInt(copia.length - 1);
            fila.add(java.util.Arrays.copyOfRange(copia, 0, corte));
            fila.add(java.util.Arrays.copyOfRange(copia, corte, copia.length));
        } else {
            fila.add(copia);
        }
    }

    @Override
    public boolean estaAberta() {
        return aberta && !caiu;
    }

    @Override
    public int ler(byte[] buffer) throws IOException {
        if (!aberta || caiu) {
            return -1;
        }
        if (posicao >= atual.length) {
            try {
                byte[] bloco = fila.poll(100, TimeUnit.MILLISECONDS);
                if (bloco == null) {
                    return (aberta && !caiu) ? 0 : -1;
                }
                atual = bloco;
                posicao = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        int n = Math.min(buffer.length, atual.length - posicao);
        System.arraycopy(atual, posicao, buffer, 0, n);
        posicao += n;
        return n;
    }

    @Override
    public void escrever(byte[] dados) throws IOException {
        if (caiu) {
            throw new IOException("Dispositivo removido (simulado)");
        }
        if (!aberta) {
            throw new IOException("Porta fechada");
        }
        byte[] resposta = simulador.processar(dados, dados.length);
        if (resposta.length > 0) {
            entregar(resposta);
        }
    }

    @Override
    public synchronized void fechar() {
        aberta = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    // ----- ganchos de teste

    /** Como puxar o cabo USB: a leitura passa a devolver -1 e a escrita falha. */
    public void simularQuedaDoCabo() {
        caiu = true;
    }

    /** Liga/desliga lixo, bits errados e quadros partidos no que a "placa" envia. */
    public void setRuido(boolean ligado) {
        this.ruido = ligado;
    }
}
