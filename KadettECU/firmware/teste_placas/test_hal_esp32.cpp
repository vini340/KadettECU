/*
 * test_hal_esp32.cpp - testa o código da placa ESP32 (src/hal_esp32.cpp + src/main.cpp) no PC.
 *
 * O que roda de verdade: o núcleo em C (lib/kecu_core), o agendador de bordas (lib/kecu_sched), a HAL do
 * ESP32 e o main.cpp, SEM alteração. O que é simulado: as bibliotecas do Arduino-ESP32 (mock/esp32): o
 * timer de hardware com alarme, os pinos, o ADC, a serial, a memória NVS, a válvula PWM e o cão de guarda.
 * A roda fônica 60-2 é desenhada por este arquivo e entregue à interrupção do pino do sensor.
 *
 * Isto prova que o código da placa faz o que deve DADO o comportamento das APIs como o simulador as
 * modela. NÃO prova que o Arduino-ESP32 de verdade se comporta igual, nem substitui o teste em bancada.
 *
 * Compila em várias variantes (veja teste_placas/Makefile): modo sombra, saídas habilitadas, duas
 * bobinas, saída ativa em nível baixo, arma-ao-ligar, e a API 3.x do Arduino-ESP32 (-DMOCK_ESP_V3).
 */
#include "harness_placa.h"

#ifndef KECU_IAC_FREQ_HZ
#define KECU_IAC_FREQ_HZ 200 /* o mesmo padrão de hal_esp32.cpp */
#endif

#define AMOSTRAS_ADC 4 /* o mesmo de KECU_ADC_AMOSTRAS em hal_esp32.cpp */

/* No 3.x o alarme não se desliga: vai para longe. "Ocioso" vale para os dois. */
SO_EM_ALGUMAS_VARIANTES static int alarme_ocioso(void) {
    return !mock_alarme_ativo || (int64_t)(mock_alarme_valor - mock_timer_contador()) > 1000000000ll;
}

/* Nenhum alarme de verdade: nada disparou e o que está programado (se houver) está estacionado longe. */
SO_EM_ALGUMAS_VARIANTES static int nenhum_alarme_real(void) {
    return mock_alarmes_disparados == 0 && alarme_ocioso();
}

/* ================================================================== 1) arranque */

static void teste_arranque(void) {
    secao("Arranque: timer, serial, ADC, pinos, interrupções, cão de guarda");
    ligar_placa_nova();
#if defined(MOCK_ESP_V3)
    CHECK(mock_timer_criado && mock_timer_freq_hz == 1000000u, "timer a 1 MHz (%u Hz)", mock_timer_freq_hz);
#else
    CHECK(mock_timer_criado && mock_timer_divisor == 80u && mock_timer_freq_hz == 1000000u,
          "timer 0 com divisor 80 = 1 MHz (divisor %u, %u Hz)", mock_timer_divisor, mock_timer_freq_hz);
#endif
    CHECK(hal_micros() == 1000u, "o relógio de microssegundos começa em 1000, não em zero (%u)", hal_micros());
    mock_tempo_para(mock_tempo_us + 250u);
    CHECK(hal_micros() == 1250u, "e anda junto com o tempo (%u)", hal_micros());
    CHECK(hal_millis() == (uint32_t)(mock_tempo_us / 1000u), "hal_millis vem do relógio do sistema (%u)", hal_millis());
    CHECK(mock_serial_iniciada && mock_serial_baud == 115200u, "serial a 115200 baud (%u)", mock_serial_baud);
    CHECK(mock_serial_rx_tam == 1024u, "buffer de recepção de 1024 bytes, definido antes do begin (%u)", (unsigned)mock_serial_rx_tam);
    CHECK(mock_wdt_timeout_ms == 2000u && mock_wdt_panic == 1 && mock_wdt_tarefa_inscrita == 1,
          "cão de guarda de 2 s com reinício, com o laço principal inscrito (%u ms, panic %d, inscrito %d)", mock_wdt_timeout_ms,
          mock_wdt_panic, mock_wdt_tarefa_inscrita);
    int atenuacao_ok = 1;
    for (int i = 0; i < KECU_ADC_CANAIS; i++) {
        if (mock_atenuacao[k_pino_adc[i]] != (uint8_t)ADC_11db) {
            atenuacao_ok = 0;
        }
    }
    CHECK(atenuacao_ok, "os 7 canais do ADC com atenuação de 11 dB");
    CHECK(mock_pino_modo[PIN_TWO_STEP] == INPUT_PULLUP && mock_pino_modo[PIN_LAUNCH] == INPUT_PULLUP,
          "chaves two-step e launch com pull-up interno");
    CHECK(mock_pino_modo[PIN_CKP] == INPUT && mock_pino_modo[PIN_VSS] == INPUT, "pinos de rotação e velocidade como entrada");
    CHECK(mock_isr_pino[PIN_CKP] != NULL && mock_isr_modo[PIN_CKP] == (KECU_CKP_BORDA_SUBIDA ? RISING : FALLING),
          "interrupção do dente no pino %d, na borda configurada", PIN_CKP);
    CHECK(mock_isr_pino[PIN_VSS] != NULL && mock_isr_modo[PIN_VSS] == RISING, "interrupção do VSS no pino %d", PIN_VSS);
    CHECK(kecu_sched_pendentes() == 0 && kecu_sched_descartados() == 0, "agendador vazio");
    CHECK(mock_secao_nivel == 0 && mock_secao_desequilibrio == 0, "nenhuma seção crítica aberta");
    CHECK(mock_mudancas_n == 0, "nenhum pino saiu do nível desligado durante o arranque (%d mudanças)", mock_mudancas_n);
#if !KECU_ENABLE_OUTPUTS
    int sombra_ok = 1;
    for (int i = 0; i < KECU_SAIDAS; i++) {
        const int p = k_pino_saida[i];
        if (mock_pino_modo[p] != 0 || mock_pino_pinmodes[p] != 0 || mock_pino_escritas[p] != 0) {
            sombra_ok = 0;
            printf("    pino %d foi tocado (modo %u, pinMode %d, escritas %d)\n", p, mock_pino_modo[p], mock_pino_pinmodes[p],
                   mock_pino_escritas[p]);
        }
    }
    CHECK(sombra_ok, "modo sombra: os 6 pinos de bico e bobina nunca são configurados nem escritos");
    CHECK(!mock_iac_anexada && mock_iac_escritas == 0, "modo sombra: a válvula de marcha lenta (pino %d) não é configurada", PIN_IAC);
#else
    int saidas_ok = 1;
    for (int i = 0; i < KECU_SAIDAS; i++) {
        const int p = k_pino_saida[i];
        const int ordem_ok = strncmp(mock_pino_historia[p], "LM", 2) == 0 || (!KECU_SAIDA_ATIVA_ALTO && strncmp(mock_pino_historia[p], "HM", 2) == 0);
        if (mock_pino_modo[p] != OUTPUT || !ordem_ok || mock_pino_nivel[p] != NIVEL_DESLIGADO) {
            saidas_ok = 0;
            printf("    pino %d: modo %u, história \"%s\", nível %u\n", p, mock_pino_modo[p], mock_pino_historia[p], mock_pino_nivel[p]);
        }
    }
    CHECK(saidas_ok, "saídas habilitadas: cada pino recebe primeiro o nível desligado e só depois vira saída");
    CHECK(mock_iac_anexada && mock_iac_pino == PIN_IAC && mock_iac_freq_hz == (uint32_t)KECU_IAC_FREQ_HZ && mock_iac_bits == 10u && mock_iac_duty == 0u,
          "válvula de marcha lenta: PWM de %d Hz, 10 bits, no pino %d, duty 0 (%u Hz, %u bits, duty %u)", KECU_IAC_FREQ_HZ, PIN_IAC,
          mock_iac_freq_hz, mock_iac_bits, mock_iac_duty);
#endif
}

