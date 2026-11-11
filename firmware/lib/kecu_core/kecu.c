/*
 * kecu.c - estado global, inicialização e o laço principal cooperativo.
 *
 * Não há sistema operacional: kecu_loop() é chamado sem parar e dispara cada tarefa quando o
 * seu momento chega (comparando o relógio de microssegundos com a próxima hora marcada):
 *   - sensores:   a cada 2 ms (leitura do ADC e filtros)
 *   - controle:   a cada 5 ms (limitador, mapas, pulso, avanço)
 *   - protocolo:  a cada volta (lê comandos, envia telemetria, esvazia a fila de saída)
 * O que é crítico em tempo (dentes da roda, ignição, injeção) NÃO está aqui: roda na
 * interrupção do sensor de rotação (kecu_engine.c).
 */
#include <string.h>

#include "kecu.h"

kecu_t g_kecu;

static uint32_t s_prox_sensores_us;
static uint32_t s_prox_controle_us;
static kecu_engine_cfg_t s_cfg_aplicada;
static uint8_t s_tem_cfg_aplicada;

/* Marca a próxima execução; se o laço atrasou mais de um período, pula as execuções perdidas. */
static uint32_t proxima(uint32_t marcada_us, uint32_t agora_us, uint32_t periodo_us) {
    uint32_t prox = marcada_us + periodo_us;
    if ((int32_t)(agora_us - prox) >= 0) {
        prox = agora_us + periodo_us;
    }
    return prox;
}

/* Só reconfigura a leitura do sinal de rotação se algo do sinal mudou (senão perderia a sincronia à toa). */
void kecu_aplicar_parametros(void) {
    const kecu_params_t *p = &g_kecu.params;

    kecu_engine_cfg_t cfg;
    cfg.modo_uniforme = (uint8_t)(p->v[P_MODO_SINAL] == 1u);
    cfg.dentes = (uint8_t)p->v[P_DENTES_RODA];
    cfg.faltando = cfg.modo_uniforme ? 0u : (uint8_t)p->v[P_DENTES_FALTANDO];
    cfg.cilindros = (uint8_t)p->v[P_CILINDROS];
    cfg.pms_offset_x10 = p->v[P_PMS_OFFSET_X10];
    cfg.vss_pulsos_km = p->v[P_VSS_PULSOS_KM];

    if (s_tem_cfg_aplicada && cfg.modo_uniforme == s_cfg_aplicada.modo_uniforme && cfg.dentes == s_cfg_aplicada.dentes
        && cfg.faltando == s_cfg_aplicada.faltando && cfg.cilindros == s_cfg_aplicada.cilindros
        && cfg.pms_offset_x10 == s_cfg_aplicada.pms_offset_x10) {
        s_cfg_aplicada.vss_pulsos_km = cfg.vss_pulsos_km;
        return;
    }
    kecu_engine_config(&cfg);
    s_cfg_aplicada = cfg;
    s_tem_cfg_aplicada = 1;
}

void kecu_init(void) {
    memset(&g_kecu, 0, sizeof g_kecu);
    kecu_params_padrao(&g_kecu.params);
    kecu_eixos_padrao(&g_kecu.eixos);
    kecu_mapas_padrao(&g_kecu.mapas);
    g_kecu.lambda = 1.0f;
    g_kecu.vbat_v = 12.0f;
    g_kecu.ect_c = KECU_ECT_EMERGENCIA_C;
    g_kecu.iat_c = KECU_IAT_EMERGENCIA_C;

    /* calibração guardada: 0 = carregou, 1 = memória vazia (usa os padrões), 2 = corrompida (padrões + aviso) */
    if (kecu_store_carregar() == 2) {
        g_kecu.erros |= KECU_ERR_NVM;
    }

    kecu_engine_init();
    s_tem_cfg_aplicada = 0; /* o decodificador acabou de voltar ao padrão: aplica de novo */
    kecu_aplicar_parametros();
    kecu_sensores_init();
    kecu_control_init();
    kecu_proto_init();

#if KECU_ENABLE_OUTPUTS && KECU_AUTO_ARMAR
    g_kecu.armado = 1;
#endif

    const uint32_t agora = hal_micros();
    s_prox_sensores_us = agora;
    s_prox_controle_us = agora;
}

void kecu_loop(void) {
    const uint32_t agora = hal_micros();

    if ((int32_t)(agora - s_prox_sensores_us) >= 0) {
        kecu_sensores_ler();
        s_prox_sensores_us = proxima(s_prox_sensores_us, agora, KECU_PERIODO_SENSORES_US);
    }
    if ((int32_t)(agora - s_prox_controle_us) >= 0) {
        kecu_control_passo(agora);
        s_prox_controle_us = proxima(s_prox_controle_us, agora, KECU_PERIODO_CONTROLE_US);
    }
    kecu_proto_processar(agora);
}
