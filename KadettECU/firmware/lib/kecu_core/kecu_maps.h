/*
 * kecu_maps.h - eixos e tabelas 16x16 (combustível/VE, ignição, alvo de lambda) e interpolação.
 * Os valores guardados são bytes "raw" (0..255); a conversão para a grandeza física está em
 * kecu_mapa_fisico e é igual à de kadettecu.modelo.TipoMapa (Java).
 */
#ifndef KECU_MAPS_H
#define KECU_MAPS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KECU_N 16
#define KECU_NUM_MAPAS 3

typedef enum {
    MAPA_COMBUSTIVEL = 0, /* VE em %: fisico = raw */
    MAPA_IGNICAO = 1,     /* avanço em graus: fisico = -10 + 0,5 * raw */
    MAPA_LAMBDA = 2       /* alvo de lambda: fisico = 0,60 + 0,005 * raw */
} kecu_mapa_id_t;

typedef struct {
    uint16_t rpm[KECU_N];
    uint16_t carga[KECU_N]; /* kPa */
} kecu_eixos_t;

typedef struct {
    uint8_t m[KECU_NUM_MAPAS][KECU_N][KECU_N]; /* [mapa][linha = carga][coluna = rpm] */
} kecu_mapas_t;

void kecu_eixos_padrao(kecu_eixos_t *e);
void kecu_mapas_padrao(kecu_mapas_t *m);

/* 1 se os dois eixos são estritamente crescentes. */
int kecu_eixos_validos(const kecu_eixos_t *e);

/* Índice fracionário (0..15) de um valor no eixo, limitado às pontas. */
float kecu_eixo_pos(const uint16_t *eixo, float valor);

/* Interpolação bilinear em índices fracionários; devolve o valor raw (0..255, fracionário). */
float kecu_mapa_interp_raw(const kecu_mapas_t *m, kecu_mapa_id_t mapa, float pos_rpm, float pos_carga);

float kecu_mapa_fisico(kecu_mapa_id_t mapa, float raw);

#ifdef __cplusplus
}
#endif

#endif
