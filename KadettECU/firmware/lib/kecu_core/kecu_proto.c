/*
 * kecu_proto.c - comunicação com o PC: decodificador de quadros, comandos e telemetria.
 *
 * Tudo roda no laço principal (nunca na interrupção), então não há disputa por dados:
 *   - a entrada é lida aos poucos (kecu_proto_processar lê no máximo alguns blocos por volta);
 *   - a saída passa por uma fila circular e só sai o que a serial aceita, sem bloquear.
 * Assim a comunicação não atrasa o controle do motor, mesmo com o PC lento ou desconectado.
 */
#include <string.h>

#include "kecu.h"
#include "kecu_proto.h"

#define RX_BUF 256 /* cabe um quadro máximo (247 bytes) */
#define TX_BUF 512
#define QUADRO_LOCAL 80 /* maior resposta própria: 5 + 64 + 2 = 71 bytes */
#define LEITURA_BLOCO 64
#define LEITURAS_POR_VOLTA 4
#define TIMEOUT_QUADRO_US 100000u /* quadro pela metade por mais tempo que isso: o cabeçalho era ruído */

static uint8_t s_rx[RX_BUF];
static uint16_t s_rx_n;
static uint32_t s_ultimo_rx_us;

static uint8_t s_tx[TX_BUF];
static uint16_t s_tx_ini;
static uint16_t s_tx_n;

static uint16_t s_periodo_ms;
static uint32_t s_prox_tele_us;
static uint8_t s_seq_tele;
static kecu_proto_stats_t s_stats;

/* ------------------------------------------------------------------ CRC e montagem de quadros */

uint16_t kecu_crc16(const uint8_t *dados, uint16_t tamanho) {
    uint16_t crc = 0xFFFFu;
    for (uint16_t i = 0; i < tamanho; i++) {
        crc = (uint16_t)(crc ^ (uint16_t)((uint16_t)dados[i] << 8));
        for (int bit = 0; bit < 8; bit++) {
            crc = (uint16_t)((crc & 0x8000u) ? (uint16_t)((uint16_t)(crc << 1) ^ 0x1021u) : (uint16_t)(crc << 1));
        }
    }
    return crc;
}

uint16_t kecu_proto_montar(uint8_t *saida, uint8_t tipo, uint8_t seq, const uint8_t *payload, uint8_t n) {
    saida[0] = (uint8_t)KECU_SOF1;
    saida[1] = (uint8_t)KECU_SOF2;
    saida[2] = n;
    saida[3] = tipo;
    saida[4] = seq;
    if (n > 0) {
        memcpy(&saida[KECU_CABECALHO], payload, n);
    }
    const uint16_t crc = kecu_crc16(&saida[2], (uint16_t)(3u + n));
    saida[KECU_CABECALHO + n] = (uint8_t)(crc & 0xFFu);
    saida[KECU_CABECALHO + n + 1] = (uint8_t)(crc >> 8);
    return (uint16_t)(KECU_CABECALHO + n + KECU_TAM_CRC);
}

/* ------------------------------------------------------------------ fila de saída */

static int tx_enfileirar(const uint8_t *dados, uint16_t n) {
    if (n > (uint16_t)(TX_BUF - s_tx_n)) {
        return 0; /* tudo ou nada: nunca deixa meio quadro na fila */
    }
    for (uint16_t i = 0; i < n; i++) {
        s_tx[(uint16_t)((s_tx_ini + s_tx_n) % TX_BUF)] = dados[i];
        s_tx_n++;
    }
    return 1;
}

static void tx_drenar(void) {
    while (s_tx_n > 0) {
        const uint16_t ate_o_fim = (uint16_t)(TX_BUF - s_tx_ini);
        const uint16_t quer = s_tx_n < ate_o_fim ? s_tx_n : ate_o_fim;
        const int escreveu = hal_serial_escrever(&s_tx[s_tx_ini], (int)quer);
        if (escreveu <= 0) {
            break;
        }
        s_tx_ini = (uint16_t)((s_tx_ini + (uint16_t)escreveu) % TX_BUF);
        s_tx_n = (uint16_t)(s_tx_n - (uint16_t)escreveu);
        if ((uint16_t)escreveu < quer) {
            break; /* a serial está cheia: continua na próxima volta */
        }
    }
}

