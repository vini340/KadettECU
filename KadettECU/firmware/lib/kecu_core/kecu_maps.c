#include "kecu_maps.h"

static const int8_t FORMA_VE[KECU_N] = {0, 4, 8, 12, 16, 19, 22, 24, 25, 24, 22, 18, 12, 6, 0, -6};
static const int8_t FORMA_IGN[KECU_N] = {0, 2, 4, 6, 9, 11, 13, 15, 17, 18, 19, 20, 21, 21, 20, 20};

void kecu_eixos_padrao(kecu_eixos_t *e) {
    static const uint16_t rpm[KECU_N] = {500,  800,  1100, 1500, 2000, 2500, 3000, 3500,
                                         4000, 4500, 5000, 5500, 6000, 6500, 7000, 7500};
    for (int i = 0; i < KECU_N; i++) {
        e->rpm[i] = rpm[i];
        e->carga[i] = (uint16_t)(20 + 6 * i);
    }
}

/* Mesmas fórmulas de MapaECU.padrao (Java): tabelas de partida conservadoras. */
void kecu_mapas_padrao(kecu_mapas_t *m) {
    for (int linha = 0; linha < KECU_N; linha++) {
        for (int col = 0; col < KECU_N; col++) {
            int ve = 40 + (linha * 30) / 15 + FORMA_VE[col];
            if (ve < 20) {
                ve = 20;
            }
            if (ve > 110) {
                ve = 110;
            }
            m->m[MAPA_COMBUSTIVEL][linha][col] = (uint8_t)ve;
            m->m[MAPA_IGNICAO][linha][col] = (uint8_t)((14 + FORMA_IGN[col] - (linha * 8) / 15 + 10) * 2);
            m->m[MAPA_LAMBDA][linha][col] = (uint8_t)(80 - (linha >= 10 ? (linha - 9) * 4 : 0));
        }
    }
}

int kecu_eixos_validos(const kecu_eixos_t *e) {
    for (int i = 1; i < KECU_N; i++) {
        if (e->rpm[i] <= e->rpm[i - 1] || e->carga[i] <= e->carga[i - 1]) {
            return 0;
        }
    }
    return 1;
}

float kecu_eixo_pos(const uint16_t *eixo, float valor) {
    if (valor <= (float)eixo[0]) {
        return 0.0f;
    }
    if (valor >= (float)eixo[KECU_N - 1]) {
        return (float)(KECU_N - 1);
    }
    for (int i = 0; i < KECU_N - 1; i++) {
        if (valor < (float)eixo[i + 1]) {
            return (float)i + (valor - (float)eixo[i]) / (float)(eixo[i + 1] - eixo[i]);
        }
    }
    return (float)(KECU_N - 1);
}

float kecu_mapa_interp_raw(const kecu_mapas_t *m, kecu_mapa_id_t mapa, float pos_rpm, float pos_carga) {
    int c0 = (int)pos_rpm; /* pos >= 0: truncar = floor */
    int l0 = (int)pos_carga;
    if (c0 > KECU_N - 2) {
        c0 = KECU_N - 2;
    }
    if (l0 > KECU_N - 2) {
        l0 = KECU_N - 2;
    }
    const float fc = pos_rpm - (float)c0;
    const float fl = pos_carga - (float)l0;
    const uint8_t (*t)[KECU_N] = m->m[mapa];
    const float topo = (float)t[l0][c0] * (1.0f - fc) + (float)t[l0][c0 + 1] * fc;
    const float base = (float)t[l0 + 1][c0] * (1.0f - fc) + (float)t[l0 + 1][c0 + 1] * fc;
    return topo * (1.0f - fl) + base * fl;
}

float kecu_mapa_fisico(kecu_mapa_id_t mapa, float raw) {
    switch (mapa) {
    case MAPA_IGNICAO:
        return -10.0f + 0.5f * raw;
    case MAPA_LAMBDA:
        return 0.60f + 0.005f * raw;
    case MAPA_COMBUSTIVEL:
    default:
        return raw;
    }
}
