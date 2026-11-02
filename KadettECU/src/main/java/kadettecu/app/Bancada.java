package kadettecu.app;

/**
 * "Bancada de testes": permite mexer à mão nas entradas de uma ECU que não está num carro de
 * verdade (o simulador em Java ou o firmware em C rodando no PC). É uma interface porque cada
 * tipo de ECU recebe os valores de um jeito diferente; a tela só conhece este contrato.
 */
public interface Bancada {

    /** Liga o modo manual (se ainda não estiver) e passa a usar estes valores nas entradas. */
    void definir(EntradasBancada entradas) throws java.io.IOException;

    /** Sai do modo manual: o simulador volta ao ciclo automático; no firmware do PC o motor para. */
    void desligar() throws java.io.IOException;

    /** true se, fora do modo manual, a ECU anda sozinha (o simulador faz um ciclo de aceleração). */
    boolean temCicloAutomatico();

    boolean estaManual();

    /** true se a bancada consegue fixar o lambda lido pela ECU (o simulador em Java calcula o dele sozinho). */
    default boolean controlaLambda() {
        return true;
    }

    /** Libera o que a bancada tiver aberto (arquivos temporários). */
    void fechar();
}
