/*
 * kecu_store.c - grava e lê a calibração na memória não volátil, com cabeçalho e CRC-16.
 * Bloco: "KECU" | versão(1) | reservado(1) | tamanho(2) | params(47) | eixos(64) | mapas(768) | CRC16(2)
 */
#include <string.h>

#include "kecu.h"
#include "kecu_proto.h"

#define MAGIC0 'K'
#define MAGIC1 'E'
#define MAGIC2 'C'
#define MAGIC3 'U'
#define VERSAO_BLOCO 1
#define TAM_CARGA (KECU_PARAMS_BYTES + 4 * KECU_N + KECU_NUM_MAPAS * KECU_N * KECU_N)
#define TAM_BLOCO (8 + TAM_CARGA + 2)

#if KECU_TEM_NVM

static uint8_t s_bloco[TAM_BLOCO];
static uint8_t s_conferir[TAM_BLOCO];

static void montar_bloco(void) {
    s_bloco[0] = MAGIC0;
    s_bloco[1] = MAGIC1;
    s_bloco[2] = MAGIC2;
    s_bloco[3] = MAGIC3;
    s_bloco[4] = VERSAO_BLOCO;
    s_bloco[5] = 0;
    s_bloco[6] = (uint8_t)(TAM_CARGA & 0xFF);
    s_bloco[7] = (uint8_t)(TAM_CARGA >> 8);
    uint8_t *d = &s_bloco[8];
    kecu_params_serializar(&g_kecu.params, d);
    d += KECU_PARAMS_BYTES;
    for (int i = 0; i < KECU_N; i++) {
        *d++ = (uint8_t)(g_kecu.eixos.rpm[i] & 0xFF);
        *d++ = (uint8_t)(g_kecu.eixos.rpm[i] >> 8);
    }
    for (int i = 0; i < KECU_N; i++) {
        *d++ = (uint8_t)(g_kecu.eixos.carga[i] & 0xFF);
        *d++ = (uint8_t)(g_kecu.eixos.carga[i] >> 8);
    }
    memcpy(d, g_kecu.mapas.m, sizeof g_kecu.mapas.m);
    const uint16_t crc = kecu_crc16(&s_bloco[0], 8 + TAM_CARGA);
    s_bloco[8 + TAM_CARGA] = (uint8_t)(crc & 0xFF);
    s_bloco[9 + TAM_CARGA] = (uint8_t)(crc >> 8);
}

int kecu_store_gravar(void) {
    montar_bloco();
    if (hal_nvm_gravar(s_bloco, TAM_BLOCO) != 0) {
        return 1;
    }
    /* lê de volta e confere: só diz que gravou se a memória realmente guardou */
    if (hal_nvm_ler(s_conferir, TAM_BLOCO) != 0 || memcmp(s_bloco, s_conferir, TAM_BLOCO) != 0) {
        return 1;
    }
    return 0;
}

/* 0 = carregou; 1 = memória vazia (primeira vez); 2 = dados corrompidos (mantém os padrões) */
int kecu_store_carregar(void) {
    if (hal_nvm_ler(s_bloco, TAM_BLOCO) != 0) {
        return 1;
    }
    if (s_bloco[0] != MAGIC0 || s_bloco[1] != MAGIC1 || s_bloco[2] != MAGIC2 || s_bloco[3] != MAGIC3) {
        return 1;
    }
    const uint16_t tamanho = (uint16_t)(s_bloco[6] | (s_bloco[7] << 8));
    if (s_bloco[4] != VERSAO_BLOCO || tamanho != TAM_CARGA) {
        return 2;
    }
    const uint16_t crc = (uint16_t)(s_bloco[8 + TAM_CARGA] | (s_bloco[9 + TAM_CARGA] << 8));
    if (crc != kecu_crc16(&s_bloco[0], 8 + TAM_CARGA)) {
        return 2;
    }
    kecu_params_t params = {{0}};
    kecu_eixos_t eixos = {{0}, {0}};
    const uint8_t *d = &s_bloco[8];
    if (kecu_params_desserializar(&params, d) != 0) {
        return 2;
    }
    d += KECU_PARAMS_BYTES;
    for (int i = 0; i < KECU_N; i++) {
        eixos.rpm[i] = (uint16_t)(d[0] | (d[1] << 8));
        d += 2;
    }
    for (int i = 0; i < KECU_N; i++) {
        eixos.carga[i] = (uint16_t)(d[0] | (d[1] << 8));
        d += 2;
    }
    if (!kecu_eixos_validos(&eixos)) {
        return 2;
    }
    g_kecu.params = params;
    g_kecu.eixos = eixos;
    memcpy(g_kecu.mapas.m, d, sizeof g_kecu.mapas.m);
    return 0;
}

#else

int kecu_store_gravar(void) {
    return 1;
}

int kecu_store_carregar(void) {
    return 1;
}

#endif
