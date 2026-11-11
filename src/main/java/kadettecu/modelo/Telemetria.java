package kadettecu.modelo;

import kadettecu.protocolo.BufferLE;

/**
 * Um instantâneo dos dados do motor, imutável (pode ser compartilhado entre threads sem
 * sincronização). Valores já convertidos para unidades físicas.
 */
public final class Telemetria {

    public static final int TAMANHO = 36;

    private final long tempoMs;
    private final int rpm;
    private final double velocidadeKmh;
    private final double ectC;
    private final double iatC;
    private final double mapKpa;
    private final double oleoBar;
    private final double tpsPct;
    private final double lambda;
    private final double bateriaV;
    private final double avancoGraus;
    private final int pwUs;
    private final int vePct;
    private final int aePct;
    private final int iacPct;
    private final double posRpm;
    private final double posCarga;
    private final int flags;
    private final int erros;

    private Telemetria(Construtor c) {
        this.tempoMs = c.tempoMs;
        this.rpm = c.rpm;
        this.velocidadeKmh = c.velocidadeKmh;
        this.ectC = c.ectC;
        this.iatC = c.iatC;
        this.mapKpa = c.mapKpa;
        this.oleoBar = c.oleoBar;
        this.tpsPct = c.tpsPct;
        this.lambda = c.lambda;
        this.bateriaV = c.bateriaV;
        this.avancoGraus = c.avancoGraus;
        this.pwUs = c.pwUs;
        this.vePct = c.vePct;
        this.aePct = c.aePct;
        this.iacPct = c.iacPct;
        this.posRpm = c.posRpm;
        this.posCarga = c.posCarga;
        this.flags = c.flags;
        this.erros = c.erros;
    }

    public static Telemetria vazia() {
        return new Construtor().ect(25).iat(25).lambda(1.0).bateria(12.0).construir();
    }

    public static Telemetria deBytes(byte[] payload) {
        if (payload.length != TAMANHO) {
            throw new IllegalArgumentException("Telemetria deve ter " + TAMANHO + " bytes, veio " + payload.length);
        }
        BufferLE b = BufferLE.paraLeitura(payload);
        Construtor c = new Construtor();
        c.tempoMs = b.lerU32();
        c.rpm = b.lerU16();
        c.velocidadeKmh = b.lerU16() / 10.0;
        c.ectC = b.lerS16() / 10.0;
        c.iatC = b.lerS16() / 10.0;
        c.mapKpa = b.lerU16() / 10.0;
        c.oleoBar = b.lerU16() / 10.0;
        c.tpsPct = b.lerU16() / 10.0;
        c.lambda = b.lerU16() / 1000.0;
        c.bateriaV = b.lerU16() / 1000.0;
        c.avancoGraus = b.lerS16() / 10.0;
        c.pwUs = b.lerU16();
        c.vePct = b.lerU8();
        c.aePct = b.lerU8();
        c.iacPct = b.lerU8();
        c.posRpm = b.lerU8() / 16.0;
        c.posCarga = b.lerU8() / 16.0;
        b.lerU8(); // reservado
        c.flags = b.lerU16();
        c.erros = b.lerU16();
        return new Telemetria(c);
    }

    public byte[] paraBytes() {
        return BufferLE.paraEscrita(TAMANHO)
                .escreverU32(tempoMs)
                .escreverU16(limitar(rpm, 0, 65535))
                .escreverU16(limitar(Math.round(velocidadeKmh * 10), 0, 65535))
                .escreverS16(limitar(Math.round(ectC * 10), -32768, 32767))
                .escreverS16(limitar(Math.round(iatC * 10), -32768, 32767))
                .escreverU16(limitar(Math.round(mapKpa * 10), 0, 65535))
                .escreverU16(limitar(Math.round(oleoBar * 10), 0, 65535))
                .escreverU16(limitar(Math.round(tpsPct * 10), 0, 65535))
                .escreverU16(limitar(Math.round(lambda * 1000), 0, 65535))
                .escreverU16(limitar(Math.round(bateriaV * 1000), 0, 65535))
                .escreverS16(limitar(Math.round(avancoGraus * 10), -32768, 32767))
                .escreverU16(limitar(pwUs, 0, 65535))
                .escreverU8(limitar(vePct, 0, 255))
                .escreverU8(limitar(aePct, 0, 255))
                .escreverU8(limitar(iacPct, 0, 255))
                .escreverU8(limitar(Math.round(posRpm * 16), 0, 255))
                .escreverU8(limitar(Math.round(posCarga * 16), 0, 255))
                .escreverU8(0)
                .escreverU16(flags)
                .escreverU16(erros)
                .bytes();
    }

