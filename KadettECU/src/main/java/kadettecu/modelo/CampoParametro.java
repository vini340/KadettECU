package kadettecu.modelo;

/**
 * Cada parâmetro ajustável do motor. A ordem das constantes é a ordem dos bytes no
 * protocolo (CMD_LER_PARAMS / CMD_ESCREVER_PARAMS) e precisa ser idêntica à tabela do
 * firmware (kecu_params.c). Os valores trafegam como inteiros; "divisor" só afeta a exibição.
 */
public enum CampoParametro {

    REV_LIMITE_RPM("Limite de rotação", "rpm", Grupo.CORTE, Tipo.U16, 1500, 12000, 6500, 1),
    REV_HISTERESE_RPM("Histerese do corte", "rpm", Grupo.CORTE, Tipo.U16, 0, 2000, 150, 1),
    TIPO_CORTE("Tipo de corte", "", Grupo.CORTE, Tipo.U8, 0, 2, 2, 1,
            "Combustível", "Ignição", "Combustível + ignição"),
    HAB_TWO_STEP("Two-step habilitado", "", Grupo.CORTE, Tipo.BOOL, 0, 1, 0, 1),
    TWO_STEP_RPM("Rotação do two-step", "rpm", Grupo.CORTE, Tipo.U16, 1000, 12000, 4500, 1),

    HAB_LAUNCH("Launch control habilitado", "", Grupo.LAUNCH, Tipo.BOOL, 0, 1, 0, 1),
    LAUNCH_RPM("Rotação de largada", "rpm", Grupo.LAUNCH, Tipo.U16, 1000, 12000, 3500, 1),
    LAUNCH_TPS_MIN("TPS mínimo para armar", "%", Grupo.LAUNCH, Tipo.U8, 0, 100, 90, 1),
    LAUNCH_VEL_MAX("Velocidade máxima para armar", "km/h", Grupo.LAUNCH, Tipo.U8, 0, 100, 10, 1),
    LAUNCH_RETARDO("Retardo de ignição", "°", Grupo.LAUNCH, Tipo.U8, 0, 30, 10, 1),

    HAB_AE("Enriquecimento habilitado", "", Grupo.ACELERACAO, Tipo.BOOL, 0, 1, 1, 1),
    AE_ENRIQUECIMENTO("Enriquecimento máximo", "%", Grupo.ACELERACAO, Tipo.U8, 0, 200, 30, 1),
    AE_LIMIAR("Limiar de variação do TPS", "%/s", Grupo.ACELERACAO, Tipo.U16, 0, 2000, 150, 1),
    AE_DURACAO_MS("Duração do enriquecimento", "ms", Grupo.ACELERACAO, Tipo.U16, 0, 2000, 400, 1),

    HAB_MARCHA_LENTA("Controle de marcha lenta", "", Grupo.MARCHA_LENTA, Tipo.BOOL, 0, 1, 1, 1),
    ML_RPM_QUENTE("Rotação alvo (motor quente)", "rpm", Grupo.MARCHA_LENTA, Tipo.U16, 400, 2000, 850, 1),
    ML_RPM_FRIO("Rotação alvo (motor frio)", "rpm", Grupo.MARCHA_LENTA, Tipo.U16, 400, 2500, 1300, 1),
    ML_TPS_MAX("TPS máximo para marcha lenta", "%", Grupo.MARCHA_LENTA, Tipo.U8, 0, 20, 4, 1),
    ML_KP("Ganho proporcional (kp)", "%/rpm", Grupo.MARCHA_LENTA, Tipo.U8, 0, 100, 5, 100),
    ML_KI("Ganho integral (ki)", "%/rpm·s", Grupo.MARCHA_LENTA, Tipo.U8, 0, 100, 2, 100),
    ML_DUTY_BASE("Abertura base da válvula (IAC)", "%", Grupo.MARCHA_LENTA, Tipo.U8, 0, 100, 35, 1),

