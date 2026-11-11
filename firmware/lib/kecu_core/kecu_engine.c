#include "kecu_engine.h"
#include "kecu_config.h"
#include "kecu_hal.h"

#define VOLTA_X10 3600 /* uma volta em décimos de grau */
#define BIT_INJECAO 7  /* bit do mapa "já agendado nesta volta" usado pela injeção */

static kecu_engine_cfg_t s_cfg;
static kecu_plano_t s_plano;

/* Escritos só pela ISR (e lidos pelo laço principal dentro de seção crítica). */
static volatile uint32_t s_ultimo_us;
static volatile uint32_t s_slot_us;
static volatile uint32_t s_slot_x16;
static volatile uint32_t s_dt_anterior;
static volatile uint32_t s_voltas;
static volatile uint32_t s_eventos_ign;
static volatile uint32_t s_eventos_inj;
static volatile uint32_t s_vss_ultimo_us;
static volatile uint32_t s_vss_periodo_us;
static volatile uint16_t s_idx;
static volatile uint16_t s_perdas_sync;
static volatile uint8_t s_sync;
static volatile uint8_t s_tem_dente;
static volatile uint8_t s_tem_referencia;
static volatile uint8_t s_tem_vss;
static volatile uint8_t s_agendado;

void kecu_engine_init(void) {
    s_cfg.modo_uniforme = 0;
    s_cfg.dentes = 60;
    s_cfg.faltando = 2;
    s_cfg.cilindros = 4;
    s_cfg.pms_offset_x10 = 0;
    s_cfg.vss_pulsos_km = 4000;
    s_plano.ativo = 0;
    s_plano.armado = 0;
    s_ultimo_us = 0;
    s_slot_us = 0;
    s_slot_x16 = 0;
    s_dt_anterior = 0;
    s_voltas = 0;
    s_eventos_ign = 0;
    s_eventos_inj = 0;
    s_vss_ultimo_us = 0;
    s_vss_periodo_us = 0;
    s_idx = 0;
    s_perdas_sync = 0;
    s_sync = 0;
    s_tem_dente = 0;
    s_tem_referencia = 0;
    s_tem_vss = 0;
    s_agendado = 0;
}

void kecu_engine_config(const kecu_engine_cfg_t *cfg) {
    hal_secao_entrar();
    s_cfg = *cfg;
    /* mudar o formato da roda invalida o que já foi medido */
    s_sync = 0;
    s_tem_referencia = 0;
    s_idx = 0;
    s_dt_anterior = 0;
    hal_secao_sair();
}

void kecu_engine_set_plano(const kecu_plano_t *plano) {
    hal_secao_entrar();
    s_plano = *plano;
    hal_secao_sair();
}

void kecu_engine_set_armado(uint8_t armado) {
    hal_secao_entrar();
    s_plano.armado = armado;
    hal_secao_sair();
}

void kecu_engine_ler(kecu_motor_snapshot_t *snap) {
    hal_secao_entrar();
    snap->ultimo_us = s_ultimo_us;
    snap->slot_us = s_slot_us;
    snap->slot_x16 = s_slot_x16;
    snap->vss_ultimo_us = s_vss_ultimo_us;
    snap->vss_periodo_us = s_tem_vss ? s_vss_periodo_us : 0;
    snap->voltas = s_voltas;
    snap->eventos_ign = s_eventos_ign;
    snap->eventos_inj = s_eventos_inj;
    snap->idx = s_idx;
    snap->perdas_sync = s_perdas_sync;
    snap->sync = s_sync;
    snap->tem_dente = s_tem_dente;
    hal_secao_sair();
}

static uint32_t filtrar(uint32_t atual, uint32_t novo) {
    return atual == 0 ? novo : (atual * 3u + novo) / 4u;
}