/* ================================================================== 2) HELLO */

static void teste_hello(void) {
    secao("Protocolo: HELLO");
    ligar_placa_nova();
    const quadro_t *r = comando(KECU_CMD_HELLO, NULL, 0);
    CHECK(r != NULL && r->tipo == KECU_RSP_INFO && r->n == KECU_INFO_BYTES, "HELLO responde INFO de %d bytes", KECU_INFO_BYTES);
    if (r == NULL || r->tipo != KECU_RSP_INFO) {
        return;
    }
    const uint16_t caps = (uint16_t)(r->d[6] | (r->d[7] << 8));
    const uint16_t caps_esperadas = (uint16_t)(KECU_CAP_NVM | (KECU_ENABLE_OUTPUTS ? KECU_CAP_SAIDAS_REAIS : 0));
    CHECK(r->d[0] == KECU_PROTOCOLO_VERSAO && r->d[1] == KECU_FW_MAIOR && r->d[2] == KECU_FW_MENOR, "versões do protocolo e do firmware");
    CHECK(r->d[3] == 1, "placa = 1 (ESP32), veio %u", r->d[3]);
    CHECK(caps == caps_esperadas, "capacidades 0x%04X (esperado 0x%04X)", caps, caps_esperadas);
}

/* ================================================================== 3) sensores */