static void responder(uint8_t tipo, uint8_t seq, const uint8_t *payload, uint8_t n) {
    uint8_t quadro[QUADRO_LOCAL];
    const uint16_t total = kecu_proto_montar(quadro, tipo, seq, payload, n);
    if (!tx_enfileirar(quadro, total)) {
        s_stats.respostas_perdidas++;
    }
}

static void ack(uint8_t seq) {
    responder((uint8_t)KECU_RSP_ACK, seq, NULL, 0);
}

static void nack(uint8_t seq, uint8_t codigo) {
    responder((uint8_t)KECU_RSP_NACK, seq, &codigo, 1);
}

/* ------------------------------------------------------------------ telemetria */

static void w16(uint8_t *o, int *i, uint16_t v) {
    o[(*i)++] = (uint8_t)(v & 0xFFu);
    o[(*i)++] = (uint8_t)(v >> 8);
}

static uint16_t u16_de(float v) {
    if (!(v > 0.0f)) {
        return 0;
    }
    return v >= 65535.0f ? 65535u : (uint16_t)(v + 0.5f);
}

static uint16_t s16_de(float v) {
    int r;
    if (v >= 32767.0f) {
        r = 32767;
    } else if (v <= -32768.0f) {
        r = -32768;
    } else {
        r = (int)(v < 0.0f ? v - 0.5f : v + 0.5f);
    }
    return (uint16_t)(int16_t)r; /* complemento de dois: é assim que o protocolo carrega o valor com sinal */
}

static uint8_t u8_de(float v) {
    if (!(v > 0.0f)) {
        return 0;
    }
    return v >= 255.0f ? 255u : (uint8_t)(v + 0.5f);
}

void kecu_proto_telemetria(uint8_t *o) {
    const kecu_t *k = &g_kecu;
    int i = 0;
    const uint32_t t = hal_millis();
    w16(o, &i, (uint16_t)(t & 0xFFFFu));
    w16(o, &i, (uint16_t)(t >> 16));
    w16(o, &i, u16_de(k->rpm));
    w16(o, &i, u16_de(k->vel_kmh * 10.0f));
    w16(o, &i, s16_de(k->ect_c * 10.0f));
    w16(o, &i, s16_de(k->iat_c * 10.0f));
    w16(o, &i, u16_de(k->map_kpa * 10.0f));
    w16(o, &i, u16_de(k->oleo_bar * 10.0f));
    w16(o, &i, u16_de(k->tps_pct * 10.0f));
    w16(o, &i, u16_de(k->lambda * 1000.0f));
    w16(o, &i, u16_de(k->vbat_v * 1000.0f));
    w16(o, &i, s16_de(k->avanco_graus * 10.0f));
    w16(o, &i, k->pw_us);
    o[i++] = k->ve_pct;
    o[i++] = u8_de(k->ae_pct);
    o[i++] = u8_de(k->iac_pct);
    o[i++] = u8_de(k->pos_rpm * 16.0f);
    o[i++] = u8_de(k->pos_carga * 16.0f);
    o[i++] = 0; /* reservado */

    uint16_t flags = 0;
    if (k->sync) {
        flags |= KECU_FLAG_SYNC;
    }
    if (k->motor_girando) {
        flags |= KECU_FLAG_MOTOR_GIRANDO;
    }
    if (k->corte) {
        flags |= KECU_FLAG_CORTE;
    }
    if (k->two_step_ativo) {
        flags |= KECU_FLAG_TWO_STEP;
    }
    if (k->launch_ativo) {
        flags |= KECU_FLAG_LAUNCH;
    }
    if (k->ae_pct > 0.5f) {
        flags |= KECU_FLAG_AE;
    }
    if (k->marcha_lenta_ativa) {
        flags |= KECU_FLAG_MARCHA_LENTA;
    }
    if (k->armado) {
        flags |= KECU_FLAG_ARMADO;
    }
    if (k->chave_two_step) {
        flags |= KECU_FLAG_CHAVE_TWO_STEP;
    }
    if (k->chave_launch) {
        flags |= KECU_FLAG_CHAVE_LAUNCH;
    }
#if !KECU_ENABLE_OUTPUTS
    flags |= KECU_FLAG_MODO_SOMBRA;
#endif
#if KECU_PLACA == 0
    flags |= KECU_FLAG_SIMULADOR;
#endif
    w16(o, &i, flags);
    w16(o, &i, k->erros);
}

