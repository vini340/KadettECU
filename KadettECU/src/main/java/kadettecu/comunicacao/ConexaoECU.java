package kadettecu.comunicacao;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import kadettecu.modelo.Calibracao;
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

/**
 * Conversa com a ECU por uma {@link PortaSerial}, sem nunca bloquear a interface gráfica.
 *
 * <p>Cada conexão tem 4 threads de apoio (todas daemon e com nome, para aparecer bem num
 * depurador):
 * <ul>
 *   <li><b>ecu-rx</b>: lê a porta o tempo todo, remonta os quadros, guarda a última telemetria
 *       e entrega as respostas de comandos a quem está esperando;</li>
 *   <li><b>ecu-tx</b>: única thread que escreve na porta (os quadros nunca se misturam);</li>
 *   <li><b>ecu-tarefas</b>: executa, uma por vez e em ordem, as operações demoradas pedidas pela
 *       tela (ler/gravar calibração inteira, por exemplo);</li>
 *   <li><b>ecu-vigia</b>: a cada 250 ms confere se a telemetria continua chegando
 *       (CONECTADO &lt;-&gt; SEM_SINAL) e mede a taxa de quadros.</li>
 * </ul>
 *
 * <p>Os métodos de comando ({@code lerParametros}, {@code escreverLinha}...) bloqueiam a thread
 * que os chama até a resposta chegar (ou o tempo esgotar). Por isso a tela os chama através de
 * {@link #emSegundoPlano(Callable)}, nunca direto da thread do Swing.
 * Só um comando fica "no ar" por vez; cada tentativa leva um número de sequência novo, de modo
 * que uma resposta atrasada de uma tentativa anterior é simplesmente ignorada.
 */
public final class ConexaoECU {

    /** Avisa o andamento de operações longas (por exemplo, "12 de 50 linhas"). */
    public interface Progresso {
        void aoAvancar(int feitos, int total);
    }

    public static final int PERIODO_TELEMETRIA_PADRAO_MS = 20;

    private static final int TIMEOUT_RESPOSTA_MS = 700;
    private static final int TENTATIVAS = 3;
    private static final int TIMEOUT_HELLO_MS = 500;
    private static final int TENTATIVAS_HELLO = 6; // placas com auto-reset (ESP32/Arduino) levam ~2 s para subir
    /**
     * GRAVAR leva mais que os outros comandos: a placa só responde quando termina de gravar a memória. A EEPROM do Arduino
     * Mega escreve um byte em ~3,4 ms, então um bloco novo de 889 bytes ocupa a placa por ~3 s (e a telemetria pausa nesse
     * tempo). Com as 3 tentativas de 700 ms dos outros comandos (2,1 s) o aplicativo desistiria ANTES do "ok", e mostraria
     * erro numa gravação que deu certo. Repetir o GRAVAR é inofensivo (regravar o mesmo bloco não muda nenhum byte).
     */
    private static final int TIMEOUT_GRAVAR_MS = 6000;
    private static final int TENTATIVAS_GRAVAR = 2;
    private static final int TIMEOUT_ESCRITA_MS = 500;
    private static final int TIMEOUT_SEM_SINAL_MS = 1000;
    private static final int INTERVALO_RETOMADA_MS = 1000;
    private static final Progresso SEM_PROGRESSO = (feitos, total) -> {
    };

    /** Tudo que pertence a UMA conexão. Ao reconectar, cria-se outra instância e a antiga é descartada. */
    private static final class Sessao {
        final PortaSerial porta;
        final DecodificadorPacotes decodificador = new DecodificadorPacotes();
        final AtomicBoolean ativa = new AtomicBoolean(true);
        final ReentrantLock comando = new ReentrantLock(true);
        final ExecutorService leitor = Executors.newSingleThreadExecutor(fabrica("ecu-rx"));
        final ExecutorService escritor = Executors.newSingleThreadExecutor(fabrica("ecu-tx"));
        final ExecutorService tarefas = Executors.newSingleThreadExecutor(fabrica("ecu-tarefas"));
        final ScheduledExecutorService vigia = Executors.newSingleThreadScheduledExecutor(fabrica("ecu-vigia"));
        volatile Pendente pendente;
        volatile long ultimaTelemetriaNs = System.nanoTime();
        final AtomicInteger seq = new AtomicInteger();
        final AtomicLong contadorTaxa = new AtomicLong();
        volatile long ultimaRetomadaNs;
        long instanteTaxaNs = System.nanoTime(); // só a thread do vigia mexe nele