static void teste_sensores(void) {
    secao("Sensores: o ADC do ESP32 vira grandeza física");
    ligar_placa_nova();
    sensores_definir(90.0, 25.0, 55.0, 50.0, 13.8, 5.0, 1.0);
    harness_zerar();
    reiniciar_placa(); /* a primeira leitura já parte do valor medido */
    mundo_por(30000);
    CHECK(fabs((double)g_kecu.ect_c - 90.0) < 0.5, "temperatura da água 90 °C: leu %.2f", (double)g_kecu.ect_c);
    CHECK(fabs((double)g_kecu.iat_c - 25.0) < 0.5, "temperatura do ar 25 °C: leu %.2f", (double)g_kecu.iat_c);
    CHECK(fabs((double)g_kecu.map_kpa - 55.0) < 0.3, "MAP 55 kPa: leu %.2f", (double)g_kecu.map_kpa);
    CHECK(fabs((double)g_kecu.tps_pct - 50.0) < 0.3, "TPS 50 %%: leu %.2f", (double)g_kecu.tps_pct);
    CHECK(fabs((double)g_kecu.vbat_v - 13.8) < 0.05, "bateria 13,8 V: leu %.3f", (double)g_kecu.vbat_v);
    CHECK(fabs((double)g_kecu.oleo_bar - 5.0) < 0.05, "óleo 5 bar: leu %.3f", (double)g_kecu.oleo_bar);
    CHECK(fabs((double)g_kecu.lambda - 1.0) < 0.01, "lambda 1,00: leu %.4f", (double)g_kecu.lambda);
    CHECK(g_kecu.erros == 0, "sensores saudáveis: nenhum erro (0x%04X)", g_kecu.erros);
    CHECK(mock_adc_leituras > 0 && mock_adc_leituras % (KECU_ADC_CANAIS * AMOSTRAS_ADC) == 0,
          "cada canal é lido %d vezes por ciclo, em média (%d leituras)", AMOSTRAS_ADC, mock_adc_leituras);
    const int ciclos = mock_adc_leituras / (KECU_ADC_CANAIS * AMOSTRAS_ADC);
    CHECK(ciclos >= 12 && ciclos <= 17, "sensores lidos a cada 2 ms: %d ciclos em 30 ms", ciclos);

    /* o mesmo valor aparece na telemetria */
    const uint8_t periodo[2] = {20, 0};
    CHECK(e_ack(comando(KECU_CMD_STREAM, periodo, 2)), "STREAM 20 ms aceito");
    mundo_por(100000);
    CHECK(g_tele_tem && g_tele_n >= 4 && g_tele_n <= 6, "telemetria a cada 20 ms: %d quadros em 100 ms", g_tele_n);
    if (g_tele_tem) {
        const int ect10 = (int16_t)(g_tele[8] | (g_tele[9] << 8));
        const int map10 = g_tele[12] | (g_tele[13] << 8);
        const int tps10 = g_tele[16] | (g_tele[17] << 8);
        CHECK(abs(ect10 - 900) <= 5 && abs(map10 - 550) <= 3 && abs(tps10 - 500) <= 3,
              "telemetria: água %.1f °C, MAP %.1f kPa, TPS %.1f %%", ect10 / 10.0, map10 / 10.0, tps10 / 10.0);
        const uint32_t t_ms = (uint32_t)(g_tele[0] | (g_tele[1] << 8) | (g_tele[2] << 16) | ((uint32_t)g_tele[3] << 24));
        CHECK(t_ms + 40u >= hal_millis() && t_ms <= hal_millis(), "carimbo de tempo da telemetria = hal_millis (%u, agora %u)", t_ms, hal_millis());
    }
    CHECK(g_crc_ruins == 0 && g_bytes_lixo == 0, "todos os quadros chegaram íntegros (%d CRC ruins, %d bytes soltos)", g_crc_ruins, g_bytes_lixo);
    CHECK(mock_serial_bloqueios == 0 && g_max_laco_us == 0, "e a serial nunca travou o laço (%d bloqueios)", mock_serial_bloqueios);

    /* falhas de sensor */
    mock_adc_mv[k_pino_adc[KECU_ADC_MAP]] = 0;                                   /* fio solto */
    mock_adc_mv[k_pino_adc[KECU_ADC_TPS]] = pino_mv_5v(5000.0);                  /* em curto com +5 V */
    mock_adc_mv[k_pino_adc[KECU_ADC_ECT]] = KECU_VREF_MV;                        /* NTC solto: o pull-up leva ao máximo */
    mock_adc_mv[k_pino_adc[KECU_ADC_LAMBDA]] = 0;
    mock_adc_mv[k_pino_adc[KECU_ADC_VBAT]] = 400;                                /* 2,3 V: fora de 6..18 V */
    mundo_por(20000);
    const uint16_t esperados = KECU_ERR_MAP | KECU_ERR_TPS | KECU_ERR_ECT | KECU_ERR_LAMBDA | KECU_ERR_BATERIA;
    CHECK((g_kecu.erros & esperados) == esperados, "falhas detectadas: erros 0x%04X (esperado ao menos 0x%04X)", g_kecu.erros, esperados);
    CHECK(g_kecu.tps_pct < 1.0f, "TPS em curto para +5 V não vira 100 %% de acelerador (%.1f)", (double)g_kecu.tps_pct);
    sensores_saudaveis();
    mundo_por(20000);
    CHECK((g_kecu.erros & esperados) == 0, "sensores de volta: erros somem (0x%04X)", g_kecu.erros);
}

/* ================================================================== 6) memória (NVS) */

static void teste_nvs(void) {
    secao("Memória NVS: gravar, reiniciar, corromper, falhar");
    ligar_placa_nova();
    CHECK(!(g_kecu.erros & KECU_ERR_NVM), "flash vazia na primeira vez: usa os padrões, sem alarme");
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500, "limite de rotação padrão 6500");

    /* muda o limite de rotação (parâmetro) e uma linha do mapa de combustível, e grava */
    const quadro_t *r = comando(KECU_CMD_LER_PARAMS, NULL, 0);
    CHECK(r != NULL && r->tipo == KECU_RSP_PARAMS && r->n == KECU_PARAMS_BYTES, "LER_PARAMS devolve %d bytes", KECU_PARAMS_BYTES);
    if (r == NULL || r->tipo != KECU_RSP_PARAMS) {
        return;
    }
    uint8_t params[KECU_PARAMS_BYTES];
    memcpy(params, r->d, sizeof params);
    params[0] = 7000 & 0xFF; /* REV_LIMITE_RPM (2 bytes, little-endian) */
    params[1] = 7000 >> 8;
    CHECK(e_ack(comando(KECU_CMD_ESCREVER_PARAMS, params, KECU_PARAMS_BYTES)), "ESCREVER_PARAMS aceito");
    uint8_t linha[2 + KECU_N];
    linha[0] = 0;
    linha[1] = 3;
    for (int i = 0; i < KECU_N; i++) {
        linha[2 + i] = (uint8_t)(40 + i * 7);
    }
    CHECK(e_ack(comando(KECU_CMD_ESCREVER_LINHA, linha, (uint8_t)(2 + KECU_N))), "ESCREVER_LINHA aceito");
    CHECK(mock_nvs_gravacoes == 0, "escrever na RAM não grava na flash");
    CHECK(e_ack(comando(KECU_CMD_GRAVAR, NULL, 0)), "GRAVAR aceito");
    CHECK(mock_nvs_gravacoes == 1 && mock_nvs_tamanho() == 889u, "um bloco de 889 bytes na flash (%d gravações, %u bytes)", mock_nvs_gravacoes,
          (unsigned)mock_nvs_tamanho());

    reiniciar_placa();
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 7000, "depois do reset o limite de rotação continua 7000 (%u)", g_kecu.params.v[P_REV_LIMITE_RPM]);
    CHECK(memcmp(g_kecu.mapas.m[0][3], &linha[2], KECU_N) == 0, "e a linha do mapa também");
    CHECK(!(g_kecu.erros & KECU_ERR_NVM), "sem alarme de memória");
    r = comando(KECU_CMD_LER_LINHA, linha, 2);
    CHECK(r != NULL && r->tipo == KECU_RSP_LINHA && r->n == 2 + KECU_N && memcmp(&r->d[2], &linha[2], KECU_N) == 0,
          "LER_LINHA devolve o que foi gravado");

    /* flash corrompida */
    mock_nvs_corromper(100);
    reiniciar_placa();
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500, "flash corrompida: volta aos padrões (%u)", g_kecu.params.v[P_REV_LIMITE_RPM]);
    CHECK(g_kecu.erros & KECU_ERR_NVM, "e acende o alarme de memória");
    mundo_por(20000);
    CHECK(g_kecu.erros & KECU_ERR_NVM, "o alarme não some sozinho com a leitura dos sensores");

    /* a flash falha na gravação */
    mock_nvs_falhar = 1;
    CHECK(e_nack(comando(KECU_CMD_GRAVAR, NULL, 0), KECU_NACK_MEMORIA), "flash recusa a gravação: NACK 5");
    mock_nvs_falhar = 0;
    CHECK(e_ack(comando(KECU_CMD_GRAVAR, NULL, 0)), "gravação normal volta a funcionar");
    mundo_por(20000);
    CHECK(!(g_kecu.erros & KECU_ERR_NVM), "e apaga o alarme de memória");

    /* motor girando: não grava, e não mexe nos campos de sinal */
    roda_iniciar(2000.0);
    mundo_por(800000);
    const int gravacoes = mock_nvs_gravacoes;
    CHECK(g_kecu.motor_girando == 1, "motor girando para o teste");
    CHECK(e_nack(comando(KECU_CMD_GRAVAR, NULL, 0), KECU_NACK_MOTOR_GIRANDO), "GRAVAR com o motor girando: NACK 4");
    CHECK(mock_nvs_gravacoes == gravacoes, "e a flash não foi tocada");
    roda_parar();
    mundo_por(400000);

    /* a leitura de uma flash vazia não pode devolver restos de uma leitura anterior */
    CHECK(e_ack(comando(KECU_CMD_ESCREVER_PARAMS, params, KECU_PARAMS_BYTES)) && e_ack(comando(KECU_CMD_GRAVAR, NULL, 0)),
          "grava de novo, agora com o limite de rotação em 7000");
    ligar_placa_nova(); /* flash apagada */
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500 && !(g_kecu.erros & KECU_ERR_NVM),
          "flash apagada: padrões, sem alarme, sem restos da gravação anterior (limite %u)", g_kecu.params.v[P_REV_LIMITE_RPM]);
}

