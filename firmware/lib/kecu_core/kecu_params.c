#include "kecu_params.h"

/* {bytes, min, max, padrão, exige_parado} - gerada a partir de CampoParametro.java */
const kecu_param_desc_t kecu_param_tab[P_COUNT] = {
    {2, 1500, 12000, 6500, 0}, /* REV_LIMITE_RPM */
    {2, 0, 2000, 150, 0},      /* REV_HISTERESE_RPM */
    {1, 0, 2, 2, 0},           /* TIPO_CORTE: 0 combustível, 1 ignição, 2 os dois */
    {1, 0, 1, 0, 0},           /* HAB_TWO_STEP */
    {2, 1000, 12000, 4500, 0}, /* TWO_STEP_RPM */
    {1, 0, 1, 0, 0},           /* HAB_LAUNCH */
    {2, 1000, 12000, 3500, 0}, /* LAUNCH_RPM */
    {1, 0, 100, 90, 0},        /* LAUNCH_TPS_MIN */
    {1, 0, 100, 10, 0},        /* LAUNCH_VEL_MAX */
    {1, 0, 30, 10, 0},         /* LAUNCH_RETARDO */
    {1, 0, 1, 1, 0},           /* HAB_AE */
    {1, 0, 200, 30, 0},        /* AE_ENRIQUECIMENTO */
    {2, 0, 2000, 150, 0},      /* AE_LIMIAR */
    {2, 0, 2000, 400, 0},      /* AE_DURACAO_MS */
    {1, 0, 1, 1, 0},           /* HAB_MARCHA_LENTA */
    {2, 400, 2000, 850, 0},    /* ML_RPM_QUENTE */
    {2, 400, 2500, 1300, 0},   /* ML_RPM_FRIO */
    {1, 0, 20, 4, 0},          /* ML_TPS_MAX */
    {1, 0, 100, 5, 0},         /* ML_KP (centésimos) */
    {1, 0, 100, 2, 0},         /* ML_KI (centésimos) */
    {1, 0, 100, 35, 0},        /* ML_DUTY_BASE */
    {2, 500, 30000, 8500, 0},  /* REQ_FUEL_US */
    {2, 0, 5000, 900, 0},      /* TEMPO_MORTO_US */
    {2, 0, 1000, 150, 0},      /* TEMPO_MORTO_COEF */
    {2, 80, 180, 130, 0},      /* AFR_ESTEQ_X10 */
    {2, 500, 8000, 3000, 0},   /* DWELL_US */
    {1, 1, 8, 4, 1},           /* CILINDROS */
    {1, 2, 120, 60, 1},        /* DENTES_RODA */
    {1, 0, 4, 2, 1},           /* DENTES_FALTANDO */
    {1, 0, 1, 0, 1},           /* MODO_SINAL: 0 roda fônica, 1 pulsos uniformes */
    {2, 0, 3600, 0, 1},        /* PMS_OFFSET_X10 */
    {2, 100, 20000, 4000, 0},  /* VSS_PULSOS_KM */
};

void kecu_params_padrao(kecu_params_t *p) {
    for (int i = 0; i < P_COUNT; i++) {
        p->v[i] = kecu_param_tab[i].padrao;
    }
}

int kecu_params_desserializar(kecu_params_t *destino, const uint8_t *payload) {
    kecu_params_t novo = {{0}}; /* só é copiado para o destino se TODOS os campos passarem na validação */
    int pos = 0;
    for (int i = 0; i < P_COUNT; i++) {
        uint16_t valor = payload[pos++];
        if (kecu_param_tab[i].bytes == 2) {
            valor = (uint16_t)(valor | (uint16_t)((uint16_t)payload[pos++] << 8));
        }
        if (valor < kecu_param_tab[i].min || valor > kecu_param_tab[i].max) {
            return 1;
        }
        novo.v[i] = valor;
    }
    *destino = novo;
    return 0;
}

void kecu_params_serializar(const kecu_params_t *p, uint8_t *payload) {
    int pos = 0;
    for (int i = 0; i < P_COUNT; i++) {
        payload[pos++] = (uint8_t)(p->v[i] & 0xFFu);
        if (kecu_param_tab[i].bytes == 2) {
            payload[pos++] = (uint8_t)(p->v[i] >> 8);
        }
    }
}

int kecu_params_mudou_campo_de_sinal(const kecu_params_t *a, const kecu_params_t *b) {
    for (int i = 0; i < P_COUNT; i++) {
        if (kecu_param_tab[i].exige_parado && a->v[i] != b->v[i]) {
            return 1;
        }
    }
    return 0;
}