/* ------------------------------------------------------------------ comandos */

static void enviar_info(uint8_t seq) {
    uint8_t d[KECU_INFO_BYTES];
    uint16_t caps = 0;
#if KECU_ENABLE_OUTPUTS
    caps |= KECU_CAP_SAIDAS_REAIS;
#endif
#if KECU_TEM_NVM
    caps |= KECU_CAP_NVM;
#endif
    d[0] = (uint8_t)KECU_PROTOCOLO_VERSAO;
    d[1] = (uint8_t)KECU_FW_MAIOR;
    d[2] = (uint8_t)KECU_FW_MENOR;
    d[3] = (uint8_t)KECU_PLACA;
    d[4] = (uint8_t)KECU_N;
    d[5] = (uint8_t)KECU_NUM_MAPAS;
    d[6] = (uint8_t)(caps & 0xFFu);
    d[7] = (uint8_t)(caps >> 8);
    responder((uint8_t)KECU_RSP_INFO, seq, d, KECU_INFO_BYTES);
}

static void cmd_stream(uint8_t seq, const uint8_t *d, uint8_t n) {
    if (n != 2) {
        nack(seq, (uint8_t)KECU_NACK_TAMANHO);
        return;
    }
    const uint16_t ms = (uint16_t)(d[0] | (uint16_t)((uint16_t)d[1] << 8));
    if (ms != 0 && (ms < 5 || ms > 1000)) {
        nack(seq, (uint8_t)KECU_NACK_FAIXA);
        return;
    }
    s_periodo_ms = ms;
    s_prox_tele_us = hal_micros() + (uint32_t)ms * 1000u;
    ack(seq);
}

static void cmd_escrever_params(uint8_t seq, const uint8_t *d, uint8_t n) {
    if (n != KECU_PARAMS_BYTES) {
        nack(seq, (uint8_t)KECU_NACK_TAMANHO);
        return;
    }
    kecu_params_t novos;
    if (kecu_params_desserializar(&novos, d) != 0) {
        nack(seq, (uint8_t)KECU_NACK_FAIXA);
        return;
    }
    if (g_kecu.motor_girando && kecu_params_mudou_campo_de_sinal(&g_kecu.params, &novos)) {
        nack(seq, (uint8_t)KECU_NACK_MOTOR_GIRANDO);
        return;
    }
    g_kecu.params = novos;
    kecu_aplicar_parametros();
    ack(seq);
}

static void cmd_ler_linha(uint8_t seq, const uint8_t *d, uint8_t n) {
    if (n != 2) {
        nack(seq, (uint8_t)KECU_NACK_TAMANHO);
        return;
    }
    if (d[0] >= KECU_NUM_MAPAS || d[1] >= KECU_N) {
        nack(seq, (uint8_t)KECU_NACK_FAIXA);
        return;
    }
    uint8_t resp[2 + KECU_N];
    resp[0] = d[0];
    resp[1] = d[1];
    memcpy(&resp[2], g_kecu.mapas.m[d[0]][d[1]], KECU_N);
    responder((uint8_t)KECU_RSP_LINHA, seq, resp, (uint8_t)(2 + KECU_N));
}

