/*
 * kecu_control.c - a "inteligência": rotação e velocidade, limitador (com histerese), two-step,
 * launch control, enriquecimento de aceleração, marcha lenta e cálculo de pulso e avanço.
 * Roda no laço principal a cada KECU_PERIODO_CONTROLE_US (5 ms) com ponto flutuante.
 */
#include "kecu.h"

static uint32_t s_ultimo_us;
static uint8_t s_tem_ultimo;
static float s_tps_anterior;
static uint8_t s_ae_ativo;
static uint32_t s_ae_fim_ms;
static float s_integral_ml;
static uint16_t s_perdas_anteriores;
static uint32_t s_sem_sinal_ms;

void kecu_control_init(void) {
    s_tem_ultimo = 0;
    s_tps_anterior = 0.0f;
    s_ae_ativo = 0;
    s_integral_ml = 0.0f;
    s_perdas_anteriores = 0;
    s_sem_sinal_ms = 0;
}

static float limitar(float v, float minimo, float maximo) {
    return v < minimo ? minimo : (v > maximo ? maximo : v);
}

static int arredondar(float v) {
    return (int)(v < 0.0f ? v - 0.5f : v + 0.5f);
}

static float alvo_marcha_lenta(const kecu_params_t *p, float ect) {
    if (!p->v[P_HAB_MARCHA_LENTA]) {
        return 750.0f;
    }
    const float f = limitar((ect - 20.0f) / 60.0f, 0.0f, 1.0f);
    const float frio = (float)p->v[P_ML_RPM_FRIO];
    const float quente = (float)p->v[P_ML_RPM_QUENTE];
    return frio + (quente - frio) * f;
}

