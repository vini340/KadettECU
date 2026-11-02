package kadettecu.modelo;

import kadettecu.protocolo.BufferLE;

/** Identificação devolvida pela ECU em resposta ao HELLO. */
public final class InfoECU {

    public static final int TAMANHO = 8;
    public static final int CAP_SAIDAS_REAIS = 1;
    public static final int CAP_MEMORIA_NVM = 2;

    private final int versaoProtocolo;
    private final int firmwareMaior;
    private final int firmwareMenor;
    private final int placa;
    private final int tamanhoMapa;
    private final int numMapas;
    private final int capacidades;

    public InfoECU(int versaoProtocolo, int firmwareMaior, int firmwareMenor, int placa, int tamanhoMapa,
                   int numMapas, int capacidades) {
        this.versaoProtocolo = versaoProtocolo;
        this.firmwareMaior = firmwareMaior;
        this.firmwareMenor = firmwareMenor;
        this.placa = placa;
        this.tamanhoMapa = tamanhoMapa;
        this.numMapas = numMapas;
        this.capacidades = capacidades;
    }

    public static InfoECU deBytes(byte[] payload) {
        if (payload.length != TAMANHO) {
            throw new IllegalArgumentException("INFO deve ter " + TAMANHO + " bytes, veio " + payload.length);
        }
        BufferLE b = BufferLE.paraLeitura(payload);
        return new InfoECU(b.lerU8(), b.lerU8(), b.lerU8(), b.lerU8(), b.lerU8(), b.lerU8(), b.lerU16());
    }

    public byte[] paraBytes() {
        return BufferLE.paraEscrita(TAMANHO).escreverU8(versaoProtocolo).escreverU8(firmwareMaior)
                .escreverU8(firmwareMenor).escreverU8(placa).escreverU8(tamanhoMapa).escreverU8(numMapas)
                .escreverU16(capacidades).bytes();
    }

    public int getVersaoProtocolo() {
        return versaoProtocolo;
    }

    public String getFirmware() {
        return firmwareMaior + "." + firmwareMenor;
    }

    public int getTamanhoMapa() {
        return tamanhoMapa;
    }

    public int getNumMapas() {
        return numMapas;
    }

    /** true se o firmware foi compilado com as saídas (bicos/bobina) habilitadas; false = modo sombra. */
    public boolean temSaidasReais() {
        return (capacidades & CAP_SAIDAS_REAIS) != 0;
    }

    public String getNomePlaca() {
        switch (placa) {
            case 0:
                return "Simulador / PC";
            case 1:
                return "ESP32";
            case 2:
                return "STM32";
            case 3:
                return "Arduino (AVR)";
            default:
                return "Placa " + placa;
        }
    }

    @Override
    public String toString() {
        return getNomePlaca() + " fw " + getFirmware() + " (protocolo " + versaoProtocolo + ")";
    }
}