static void cmd_escrever_linha(uint8_t seq, const uint8_t *d, uint8_t n) {
    if (n != 2 + KECU_N) {
        nack(seq, (uint8_t)KECU_NACK_TAMANHO);
        return;
    }
    if (d[0] >= KECU_NUM_MAPAS || d[1] >= KECU_N) {
        nack(seq, (uint8_t)KECU_NACK_FAIXA);
        return;
    }
    memcpy(g_kecu.mapas.m[d[0]][d[1]], &d[2], KECU_N);
    ack(seq);
}

static void cmd_ler_eixos(uint8_t seq) {
    uint8_t resp[4 * KECU_N];
    int i = 0;
    for (int c = 0; c < KECU_N; c++) {
        w16(resp, &i, g_kecu.eixos.rpm[c]);
    }
    for (int c = 0; c < KECU_N; c++) {
        w16(resp, &i, g_kecu.eixos.carga[c]);
    }
    responder((uint8_t)KECU_RSP_EIXOS, seq, resp, (uint8_t)(4 * KECU_N));
}

static void cmd_escrever_eixos(uint8_t seq, const uint8_t *d, uint8_t n) {
    if (n != 4 * KECU_N) {
        nack(seq, (uint8_t)KECU_NACK_TAMANHO);
        return;
    }
    kecu_eixos_t novos = {{0}, {0}};
    for (int c = 0; c < KECU_N; c++) {
        novos.rpm[c] = (uint16_t)(d[2 * c] | (uint16_t)((uint16_t)d[2 * c + 1] << 8));
        novos.carga[c] = (uint16_t)(d[2 * KECU_N + 2 * c] | (uint16_t)((uint16_t)d[2 * KECU_N + 2 * c + 1] << 8));
    }
    if (!kecu_eixos_validos(&novos)) {
        nack(seq, (uint8_t)KECU_NACK_FAIXA);
        return;
    }
    g_kecu.eixos = novos;
    ack(seq);
}

static void cmd_gravar(uint8_t seq) {
    if (g_kecu.motor_girando) {
        nack(seq, (uint8_t)KECU_NACK_MOTOR_GIRANDO);
        return;
    }
    if (kecu_store_gravar() != 0) {
        g_kecu.erros |= KECU_ERR_NVM;
        nack(seq, (uint8_t)KECU_NACK_MEMORIA);
        return;
    }
    g_kecu.erros = (uint16_t)(g_kecu.erros & ~KECU_ERR_NVM);
    ack(seq);
}

static void cmd_padroes(uint8_t seq) {
    if (g_kecu.motor_girando) {
        nack(seq, (uint8_t)KECU_NACK_MOTOR_GIRANDO);
        return;
    }
    kecu_params_padrao(&g_kecu.params);
    kecu_eixos_padrao(&g_kecu.eixos);
    kecu_mapas_padrao(&g_kecu.mapas);
    kecu_aplicar_parametros();
    ack(seq); /* só a RAM muda; para guardar, o PC manda GRAVAR */
}

static void cmd_armar(uint8_t seq, const uint8_t *d, uint8_t n) {
    if (n != 1) {
        nack(seq, (uint8_t)KECU_NACK_TAMANHO);
        return;
    }
    if (d[0] == 0) {
        /* Desarmar vale JÁ: primeiro o planejador da interrupção para de agendar (senão ele só ficaria
         * sabendo no próximo passo do controle, até 5 ms depois, e ainda pediria pulsos), depois a HAL
         * cancela o que já estava agendado e força as saídas para o nível desligado. */
        g_kecu.armado = 0;
        kecu_engine_set_armado(0);
#if KECU_ENABLE_OUTPUTS
        hal_desligar_saidas();
#endif
        ack(seq);
        return;
    }
#if KECU_ENABLE_OUTPUTS
    if (d[0] != 1) {
        nack(seq, (uint8_t)KECU_NACK_FAIXA);
        return;
    }
    g_kecu.armado = 1;
    ack(seq);
#else
    nack(seq, (uint8_t)KECU_NACK_SAIDAS_DESABILITADAS);
#endif
}