/* O laço do ESP32 alimenta o cão de guarda em TODA volta (o que teste_serial rodou acima). */
static void teste_serial_cao_de_guarda_esp32(void) {
    CHECK(mock_wdt_resets == g_lacos, "o cão de guarda foi alimentado em cada volta do laço (%d de %d)", mock_wdt_resets, g_lacos);
}

/* ================================================================== modo sombra com o motor girando */

#if !KECU_ENABLE_OUTPUTS
static void teste_sombra(void) {
    secao("Modo sombra: motor girando, nenhum pino de saída mexe, ARMAR é recusado");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    mundo_por(2000000);
    CHECK(g_kecu.motor_girando == 1 && g_kecu.eventos_ignicao > 100, "motor girando, eventos planejados (%u)", g_kecu.eventos_ignicao);
    CHECK(mock_mudancas_n == 0, "nenhuma mudança de nível em pino algum (%d)", mock_mudancas_n);
    CHECK(nenhum_alarme_real(), "nenhum alarme de timer disparou nem está programado para perto (%d programados, %d disparados)",
          mock_alarmes_programados, mock_alarmes_disparados);
    CHECK(kecu_sched_pendentes() == 0, "agendador vazio");
    int sombra_ok = 1;
    for (int i = 0; i < KECU_SAIDAS; i++) {
        const int p = k_pino_saida[i];
        if (mock_pino_modo[p] != 0 || mock_pino_pinmodes[p] != 0 || mock_pino_escritas[p] != 0) {
            sombra_ok = 0;
        }
    }
    CHECK(sombra_ok, "os 6 pinos de saída seguem intocados");
    CHECK(!mock_iac_anexada && mock_iac_escritas == 0, "a válvula de marcha lenta segue intocada");
    CHECK(g_kecu.iac_pct > 0.0f, "(a ECU calcula a válvula: %.1f %%, mas não a aciona)", (double)g_kecu.iac_pct);

    const uint8_t ligar = 1;
    const uint8_t desligar = 0;
    CHECK(e_nack(comando(KECU_CMD_ARMAR, &ligar, 1), KECU_NACK_SAIDAS_DESABILITADAS), "ARMAR(1) recusado com NACK 6");
    CHECK(g_kecu.armado == 0, "continua desarmada");
    CHECK(e_ack(comando(KECU_CMD_ARMAR, &desligar, 1)), "ARMAR(0) é aceito (desarmar nunca é recusado)");
    const uint8_t periodo[2] = {20, 0};
    CHECK(e_ack(comando(KECU_CMD_STREAM, periodo, 2)), "STREAM");
    mundo_por(100000);
    const uint16_t flags = g_tele_tem ? (uint16_t)(g_tele[32] | (g_tele[33] << 8)) : 0;
    CHECK((flags & KECU_FLAG_MODO_SOMBRA) && !(flags & KECU_FLAG_ARMADO) && !(flags & KECU_FLAG_SIMULADOR) && (flags & KECU_FLAG_MOTOR_GIRANDO),
          "telemetria avisa modo sombra (flags 0x%04X)", flags);
    CHECK(mock_mudancas_n == 0, "e ainda nenhum pino mexeu");
    roda_parar();
}
#endif

/* ================================================================== saídas habilitadas */