    REQ_FUEL_US("Pulso base (reqFuel)", "µs", Grupo.COMBUSTIVEL, Tipo.U16, 500, 30000, 8500, 1),
    TEMPO_MORTO_US("Tempo morto do injetor (14 V)", "µs", Grupo.COMBUSTIVEL, Tipo.U16, 0, 5000, 900, 1),
    TEMPO_MORTO_COEF("Correção do tempo morto", "µs/V", Grupo.COMBUSTIVEL, Tipo.U16, 0, 1000, 150, 1),
    AFR_ESTEQ_X10("AFR estequiométrico", ":1", Grupo.COMBUSTIVEL, Tipo.U16, 80, 180, 130, 10),
    DWELL_US("Carga da bobina (dwell)", "µs", Grupo.COMBUSTIVEL, Tipo.U16, 500, 8000, 3000, 1),

    CILINDROS("Cilindros", "", Grupo.SINAL, Tipo.U8, 1, 8, 4, 1),
    DENTES_RODA("Dentes da roda (pulsos/volta)", "", Grupo.SINAL, Tipo.U8, 2, 120, 60, 1),
    DENTES_FALTANDO("Dentes faltando", "", Grupo.SINAL, Tipo.U8, 0, 4, 2, 1),
    MODO_SINAL("Sinal de rotação", "", Grupo.SINAL, Tipo.U8, 0, 1, 0, 1,
            "Roda fônica", "Pulsos uniformes"),
    PMS_OFFSET_X10("Offset do PMS", "°", Grupo.SINAL, Tipo.U16, 0, 3600, 0, 10),
    VSS_PULSOS_KM("Sensor de velocidade (VSS)", "pulsos/km", Grupo.SINAL, Tipo.U16, 100, 20000, 4000, 1);

    public enum Tipo {
        BOOL(1), U8(1), U16(2);

        private final int bytes;

        Tipo(int bytes) {
            this.bytes = bytes;
        }

        public int getBytes() {
            return bytes;
        }
    }

    public enum Grupo {
        CORTE("CORTE DE GIRO / TWO-STEP"),
        LAUNCH("LAUNCH CONTROL"),
        ACELERACAO("ENRIQUECIMENTO EM ACELERAÇÃO"),
        MARCHA_LENTA("MARCHA LENTA"),
        COMBUSTIVEL("INJETORES E IGNIÇÃO"),
        SINAL("SINAL DE ROTAÇÃO E MOTOR");

        private final String titulo;

        Grupo(String titulo) {
            this.titulo = titulo;
        }

        public String getTitulo() {
            return titulo;
        }
    }

    private final String rotulo;
    private final String unidade;
    private final Grupo grupo;
    private final Tipo tipo;
    private final int min;
    private final int max;
    private final int padrao;
    private final int divisor;
    private final String[] opcoes;

    CampoParametro(String rotulo, String unidade, Grupo grupo, Tipo tipo, int min, int max, int padrao,
                   int divisor, String... opcoes) {
        this.rotulo = rotulo;
        this.unidade = unidade;
        this.grupo = grupo;
        this.tipo = tipo;
        this.min = min;
        this.max = max;
        this.padrao = padrao;
        this.divisor = divisor;
        this.opcoes = opcoes;
    }

    public String getRotulo() {
        return rotulo;
    }

    public String getUnidade() {
        return unidade;
    }

    public Grupo getGrupo() {
        return grupo;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int getPadrao() {
        return padrao;
    }

    public int getDivisor() {
        return divisor;
    }

    public String[] getOpcoes() {
        return opcoes.clone();
    }

    public boolean temOpcoes() {
        return opcoes.length > 0;
    }

    /** Campos que definem como o sinal de rotação é lido: mudar com o motor girando derrubaria a sincronia. */
    public boolean exigeMotorParado() {
        return this == CILINDROS || this == DENTES_RODA || this == DENTES_FALTANDO || this == MODO_SINAL
                || this == PMS_OFFSET_X10;
    }

    public boolean dentroDaFaixa(int valor) {
        return valor >= min && valor <= max;
    }

    /** Soma dos tamanhos de todos os campos: é o tamanho do payload de parâmetros. */
    public static int tamanhoTotal() {
        int total = 0;
        for (CampoParametro c : values()) {
            total += c.tipo.getBytes();
        }
        return total;
    }
}
