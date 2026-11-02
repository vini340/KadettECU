package kadettecu.app;

import kadettecu.comunicacao.PortaSerial;

/** O que uma {@link OpcaoPorta} produz ao ser aberta: a porta de comunicação e, se existir, a bancada de testes. */
public final class Ligacao {

    private final PortaSerial porta;
    private final Bancada bancada;

    public Ligacao(PortaSerial porta, Bancada bancada) {
        this.porta = porta;
        this.bancada = bancada;
    }

    public PortaSerial getPorta() {
        return porta;
    }

    /** null quando a ECU é de verdade (não há como mexer nas entradas à mão). */
    public Bancada getBancada() {
        return bancada;
    }
}