static void cmd_chaves(uint8_t seq, const uint8_t *d, uint8_t n) {
    if (n != 1) {
        nack(seq, (uint8_t)KECU_NACK_TAMANHO);
        return;
    }
    g_kecu.chaves_sw = (uint8_t)(d[0] & (KECU_CHAVE_TWO_STEP | KECU_CHAVE_LAUNCH));
    ack(seq);
}

static void tratar(uint8_t tipo, uint8_t seq, const uint8_t *d, uint8_t n) {
    switch (tipo) {
    case KECU_CMD_HELLO:
        enviar_info(seq);
        break;
    case KECU_CMD_STREAM:
        cmd_stream(seq, d, n);
        break;
    case KECU_CMD_LER_PARAMS: {
        uint8_t resp[KECU_PARAMS_BYTES];
        kecu_params_serializar(&g_kecu.params, resp);
        responder((uint8_t)KECU_RSP_PARAMS, seq, resp, KECU_PARAMS_BYTES);
        break;
    }
    case KECU_CMD_ESCREVER_PARAMS:
        cmd_escrever_params(seq, d, n);
        break;
    case KECU_CMD_LER_LINHA:
        cmd_ler_linha(seq, d, n);
        break;
    case KECU_CMD_ESCREVER_LINHA:
        cmd_escrever_linha(seq, d, n);
        break;
    case KECU_CMD_LER_EIXOS:
        cmd_ler_eixos(seq);
        break;
    case KECU_CMD_ESCREVER_EIXOS:
        cmd_escrever_eixos(seq, d, n);
        break;
    case KECU_CMD_GRAVAR:
        cmd_gravar(seq);
        break;
    case KECU_CMD_PADROES:
        cmd_padroes(seq);
        break;
    case KECU_CMD_ARMAR:
        cmd_armar(seq, d, n);
        break;
    case KECU_CMD_CHAVES:
        cmd_chaves(seq, d, n);
        break;
    default:
        nack(seq, (uint8_t)KECU_NACK_COMANDO);
        break;
    }
}

/* ------------------------------------------------------------------ decodificador (mesmo algoritmo do Java) */

static void rx_remover(uint16_t k) {
    if (k == 0) {
        return;
    }
    memmove(s_rx, &s_rx[k], (size_t)(s_rx_n - k));
    s_rx_n = (uint16_t)(s_rx_n - k);
}

static void rx_descartar(uint16_t k) {
    if (k > 0) {
        s_stats.bytes_descartados += k;
        rx_remover(k);
    }
}

static void rx_extrair(void) {
    for (;;) {
        uint16_t ini = 0;
        while ((uint16_t)(ini + 1u) < s_rx_n && !(s_rx[ini] == KECU_SOF1 && s_rx[ini + 1] == KECU_SOF2)) {
            ini++;
        }
        if ((uint16_t)(ini + 1u) >= s_rx_n) {
            /* sem SOF completo: guarda só um possível 1º byte do próximo SOF */
            const uint16_t manter = (uint16_t)((s_rx_n > 0 && s_rx[s_rx_n - 1] == KECU_SOF1) ? 1 : 0);
            rx_descartar((uint16_t)(s_rx_n - manter));
            return;
        }
        rx_descartar(ini);
        if (s_rx_n < KECU_CABECALHO) {
            return;
        }
        const uint8_t len = s_rx[2];
        if (len > KECU_PAYLOAD_MAX) {
            rx_descartar(1);
            continue;
        }
        const uint16_t total = (uint16_t)(KECU_CABECALHO + len + KECU_TAM_CRC);
        if (s_rx_n < total) {
            return;
        }
        const uint16_t crc_rx = (uint16_t)(s_rx[total - 2] | (uint16_t)((uint16_t)s_rx[total - 1] << 8));
        if (crc_rx == kecu_crc16(&s_rx[2], (uint16_t)(3u + len))) {
            s_stats.quadros_validos++;
            tratar(s_rx[3], s_rx[4], &s_rx[KECU_CABECALHO], len);
            rx_remover(total);
        } else {
            s_stats.erros_crc++;
            rx_descartar(1);
        }
    }
}