        Sessao(PortaSerial porta) {
            this.porta = porta;
        }

        int proximoSeq() {
            return seq.updateAndGet(v -> (v + 1) & 0xFF);
        }
    }

    /** Um comando esperando resposta. */
    private static final class Pendente {
        final int seq;
        final CompletableFuture<Pacote> futuro = new CompletableFuture<>();

        Pendente(int seq) {
            this.seq = seq;
        }
    }

    private final Object lockCiclo = new Object();
    private final Object lockEstado = new Object();
    private final List<OuvinteConexao> ouvintes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Telemetria> ultima = new AtomicReference<>(Telemetria.vazia());
    private final AtomicLong telemetriasRecebidas = new AtomicLong();
    private volatile EstadoConexao estado = EstadoConexao.DESCONECTADO;
    private volatile InfoECU info;
    private volatile Sessao atual;
    private volatile double taxaHz;
    private volatile long pacotesValidosTotal;
    private volatile long errosCrcTotal;

    private static ThreadFactory fabrica(String nome) {
        return r -> {
            Thread t = new Thread(r, nome);
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------ ciclo de vida

    /**
     * Abre a porta, confere a versão do protocolo (HELLO) e liga o envio contínuo de telemetria.
     * Bloqueia por até alguns segundos: chame fora da thread do Swing.
     */
    public InfoECU conectar(PortaSerial porta) throws IOException {
        synchronized (lockCiclo) {
            encerrarSessao(atual); // limpa qualquer resto de uma conexão anterior (inclusive em ERRO)
            atual = null;
            mudarEstado(EstadoConexao.CONECTANDO);
            info = null;
            ultima.set(Telemetria.vazia());
            telemetriasRecebidas.set(0);
            taxaHz = 0;
            pacotesValidosTotal = 0;
            errosCrcTotal = 0;
            registrar("Abrindo " + porta.getNome() + "...");
            Sessao s = new Sessao(porta);
            try {
                porta.abrir();
                atual = s;
                s.leitor.execute(() -> lacoLeitura(s));
                s.vigia.scheduleWithFixedDelay(() -> vigiar(s), 250, 250, TimeUnit.MILLISECONDS);

                Pacote resposta = pedir(s, Protocolo.CMD_HELLO, new byte[0], Protocolo.RSP_INFO,
                        TENTATIVAS_HELLO, TIMEOUT_HELLO_MS);
                InfoECU i = InfoECU.deBytes(resposta.getPayload());
                if (i.getVersaoProtocolo() != Protocolo.VERSAO) {
                    throw new ErroProtocolo("Protocolo incompatível: a placa fala a versão " + i.getVersaoProtocolo()
                            + " e o aplicativo a versão " + Protocolo.VERSAO + ". Atualize o firmware ou o app.");
                }
                if (i.getTamanhoMapa() != Protocolo.TAMANHO_MAPA || i.getNumMapas() != Protocolo.NUM_MAPAS) {
                    throw new ErroProtocolo("A placa usa mapas " + i.getTamanhoMapa() + "x" + i.getTamanhoMapa()
                            + " (" + i.getNumMapas() + " tabelas); este aplicativo espera " + Protocolo.TAMANHO_MAPA
                            + "x" + Protocolo.TAMANHO_MAPA + " (" + Protocolo.NUM_MAPAS + " tabelas).");
                }
                info = i;
                pedir(s, Protocolo.CMD_STREAM, periodoPayload(PERIODO_TELEMETRIA_PADRAO_MS), Protocolo.RSP_ACK,
                        TENTATIVAS, TIMEOUT_RESPOSTA_MS);
                s.ultimaTelemetriaNs = System.nanoTime();
                mudarEstado(EstadoConexao.CONECTADO);
                registrar("Conectado: " + i + (i.temSaidasReais() ? "" : " [modo sombra: saídas desligadas]"));
                return i;
            } catch (IOException | RuntimeException e) {
                registrar("Falha ao conectar: " + e.getMessage());
                encerrarSessao(s);
                atual = null;
                mudarEstado(EstadoConexao.DESCONECTADO);
                throw e;
            }
        }
    }

    /** Desliga a telemetria, para as threads e fecha a porta. Pode ser chamado a qualquer momento. */
    public void desconectar() {
        synchronized (lockCiclo) {
            Sessao s = atual;
            if (s != null && s.ativa.get() && estado != EstadoConexao.ERRO) {
                try {
                    // Pede para a placa parar de transmitir (melhor esforço: se falhar, a porta fecha do mesmo jeito).
                    Pacote parar = new Pacote(Protocolo.CMD_STREAM, s.proximoSeq(), periodoPayload(0));
                    s.escritor.submit(() -> {
                        s.porta.escrever(parar.codificar());
                        return null;
                    }).get(300, TimeUnit.MILLISECONDS);
                } catch (Exception ignorado) {
                    // nada a fazer: o importante é liberar a porta
                }
            }
            encerrarSessao(s);
            atual = null;
            info = null;
            mudarEstado(EstadoConexao.DESCONECTADO);
            registrar("Desconectado.");
        }
    }

    /** Fecha tudo de uma sessão e espera as threads terminarem (por pouco tempo). */
    private void encerrarSessao(Sessao s) {
        if (s == null) {
            return;
        }
        s.ativa.set(false);
        falharPendente(s, "Conexão encerrada");
        s.vigia.shutdownNow();
        s.escritor.shutdown();
        s.tarefas.shutdown(); // deixa as tarefas na fila terminarem: elas falham rápido porque a sessão não está ativa
        s.leitor.shutdown();
        try {
            // o rx sai em até ~100 ms (tempo de leitura da porta); só então fechamos, para não fechar "por baixo" de uma leitura
            if (!s.leitor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                registrar("Aviso: a thread de leitura demorou para parar.");
            }
            s.escritor.awaitTermination(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        s.porta.fechar();
    }

    /** Chamado quando a porta cai (cabo removido, erro de I/O). Não espera ninguém: pode rodar na thread rx. */
    private void falhar(Sessao s, String motivo) {
        if (!s.ativa.compareAndSet(true, false)) {
            return;
        }
        registrar(motivo);
        falharPendente(s, motivo);
        s.porta.fechar();
        s.vigia.shutdownNow();
        s.escritor.shutdownNow();
        s.tarefas.shutdown();
        s.leitor.shutdown();
        if (atual == s) {
            mudarEstado(EstadoConexao.ERRO);
        }
    }

    private void falharPendente(Sessao s, String motivo) {
        Pendente p = s.pendente;
        if (p != null) {
            p.futuro.completeExceptionally(new ErroProtocolo(motivo));
        }
    }

    // ------------------------------------------------------------------ threads internas

    private void lacoLeitura(Sessao s) {
        byte[] buffer = new byte[512];
        while (s.ativa.get()) {
            int n;
            try {
                n = s.porta.ler(buffer);
            } catch (IOException e) {
                falhar(s, "Erro de leitura na porta: " + e.getMessage());
                return;
            }
            if (n < 0) {
                if (s.ativa.get()) {
                    falhar(s, "A porta serial foi fechada ou o cabo USB foi removido.");
                }
                return;
            }
            if (n == 0) {
                continue; // tempo de leitura esgotado sem dados: normal
            }
            for (Pacote p : s.decodificador.alimentar(buffer, n)) {
                tratar(s, p);
            }
            if (atual == s) {
                pacotesValidosTotal = s.decodificador.getPacotesValidos();
                errosCrcTotal = s.decodificador.getErrosCrc();
            }
        }
    }

    private void tratar(Sessao s, Pacote p) {
        if (p.getTipo() == Protocolo.TELEMETRIA) {
            try {
                ultima.set(Telemetria.deBytes(p.getPayload()));
            } catch (IllegalArgumentException e) {
                registrar("Telemetria com tamanho inválido ignorada.");
                return;
            }
            telemetriasRecebidas.incrementAndGet();
            s.contadorTaxa.incrementAndGet();
            s.ultimaTelemetriaNs = System.nanoTime();
            if (atual == s && estado == EstadoConexao.SEM_SINAL) {
                registrar("Telemetria voltou.");
                mudarEstado(EstadoConexao.CONECTADO);
            }
            return;
        }
        Pendente pend = s.pendente;
        if (pend != null && pend.seq == p.getSeq()) {
            pend.futuro.complete(p);
        }
    }

    private void vigiar(Sessao s) {
        try {
            if (!s.ativa.get() || atual != s) {
                return;
            }
            long agora = System.nanoTime();
            long semDadosMs = (agora - s.ultimaTelemetriaNs) / 1_000_000L;
            if (estado == EstadoConexao.CONECTADO && semDadosMs > TIMEOUT_SEM_SINAL_MS) {
                registrar("Sem telemetria há " + semDadosMs + " ms.");
                mudarEstado(EstadoConexao.SEM_SINAL);
            }
            if (estado == EstadoConexao.SEM_SINAL
                    && (agora - s.ultimaRetomadaNs) / 1_000_000L >= INTERVALO_RETOMADA_MS) {
                // Se a placa reiniciou, ela volta com a telemetria desligada: pedimos de novo, sem esperar resposta.
                s.ultimaRetomadaNs = agora;
                final Pacote religar = new Pacote(Protocolo.CMD_STREAM, s.proximoSeq(),
                        periodoPayload(PERIODO_TELEMETRIA_PADRAO_MS));
                s.escritor.execute(() -> {
                    try {
                        s.porta.escrever(religar.codificar());
                    } catch (IOException e) {
                        falhar(s, "Erro de escrita na porta: " + e.getMessage());
                    }
                });
            }
            long decorridoNs = agora - s.instanteTaxaNs;
            if (decorridoNs >= 1_000_000_000L) {
                taxaHz = s.contadorTaxa.getAndSet(0) * 1e9 / decorridoNs;
                s.instanteTaxaNs = agora;
            }
        } catch (RuntimeException e) {
            registrar("Erro interno no vigia: " + e);
        }
    }

    // ------------------------------------------------------------------ envio e resposta

    private static byte[] periodoPayload(int ms) {
        return BufferLE.paraEscrita(2).escreverU16(ms).bytes();
    }

    private Sessao sessaoAtiva() {
        Sessao s = atual;
        if (s == null || !s.ativa.get()) {
            throw new ErroProtocolo("Não há conexão com a ECU.");
        }
        return s;
    }

    private void enviar(Sessao s, Pacote pacote) {
        final byte[] bytes = pacote.codificar();
        Future<Void> envio;
        try {
            envio = s.escritor.submit(() -> {
                s.porta.escrever(bytes);
                return null;
            });
        } catch (RejectedExecutionException e) {
            throw new ErroProtocolo("Conexão encerrada.");
        }
        try {
            envio.get(TIMEOUT_ESCRITA_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            envio.cancel(true);
            throw new ErroProtocolo("Tempo esgotado ao escrever na porta.");
        } catch (ExecutionException e) {
            String causa = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            falhar(s, "Erro de escrita na porta: " + causa);
            throw new ErroProtocolo("Falha ao escrever na porta: " + causa);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroProtocolo("Operação interrompida.");
        }
    }

    /** Envia um comando e espera a resposta do tipo esperado, com nova tentativa se o tempo esgotar. */
    private Pacote pedir(Sessao s, int comando, byte[] payload, int respostaEsperada, int tentativas,
                         int timeoutMs) {
        s.comando.lock();
        try {
            for (int tentativa = 1; tentativa <= tentativas; tentativa++) {
                if (!s.ativa.get()) {
                    throw new ErroProtocolo("Conexão encerrada.");
                }
                Pendente pend = new Pendente(s.proximoSeq());
                s.pendente = pend;
                try {
                    enviar(s, new Pacote(comando, pend.seq, payload));
                    Pacote resposta = pend.futuro.get(timeoutMs, TimeUnit.MILLISECONDS);
                    if (resposta.getTipo() == Protocolo.RSP_NACK) {
                        byte[] dados = resposta.getPayload();
                        throw new ErroProtocolo(dados.length > 0 ? dados[0] & 0xFF : 0);
                    }
                    if (resposta.getTipo() != respostaEsperada) {
                        throw new ErroProtocolo(String.format("Resposta inesperada 0x%02X ao comando 0x%02X.",
                                resposta.getTipo(), comando));
                    }
                    return resposta;
                } catch (TimeoutException e) {
                    registrar(String.format("Sem resposta ao comando 0x%02X (tentativa %d de %d).", comando,
                            tentativa, tentativas));
                } catch (ExecutionException e) {
                    throw (e.getCause() instanceof ErroProtocolo) ? (ErroProtocolo) e.getCause()
                            : new ErroProtocolo(String.valueOf(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ErroProtocolo("Operação interrompida.");
                } finally {
                    s.pendente = null;
                }
            }
            throw new ErroProtocolo("A ECU não respondeu ao comando 0x" + Integer.toHexString(comando)
                    + " após " + tentativas + " tentativas.");
        } finally {
            s.comando.unlock();
        }
    }

    private Pacote pedir(int comando, byte[] payload, int respostaEsperada) {
        return pedir(sessaoAtiva(), comando, payload, respostaEsperada, TENTATIVAS, TIMEOUT_RESPOSTA_MS);
    }

    // ------------------------------------------------------------------ comandos (bloqueantes)

    public ParametrosMotor lerParametros() {
        return ParametrosMotor.deBytes(pedir(Protocolo.CMD_LER_PARAMS, new byte[0], Protocolo.RSP_PARAMS)
                .getPayload());
    }

    public void escreverParametros(ParametrosMotor parametros) {
        pedir(Protocolo.CMD_ESCREVER_PARAMS, parametros.paraBytes(), Protocolo.RSP_ACK);
    }

    public EixosMapa lerEixos() {
        return EixosMapa.deBytes(pedir(Protocolo.CMD_LER_EIXOS, new byte[0], Protocolo.RSP_EIXOS).getPayload());
    }

    public void escreverEixos(EixosMapa eixos) {
        eixos.validar();
        pedir(Protocolo.CMD_ESCREVER_EIXOS, eixos.paraBytes(), Protocolo.RSP_ACK);
    }

    /** Lê uma linha (16 valores) do mapa da placa e grava no objeto {@code mapa}. */
    public void lerLinha(MapaECU mapa, int linha) {
        int id = mapa.getTipo().getId();
        byte[] dados = pedir(Protocolo.CMD_LER_LINHA, new byte[] {(byte) id, (byte) linha}, Protocolo.RSP_LINHA)
                .getPayload();
        if (dados.length != 2 + Protocolo.TAMANHO_MAPA || (dados[0] & 0xFF) != id || (dados[1] & 0xFF) != linha) {
            throw new ErroProtocolo("Resposta de linha inconsistente (mapa " + id + ", linha " + linha + ").");
        }
        mapa.linhaDeBytes(linha, Arrays.copyOfRange(dados, 2, dados.length));
    }

    /** Envia uma linha do mapa local para a placa (efeito imediato na RAM da ECU). */
    public void escreverLinha(MapaECU mapa, int linha) {
        byte[] valores = mapa.linhaParaBytes(linha);
        byte[] payload = BufferLE.paraEscrita(2 + valores.length).escreverU8(mapa.getTipo().getId())
                .escreverU8(linha).escreverBytes(valores).bytes();
        pedir(Protocolo.CMD_ESCREVER_LINHA, payload, Protocolo.RSP_ACK);
    }

    public Calibracao lerCalibracao(Progresso progresso) {
        Progresso p = progresso == null ? SEM_PROGRESSO : progresso;
        int total = 2 + Protocolo.NUM_MAPAS * Protocolo.TAMANHO_MAPA;
        int feitos = 0;
        Calibracao cal = Calibracao.padrao();
        cal.getParametros().copiarDe(lerParametros());
        p.aoAvancar(++feitos, total);
        cal.getEixos().copiarDe(lerEixos());
        p.aoAvancar(++feitos, total);
        for (TipoMapa tipo : TipoMapa.values()) {
            MapaECU mapa = cal.getMapa(tipo);
            for (int linha = 0; linha < Protocolo.TAMANHO_MAPA; linha++) {
                lerLinha(mapa, linha);
                p.aoAvancar(++feitos, total);
            }
        }
        return cal;
    }

    public void escreverCalibracao(Calibracao cal, Progresso progresso) {
        Progresso p = progresso == null ? SEM_PROGRESSO : progresso;
        int total = 2 + Protocolo.NUM_MAPAS * Protocolo.TAMANHO_MAPA;
        int feitos = 0;
        escreverParametros(cal.getParametros());
        p.aoAvancar(++feitos, total);
        escreverEixos(cal.getEixos());
        p.aoAvancar(++feitos, total);
        for (TipoMapa tipo : TipoMapa.values()) {
            MapaECU mapa = cal.getMapa(tipo);
            for (int linha = 0; linha < Protocolo.TAMANHO_MAPA; linha++) {
                escreverLinha(mapa, linha);
                p.aoAvancar(++feitos, total);
            }
        }
    }

    /** Persiste a calibração atual da RAM na memória não volátil da placa (recusado com o motor girando). */
    public void gravarNaMemoria() {
        pedir(sessaoAtiva(), Protocolo.CMD_GRAVAR, new byte[0], Protocolo.RSP_ACK, TENTATIVAS_GRAVAR, TIMEOUT_GRAVAR_MS);
    }

    /** Carrega os valores de fábrica na RAM da placa (não grava; use gravarNaMemoria para manter). */
    public void restaurarPadroes() {
        pedir(Protocolo.CMD_PADROES, new byte[0], Protocolo.RSP_ACK);
    }

    /** Arma/desarma as saídas reais (bicos e bobina). Recusado se o firmware está em modo sombra. */
    public void armarSaidas(boolean armar) {
        pedir(Protocolo.CMD_ARMAR, new byte[] {(byte) (armar ? 1 : 0)}, Protocolo.RSP_ACK);
    }

    /** Liga/desliga as chaves virtuais (bancada): veja {@link Protocolo#CHAVE_TWO_STEP} e {@link Protocolo#CHAVE_LAUNCH}. */
    public void definirChaves(int mascara) {
        pedir(Protocolo.CMD_CHAVES, new byte[] {(byte) mascara}, Protocolo.RSP_ACK);
    }

    public void definirPeriodoTelemetria(int ms) {
        pedir(Protocolo.CMD_STREAM, periodoPayload(ms), Protocolo.RSP_ACK);
    }

    /**
     * Executa uma operação demorada na thread "ecu-tarefas" e devolve um CompletableFuture.
     * É assim que a interface chama os comandos acima sem congelar: quem recebe o resultado
     * deve voltar à thread do Swing com SwingUtilities.invokeLater.
     */
    public <T> CompletableFuture<T> emSegundoPlano(Callable<T> tarefa) {
        CompletableFuture<T> futuro = new CompletableFuture<>();
        Sessao s = atual;
        if (s == null || !s.ativa.get()) {
            futuro.completeExceptionally(new ErroProtocolo("Não há conexão com a ECU."));
            return futuro;
        }
        try {
            s.tarefas.execute(() -> {
                try {
                    futuro.complete(tarefa.call());
                } catch (Throwable e) {
                    futuro.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            futuro.completeExceptionally(new ErroProtocolo("Conexão encerrada."));
        }
        return futuro;
    }

    // ------------------------------------------------------------------ consulta e ouvintes

    public Telemetria getUltimaTelemetria() {
        return ultima.get();
    }

    public EstadoConexao getEstado() {
        return estado;
    }

    /** Identificação da placa (null enquanto não conectado). */
    public InfoECU getInfo() {
        return info;
    }

    public String getNomePorta() {
        Sessao s = atual;
        return s == null ? "" : s.porta.getNome();
    }

    public long getTelemetriasRecebidas() {
        return telemetriasRecebidas.get();
    }

    /** Quadros de telemetria por segundo, medido a cada segundo. */
    public double getTaxaTelemetria() {
        return taxaHz;
    }

    public long getPacotesValidos() {
        return pacotesValidosTotal;
    }

    public long getErrosCrc() {
        return errosCrcTotal;
    }

    public void adicionarOuvinte(OuvinteConexao ouvinte) {
        ouvintes.add(ouvinte);
    }

    public void removerOuvinte(OuvinteConexao ouvinte) {
        ouvintes.remove(ouvinte);
    }

    private void mudarEstado(EstadoConexao novo) {
        synchronized (lockEstado) {
            if (estado == novo) {
                return;
            }
            estado = novo;
            for (OuvinteConexao o : ouvintes) {
                try {
                    o.aoMudarEstado(novo);
                } catch (RuntimeException e) {
                    // um ouvinte com defeito não pode derrubar a conexão
                }
            }
        }
    }

    private void registrar(String mensagem) {
        for (OuvinteConexao o : ouvintes) {
            try {
                o.aoRegistrar(mensagem);
            } catch (RuntimeException e) {
                // idem
            }
        }
    }
}
