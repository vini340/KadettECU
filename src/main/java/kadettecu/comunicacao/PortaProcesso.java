package kadettecu.comunicacao;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * "Porta serial" ligada a um programa externo: o que o aplicativo escreve vai para a entrada
 * padrão (stdin) do programa e o que ele imprime na saída padrão (stdout) chega como se viesse
 * da placa. Serve para ligar o aplicativo ao <b>firmware de verdade compilado para o PC</b>
 * ({@code firmware/host/build/ecu_host}, a mesma lógica em C que roda na placa) e testar a
 * comunicação sem hardware nenhum.
 *
 * <p>Uma thread própria ("ecu-processo-rx") fica lendo a saída do programa e coloca os blocos
 * numa fila; {@link #ler} tira dessa fila esperando no máximo 100 ms, como a porta real. Se o
 * programa terminar, {@link #ler} passa a devolver -1 (igual a puxar o cabo USB).
 */
public final class PortaProcesso implements PortaSerial {

    private final String nome;
    private final List<String> comando;
    private final LinkedBlockingQueue<byte[]> fila = new LinkedBlockingQueue<>();
    private volatile boolean aberta;
    private volatile boolean terminou;
    private Process processo;
    private OutputStream entrada;
    private Thread leitor;
    private byte[] atual = new byte[0];
    private int posicao;

    /**
     * @param nome    nome mostrado na interface
     * @param comando programa e argumentos (por exemplo {@code ecu_host --rpm 3000})
     */
    public PortaProcesso(String nome, List<String> comando) {
        if (comando == null || comando.isEmpty()) {
            throw new IllegalArgumentException("Falta o comando do processo");
        }
        this.nome = nome;
        this.comando = new ArrayList<>(comando);
    }

    @Override
    public String getNome() {
        return nome;
    }

    @Override
    public synchronized void abrir() throws IOException {
        if (aberta) {
            throw new IOException("Porta já aberta");
        }
        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        processo = pb.start();
        entrada = processo.getOutputStream();
        fila.clear();
        atual = new byte[0];
        posicao = 0;
        terminou = false;
        aberta = true;
        final InputStream saida = processo.getInputStream();
        leitor = new Thread(() -> bombear(saida), "ecu-processo-rx");
        leitor.setDaemon(true);
        leitor.start();
    }

    private void bombear(InputStream saida) {
        byte[] buffer = new byte[4096];
        try {
            int n;
            while ((n = saida.read(buffer)) >= 0) {
                if (n > 0) {
                    fila.add(Arrays.copyOf(buffer, n));
                }
            }
        } catch (IOException e) {
            // processo encerrado ou porta fechada: tratado como fim da transmissão
        } finally {
            terminou = true;
        }
    }

    @Override
    public boolean estaAberta() {
        return aberta && !terminou;
    }

    @Override
    public int ler(byte[] buffer) throws IOException {
        if (!aberta) {
            return -1;
        }
        if (posicao >= atual.length) {
            try {
                byte[] bloco = fila.poll(100, TimeUnit.MILLISECONDS);
                if (bloco == null) {
                    if (terminou && fila.isEmpty()) {
                        return -1;
                    }
                    return aberta ? 0 : -1;
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
        OutputStream saida = entrada;
        if (!aberta || saida == null) {
            throw new IOException("Porta fechada");
        }
        if (terminou) {
            throw new IOException("O processo terminou");
        }
        saida.write(dados);
        saida.flush();
    }

    /** Encerra o programa à força, sem avisar (simula a placa travar ou perder a alimentação). */
    public void matar() {
        Process p = processo;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    /** true enquanto o programa externo está rodando. */
    public boolean processoVivo() {
        Process p = processo;
        return p != null && p.isAlive();
    }

    @Override
    public synchronized void fechar() {
        aberta = false;
        OutputStream saida = entrada;
        if (saida != null) {
            try {
                saida.close(); // o programa vê o fim da entrada e sai sozinho
            } catch (IOException ignorado) {
                // já fechado
            }
        }
        Process p = processo;
        if (p != null) {
            try {
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        Thread t = leitor;
        if (t != null) {
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        processo = null;
        entrada = null;
        leitor = null;
    }
}
