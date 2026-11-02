package kadettecu.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Ponte entre as threads de comunicação e a thread do Swing.
 *
 * <p>Regra do Swing: componentes só podem ser mexidos na thread de eventos (EDT). As operações
 * com a ECU rodam em outras threads e devolvem um {@link CompletableFuture}; este utilitário
 * espera o resultado SEM bloquear ninguém e entrega o sucesso ou o erro de volta já dentro da EDT.
 */
public final class Tarefa {

    private Tarefa() {
    }

    public static <T> void aoTerminar(CompletableFuture<T> futuro, Consumer<T> aoSucesso, Consumer<Throwable> aoErro) {
        futuro.whenComplete((resultado, erro) -> SwingUtilities.invokeLater(() -> {
            if (erro != null) {
                aoErro.accept(causaReal(erro));
            } else {
                aoSucesso.accept(resultado);
            }
        }));
    }

    /** Variante para quando só importa saber que terminou bem. */
    public static void aoTerminarOk(CompletableFuture<?> futuro, Runnable aoSucesso, Consumer<Throwable> aoErro) {
        aoTerminar(futuro, ignorado -> aoSucesso.run(), aoErro);
    }

    /** Remove os "embrulhos" (CompletionException/ExecutionException) para chegar ao erro de verdade. */
    public static Throwable causaReal(Throwable erro) {
        Throwable atual = erro;
        while ((atual instanceof CompletionException || atual instanceof ExecutionException)
                && atual.getCause() != null) {
            atual = atual.getCause();
        }
        return atual;
    }
}