#if KECU_ENABLE_OUTPUTS
static medida_t medir_armado(double rpm, uint32_t custo_isr_us, uint32_t lat_alarme_us, const char *rotulo) {
    ligar_placa_nova();
    mock_isr_custo_us = custo_isr_us;
    mock_lat_alarme_us = lat_alarme_us;
    roda_iniciar(rpm);
    mundo_por(1500000);
    CHECK(g_kecu.sync == 1, "%s: sincronizado", rotulo);
    CHECK(armar(1) && g_kecu.armado == 1, "%s: ARMAR(1) aceito", rotulo);
    mundo_por(600000); /* o degrau de TPS do início dispara o enriquecimento de aceleração (400 ms) */
    const int ini = mock_mudancas_n;
    mundo_por(1000000);

    const double adv = (double)g_kecu.avanco_graus;
    const double morto = 900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v);
    const double inj_esperado = ((double)g_kecu.pw_us - morto) / 2.0 + morto;
    const double dwell_esperado = fmin((double)g_kecu.params.v[P_DWELL_US] * 14.0 / (double)g_kecu.vbat_v, 60e6 / rpm * 0.425);
    const double tol = (double)lat_alarme_us + (double)custo_isr_us + 3.0;
    const medida_t m = medir(ini, rpm, 0.0, adv, dwell_esperado, inj_esperado, tol, (double)custo_isr_us);
    const double voltas = rpm / 60.0;
    CHECK(fabs((double)m.faltas_de_fagulha - 2.0 * voltas) <= 3.0, "%s: %d centelhas em 1 s (esperado %.0f)", rotulo, m.faltas_de_fagulha, 2.0 * voltas);
    CHECK(fabs((double)m.inj_eventos - voltas) <= 2.0, "%s: %d injeções em 1 s (esperado %.0f)", rotulo, m.inj_eventos, voltas);
    CHECK(m.max_err_fagulha < 0.6, "%s: centelha em PMS - %.1f graus, erro máximo %.3f graus", rotulo, adv, m.max_err_fagulha);
    CHECK(m.max_err_inj < 0.6 + (double)custo_isr_us / (60e6 / rpm) * 360.0, "%s: início da injeção no dente de referência, erro %.3f graus", rotulo, m.max_err_inj);
    CHECK(m.bobina_errada == 0, "%s: cada centelha na bobina certa (%d erradas)", rotulo, m.bobina_errada);
    CHECK(m.inj_dessincronizados == 0, "%s: os 4 bicos abrem juntos (%d diferenças)", rotulo, m.inj_dessincronizados);
    CHECK(m.dwell_errado == 0, "%s: tempo de carga da bobina %.0f us (%d fora)", rotulo, dwell_esperado, m.dwell_errado);
    CHECK(m.inj_largura_errada == 0, "%s: largura da injeção %.0f us (%d fora)", rotulo, inj_esperado, m.inj_largura_errada);
    CHECK(m.pino_estranho == 0, "%s: só os 6 pinos de saída mudam (%d mudanças em outros)", rotulo, m.pino_estranho);
    CHECK(kecu_sched_descartados() == 0 && kecu_sched_forcados() == 0, "%s: sem descartes nem saídas forçadas (%u / %u)", rotulo,
          kecu_sched_descartados(), kecu_sched_forcados());
    CHECK(mock_alarmes_no_passado == 0 && mock_alarme_margem_min_us >= 3, "%s: o alarme nunca é programado a menos de 3 us (menor margem %lld us, %d no passado)",
          rotulo, (long long)mock_alarme_margem_min_us, mock_alarmes_no_passado);
    CHECK(mock_secao_desequilibrio == 0 && mock_secao_nivel == 0 && g_secao_vazou == 0, "%s: seções críticas equilibradas (profundidade máxima %d)", rotulo,
          mock_secao_max);
    CHECK(mock_escritas_fora_de_secao == g_base_escritas && mock_alarme_fora_de_secao == g_base_alarme,
          "%s: todo pino de saída e todo alarme é mexido dentro de seção crítica (%d escritas e %d alarmes fora)", rotulo,
          mock_escritas_fora_de_secao - g_base_escritas, mock_alarme_fora_de_secao - g_base_alarme);
    CHECK(mock_wdt_resets == g_lacos, "%s: cão de guarda alimentado em cada volta", rotulo);
    if (custo_isr_us > 0 && m.dwell_encurtado > 0) {
        printf("    %s: %d de %d centelhas carregaram até %u us menos (a interrupção do dente atrasou o início)\n", rotulo, m.dwell_encurtado,
               m.faltas_de_fagulha, custo_isr_us);
    }
    return m;
}

static void teste_saidas_normal(void) {
    secao("Saídas: rotação 60-2 nos pinos do sensor, ângulo, largura e pino de cada pulso");
    char rotulo[48];
    static const double rpms[] = {800.0, 3000.0, 6000.0};
    for (size_t i = 0; i < sizeof rpms / sizeof rpms[0]; i++) {
        snprintf(rotulo, sizeof rotulo, "%.0f rpm", rpms[i]);
        const medida_t m = medir_armado(rpms[i], 0, 0, rotulo);
        CHECK(m.dwell_encurtado == 0, "%s: sem atraso de interrupção a bobina carrega o tempo todo", rotulo);
        for (int s = KECU_SAIDA_INJ1; s <= KECU_SAIDA_INJ4; s++) {
            CHECK(m.transicoes[s] > 0, "%s: o bico %d (pino %d) mexeu", rotulo, s + 1, k_pino_saida[s]);
        }
        CHECK(m.transicoes[KECU_SAIDA_BOBINA1] > 0, "%s: a bobina 1 (pino %d) mexeu", rotulo, k_pino_saida[KECU_SAIDA_BOBINA1]);
#if KECU_BOBINAS == 1
        CHECK(m.transicoes[KECU_SAIDA_BOBINA2] == 0, "%s: com uma bobina só, o pino da bobina 2 (%d) fica parado", rotulo, k_pino_saida[KECU_SAIDA_BOBINA2]);
#else
        CHECK(m.transicoes[KECU_SAIDA_BOBINA2] > 0, "%s: a bobina 2 (pino %d) mexeu", rotulo, k_pino_saida[KECU_SAIDA_BOBINA2]);
        CHECK(abs(m.transicoes[KECU_SAIDA_BOBINA1] - m.transicoes[KECU_SAIDA_BOBINA2]) <= 2, "%s: as duas bobinas se alternam (%d / %d)", rotulo,
              m.transicoes[KECU_SAIDA_BOBINA1], m.transicoes[KECU_SAIDA_BOBINA2]);
#endif
    }
}