    private static int limitar(long v, int min, int max) {
        return (int) Math.max(min, Math.min(max, v));
    }

    public long getTempoMs() {
        return tempoMs;
    }

    public int getRpm() {
        return rpm;
    }

    public double getVelocidadeKmh() {
        return velocidadeKmh;
    }

    public double getEctC() {
        return ectC;
    }

    public double getIatC() {
        return iatC;
    }

    public double getMapKpa() {
        return mapKpa;
    }

    public double getOleoBar() {
        return oleoBar;
    }

    public double getTpsPct() {
        return tpsPct;
    }

    public double getLambda() {
        return lambda;
    }

    public double getBateriaV() {
        return bateriaV;
    }

    public double getAvancoGraus() {
        return avancoGraus;
    }

    public int getPwUs() {
        return pwUs;
    }

    public int getVePct() {
        return vePct;
    }

    public int getAePct() {
        return aePct;
    }

    public int getIacPct() {
        return iacPct;
    }

    /** Posição do ponto de operação no eixo de RPM da tabela, em índices (0 a 15, fracionário). */
    public double getPosRpm() {
        return posRpm;
    }

    /** Posição do ponto de operação no eixo de carga (MAP) da tabela, em índices (0 a 15). */
    public double getPosCarga() {
        return posCarga;
    }

    public int getFlags() {
        return flags;
    }

    public int getErros() {
        return erros;
    }

    public double getAfr(double estequiometrico) {
        return lambda * estequiometrico;
    }

    public boolean temFlag(int mascara) {
        return (flags & mascara) != 0;
    }

    public boolean temErro(int mascara) {
        return (erros & mascara) != 0;
    }

    @Override
    public String toString() {
        return String.format("Telemetria[%d rpm, %.1f km/h, ECT %.1f, MAP %.1f, TPS %.1f, λ %.3f, %.2f V]",
                rpm, velocidadeKmh, ectC, mapKpa, tpsPct, lambda, bateriaV);
    }

    /** Montagem passo a passo (os campos não informados ficam em zero). */
    public static final class Construtor {
        private long tempoMs;
        private int rpm;
        private double velocidadeKmh;
        private double ectC;
        private double iatC;
        private double mapKpa;
        private double oleoBar;
        private double tpsPct;
        private double lambda;
        private double bateriaV;
        private double avancoGraus;
        private int pwUs;
        private int vePct;
        private int aePct;
        private int iacPct;
        private double posRpm;
        private double posCarga;
        private int flags;
        private int erros;

        public Construtor tempoMs(long v) {
            tempoMs = v;
            return this;
        }

        public Construtor rpm(int v) {
            rpm = v;
            return this;
        }

        public Construtor velocidade(double v) {
            velocidadeKmh = v;
            return this;
        }

        public Construtor ect(double v) {
            ectC = v;
            return this;
        }

        public Construtor iat(double v) {
            iatC = v;
            return this;
        }

        public Construtor map(double v) {
            mapKpa = v;
            return this;
        }

        public Construtor oleo(double v) {
            oleoBar = v;
            return this;
        }

        public Construtor tps(double v) {
            tpsPct = v;
            return this;
        }

        public Construtor lambda(double v) {
            lambda = v;
            return this;
        }

        public Construtor bateria(double v) {
            bateriaV = v;
            return this;
        }

        public Construtor avanco(double v) {
            avancoGraus = v;
            return this;
        }

        public Construtor pwUs(int v) {
            pwUs = v;
            return this;
        }

        public Construtor ve(int v) {
            vePct = v;
            return this;
        }

        public Construtor ae(int v) {
            aePct = v;
            return this;
        }

        public Construtor iac(int v) {
            iacPct = v;
            return this;
        }

        public Construtor posicao(double rpmIdx, double cargaIdx) {
            posRpm = rpmIdx;
            posCarga = cargaIdx;
            return this;
        }

        public Construtor flags(int v) {
            flags = v;
            return this;
        }

        public Construtor erros(int v) {
            erros = v;
            return this;
        }

        public Telemetria construir() {
            return new Telemetria(this);
        }
    }
}
