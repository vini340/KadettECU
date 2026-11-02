package kadettecu.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kadettecu.comunicacao.ConexaoECU;
import kadettecu.comunicacao.ErroProtocolo;
import kadettecu.comunicacao.OuvinteConexao;
import kadettecu.modelo.Calibracao;
import kadettecu.modelo.EixosMapa;
import kadettecu.modelo.EstadoConexao;
import kadettecu.modelo.InfoECU;
import kadettecu.modelo.MapaECU;
import kadettecu.modelo.ParametrosMotor;
import kadettecu.modelo.TipoMapa;
import kadettecu.protocolo.Protocolo;

/**
 * O "cérebro" do aplicativo (a camada entre a tela e a comunicação): guarda a calibração que o
 * usuário está editando, lembra o que a ECU tem de fato, e oferece as operações (conectar, ler,
 * enviar, gravar...) sem nunca bloquear quem chama: todas devolvem um {@link CompletableFuture}.
 *
 * <p><b>Regra de threads.</b> Esta classe não conhece o Swing. Em vez disso, o construtor recebe um
 * {@link Executor} "da tela" (a interface passa {@code SwingUtilities::invokeLater}). A calibração
 * local só é lida e alterada nessa thread, então os editores nunca veem uma tabela mudando "por baixo"
 * deles. O trabalho lento (falar com a placa) roda nas threads da conexão e só o passo final de
 * atualizar a calibração volta para a thread da tela. Em testes, basta passar {@code Runnable::run}.
 *
 * <p>Os métodos {@code getCalibracao}, {@code notificarEdicao}, {@code enviar*} e {@code salvarArquivo}
 * devem ser chamados na thread da tela (eles fazem uma cópia da calibração no momento da chamada).
 */
public final class SessaoECU {

    /** Quem quer ser avisado do que acontece. Os métodos podem ser chamados em qualquer thread. */
    public interface Ouvinte {

        default void aoMudarEstado(EstadoConexao estado) {
        }

        default void aoRegistrar(String mensagem) {
        }

        /** A calibração local foi trocada por inteiro (leitura da ECU ou abertura de arquivo): os editores precisam se atualizar. */
        default void aoSubstituirCalibracao() {
        }

        /** Mudou a situação "igual à ECU / com alterações". */
        default void aoMudarSituacaoCalibracao(boolean alterada) {
        }

        /** Andamento de uma operação longa. */
        default void aoProgredir(String operacao, int feitos, int total) {
        }
    }

