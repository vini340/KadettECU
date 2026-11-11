/*
 * kecu.h - estado global e funções principais do núcleo do firmware KadettECU.
 *
 * Uso no programa da placa:
 *   setup():  kecu_init();   + ligar as interrupções dos sensores a kecu_engine_on_tooth / _on_vss
 *   loop():   kecu_loop();   (chamar o mais rápido possível, sem delay())
 */
#ifndef KECU_H
#define KECU_H

#include <stdint.h>

#include "kecu_config.h"
#include "kecu_engine.h"
#include "kecu_hal.h"
#include "kecu_maps.h"
#include "kecu_params.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Bits do campo "erros" da telemetria (iguais a Protocolo.ERR_* no Java). */
#define KECU_ERR_ECT 0x0001u
#define KECU_ERR_IAT 0x0002u
#define KECU_ERR_MAP 0x0004u
#define KECU_ERR_TPS 0x0008u
#define KECU_ERR_BATERIA 0x0010u
#define KECU_ERR_SEM_ROTACAO 0x0020u
#define KECU_ERR_NVM 0x0040u
#define KECU_ERR_LAMBDA 0x0080u

/* Bits do campo "chaves" (CMD_CHAVES). */
#define KECU_CHAVE_TWO_STEP 0x01u
#define KECU_CHAVE_LAUNCH 0x02u

typedef struct {
    /* calibração que está na RAM (a que o motor usa) */
    kecu_params_t params;
    kecu_eixos_t eixos;
    kecu_mapas_t mapas;

    /* sensores, já filtrados e em unidades físicas */
    float ect_c;
    float iat_c;
    float map_kpa;
    float tps_pct;
    float vbat_v;
    float oleo_bar;
    float lambda;
    uint16_t erros;

    /* motor */
    float rpm;
    float vel_kmh;
    uint8_t sync;
    uint8_t motor_girando;

    /* chaves */
    uint8_t chaves_sw;      /* enviadas pelo PC (bancada) */
    uint8_t chave_two_step; /* efetiva: hardware OU software */
    uint8_t chave_launch;

    /* decisões de controle */
    uint8_t corte;
    uint8_t two_step_ativo;
    uint8_t launch_ativo;
    uint8_t marcha_lenta_ativa;
    uint8_t armado;

    /* grandezas calculadas */
    float ae_pct;
    float iac_pct;
    float avanco_graus;
    float pos_rpm;
    float pos_carga;
    uint16_t pw_us; /* pulso por ciclo: combustível + tempo morto */
    uint8_t ve_pct;

    /* diagnóstico */
    uint32_t ciclos_controle;
    uint32_t eventos_ignicao;
    uint32_t eventos_injecao;
    uint16_t perdas_sync;
} kecu_t;

extern kecu_t g_kecu;

void kecu_init(void);
void kecu_loop(void);

/* Reaplica os parâmetros do g_kecu.params nas partes que os copiam (decodificador de rotação). */
void kecu_aplicar_parametros(void);

/* Módulos internos (um arquivo cada). */
void kecu_sensores_init(void);
void kecu_sensores_ler(void);
void kecu_control_init(void);
void kecu_control_passo(uint32_t agora_us);
void kecu_proto_init(void);
void kecu_proto_processar(uint32_t agora_us);
int kecu_store_gravar(void);
int kecu_store_carregar(void);

#ifdef __cplusplus
}
#endif

#endif