void kecu_proto_alimentar(const uint8_t *dados, int n) {
    s_ultimo_rx_us = hal_micros();
    for (int i = 0; i < n; i++) {
        if (s_rx_n == RX_BUF) {
            rx_descartar(1);
        }
        s_rx[s_rx_n++] = dados[i];
        rx_extrair();
    }
}

/* ------------------------------------------------------------------ ciclo */

void kecu_proto_init(void) {
    s_rx_n = 0;
    s_ultimo_rx_us = hal_micros();
    s_tx_ini = 0;
    s_tx_n = 0;
    s_periodo_ms = 0;
    s_prox_tele_us = hal_micros();
    s_seq_tele = 0;
    memset(&s_stats, 0, sizeof s_stats);
}

void kecu_proto_stats(kecu_proto_stats_t *destino) {
    *destino = s_stats;
}

uint16_t kecu_proto_periodo_ms(void) {
    return s_periodo_ms;
}

void kecu_proto_processar(uint32_t agora_us) {
    /* 1) entrada: poucos blocos por volta, para o controle nunca ficar esperando a serial */
    uint8_t bloco[LEITURA_BLOCO];
    for (int v = 0; v < LEITURAS_POR_VOLTA; v++) {
        const int lidos = hal_serial_ler(bloco, LEITURA_BLOCO);
        if (lidos <= 0) {
            break;
        }
        kecu_proto_alimentar(bloco, lidos);
        if (lidos < LEITURA_BLOCO) {
            break;
        }
    }

    /*
     * 1b) Ruído com cara de início de quadro ("A5 5A" + um tamanho qualquer) faria o decodificador esperar bytes
     * que nunca chegam, escondendo os quadros verdadeiros que vieram logo depois. Se o quadro fica pela metade
     * por mais de 100 ms (um quadro inteiro leva ~20 ms a 115200 baud), descarta o 1º byte e procura outro.
     */
    if (s_rx_n > 0 && (int32_t)(agora_us - s_ultimo_rx_us) > (int32_t)TIMEOUT_QUADRO_US) {
        s_ultimo_rx_us = agora_us;
        rx_descartar(1);
        rx_extrair();
    }

    /* 2) telemetria periódica, sem acumular rajadas se o laço atrasar */
    if (s_periodo_ms != 0 && (int32_t)(agora_us - s_prox_tele_us) >= 0) {
        const uint32_t periodo_us = (uint32_t)s_periodo_ms * 1000u;
        s_prox_tele_us += periodo_us;
        if ((int32_t)(agora_us - s_prox_tele_us) >= 0) {
            s_prox_tele_us = agora_us + periodo_us;
        }
        uint8_t payload[KECU_TELEMETRIA_BYTES];
        kecu_proto_telemetria(payload);
        uint8_t quadro[QUADRO_LOCAL];
        s_seq_tele = (uint8_t)(s_seq_tele + 1u);
        const uint16_t total = kecu_proto_montar(quadro, (uint8_t)KECU_RSP_TELEMETRIA, s_seq_tele, payload,
                                                 KECU_TELEMETRIA_BYTES);
        /* telemetria é descartável; já resposta de comando não (ela tem prioridade por ser enfileirada antes) */
        if (tx_enfileirar(quadro, total)) {
            s_stats.telemetrias_enviadas++;
        } else {
            s_stats.telemetrias_perdidas++;
        }
    }

    /* 3) saída */
    tx_drenar();
}