/*
 * Período do dente: média móvel exponencial (peso 1/4 no dente novo) em ponto fixo com 4 bits
 * fracionários (1/16 us). Em microssegundos inteiros o truncamento da divisão enviesaria o período
 * em até 0,75 us (cerca de 0,5 % de rotação a 6000 rpm, o suficiente para deslocar a histerese do
 * limitador); em 1/16 us o erro cai para cerca de 0,04 %. s_slot_us (inteiro) é o que o planejador usa.
 */
static void atualizar_slot(uint32_t novo_x16) {
    s_slot_x16 = s_slot_x16 == 0 ? novo_x16 : (s_slot_x16 * 3u + novo_x16 + 2u) / 4u;
    s_slot_us = (s_slot_x16 + 8u) >> 4;
}

static int32_t volta(int32_t ang_x10) {
    ang_x10 %= VOLTA_X10;
    return ang_x10 < 0 ? ang_x10 + VOLTA_X10 : ang_x10;
}

#if KECU_ENABLE_OUTPUTS
static void agendar(kecu_saida_t saida, int32_t d_x10, int32_t slot_x10, uint32_t slot_us, uint32_t largura_us) {
    const uint32_t atraso = (uint32_t)d_x10 * slot_us / (uint32_t)slot_x10;
    hal_agendar_pulso(saida, atraso, largura_us);
}
#endif

/*
 * Planejador: chamado a cada dente, DEPOIS de atualizar a posição. Para cada evento (início
 * da carga da bobina, injeção) calcula em que ângulo da volta ele deve ocorrer; se esse ângulo
 * cai entre este dente e o próximo, converte o ângulo restante em tempo (usando a duração do
 * último dente) e pede à HAL para disparar. Cada evento é agendado uma vez por volta.
 */
static void planejar(uint16_t idx, uint32_t slot_us) {
    if (!s_plano.ativo || !s_sync || slot_us == 0 || s_cfg.modo_uniforme || s_cfg.cilindros != 4) {
        return;
    }
    const uint16_t dentes = s_cfg.dentes;
    const uint16_t ultimo_real = (uint16_t)(dentes - s_cfg.faltando - 1u);
    const int32_t ang_dente = ((int32_t)idx * VOLTA_X10) / dentes;
    const int32_t ang_prox = idx >= ultimo_real ? VOLTA_X10 : (((int32_t)idx + 1) * VOLTA_X10) / dentes;
    const int32_t intervalo = ang_prox - ang_dente; /* o último dente "enxerga" também a falha */
    const int32_t slot_x10 = VOLTA_X10 / dentes;
    const uint32_t rev_us = slot_us * dentes;
    if (rev_us > 1000000u) {
        return; /* abaixo de 60 rpm não há o que planejar */
    }

    if (idx == 0) {
        s_agendado = 0;
    }

    /* ---- ignição: 2 eventos por volta (180 graus de virabrequim entre eles num 4 cilindros) */
    if (!s_plano.corte_ign) {
        const int32_t espaco_x10 = 7200 / s_cfg.cilindros;
        uint32_t dwell_us = s_plano.dwell_us;
        const uint32_t max_dwell_us = (rev_us * (uint32_t)espaco_x10 / VOLTA_X10) * 85u / 100u;
        if (dwell_us > max_dwell_us) {
            dwell_us = max_dwell_us;
        }
        const int32_t dwell_ang = (int32_t)(dwell_us * (uint32_t)VOLTA_X10 / rev_us);
        for (uint8_t k = 0; k < s_cfg.cilindros / 2u; k++) {
            if (s_agendado & (uint8_t)(1u << k)) {
                continue;
            }
            const int32_t tdc = volta((int32_t)s_cfg.pms_offset_x10 + (int32_t)k * espaco_x10);
            const int32_t ang_fagulha = volta(tdc - (int32_t)s_plano.avanco_x10);
            const int32_t ang_inicio = volta(ang_fagulha - dwell_ang);
            const int32_t d = volta(ang_inicio - ang_dente);
            if (d < intervalo) {
                s_agendado |= (uint8_t)(1u << k);
                s_eventos_ign++;
#if KECU_ENABLE_OUTPUTS
                if (s_plano.armado) {
                    agendar((kecu_saida_t)(KECU_SAIDA_BOBINA1 + (k % KECU_BOBINAS)), d, slot_x10, slot_us, dwell_us);
                }
#endif
            }
        }
    }

    /* ---- injeção simultânea (todos os bicos juntos), uma vez por volta, começando no dente de referência */
    if (!s_plano.corte_comb && s_plano.inj_us > 0 && !(s_agendado & (uint8_t)(1u << BIT_INJECAO))) {
        const int32_t d = volta(0 - ang_dente);
        if (d < intervalo) {
            s_agendado |= (uint8_t)(1u << BIT_INJECAO);
            s_eventos_inj++;
#if KECU_ENABLE_OUTPUTS
            if (s_plano.armado) {
                for (int i = 0; i < 4; i++) {
                    agendar((kecu_saida_t)(KECU_SAIDA_INJ1 + i), d, slot_x10, slot_us, s_plano.inj_us);
                }
            }
#endif
        }
    }
#if !KECU_ENABLE_OUTPUTS
    (void)slot_x10;
#endif
}

