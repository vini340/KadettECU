#include <math.h>

#include "kecu.h"

#define V_PIN_MIN KECU_ERRO_V_MIN_MV
#define V_PIN_MAX (KECU_VREF_MV - KECU_ERRO_V_MIN_MV)

static uint8_t s_iniciado;
static float s_beta;

static float filtro(float atual, float novo, float alfa) {
    return atual + alfa * (novo - atual);
}

/* NTC (ECT, IAT): a janela de falha vale para a tensão NO PINO, que é onde fica o divisor do próprio NTC. */
static int pino_valido(uint16_t mv) {
    return mv >= V_PIN_MIN && mv <= V_PIN_MAX;
}

/*
 * Sensores alimentados com 5 V (MAP, TPS, óleo, lambda): a janela de falha vale para a tensão NO
 * SENSOR (pino x razão do divisor). Se valesse no pino, um fio em curto para +5 V ainda cairia
 * "dentro da faixa" do ADC (5 V / 1,6 = 3,1 V) e seria lido como 100 % de acelerador, sem alarme.
 */
static int sensor5v_valido(uint16_t pino_mv) {
    const float v = (float)pino_mv * KECU_RAZAO_5V;
    return v >= (float)KECU_ERRO_V_MIN_MV && v <= (float)(5000 - KECU_ERRO_V_MIN_MV);
}

static float limitar(float v, float minimo, float maximo) {
    return v < minimo ? minimo : (v > maximo ? maximo : v);
}

/* Interpolação linear de dois pontos (tensão do sensor em mV -> grandeza). */
static float linear2(float v_mv, float v1, float y1, float v2, float y2) {
    return y1 + (v_mv - v1) * (y2 - y1) / (v2 - v1);
}

/* NTC pelo modelo beta, com beta calculado dos dois pontos medidos (config). */
static float ntc_celsius(uint16_t pino_mv) {
    const float v = (float)pino_mv;
    const float r = KECU_NTC_PULLUP_OHM * v / ((float)KECU_VREF_MV - v);
    const float t1 = KECU_NTC_T1_C + 273.15f;
    const float t2 = KECU_NTC_T2_C + 273.15f;
    if (s_beta == 0.0f) {
        s_beta = logf(KECU_NTC_R1_OHM / KECU_NTC_R2_OHM) / (1.0f / t1 - 1.0f / t2);
    }
    const float tk = 1.0f / (1.0f / t1 + logf(r / KECU_NTC_R1_OHM) / s_beta);
    return tk - 273.15f;
}

void kecu_sensores_init(void) {
    s_iniciado = 0;
    s_beta = 0.0f;
}

void kecu_sensores_ler(void) {
    kecu_t *k = &g_kecu;
    uint16_t erros = (uint16_t)(k->erros & (KECU_ERR_NVM | KECU_ERR_SEM_ROTACAO));

    const uint16_t p_ect = hal_adc_ler_mv(KECU_ADC_ECT);
    const uint16_t p_iat = hal_adc_ler_mv(KECU_ADC_IAT);
    const uint16_t p_map = hal_adc_ler_mv(KECU_ADC_MAP);
    const uint16_t p_tps = hal_adc_ler_mv(KECU_ADC_TPS);
    const uint16_t p_bat = hal_adc_ler_mv(KECU_ADC_VBAT);
    const uint16_t p_oleo = hal_adc_ler_mv(KECU_ADC_OLEO);
    const uint16_t p_lam = hal_adc_ler_mv(KECU_ADC_LAMBDA);

    float ect = KECU_ECT_EMERGENCIA_C;
    float iat = KECU_IAT_EMERGENCIA_C;
    float map = KECU_MAP_EMERGENCIA_KPA;
    float tps = k->tps_pct;
    float bat = 12.0f;
    float oleo = 0.0f;
    float lam = 1.0f;

    if (pino_valido(p_ect)) {
        ect = limitar(ntc_celsius(p_ect), -40.0f, 150.0f);
    } else {
        erros |= KECU_ERR_ECT;
    }
    if (pino_valido(p_iat)) {
        iat = limitar(ntc_celsius(p_iat), -40.0f, 150.0f);
    } else {
        erros |= KECU_ERR_IAT;
    }
    if (sensor5v_valido(p_map)) {
        const float v = (float)p_map * KECU_RAZAO_5V;
        map = limitar(linear2(v, KECU_MAP_V1_MV, KECU_MAP_KPA1, KECU_MAP_V2_MV, KECU_MAP_KPA2), 0.0f, 400.0f);
    } else {
        erros |= KECU_ERR_MAP;
        map = KECU_MAP_EMERGENCIA_KPA + 0.4f * k->tps_pct;
    }
    if (sensor5v_valido(p_tps)) {
        const float v = (float)p_tps * KECU_RAZAO_5V;
        tps = limitar(linear2(v, KECU_TPS_V_FECHADO_MV, 0.0f, KECU_TPS_V_ABERTO_MV, 100.0f), 0.0f, 100.0f);
    } else {
        erros |= KECU_ERR_TPS;
        tps = 0.0f;
    }
    bat = (float)p_bat * KECU_RAZAO_BATERIA / 1000.0f;
    if (bat < 6.0f || bat > 18.0f) {
        erros |= KECU_ERR_BATERIA;
        bat = limitar(bat, 6.0f, 18.0f);
    }
    if (sensor5v_valido(p_oleo)) {
        const float v = (float)p_oleo * KECU_RAZAO_5V;
        oleo = limitar(linear2(v, KECU_OLEO_V1_MV, KECU_OLEO_BAR1, KECU_OLEO_V2_MV, KECU_OLEO_BAR2), 0.0f, 20.0f);
    }
    if (sensor5v_valido(p_lam)) {
        const float v = (float)p_lam * KECU_RAZAO_5V;
        const float afr = linear2(v, KECU_LAMBDA_V1_MV, KECU_LAMBDA_AFR1, KECU_LAMBDA_V2_MV, KECU_LAMBDA_AFR2);
        lam = limitar(afr / KECU_LAMBDA_AFR_REF, 0.5f, 2.0f);
    } else {
        erros |= KECU_ERR_LAMBDA;
    }

    if (!s_iniciado) {
        /* primeira leitura: começa já no valor medido, sem "subir" desde zero */
        k->ect_c = ect;
        k->iat_c = iat;
        k->map_kpa = map;
        k->tps_pct = tps;
        k->vbat_v = bat;
        k->oleo_bar = oleo;
        k->lambda = lam;
        s_iniciado = 1;
    } else {
        k->ect_c = filtro(k->ect_c, ect, 0.01f);
        k->iat_c = filtro(k->iat_c, iat, 0.01f);
        k->map_kpa = filtro(k->map_kpa, map, 0.35f);
        k->tps_pct = filtro(k->tps_pct, tps, 0.5f);
        k->vbat_v = filtro(k->vbat_v, bat, 0.05f);
        k->oleo_bar = filtro(k->oleo_bar, oleo, 0.05f);
        k->lambda = filtro(k->lambda, lam, 0.2f);
    }
    k->erros = erros;
}