static void teste_isr_lenta(void) {
    secao("Saídas: interrupção do dente lenta (10 us) e alarme com 2 us de atraso");
    static const double rpms[] = {1000.0, 3000.0, 6000.0};
    char rotulo[48];
    for (size_t i = 0; i < sizeof rpms / sizeof rpms[0]; i++) {
        snprintf(rotulo, sizeof rotulo, "%.0f rpm, ISR 10 us", rpms[i]);
        medir_armado(rpms[i], 10, 2, rotulo);
    }
}

/*
 * A interrupção do dente demora 300 us (muito mais que no ESP32 de verdade) a 800 rpm. Como a hal_esp32 âncora o
 * agendador no instante do dente (antes de qualquer cálculo), a centelha continua no ângulo: só a rara centelha
 * que cai dentro de uma interrupção atrasa. Sem a âncora TODAS as centelhas atrasariam 300 us (1,4 grau).
 */
static void teste_ancora(void) {
    secao("Saídas: a âncora do dente mantém a centelha no ângulo com a interrupção lenta (300 us a 800 rpm)");
    const medida_t m = medir_armado(800.0, 300, 2, "800 rpm, ISR 300 us");
    CHECK(m.fagulhas_acima_06 * 10 <= m.faltas_de_fagulha, "no máximo 10 %% das centelhas fora de 0,6 grau (%d de %d)", m.fagulhas_acima_06,
          m.faltas_de_fagulha);
}

/* hal_timer_armar nunca programa o alarme a menos de 3 us do contador, mesmo para uma borda que vence em 1 us. */
static void teste_alarme_margem(void) {
    secao("Saídas: o alarme nunca é programado a menos de 3 us à frente");
    static const uint32_t atrasos[] = {0, 1, 2, 3, 5, 40};
    for (size_t i = 0; i < sizeof atrasos / sizeof atrasos[0]; i++) {
        ligar_placa_nova();
        mundo_por(1000);
        mock_alarme_margem_min_us = INT64_MAX;
        const int ini = mock_mudancas_n;
        const uint64_t t0 = mock_tempo_us;
        kecu_sched_pulso(KECU_SAIDA_INJ1, atrasos[i], 500);
        CHECK(mock_alarme_margem_min_us >= 3, "borda a %u us: o alarme fica a no mínimo 3 us do contador (margem %lld us)", atrasos[i],
              (long long)mock_alarme_margem_min_us);
        mundo_por(2000);
        int liga_us = -1;
        int desliga_us = -1;
        for (int j = ini; j < mock_mudancas_n; j++) {
            if (mock_mudancas[j].pino != k_pino_saida[KECU_SAIDA_INJ1]) {
                continue;
            }
            if (mock_mudancas[j].nivel == NIVEL_LIGADO) {
                liga_us = (int)(mock_mudancas[j].t_us - t0);
            } else {
                desliga_us = (int)(mock_mudancas[j].t_us - t0);
            }
        }
        CHECK(liga_us >= (int)atrasos[i] && liga_us <= (int)atrasos[i] + 3, "o bico liga a %u us (a +3 us no máximo): ligou a %d us", atrasos[i], liga_us);
        CHECK(desliga_us == (int)atrasos[i] + 500 || (desliga_us >= (int)atrasos[i] + 500 && desliga_us <= (int)atrasos[i] + 503),
              "e desliga a %u us: desligou a %d us", atrasos[i] + 500, desliga_us);
        CHECK(todas_saidas_desligadas() && kecu_sched_pendentes() == 0, "no fim tudo desligado");
    }
}