void kecu_control_passo(uint32_t agora_us) {
    kecu_t *k = &g_kecu;
    const kecu_params_t *p = &k->params;

    float dt = 0.005f;
    if (s_tem_ultimo) {
        const int32_t d = (int32_t)(agora_us - s_ultimo_us);
        if (d > 0 && d < 100000) {
            dt = (float)d * 1e-6f;
        }
    }
    s_ultimo_us = agora_us;
    s_tem_ultimo = 1;
    /* hal_millis() (e não agora_us/1000): este dá a volta só a cada ~49 dias; o de microssegundos, a cada 71 min. */
    const uint32_t agora_ms = hal_millis();

    /* ---------------------------------------------------------------- rotação e velocidade */
    kecu_motor_snapshot_t m;
    kecu_engine_ler(&m);
    int32_t sem_dente = (int32_t)(agora_us - m.ultimo_us); /* a ISR pode ser mais nova que agora_us: nunca negativo */
    if (sem_dente < 0) {
        sem_dente = 0;
    }
    float rpm = 0.0f;
    if (m.tem_dente && m.slot_x16 != 0 && sem_dente <= KECU_TIMEOUT_MOTOR_US) {
        rpm = 960e6f / ((float)m.slot_x16 * (float)p->v[P_DENTES_RODA]); /* 60e6 us/min * 16 (o período vem em 1/16 us) */
    }
    k->rpm = rpm;
    k->sync = (uint8_t)(m.sync && rpm > 0.0f); /* parado não há sincronia, mesmo que o decodificador ainda guarde o último estado */
    k->motor_girando = rpm > 0.0f;
    float vel = 0.0f;
    if (m.vss_periodo_us != 0) {
        int32_t sem_vss = (int32_t)(agora_us - m.vss_ultimo_us);
        if (sem_vss < 0) {
            sem_vss = 0;
        }
        if (sem_vss <= KECU_TIMEOUT_VSS_US) {
            vel = 3.6e9f / ((float)p->v[P_VSS_PULSOS_KM] * (float)m.vss_periodo_us);
        }
    }
    k->vel_kmh = vel;
    k->perdas_sync = m.perdas_sync;
    k->eventos_ignicao = m.eventos_ign;
    k->eventos_injecao = m.eventos_inj;

    /* falha de sinal da roda: perdeu a sincronia com o motor girando nos últimos 2 s */
    if (m.perdas_sync != s_perdas_anteriores) {
        s_perdas_anteriores = m.perdas_sync;
        s_sem_sinal_ms = agora_ms + 2000u;
    }
    if ((int32_t)(s_sem_sinal_ms - agora_ms) > 0 && s_sem_sinal_ms != 0) {
        k->erros |= KECU_ERR_SEM_ROTACAO;
    } else {
        k->erros = (uint16_t)(k->erros & ~KECU_ERR_SEM_ROTACAO);
    }

    const float tps = k->tps_pct;
    const float map = k->map_kpa;
    const float ect = k->ect_c;
    const float iat = k->iat_c;
    const float bateria = k->vbat_v;
    const int rpm_i = arredondar(rpm);
    const uint8_t girando = k->motor_girando;

    /* ---------------------------------------------------------------- chaves */
    k->chave_two_step = (uint8_t)(hal_entrada_ativa(KECU_IN_TWO_STEP) || (k->chaves_sw & KECU_CHAVE_TWO_STEP));
    k->chave_launch = (uint8_t)(hal_entrada_ativa(KECU_IN_LAUNCH) || (k->chaves_sw & KECU_CHAVE_LAUNCH));

    /* ---------------------------------------------------------------- limitador, two-step e launch */
    const uint8_t two_step = (uint8_t)(p->v[P_HAB_TWO_STEP] && k->chave_two_step);
    const uint8_t launch = (uint8_t)(p->v[P_HAB_LAUNCH] && k->chave_launch && tps >= (float)p->v[P_LAUNCH_TPS_MIN]
                                     && vel <= (float)p->v[P_LAUNCH_VEL_MAX]);
    int limite = (int)p->v[P_REV_LIMITE_RPM];
    if (two_step && (int)p->v[P_TWO_STEP_RPM] < limite) {
        limite = (int)p->v[P_TWO_STEP_RPM];
    }
    if (launch && (int)p->v[P_LAUNCH_RPM] < limite) {
        limite = (int)p->v[P_LAUNCH_RPM];
    }
    int histerese = (int)p->v[P_REV_HISTERESE_RPM];
    if (histerese > limite / 2) {
        histerese = limite / 2;
    }
    if (rpm_i >= limite) {
        k->corte = 1;
    } else if (rpm_i <= limite - histerese) {
        k->corte = 0;
    }
    k->two_step_ativo = two_step;
    k->launch_ativo = launch;
    const uint8_t tipo_corte = (uint8_t)p->v[P_TIPO_CORTE];
    const uint8_t corte_comb = (uint8_t)(k->corte && (tipo_corte == 0 || tipo_corte == 2));
    const uint8_t corte_ign = (uint8_t)(k->corte && (tipo_corte == 1 || tipo_corte == 2));

    /* ---------------------------------------------------------------- enriquecimento de aceleração */
    const float tps_por_s = (tps - s_tps_anterior) / dt;
    s_tps_anterior = tps;
    if (p->v[P_HAB_AE] && tps_por_s > (float)p->v[P_AE_LIMIAR]) {
        s_ae_ativo = 1;
        s_ae_fim_ms = agora_ms + p->v[P_AE_DURACAO_MS];
    }
    float ae = 0.0f;
    if (s_ae_ativo) {
        int32_t resta = (int32_t)(s_ae_fim_ms - agora_ms);
        if (resta > (int32_t)p->v[P_AE_DURACAO_MS]) {
            resta = (int32_t)p->v[P_AE_DURACAO_MS]; /* trava de segurança: o enriquecimento nunca passa do valor ajustado */
        }
        if (resta > 0 && p->v[P_AE_DURACAO_MS] > 0) {
            ae = (float)p->v[P_AE_ENRIQUECIMENTO] * (float)resta / (float)p->v[P_AE_DURACAO_MS];
        } else {
            s_ae_ativo = 0;
        }
    }
    k->ae_pct = ae;

    /* ---------------------------------------------------------------- marcha lenta (PI na válvula IAC) */
    const float alvo_ml = alvo_marcha_lenta(p, ect);
    const uint8_t ml = (uint8_t)(p->v[P_HAB_MARCHA_LENTA] && girando && tps < (float)p->v[P_ML_TPS_MAX]
                                 && rpm < alvo_ml + 500.0f);
    float iac = girando ? (float)p->v[P_ML_DUTY_BASE] : 0.0f;
    if (ml) {
        const float erro = alvo_ml - rpm;
        const float ki = (float)p->v[P_ML_KI] / 100.0f;
        const float kp = (float)p->v[P_ML_KP] / 100.0f;
        s_integral_ml = limitar(s_integral_ml + ki * erro * dt, -30.0f, 30.0f);
        iac = limitar((float)p->v[P_ML_DUTY_BASE] + kp * erro + s_integral_ml, 0.0f, 100.0f);
    } else {
        s_integral_ml = 0.0f;
    }
    k->marcha_lenta_ativa = ml;
    k->iac_pct = iac;
    hal_iac_pwm(iac);

    /* ---------------------------------------------------------------- tabelas no ponto de operação */
    k->pos_rpm = kecu_eixo_pos(k->eixos.rpm, rpm);
    k->pos_carga = kecu_eixo_pos(k->eixos.carga, map);
    const float ve = kecu_mapa_interp_raw(&k->mapas, MAPA_COMBUSTIVEL, k->pos_rpm, k->pos_carga);
    float avanco = kecu_mapa_fisico(MAPA_IGNICAO, kecu_mapa_interp_raw(&k->mapas, MAPA_IGNICAO, k->pos_rpm, k->pos_carga));
    const float alvo_lambda = kecu_mapa_fisico(MAPA_LAMBDA,
                                               kecu_mapa_interp_raw(&k->mapas, MAPA_LAMBDA, k->pos_rpm, k->pos_carga));
    if (launch) {
        avanco -= (float)p->v[P_LAUNCH_RETARDO];
    }
    const uint8_t partida = (uint8_t)(girando && rpm < (float)KECU_RPM_PARTIDA_MAX);
    if (partida) {
        avanco = 10.0f; /* na partida o avanço é fixo e conservador */
    }
    k->avanco_graus = girando ? avanco : 0.0f;
    k->ve_pct = (uint8_t)limitar(ve + 0.5f, 0.0f, 255.0f);

    /* ---------------------------------------------------------------- pulso do injetor */
    const float corr_ar = 293.15f / (273.15f + iat);
    const float aquecimento = 1.0f + (ect < 80.0f ? (80.0f - ect) / 80.0f * 0.4f : 0.0f);
    float pulso;
    if (partida) {
        pulso = (float)p->v[P_REQ_FUEL_US] * 0.4f * aquecimento;
    } else {
        pulso = (float)p->v[P_REQ_FUEL_US] * (ve / 100.0f) * (map / 100.0f) * corr_ar / alvo_lambda * aquecimento
                * (1.0f + ae / 100.0f);
    }
    const float morto = limitar((float)p->v[P_TEMPO_MORTO_US] + (float)p->v[P_TEMPO_MORTO_COEF] * (14.0f - bateria),
                                0.0f, 5000.0f);
    float pw = girando ? pulso + morto : 0.0f;
    if (corte_comb) {
        pw = 0.0f;
    }
    k->pw_us = (uint16_t)limitar(pw + 0.5f, 0.0f, 65535.0f);

    /* ---------------------------------------------------------------- plano para a ISR do sensor de rotação */
    kecu_plano_t plano;
    plano.avanco_x10 = (int16_t)limitar((float)arredondar(k->avanco_graus * 10.0f), -900.0f, 1800.0f);
    plano.dwell_us = (uint16_t)limitar((float)p->v[P_DWELL_US] * limitar(14.0f / bateria, 0.8f, 1.6f), 500.0f, 8000.0f);
    plano.inj_us = (uint16_t)limitar(pulso * 0.5f + morto + 0.5f, 0.0f, 65535.0f);
    plano.corte_comb = corte_comb;
    plano.corte_ign = corte_ign;
    plano.ativo = (uint8_t)(girando && rpm_i >= 100);
    plano.armado = k->armado;
    kecu_engine_set_plano(&plano);

    k->ciclos_controle++;
}
