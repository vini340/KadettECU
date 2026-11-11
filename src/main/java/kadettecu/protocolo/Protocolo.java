package kadettecu.protocolo;

/**
 * Constantes do protocolo serial PC <-> ECU (versão 1).
 * A especificação completa está em docs/PROTOCOLO.md; o firmware usa os mesmos valores.
 */
public final class Protocolo {

    private Protocolo() {
    }

    public static final int VERSAO = 1;

    /** Quadro: SOF1 SOF2 LEN TIPO SEQ [PAYLOAD] CRC16(LE). O CRC cobre LEN, TIPO, SEQ e PAYLOAD. */
    public static final int SOF1 = 0xA5;
    public static final int SOF2 = 0x5A;
    public static final int TAMANHO_CABECALHO = 5;
    public static final int TAMANHO_CRC = 2;
    public static final int PAYLOAD_MAX = 240;

    public static final int TAMANHO_MAPA = 16;
    public static final int NUM_MAPAS = 3;

    // Comandos (PC -> ECU)
    public static final int CMD_HELLO = 0x01;
    public static final int CMD_STREAM = 0x02;
    public static final int CMD_LER_PARAMS = 0x10;
    public static final int CMD_ESCREVER_PARAMS = 0x11;
    public static final int CMD_LER_LINHA = 0x20;
    public static final int CMD_ESCREVER_LINHA = 0x21;
    public static final int CMD_LER_EIXOS = 0x22;
    public static final int CMD_ESCREVER_EIXOS = 0x23;
    public static final int CMD_GRAVAR = 0x30;
    public static final int CMD_PADROES = 0x31;
    public static final int CMD_ARMAR = 0x40;
    public static final int CMD_CHAVES = 0x41;

    // Respostas (ECU -> PC)
    public static final int RSP_ACK = 0x80;
    public static final int RSP_INFO = 0x81;
    public static final int RSP_NACK = 0x8F;
    public static final int RSP_PARAMS = 0x90;
    public static final int RSP_LINHA = 0xA0;
    public static final int RSP_EIXOS = 0xA1;
    public static final int TELEMETRIA = 0xF0;

    // Códigos do NACK
    public static final int ERRO_COMANDO_DESCONHECIDO = 1;
    public static final int ERRO_TAMANHO = 2;
    public static final int ERRO_FAIXA = 3;
    public static final int ERRO_MOTOR_GIRANDO = 4;
    public static final int ERRO_MEMORIA = 5;
    public static final int ERRO_SAIDAS_DESABILITADAS = 6;

    // Flags de estado (campo "flags" da telemetria)
    public static final int FLAG_SYNC = 1 << 0;
    public static final int FLAG_MOTOR_GIRANDO = 1 << 1;
    public static final int FLAG_CORTE = 1 << 2;
    public static final int FLAG_TWO_STEP = 1 << 3;
    public static final int FLAG_LAUNCH = 1 << 4;
    public static final int FLAG_AE = 1 << 5;
    public static final int FLAG_MARCHA_LENTA = 1 << 6;
    public static final int FLAG_ARMADO = 1 << 7;
    public static final int FLAG_CHAVE_TWO_STEP = 1 << 8;
    public static final int FLAG_CHAVE_LAUNCH = 1 << 9;
    public static final int FLAG_MODO_SOMBRA = 1 << 10;
    public static final int FLAG_SIMULADOR = 1 << 11;

    // Bits do campo "erros" da telemetria
    public static final int ERR_ECT = 1 << 0;
    public static final int ERR_IAT = 1 << 1;
    public static final int ERR_MAP = 1 << 2;
    public static final int ERR_TPS = 1 << 3;
    public static final int ERR_BATERIA = 1 << 4;
    public static final int ERR_SEM_ROTACAO = 1 << 5;
    public static final int ERR_NVM = 1 << 6;
    public static final int ERR_LAMBDA = 1 << 7;

    // Bits do campo "chaves" (CMD_CHAVES)
    public static final int CHAVE_TWO_STEP = 1;
    public static final int CHAVE_LAUNCH = 2;

    public static String descreverErro(int codigo) {
        switch (codigo) {
            case ERRO_COMANDO_DESCONHECIDO:
                return "comando desconhecido";
            case ERRO_TAMANHO:
                return "tamanho de payload inválido";
            case ERRO_FAIXA:
                return "valor fora da faixa permitida";
            case ERRO_MOTOR_GIRANDO:
                return "operação não permitida com o motor girando";
            case ERRO_MEMORIA:
                return "falha ao gravar na memória";
            case ERRO_SAIDAS_DESABILITADAS:
                return "firmware em modo sombra: saídas (bicos/bobina) desabilitadas na compilação";
            default:
                return "erro " + codigo;
        }
    }
}