/* Mostra a válvula de marcha lenta só mexendo com a ECU armada. */
static void teste_iac_e_arme(void) {
    secao("Válvula de marcha lenta e ARMAR/desarmar: nada se move desarmada");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    mundo_por(1500000);
    CHECK(g_kecu.motor_girando == 1 && g_kecu.armado == 0, "motor girando, ECU desarmada");
    CHECK(mock_mudancas_n == 0, "desarmada: nenhum pino de saída mexe (%d mudanças)", mock_mudancas_n);
    CHECK(nenhum_alarme_real(), "desarmada: nenhum alarme de timer de verdade (%d programados, %d disparados)", mock_alarmes_programados,
          mock_alarmes_disparados);
    CHECK(g_kecu.eventos_ignicao > 50, "mas a ECU planeja os eventos (%u)", g_kecu.eventos_ignicao);
    CHECK(g_kecu.iac_pct > 1.0f && mock_iac_duty == 0u && mock_iac_escritas > 0,
          "desarmada: a ECU calcula a válvula (%.1f %%) mas o duty do pino fica em 0 (%u)", (double)g_kecu.iac_pct, mock_iac_duty);

    CHECK(armar(1) && g_kecu.armado == 1, "ARMAR(1) aceito");
    mundo_por(600000);
    const uint32_t duty_esperado = (uint32_t)((double)g_kecu.iac_pct * 1023.0 / 100.0 + 0.5);
    CHECK(mock_iac_duty > 0 && (mock_iac_duty + 1 >= duty_esperado) && (mock_iac_duty <= duty_esperado + 1),
          "armada: duty da válvula = %.1f %% de 1023 = %u (veio %u)", (double)g_kecu.iac_pct, duty_esperado, mock_iac_duty);
    int ini = mock_mudancas_n;
    mundo_por(300000);
    CHECK(mock_mudancas_n - ini > 100, "armada: os pinos passam a mexer (%d mudanças em 300 ms)", mock_mudancas_n - ini);

    /* desarma exatamente com a bobina carregando */
    int guarda = 0;
    while (mock_pino_nivel[k_pino_saida[KECU_SAIDA_BOBINA1]] != NIVEL_LIGADO && guarda++ < 20000) {
        mundo_por(20);
    }
    CHECK(mock_pino_nivel[k_pino_saida[KECU_SAIDA_BOBINA1]] == NIVEL_LIGADO, "pegou a bobina carregando para desarmar");
    {
        const uint8_t desligar = 0;
        const uint8_t seq = g_seq++;
        g_quadros_n = 0;
        injetar_comando(seq, KECU_CMD_ARMAR, &desligar, 1);
        laco_agora(); /* no mesmo instante: a bobina ainda está carregando quando o comando é executado */
        laco_agora();
        CHECK(achar_resposta(seq) != NULL && achar_resposta(seq)->tipo == KECU_RSP_ACK, "ARMAR(0) respondido com ACK");
    }
    CHECK(g_kecu.armado == 0, "desarmou");
    CHECK(todas_saidas_desligadas(), "e todos os pinos foram para o nível desligado na hora");
    CHECK(alarme_ocioso() && kecu_sched_pendentes() == 0, "o alarme foi parado e a fila esvaziada");
    ini = mock_mudancas_n;
    mundo_por(10000);
    CHECK(mock_iac_duty == 0u, "a válvula volta a duty 0 em até um ciclo de controle (%u)", mock_iac_duty);
    mundo_por(400000);
    CHECK(mock_mudancas_n == ini, "desarmada, com o motor girando: nenhuma mudança de pino em 400 ms (%d)", mock_mudancas_n - ini);

    CHECK(armar(1), "ARMAR(1) de novo");
    mundo_por(300000);
    CHECK(mock_mudancas_n - ini > 50, "armar de novo retoma os pulsos (%d)", mock_mudancas_n - ini);
    CHECK(mock_iac_duty > 0u, "e a válvula");

    /* o motor para com a ECU armada */
    roda_parar();
    mundo_por(500000);
    CHECK(todas_saidas_desligadas(), "motor parou: nenhum pino fica ligado");
    CHECK(kecu_sched_pendentes() == 0 && alarme_ocioso(), "agendador vazio, alarme parado");
    CHECK(kecu_sched_forcados() == 0, "sem precisar do vigia");
    CHECK(mock_secao_desequilibrio == 0 && g_secao_vazou == 0, "seções críticas equilibradas");
    CHECK(mock_escritas_fora_de_secao == g_base_escritas && mock_alarme_fora_de_secao == g_base_alarme,
          "inclusive o desarme pelo protocolo mexe nos pinos e no alarme dentro de seção crítica (%d escritas e %d alarmes fora)",
          mock_escritas_fora_de_secao - g_base_escritas, mock_alarme_fora_de_secao - g_base_alarme);
}

/* O alarme do timer se perde de vez em quando: a rede de proteção do laço principal tem de cobrir. */
static void teste_alarme_perdido(void) {
    secao("Saídas: alarme do timer perdido (1 a cada 4) e alarme vencido que não dispara");
    const double rpm = 3000.0;
    ligar_placa_nova();
    roda_iniciar(rpm);
    mundo_por(1500000);
    CHECK(armar(1), "ARMAR(1)");
    mundo_por(600000);
    int ini = mock_mudancas_n;
    mundo_por(1000000);
    const medida_t normal = medir(ini, rpm, 0.0, (double)g_kecu.avanco_graus, 0.0, 0.0, 3.0, 0.0);

    mock_alarme_perde_a_cada = 4;
    mundo_por(300000);
    ini = mock_mudancas_n;
    mundo_por(1000000);
    const medida_t perdendo = medir(ini, rpm, 0.0, (double)g_kecu.avanco_graus, 0.0, 0.0, 3.0, 0.0);
    CHECK(perdendo.max_ligado_us <= normal.max_ligado_us + 600u,
          "alarmes perdidos: nenhum pulso fica ligado a mais que o normal + 0,6 ms (%llu us contra %llu us)",
          (unsigned long long)perdendo.max_ligado_us, (unsigned long long)normal.max_ligado_us);
    CHECK(perdendo.faltas_de_fagulha >= 95, "alarmes perdidos: ainda saem as centelhas (%d em 1 s)", perdendo.faltas_de_fagulha);
    CHECK(perdendo.max_err_fagulha < 8.0, "alarmes perdidos: erro máximo da centelha %.2f graus (a rede de proteção, não o timer, executou algumas bordas)",
          perdendo.max_err_fagulha);
    mock_alarme_perde_a_cada = 0;
    roda_parar();
    mundo_por(300000);
    CHECK(todas_saidas_desligadas() && kecu_sched_pendentes() == 0, "motor parou: tudo desligado depois de alarmes perdidos");
    CHECK(mock_secao_desequilibrio == 0, "seções críticas equilibradas");

    /* hardware que NUNCA dispara um alarme já vencido (pior caso): programar sempre à frente evita o problema */
    ligar_placa_nova();
    mock_alarme_passado_dispara = 0;
    mock_isr_custo_us = 12;
    roda_iniciar(rpm);
    mundo_por(1500000);
    CHECK(armar(1), "ARMAR(1) (alarme vencido não dispara)");
    mundo_por(600000);
    ini = mock_mudancas_n;
    mundo_por(1000000);
    const medida_t m = medir(ini, rpm, 0.0, (double)g_kecu.avanco_graus, 0.0, 0.0, 15.0, 15.0);
    CHECK(m.faltas_de_fagulha >= 97 && m.max_err_fagulha < 1.0, "alarme vencido não dispara: %d centelhas, erro máximo %.3f graus", m.faltas_de_fagulha,
          m.max_err_fagulha);
    CHECK(mock_alarmes_no_passado == 0, "e nenhum alarme foi escrito no passado (%d)", mock_alarmes_no_passado);
    CHECK(m.max_ligado_us < 8000u, "nenhum pulso fica ligado por mais de 8 ms (%llu us)", (unsigned long long)m.max_ligado_us);
}