    private final ConexaoECU conexao = new ConexaoECU();
    private final Executor tela;
    private final ExecutorService servico = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ecu-sessao");
        t.setDaemon(true);
        return t;
    });
    private final List<Ouvinte> ouvintes = new CopyOnWriteArrayList<>();
    private final Calibracao calibracao = Calibracao.padrao();
    private final Calibracao calibracaoEcu = Calibracao.padrao();
    private boolean alterada;
    private volatile Bancada bancada;
    private volatile OpcaoPorta opcaoAtual;

    public SessaoECU(Executor tela) {
        this.tela = tela;
        conexao.adicionarOuvinte(new OuvinteConexao() {
            @Override
            public void aoMudarEstado(EstadoConexao estado) {
                for (Ouvinte o : ouvintes) {
                    avisar(() -> o.aoMudarEstado(estado));
                }
            }

            @Override
            public void aoRegistrar(String mensagem) {
                registrar(mensagem);
            }
        });
    }

    // ------------------------------------------------------------------ consulta

    public ConexaoECU getConexao() {
        return conexao;
    }

    public EstadoConexao getEstado() {
        return conexao.getEstado();
    }

    /** A calibração em edição. Só pode ser lida e alterada na thread da tela. */
    public Calibracao getCalibracao() {
        return calibracao;
    }

    /** O que a ECU tem agora, até onde sabemos (a última leitura ou envio). Só na thread da tela. */
    public Calibracao getCalibracaoEcu() {
        return calibracaoEcu;
    }

    public boolean isAlterada() {
        return alterada;
    }

    /** Bancada de testes da ligação atual, ou null (ECU de verdade ou desconectado). */
    public Bancada getBancada() {
        return bancada;
    }

    public OpcaoPorta getOpcaoAtual() {
        return opcaoAtual;
    }

    public void adicionarOuvinte(Ouvinte ouvinte) {
        ouvintes.add(ouvinte);
    }

    public void removerOuvinte(Ouvinte ouvinte) {
        ouvintes.remove(ouvinte);
    }

    /** Escreve uma linha no registro de eventos (a aba REGISTRO). */
    public void registrar(String mensagem) {
        for (Ouvinte o : ouvintes) {
            avisar(() -> o.aoRegistrar(mensagem));
        }
    }

    private static void avisar(Runnable aviso) {
        try {
            aviso.run();
        } catch (RuntimeException e) {
            // um ouvinte com defeito não pode atrapalhar a comunicação
        }
    }

    private void progresso(String operacao, int feitos, int total) {
        for (Ouvinte o : ouvintes) {
            avisar(() -> o.aoProgredir(operacao, feitos, total));
        }
    }

    // ------------------------------------------------------------------ conexão

    /** Abre a ligação escolhida e faz o HELLO. O resultado chega pelo futuro (a tela decide o que fazer). */
    public CompletableFuture<InfoECU> conectar(OpcaoPorta opcao, int baud) {
        return CompletableFuture.supplyAsync(() -> {
            encerrarBancada();
            Ligacao ligacao;
            try {
                ligacao = opcao.abrir(baud);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            bancada = ligacao.getBancada();
            opcaoAtual = opcao;
            try {
                return conexao.conectar(ligacao.getPorta());
            } catch (IOException e) {
                encerrarBancada();
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                encerrarBancada();
                throw e;
            }
        }, servico);
    }

    public CompletableFuture<Void> desconectar() {
        return CompletableFuture.runAsync(() -> {
            conexao.desconectar();
            encerrarBancada();
        }, servico);
    }

    private void encerrarBancada() {
        Bancada b = bancada;
        bancada = null;
        if (b != null) {
            b.fechar();
        }
    }

    /** Desliga tudo ao fechar o programa (espera pouco: no pior caso a porta é fechada pelo sistema). */
    public void fechar() {
        if (servico.isShutdown()) {
            return; // já foi fechada (por exemplo, pela janela e depois pelo gancho de saída da JVM)
        }
        try {
            servico.submit(() -> {
                conexao.desconectar();
                encerrarBancada();
            }).get(2500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            // seguimos: o importante é o programa poder fechar
        }
        servico.shutdownNow();
    }

    // ------------------------------------------------------------------ calibração: ECU -> tela

    /** Lê toda a calibração da ECU e a coloca como calibração local (descarta as alterações locais). */
    public CompletableFuture<Void> lerDaEcu() {
        return conexao.emSegundoPlano(() -> conexao.lerCalibracao((feitos, total) -> progresso("Lendo da ECU", feitos, total)))
                .thenAcceptAsync(cal -> aplicarCalibracao(cal, true), tela);
    }

    /**
     * Lê a calibração da ECU só para comparar: a calibração da tela fica como está, e o aviso "alterado" passa a
     * valer em relação ao que a ECU tem de verdade (depois de reconectar, ela pode ter outros valores, por exemplo
     * os da memória da placa).
     */
    public CompletableFuture<Void> lerReferenciaDaEcu() {
        return conexao.emSegundoPlano(() -> conexao.lerCalibracao((feitos, total) -> progresso("Lendo da ECU", feitos, total)))
                .thenAcceptAsync(cal -> {
                    copiarPara(calibracaoEcu, cal);
                    recalcularSituacao();
                }, tela);
    }

    /** Carrega os valores de fábrica na ECU e traz o resultado para a tela. */
    public CompletableFuture<Void> restaurarPadroes() {
        return conexao.emSegundoPlano(() -> {
            conexao.restaurarPadroes();
            return conexao.lerCalibracao((feitos, total) -> progresso("Lendo valores de fábrica", feitos, total));
        }).thenAcceptAsync(cal -> aplicarCalibracao(cal, true), tela);
    }

    /** Troca a calibração local por inteiro; {@code sincronizada} diz se ela é, de fato, a que está na ECU. */
    private void aplicarCalibracao(Calibracao nova, boolean sincronizada) {
        copiarPara(calibracao, nova);
        if (sincronizada) {
            copiarPara(calibracaoEcu, nova);
        }
        for (Ouvinte o : ouvintes) {
            avisar(o::aoSubstituirCalibracao);
        }
        recalcularSituacao();
    }

    private static void copiarPara(Calibracao destino, Calibracao origem) {
        destino.getParametros().copiarDe(origem.getParametros());
        destino.getEixos().copiarDe(origem.getEixos());
        for (TipoMapa tipo : TipoMapa.values()) {
            destino.getMapa(tipo).copiarDe(origem.getMapa(tipo));
        }
    }

    private static boolean iguais(Calibracao a, Calibracao b) {
        if (!a.getParametros().equals(b.getParametros()) || !a.getEixos().equals(b.getEixos())) {
            return false;
        }
        for (TipoMapa tipo : TipoMapa.values()) {
            if (!a.getMapa(tipo).equals(b.getMapa(tipo))) {
                return false;
            }
        }
        return true;
    }

    /** Os editores chamam isto depois de qualquer alteração: atualiza o aviso "alterações não enviadas". */
    public void notificarEdicao() {
        recalcularSituacao();
    }

    private void recalcularSituacao() {
        boolean agora = !iguais(calibracao, calibracaoEcu);
        if (agora != alterada) {
            alterada = agora;
            for (Ouvinte o : ouvintes) {
                avisar(() -> o.aoMudarSituacaoCalibracao(agora));
            }
        }
    }

    // ------------------------------------------------------------------ calibração: tela -> ECU

    /** Envia tudo (parâmetros, eixos e as três tabelas) para a RAM da ECU. Não grava na memória permanente. */
    public CompletableFuture<Void> enviarTudo() {
        final Calibracao copia = calibracao.copia();
        return conexao.emSegundoPlano(() -> {
            conexao.escreverCalibracao(copia, (feitos, total) -> progresso("Enviando à ECU", feitos, total));
            return copia;
        }).thenAcceptAsync(enviada -> {
            copiarPara(calibracaoEcu, enviada);
            recalcularSituacao();
        }, tela);
    }

    public CompletableFuture<Void> enviarParametros() {
        final ParametrosMotor copia = calibracao.getParametros().copia();
        return conexao.emSegundoPlano(() -> {
            conexao.escreverParametros(copia);
            return copia;
        }).thenAcceptAsync(enviada -> {
            calibracaoEcu.getParametros().copiarDe(enviada);
            recalcularSituacao();
        }, tela);
    }

    public CompletableFuture<Void> enviarEixos() {
        final EixosMapa copia = calibracao.getEixos().copia();
        return conexao.emSegundoPlano(() -> {
            conexao.escreverEixos(copia);
            return copia;
        }).thenAcceptAsync(enviada -> {
            calibracaoEcu.getEixos().copiarDe(enviada);
            recalcularSituacao();
        }, tela);
    }

    /** Envia só algumas linhas de uma tabela (o modo "ao vivo" do editor usa isto a cada alteração). */
    public CompletableFuture<Void> enviarLinhas(TipoMapa tipo, Collection<Integer> linhas) {
        final MapaECU copia = calibracao.getMapa(tipo).copia();
        final List<Integer> escolhidas = new ArrayList<>(linhas);
        return conexao.emSegundoPlano(() -> {
            for (int linha : escolhidas) {
                conexao.escreverLinha(copia, linha);
            }
            return copia;
        }).thenAcceptAsync(enviada -> {
            for (int linha : escolhidas) {
                calibracaoEcu.getMapa(tipo).linhaDeBytes(linha, enviada.linhaParaBytes(linha));
            }
            recalcularSituacao();
        }, tela);
    }

    /** Grava na memória permanente da placa o que está na RAM (a ECU recusa com o motor girando). */
    public CompletableFuture<Void> gravarNaMemoria() {
        return conexao.emSegundoPlano(() -> {
            conexao.gravarNaMemoria();
            return null;
        });
    }

    public CompletableFuture<Void> armarSaidas(boolean armar) {
        return conexao.emSegundoPlano(() -> {
            conexao.armarSaidas(armar);
            return null;
        });
    }

    /** Liga ou desliga as chaves virtuais de two-step e launch (as mesmas entradas que os botões do painel). */
    public CompletableFuture<Void> definirChaves(boolean twoStep, boolean launch) {
        final int mascara = (twoStep ? Protocolo.CHAVE_TWO_STEP : 0) | (launch ? Protocolo.CHAVE_LAUNCH : 0);
        return conexao.emSegundoPlano(() -> {
            conexao.definirChaves(mascara);
            return null;
        });
    }

    // ------------------------------------------------------------------ arquivos .kecu

    public CompletableFuture<Void> salvarArquivo(Path destino) {
        final Calibracao copia = calibracao.copia();
        return CompletableFuture.runAsync(() -> {
            try {
                copia.salvar(destino);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, servico);
    }

    /** Abre um arquivo .kecu como calibração local (fica marcada como "com alterações" em relação à ECU). */
    public CompletableFuture<Void> carregarArquivo(Path origem) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Calibracao.carregar(origem);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, servico).thenAcceptAsync(cal -> aplicarCalibracao(cal, false), tela);
    }

    // ------------------------------------------------------------------ erros

    /** Texto em português, pronto para mostrar ao usuário, para um erro vindo de qualquer operação acima. */
    public static String descreverErro(Throwable erro) {
        Throwable t = erro;
        while ((t instanceof CompletionException || t instanceof ExecutionException || t instanceof UncheckedIOException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof CancellationException) {
            return "Operação cancelada.";
        }
        if (t instanceof ErroProtocolo || t instanceof IOException) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