void kecu_engine_on_tooth(uint32_t t_us) {
    const uint32_t dt = t_us - s_ultimo_us;

    if (!s_tem_dente || dt > KECU_TIMEOUT_MOTOR_US) {
        /* primeiro dente, ou o motor ficou parado: recomeça do zero */
        s_tem_dente = 1;
        s_ultimo_us = t_us;
        s_dt_anterior = 0;
        s_slot_us = 0;
        s_slot_x16 = 0;
        s_sync = 0;
        s_idx = 0;
        s_tem_referencia = 0;
        return;
    }
    s_ultimo_us = t_us;

    if (s_cfg.modo_uniforme) {
        atualizar_slot(dt << 4);
        s_idx = (uint16_t)((s_idx + 1u) % s_cfg.dentes);
        if (s_idx == 0) {
            s_voltas++;
        }
        s_sync = s_dt_anterior != 0;
        s_dt_anterior = dt;
        return;
    }

    const uint32_t faltando = s_cfg.faltando;
    if (s_cfg.dentes <= 2u * faltando) {
        return; /* configuração impossível: não decodifica */
    }
    uint8_t falha = 0;
    if (faltando > 0 && s_dt_anterior != 0 && dt * 4u > s_dt_anterior * (faltando + 1u) * 3u) {
        falha = 1;
    }

    if (falha) {
        const uint16_t esperado = (uint16_t)(s_cfg.dentes - faltando - 1u);
        if (s_tem_referencia) {
            if (s_idx == esperado) {
                s_sync = 1;
            } else {
                if (s_sync) {
                    s_perdas_sync++;
                }
                s_sync = 0;
            }
        }
        s_tem_referencia = 1;
        s_idx = 0;
        s_voltas++;
        atualizar_slot(((dt << 4) + (faltando + 1u) / 2u) / (faltando + 1u));
    } else {
        if (s_tem_referencia) {
            if (s_idx < s_cfg.dentes) {
                s_idx++;
            }
            if (s_idx > s_cfg.dentes - faltando - 1u) { /* mais dentes que o esperado: ruído */
                if (s_sync) {
                    s_perdas_sync++;
                }
                s_sync = 0;
            }
        }
        atualizar_slot(dt << 4);
    }
    s_dt_anterior = dt;

    if (s_tem_referencia) {
        planejar(s_idx, s_slot_us);
    }
}

void kecu_engine_on_vss(uint32_t t_us) {
    const uint32_t dt = t_us - s_vss_ultimo_us;
    if (s_tem_vss && dt < KECU_TIMEOUT_VSS_US) {
        s_vss_periodo_us = filtrar(s_vss_periodo_us, dt);
    } else {
        s_vss_periodo_us = 0;
    }
    s_vss_ultimo_us = t_us;
    s_tem_vss = 1;
}