/* O contador de 32 bits de hal_micros() dá a volta (a cada 71 min) com a ECU armada. */
static void teste_volta_32_bits(void) {
    secao("Saídas: hal_micros() dá a volta dos 32 bits com a ECU armada");
    const double rpm = 3000.0;
    ligar_placa_nova();
    mock_timer_definir_contador(4294967296ull - 1900000ull); /* 1,9 s antes da volta */
    kecu_sched_init();
    kecu_init();
    roda_iniciar(rpm);
    mundo_por(900000);
    CHECK(g_kecu.sync == 1, "sincronizado antes da volta");
    CHECK(armar(1), "ARMAR(1)");
    mundo_por(600000); /* contador a 0,4 s da volta */
    const uint64_t antes = mock_timer_contador();
    const uint32_t ms_antes = hal_millis();
    const int ini = mock_mudancas_n;
    mundo_por(1000000);
    CHECK(antes < 4294967296ull && mock_timer_contador() > 4294967296ull, "a janela de medição cruza a volta dos 32 bits");
    CHECK(hal_millis() - ms_antes >= 999u && hal_millis() - ms_antes <= 1001u,
          "hal_millis() não sofre a volta do contador de microssegundos (passaram %u ms em 1 s)", hal_millis() - ms_antes);
    const double morto = 900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v);
    const double inj_esperado = ((double)g_kecu.pw_us - morto) / 2.0 + morto;
    const double dwell_esperado = (double)g_kecu.params.v[P_DWELL_US] * 14.0 / (double)g_kecu.vbat_v;
    const medida_t m = medir(ini, rpm, 0.0, (double)g_kecu.avanco_graus, dwell_esperado, inj_esperado, 3.0, 0.0);
    CHECK(m.faltas_de_fagulha >= 97 && m.max_err_fagulha < 0.6, "%d centelhas, erro máximo %.3f graus", m.faltas_de_fagulha, m.max_err_fagulha);
    CHECK(m.dwell_errado == 0 && m.inj_largura_errada == 0, "larguras certas dos dois lados da volta (%d / %d fora)", m.dwell_errado, m.inj_largura_errada);
    CHECK(kecu_sched_descartados() == 0 && kecu_sched_forcados() == 0, "sem descartes nem saídas forçadas");
    CHECK(mock_alarmes_no_passado == 0, "nenhum alarme no passado (%d)", mock_alarmes_no_passado);
    roda_parar();
    mundo_por(300000);
    CHECK(todas_saidas_desligadas(), "motor parou: tudo desligado");
}

/* Pino de bico/bobina alterado só pelo caminho certo: nenhum outro pino é escrito. */
static void teste_pinos_nao_usados(void) {
    secao("Saídas: nenhum outro pino é escrito");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    mundo_por(1500000);
    armar(1);
    mundo_por(1000000);
    int estranhos = 0;
    for (int p = 0; p < MOCK_PINOS; p++) {
        if (saida_do_pino(p) >= 0) {
            continue;
        }
        if (mock_pino_escritas[p] != 0) {
            estranhos++;
            printf("    pino %d recebeu %d escritas\n", p, mock_pino_escritas[p]);
        }
    }
    CHECK(estranhos == 0, "só os pinos de bico e bobina recebem digitalWrite (%d pinos estranhos)", estranhos);
    roda_parar();
}
#endif

/* ================================================================== arma-ao-ligar */

#if KECU_ENABLE_OUTPUTS && KECU_AUTO_ARMAR
static void teste_auto_armar(void) {
    secao("Arma ao ligar (KECU_AUTO_ARMAR): roda sem o notebook");
    ligar_placa_nova();
    CHECK(g_kecu.armado == 1, "armada logo depois do setup()");
    mundo_por(100000);
    CHECK(mock_mudancas_n == 0, "motor parado: nenhum pino mexe");
    roda_iniciar(3000.0);
    mundo_por(1500000);
    CHECK(mock_mudancas_n > 200, "sem nenhum ARMAR, os pulsos saem (%d mudanças)", mock_mudancas_n);
    CHECK(g_kecu.armado == 1 && mock_iac_duty > 0u, "válvula de marcha lenta ativa (duty %u)", mock_iac_duty);
    CHECK(armar(0) && g_kecu.armado == 0, "o PC consegue desarmar");
    CHECK(todas_saidas_desligadas(), "e tudo desliga");
    const int n = mock_mudancas_n;
    mundo_por(300000);
    CHECK(mock_mudancas_n == n, "desarmada, nada mexe");
    roda_parar();
    reiniciar_placa();
    CHECK(g_kecu.armado == 1, "depois de um reset, arma de novo sozinha");
}
#endif

int main(void) {
    printf("Teste do código do ESP32 contra o simulador do Arduino-ESP32\n");
#if defined(MOCK_ESP_V3)
    printf("API do Arduino-ESP32: 3.x (simulada)");
#else
    printf("API do Arduino-ESP32: 2.0.x (simulada)");
#endif
    printf(", saídas %s, %d bobina(s), saída ativa em nível %s, arma ao ligar %s\n\n", KECU_ENABLE_OUTPUTS ? "HABILITADAS" : "desabilitadas (modo sombra)",
           KECU_BOBINAS, KECU_SAIDA_ATIVA_ALTO ? "alto" : "baixo", KECU_AUTO_ARMAR ? "sim" : "não");
#if KECU_ENABLE_OUTPUTS && KECU_AUTO_ARMAR
    teste_arranque();
    teste_auto_armar();
#else
    teste_arranque();
    teste_hello();
    teste_sensores();
    teste_chaves();
    teste_rotacao();
    teste_nvs();
    teste_serial();
    teste_serial_cao_de_guarda_esp32();
    teste_serial_fifo();
#if KECU_ENABLE_OUTPUTS
    teste_saidas_normal();
    teste_isr_lenta();
    teste_ancora();
    teste_alarme_margem();
    teste_iac_e_arme();
    teste_alarme_perdido();
    teste_volta_32_bits();
    teste_pinos_nao_usados();
#else
    teste_sombra();
#endif
#endif
    printf("\n%d verificações OK, %d falhas\n", g_ok, g_falhas);
    return g_falhas == 0 ? 0 : 1;
}
