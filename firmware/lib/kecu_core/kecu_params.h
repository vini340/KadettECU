/*
 * kecu_params.h - parâmetros ajustáveis do motor.
 * A ORDEM dos campos é a ordem dos bytes no protocolo e tem de ser idêntica à do enum
 * kadettecu.modelo.CampoParametro (Java). O teste de integração confere campo por campo.
 */
#ifndef KECU_PARAMS_H
#define KECU_PARAMS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    P_REV_LIMITE_RPM = 0,
    P_REV_HISTERESE_RPM = 1,
    P_TIPO_CORTE = 2,
    P_HAB_TWO_STEP = 3,
    P_TWO_STEP_RPM = 4,
    P_HAB_LAUNCH = 5,
    P_LAUNCH_RPM = 6,
    P_LAUNCH_TPS_MIN = 7,
    P_LAUNCH_VEL_MAX = 8,
    P_LAUNCH_RETARDO = 9,
    P_HAB_AE = 10,
    P_AE_ENRIQUECIMENTO = 11,
    P_AE_LIMIAR = 12,
    P_AE_DURACAO_MS = 13,
    P_HAB_MARCHA_LENTA = 14,
    P_ML_RPM_QUENTE = 15,
    P_ML_RPM_FRIO = 16,
    P_ML_TPS_MAX = 17,
    P_ML_KP = 18,
    P_ML_KI = 19,
    P_ML_DUTY_BASE = 20,
    P_REQ_FUEL_US = 21,
    P_TEMPO_MORTO_US = 22,
    P_TEMPO_MORTO_COEF = 23,
    P_AFR_ESTEQ_X10 = 24,
    P_DWELL_US = 25,
    P_CILINDROS = 26,
    P_DENTES_RODA = 27,
    P_DENTES_FALTANDO = 28,
    P_MODO_SINAL = 29,
    P_PMS_OFFSET_X10 = 30,
    P_VSS_PULSOS_KM = 31,
    P_COUNT = 32
} kecu_param_id_t;

#define KECU_PARAMS_BYTES 47

typedef struct {
    uint16_t v[P_COUNT];
} kecu_params_t;

typedef struct {
    uint8_t bytes;         /* 1 ou 2 no protocolo */
    uint16_t min;
    uint16_t max;
    uint16_t padrao;
    uint8_t exige_parado;  /* só pode mudar com o motor parado */
} kecu_param_desc_t;

extern const kecu_param_desc_t kecu_param_tab[P_COUNT];

void kecu_params_padrao(kecu_params_t *p);

/* Converte o payload (47 bytes) em parâmetros. Devolve 0 se tudo está na faixa; senão 1 e NÃO altera destino. */
int kecu_params_desserializar(kecu_params_t *destino, const uint8_t *payload);

void kecu_params_serializar(const kecu_params_t *p, uint8_t *payload);

/* true se algum campo "exige_parado" difere entre a e b. */
int kecu_params_mudou_campo_de_sinal(const kecu_params_t *a, const kecu_params_t *b);

#ifdef __cplusplus
}
#endif

#endif
