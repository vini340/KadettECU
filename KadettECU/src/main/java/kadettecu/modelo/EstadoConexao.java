package kadettecu.modelo;

/** Situação do enlace entre o notebook e a ECU. */
public enum EstadoConexao {
    DESCONECTADO("DESCONECTADO"),
    CONECTANDO("CONECTANDO"),
    CONECTADO("CONECTADO"),
    SEM_SINAL("SEM SINAL"),
    ERRO("ERRO");

    private final String rotulo;

    EstadoConexao(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }
}
