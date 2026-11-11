/*
 * kecu_engine.h - leitura do sinal de rotação (roda fônica 60-2 ou pulsos uniformes), do sensor
 * de velocidade e o planejador de eventos de ignição/injeção.
 *
 * As funções kecu_engine_on_tooth e kecu_engine_on_vss são chamadas da INTERRUPÇÃO do pino do
 * sensor (borda de subida/descida) com o instante em microssegundos. Dentro delas só há
 * aritmética inteira, para rodar rápido até em AVR sem unidade de ponto flutuante.
 */
#ifndef KECU_ENGINE_H
#define KECU_ENGINE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef KECU_BOBINAS
#define KECU_BOBINAS 1 /* 1 = uma bobina + distribuidor; 2 = duas bobinas (centelha perdida) */
#endif

typedef struct {
    uint8_t modo_uniforme; /* 1 = pulsos uniformes (só mede rotação) */
    uint8_t dentes;        /* dentes da roda (ou pulsos por volta) */
    uint8_t faltando;      /* dentes que faltam na roda */
    uint8_t cilindros;
    uint16_t pms_offset_x10; /* décimos de grau do 1º dente após a falha até o PMS */
    uint16_t vss_pulsos_km;
} kecu_engine_cfg_t;

typedef struct {
    int16_t avanco_x10;  /* décimos de grau antes do PMS (pode ser negativo) */
    uint16_t dwell_us;
    uint16_t inj_us;     /* largura de CADA injeção (já com tempo morto) */
    uint8_t corte_comb;
    uint8_t corte_ign;
    uint8_t ativo;       /* 0 = planejador parado */
    uint8_t armado;      /* só aciona as saídas se 1 (e se KECU_ENABLE_OUTPUTS == 1) */
} kecu_plano_t;

typedef struct {
    uint32_t ultimo_us;
    uint32_t slot_us;  /* período de um dente (filtrado), em microssegundos inteiros */
    uint32_t slot_x16; /* o mesmo período em 1/16 us: usado para calcular a rotação sem viés de arredondamento */
    uint32_t vss_ultimo_us;
    uint32_t vss_periodo_us;
    uint32_t voltas;
    uint32_t eventos_ign; /* eventos de ignição planejados (contam mesmo em modo sombra) */
    uint32_t eventos_inj;
    uint16_t idx;
    uint16_t perdas_sync;
    uint8_t sync;
    uint8_t tem_dente;
} kecu_motor_snapshot_t;

void kecu_engine_init(void);
void kecu_engine_config(const kecu_engine_cfg_t *cfg);
void kecu_engine_set_plano(const kecu_plano_t *plano);
void kecu_engine_set_armado(uint8_t armado); /* efeito imediato (usado para DESARMAR sem esperar o próximo passo do controle) */
void kecu_engine_ler(kecu_motor_snapshot_t *snap);

void kecu_engine_on_tooth(uint32_t t_us);
void kecu_engine_on_vss(uint32_t t_us);

#ifdef __cplusplus
}
#endif

#endif
