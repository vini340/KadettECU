package kadettecu.app;

import kadettecu.simulador.ModeloVeiculo;

/** Bancada do simulador em Java: usa o "modo bancada" do {@link ModeloVeiculo}. */
public final class BancadaSimulador implements Bancada {

    private final ModeloVeiculo carro;

    public BancadaSimulador(ModeloVeiculo carro) {
        this.carro = carro;
    }

    @Override
    public void definir(EntradasBancada e) {
        carro.definirBancada(e.getRpm(), e.getVelocidadeKmh(), e.getTpsPct(), e.getMapKpa(), e.getEctC(), e.getIatC(),
                e.getBateriaV(), e.getOleoBar());
    }

    @Override
    public void desligar() {
        carro.encerrarBancada();
    }

    @Override
    public boolean temCicloAutomatico() {
        return true;
    }

    @Override
    public boolean estaManual() {
        return carro.isBancada();
    }

    @Override
    public boolean controlaLambda() {
        return false;
    }

    @Override
    public void fechar() {
        // nada a liberar: o simulador vive só na memória
    }
}
