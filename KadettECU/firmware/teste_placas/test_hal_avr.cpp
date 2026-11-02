/*
 * test_hal_avr.cpp - testa o código da placa Arduino Mega 2560 (src/hal_avr.cpp + src/main.cpp) no PC.
 *
 * O que roda de verdade: o núcleo em C (lib/kecu_core), o agendador de bordas (lib/kecu_sched), a HAL do
 * Mega e o main.cpp, SEM alteração. O que é simulado (mock/avr): os registradores do Timer1 (contador de 16
 * bits a 2 MHz, estouro, comparador A), a PORTA, o bit I do SREG (o AVR não aninha interrupções), a EEPROM
 * (3,3 ms por byte gravado), o cão de guarda, o ADC de 10 bits (que arrasta a carga do canal anterior) e a
 * serial com buffers de 256/128 bytes. A roda fônica 60-2 é desenhada pelo harness_placa.h e entregue à
 * interrupção do pino do sensor.
 *
 * Isto prova que o código da placa faz o que deve DADO o comportamento dos registradores como o simulador os
 * modela. NÃO prova que o ATmega2560 de verdade se comporta igual, NÃO mede o tempo que o avr-gcc leva para
 * executar o planejador dentro da interrupção do dente (aqui ele é um parâmetro: mock_isr_custo_us), e não
 * substitui o teste em bancada.
 *
 * Compila em variantes (veja teste_placas/Makefile): modo sombra, saídas habilitadas, duas bobinas, saída
 * ativa em nível baixo e arma-ao-ligar.
 */
#include "harness_placa.h"

#include "hal_board.h"

/* O mesmo padrão de hal_avr.cpp: o cão de guarda vem ligado junto com as saídas. */
#ifndef KECU_AVR_WDT
#define KECU_AVR_WDT KECU_ENABLE_OUTPUTS
#endif
#define AMOSTRAS_ADC 1                                        /* KECU_ADC_AMOSTRAS de hal_avr.cpp */
#define CONVERSOES_POR_RODADA (KECU_ADC_CANAIS * (1 + AMOSTRAS_ADC)) /* a 1ª de cada canal é descartada */
#define US_POR_CONVERSAO 112                                  /* analogRead: 13 ciclos do ADC a 125 kHz + sobrecarga */
#define ALARME_MIN_TICKS 8                                    /* KECU_ALARME_MIN_TICKS de hal_avr.cpp */

static int comparador_ocioso(void) {
    return !mock_timer1_comparador_ligado();
}

/* Avança o mundo, com o laço principal rodando uma vez por segundo, até hal_micros() chegar a "alvo" (módulo 2^32). */
SO_EM_ALGUMAS_VARIANTES static void pular_ate_hal_micros(uint32_t alvo) {
    const uint64_t fim = mock_tempo_us + (uint64_t)(uint32_t)(alvo - hal_micros());
    while (mock_tempo_us < fim) {
        const uint64_t falta = fim - mock_tempo_us;
        mock_correr_ate(mock_tempo_us + (falta > 1000000u ? 1000000u : falta));
        laco_agora();
    }
}

/* ================================================================== 1) arranque */

static void teste_arranque(void) {
    secao("Arranque: Timer1, serial, pinos, interrupções, cão de guarda");
    ligar_placa_nova();
    CHECK(mock_timer1_divisor() == 8u, "Timer1 com prescaler 8 = 2 MHz, 0,5 us por tick (divisor %u)", mock_timer1_divisor());
    CHECK(mock_timer1_modo_normal(), "Timer1 em modo normal (o Arduino o deixa em PWM de 8 bits)");
    CHECK(!mock_timer1_comparador_ligado(), "o comparador A só liga quando há borda agendada");
    CHECK(hal_micros() == 1000u, "o relógio de microssegundos começa em 1000, não em zero (%u)", hal_micros());
    mock_tempo_para(mock_tempo_us + 250u);
    CHECK(hal_micros() == 1250u, "e anda junto com o tempo (%u)", hal_micros());
    CHECK(hal_millis() == (uint32_t)(mock_tempo_us / 1000u), "hal_millis vem do Timer0 do Arduino (%u)", hal_millis());
    CHECK(mock_serial_iniciada && mock_serial_baud == 115200u, "serial a 115200 baud (%u)", mock_serial_baud);
    CHECK(mock_serial_rx_tam == 256u && mock_serial_tx_tam == 128u,
          "buffers da serial de 256/128 bytes (os -D do platformio.ini): %u/%u", (unsigned)mock_serial_rx_tam, (unsigned)mock_serial_tx_tam);
#if KECU_AVR_WDT
    CHECK(mock_wdt_ativo && mock_wdt_timeout_ms == 2000u, "cão de guarda de 2 s ligado junto com as saídas (%u ms)", mock_wdt_timeout_ms);
#else
    CHECK(!mock_wdt_ativo, "modo sombra: cão de guarda desligado");
#endif
    CHECK(mock_pino_modo[PIN_TWO_STEP] == (uint8_t)(0x80 | INPUT_PULLUP) && mock_pino_modo[PIN_LAUNCH] == (uint8_t)(0x80 | INPUT_PULLUP),
          "chaves two-step e launch com pull-up interno");
    CHECK(mock_pino_modo[PIN_CKP] == (uint8_t)(0x80 | INPUT) && mock_pino_modo[PIN_VSS] == (uint8_t)(0x80 | INPUT),
          "pinos de rotação e velocidade como entrada");
    CHECK(mock_isr_pino[PIN_CKP] != NULL && mock_isr_modo[PIN_CKP] == (KECU_CKP_BORDA_SUBIDA ? RISING : FALLING),
          "interrupção do dente no pino %d, na borda configurada", PIN_CKP);
    CHECK(mock_isr_pino[PIN_VSS] != NULL && mock_isr_modo[PIN_VSS] == RISING, "interrupção do VSS no pino %d", PIN_VSS);
    CHECK(kecu_sched_pendentes() == 0 && kecu_sched_descartados() == 0, "agendador vazio");
    CHECK(mock_secao_nivel == 0 && mock_secao_desequilibrio == 0, "interrupções ligadas depois do setup(), nenhuma religada dentro de interrupção");
    CHECK(mock_mudancas_n == 0, "nenhum pino saiu do nível desligado durante o arranque (%d mudanças)", mock_mudancas_n);
    CHECK(mock_pullup_conflitos == 0, "o pull-up interno nunca brigou com o resistor externo do estágio de potência");
#if !KECU_ENABLE_OUTPUTS
    CHECK(mock_porta_escritas == 0 && mock_ddra_escritas == 0, "modo sombra: PORTA e DDRA nunca são escritos (%d / %d)", mock_porta_escritas,
          mock_ddra_escritas);
    CHECK(mock_iac_escritas == 0 && mock_pino_pinmodes[PIN_IAC] == 0 && mock_pino_escritas[PIN_IAC] == 0 && (uint8_t)TCCR2B == _BV(CS22),
          "modo sombra: o pino da válvula de marcha lenta e o Timer2 ficam como o Arduino deixou");
#else
    int ordem_ok = 1;
    for (int i = 0; i < KECU_SAIDAS; i++) {
        const int p = 22 + i;
        /* ativo em alto: o nível desligado (0) já era o do registrador, só a direção muda ("D"); ativo em baixo: "h" antes de "D" */
        const char *esperado = KECU_SAIDA_ATIVA_ALTO ? "D" : "hD";
        if (strcmp(mock_pino_historia[p], esperado) != 0) {
            ordem_ok = 0;
            printf("    pino %d: história \"%s\" (esperado \"%s\")\n", p, mock_pino_historia[p], esperado);
        }
    }
    CHECK(ordem_ok, "saídas habilitadas: o nível desligado vai para a PORTA ANTES de a direção virar saída");
    CHECK((uint8_t)DDRA == MASCARA_SAIDAS && (uint8_t)PORTA == (KECU_SAIDA_ATIVA_ALTO ? 0 : MASCARA_SAIDAS),
          "só os 6 bits de saída viram saída (DDRA 0x%02X, PORTA 0x%02X)", (unsigned)(uint8_t)DDRA, (unsigned)(uint8_t)PORTA);
    CHECK(mock_pino_historia[28][0] == 0 && mock_pino_historia[29][0] == 0, "os pinos 28 e 29 da PORTA não são tocados");
    CHECK(mock_pino_modo[PIN_IAC] == (uint8_t)(0x80 | OUTPUT) && mock_iac_pino == PIN_IAC && mock_iac_duty == 0u && mock_iac_escritas >= 1
              && ((uint8_t)TCCR2B & 7) == (_BV(CS22) | _BV(CS20)),
          "válvula de marcha lenta: Timer2 com prescaler 128 (245 Hz), duty 0, no pino %d", PIN_IAC);
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
    CHECK(r->d[3] == 3, "placa = 3 (Arduino AVR), veio %u", r->d[3]);
    CHECK(caps == caps_esperadas, "capacidades 0x%04X (esperado 0x%04X)", caps, caps_esperadas);
}

/* ================================================================== 3) relógio do Timer1 */

static void teste_relogio(void) {
    secao("Relógio do Timer1: estouros, leitura com interrupções desligadas, volta dos 32 bits");
    ligar_placa_nova();

    /* 1) 300 mil passos de tamanho irregular (1 a 90 us): hal_micros() tem de bater com o tempo do mundo em TODOS */
    const uint64_t t0 = mock_tempo_us;
    const uint32_t m0 = hal_micros();
    uint32_t semente = 12345u;
    int errados = 0;
    for (int i = 0; i < 300000; i++) {
        semente = semente * 1664525u + 1013904223u;
        mock_correr_ate(mock_tempo_us + 1u + (semente >> 16) % 90u);
        if (hal_micros() != (uint32_t)(m0 + (uint32_t)(mock_tempo_us - t0))) {
            errados++;
        }
    }
    CHECK(errados == 0, "hal_micros() acompanha o tempo em 300 mil leituras, atravessando muitos estouros (%d erradas)", errados);
    const uint64_t decorrido = mock_tempo_us - t0;
    CHECK((uint64_t)mock_ovf_disparos == (2000u + 2u * decorrido) / 65536u,
          "a interrupção de estouro rodou uma vez por volta do contador de 16 bits (%d voltas em %llu us)", mock_ovf_disparos,
          (unsigned long long)decorrido);

    /* 2) o contador dá a volta com as interrupções desligadas: o aviso fica pendente e hal_micros() continua certo */
    {
        const uint16_t tc = mock_timer1_contador();
        mock_correr_ate(mock_tempo_us + (65536u - tc) / 2u - 10u); /* 10 us antes da volta */
        cli();
        const uint32_t a = hal_micros();
        const int ovf0 = mock_ovf_disparos;
        mock_correr_ate(mock_tempo_us + 30u); /* atravessa a volta com o bit I desligado */
        CHECK(hal_micros() - a == 30u, "atravessando a volta com as interrupções desligadas: +30 us (veio +%u)", hal_micros() - a);
        CHECK(mock_ovf_disparos == ovf0, "o aviso de estouro ficou pendente");
        mock_correr_ate(mock_tempo_us + 20u);
        CHECK(hal_micros() - a == 50u, "e continua certo 20 us depois: +50 us (veio +%u)", hal_micros() - a);
        sei();
        CHECK(mock_ovf_disparos == ovf0 + 1, "ao religar as interrupções a volta é contada uma vez");
        CHECK(hal_micros() - a == 50u, "e o relógio não pula (+%u)", hal_micros() - a);
    }

    /* 3) a volta dos 32 bits (a cada 71,6 min), com o laço principal rodando uma vez por segundo */
    pular_ate_hal_micros(0u - 2500000u); /* 2,5 s antes da volta */
    const uint32_t antes = hal_micros();
    const uint64_t t_ref = mock_tempo_us;
    CHECK(antes > 0xFF000000u, "pulou para perto da volta dos 32 bits (hal_micros = %u)", antes);
    int errados2 = 0;
    for (int i = 0; i < 4000; i++) {
        mock_correr_ate(mock_tempo_us + 1000u);
        if (hal_micros() != (uint32_t)(antes + (uint32_t)(mock_tempo_us - t_ref))) {
            errados2++;
        }
    }
    CHECK(errados2 == 0, "hal_micros() atravessa a volta dos 32 bits sem pular (%d erradas em 4 s)", errados2);
    CHECK(hal_micros() < 2000000u, "e deu a volta de verdade: agora %u", hal_micros());
    CHECK(mock_secao_nivel == 0 && mock_secao_desequilibrio == 0, "interrupções ligadas no fim");
}

/* Avança o mundo até "us_antes" microssegundos antes da próxima volta do contador de 16 bits (cada volta dura 32,768 ms). */
static void ir_para_antes_da_volta(uint32_t us_antes) {
    uint32_t falta = (65536u - (uint32_t)mock_timer1_contador()) / 2u;
    if (falta <= us_antes) {
        falta += 32768u; /* já passou desse ponto: vai para a volta seguinte */
    }
    mock_correr_ate(mock_tempo_us + falta - us_antes);
}

/*
 * A corrida clássica do contador de 16 bits: ele dá a volta ENTRE a leitura de TCNT1 e a do resto do relógio (a contagem de
 * voltas e o aviso TOV1). Aqui cada leitura de TCNT1 é seguida de 2 us (mock_tcnt1_custo_us): uma volta que cai nessa janela
 * tem de ser contada UMA vez, nem zero nem duas, com as interrupções ligadas ou desligadas no código de quem chama.
 */
static void teste_relogio_corrida(void) {
    secao("Relógio do Timer1: o contador dá a volta no meio da leitura (corrida entre TCNT1 e o estouro)");
    ligar_placa_nova();
    mundo_por(1000);
    const uint64_t t0 = mock_tempo_us;
    const uint32_t m0 = hal_micros(); /* hal_micros(t) = m0 + (t - t0) */
    int errados_micros = 0;
    int errados_alarme = 0;
    int casos = 0;
    for (int volta = 0; volta < 3; volta++) {
        for (int ligadas = 1; ligadas >= 0; ligadas--) {
            for (uint32_t d = 1; d <= 4; d++) {
                /* 1) hal_micros() chamado d us antes da volta do contador */
                {
                    ir_para_antes_da_volta(d);
                    if (!ligadas) {
                        cli();
                    }
                    mock_tcnt1_custo_us = 2;
                    const uint64_t t_chamada = mock_tempo_us;
                    const uint32_t h = hal_micros();
                    mock_tcnt1_custo_us = 0;
                    if (h != m0 + (uint32_t)(t_chamada - t0)) {
                        errados_micros++;
                        printf("    hal_micros a %u us da volta (interrupções %s): +%d us de erro\n", d, ligadas ? "ligadas" : "desligadas", (int)(h - (m0 + (uint32_t)(t_chamada - t0))));
                    }
                    if (!ligadas) {
                        sei();
                    }
                }
                /* 2) hal_timer_armar() pedindo um alarme para daqui a 100 us, também d us antes da volta */
                {
                    ir_para_antes_da_volta(d);
                    if (!ligadas) {
                        cli();
                    }
                    const uint32_t alvo = m0 + (uint32_t)(mock_tempo_us - t0) + 100u;
                    mock_tcnt1_custo_us = 2;
                    hal_timer_armar(alvo);
                    mock_tcnt1_custo_us = 0;
                    const uint32_t m_agora = m0 + (uint32_t)(mock_tempo_us - t0);
                    const int a_frente = (int)(uint16_t)((uint16_t)OCR1A - mock_timer1_contador());
                    const int esperado = 2 * (int)(alvo - m_agora);
                    if (abs(a_frente - esperado) > 1) {
                        errados_alarme++;
                        printf("    hal_timer_armar a %u us da volta (interrupções %s): alarme a %d ticks (esperado %d)\n", d, ligadas ? "ligadas" : "desligadas", a_frente, esperado);
                    }
                    hal_timer_parar();
                    if (!ligadas) {
                        sei();
                    }
                }
                casos++;
            }
        }
    }
    CHECK(errados_micros == 0, "hal_micros() certo nos %d casos em que a volta cai dentro da leitura (%d erros)", casos, errados_micros);
    CHECK(errados_alarme == 0, "hal_timer_armar() programa o alarme no instante certo nos %d casos (%d erros)", casos, errados_alarme);

    /* o contador deu a volta com as interrupções desligadas (por exemplo, dentro da interrupção do dente): o aviso TOV1 fica pendente
     * e a volta ainda não foi contada. hal_timer_armar() tem de somar essa volta, senão acha que é 32,8 ms mais cedo e atrasa o alarme. */
    int errados_pendente = 0;
    int casos_pendentes = 0;
    for (uint32_t depois = 1; depois <= 40; depois += 13) {
        ir_para_antes_da_volta(5);
        cli();
        mock_correr_ate(mock_tempo_us + 5u + depois); /* atravessa a volta com o bit I desligado */
        const uint32_t alvo = m0 + (uint32_t)(mock_tempo_us - t0) + 100u;
        hal_timer_armar(alvo);
        const int a_frente = (int)(uint16_t)((uint16_t)OCR1A - mock_timer1_contador());
        if (abs(a_frente - 200) > 1) {
            errados_pendente++;
            printf("    hal_timer_armar com a volta pendente (%u us depois dela): alarme a %d ticks (esperado 200)\n", depois, a_frente);
        }
        hal_timer_parar();
        sei();
        casos_pendentes++;
    }
    CHECK(errados_pendente == 0, "hal_timer_armar() soma a volta pendente do contador nos %d casos (%d erros)", casos_pendentes, errados_pendente);
    CHECK(mock_secao_nivel == 0 && mock_secao_desequilibrio == 0, "interrupções ligadas no fim e nenhuma religada dentro de interrupção");
    CHECK(hal_micros() - m0 == (uint32_t)(mock_tempo_us - t0), "e o relógio continua certo depois de tudo (%u)", hal_micros() - m0);
}

/* hal_timer_armar não pode escrever no passado nem quando o tempo passa entre as duas leituras do contador. */
static void teste_alarme_leitura_fresca(void) {
    secao("Comparador A: a leitura fresca do contador impede escrever no passado");
    ligar_placa_nova();
    /* 40 ms: o contador passa por OCR1A (0) e o aviso OCF1A fica "velho" no registrador, como no chip, mesmo com a interrupção desligada */
    mundo_por(40000);
    CHECK((uint8_t)TIFR1 & _BV(OCF1A), "(o aviso do comparador está velho, esperando alguém apagá-lo)");
    /* uma "interrupção de pino" de teste pede o alarme para daqui a 6 us e a 3ª leitura do contador (a fresca) custa 50 us */
    static uint64_t t_armou = 0;
    static void (*const pede_alarme)(void) = [] {
        hal_timer_armar(hal_micros() + 6u);
        t_armou = mock_tempo_us; /* o instante em que OCR1A foi escrito */
    };
    mock_isr_pino[5] = pede_alarme;
    mock_isr_custo_us = 50;
    mock_isr_leitura_do_custo = 3;
    mock_alarme_margem_min_ticks = INT32_MAX;
    mock_alarmes_no_passado = 0;
    mock_alarmes_disparados = 0;
    mock_pulso_pino(5);
    CHECK(mock_alarmes_no_passado == 0 && mock_alarme_margem_min_ticks >= ALARME_MIN_TICKS,
          "mesmo com 50 us gastos entre a conta e a escrita, o alarme fica a no mínimo %d ticks do contador (margem %d, %d no passado)",
          ALARME_MIN_TICKS, mock_alarme_margem_min_ticks, mock_alarmes_no_passado);
    mundo_por(100);
    const long long atraso_disparo = (long long)mock_alarme_disparo_us - (long long)t_armou;
    CHECK(mock_alarmes_disparados == 1 && atraso_disparo >= 3 && atraso_disparo <= 8,
          "e o alarme dispara na hora, 4 us depois de escrito (%d disparos, %lld us depois): não 32 ms depois, nem logo por causa de um aviso velho",
          mock_alarmes_disparados, atraso_disparo);
    mock_isr_pino[5] = NULL;
    mock_isr_custo_us = 0;
    mock_isr_leitura_do_custo = 2;

    /* uma borda que venceu há 20 ms (a interrupção do dente demorou demais): o alarme sai para já, não para daqui a ~13 ms */
    mock_alarmes_disparados = 0;
    hal_timer_armar(hal_micros() - 20000u);
    mundo_por(30);
    CHECK(mock_alarmes_disparados == 1, "borda vencida há 20 ms: o alarme dispara em até 30 us (%d disparos), não uns 13 ms depois", mock_alarmes_disparados);

    hal_timer_parar();
    CHECK(comparador_ocioso(), "hal_timer_parar desliga a interrupção do comparador");

    /* chamadas diretas, com as interrupções ligadas no código principal: as duas funções fazem a própria seção crítica */
    const int fora_antes = mock_alarme_fora_de_secao;
    hal_timer_armar(hal_micros() + 1000u);
    hal_timer_parar();
    CHECK(mock_alarme_fora_de_secao == fora_antes, "hal_timer_armar e hal_timer_parar protegem a si mesmas (%d acessos fora de seção)",
          mock_alarme_fora_de_secao - fora_antes);
    CHECK(comparador_ocioso() && mock_secao_nivel == 0 && mock_secao_desequilibrio == 0, "e deixam as interrupções como estavam");
}

/* ================================================================== 4) seção crítica */

static void teste_secoes(void) {
    secao("Seção crítica aninhada: o bit I volta a ser o que era");
    ligar_placa_nova();
    CHECK(mock_secao_nivel == 0, "interrupções ligadas no início");
    hal_secao_entrar();
    CHECK(mock_secao_nivel == 1, "dentro da seção as interrupções ficam desligadas");
    hal_secao_entrar();
    hal_secao_sair();
    CHECK(mock_secao_nivel == 1, "fechou a seção interna: a externa continua aberta");
    hal_secao_sair();
    CHECK(mock_secao_nivel == 0, "fechou a externa: as interrupções voltam");
    hal_secao_sair();
    CHECK(mock_secao_nivel == 0 && mock_secao_desequilibrio == 0, "sair a mais não liga nem desliga nada");
    cli(); /* como dentro de uma interrupção: quem chamou já estava com as interrupções desligadas */
    hal_secao_entrar();
    hal_secao_sair();
    CHECK(mock_secao_nivel == 1, "se já estavam desligadas, continuam desligadas depois da seção");
    sei();
    CHECK(mock_secao_nivel == 0 && mock_secao_desequilibrio == 0, "religou ao final");
}

/* ================================================================== 5) sensores */

static void teste_sensores(void) {
    secao("Sensores: o ADC de 10 bits do Mega vira grandeza física");
    ligar_placa_nova();
    sensores_definir(90.0, 25.0, 55.0, 50.0, 13.8, 5.0, 1.0);
    harness_zerar();
    reiniciar_placa(); /* a primeira leitura já parte do valor medido */
    mundo_por(30000);
    CHECK(fabs((double)g_kecu.ect_c - 90.0) < 0.8, "temperatura da água 90 °C: leu %.2f", (double)g_kecu.ect_c);
    CHECK(fabs((double)g_kecu.iat_c - 25.0) < 0.8, "temperatura do ar 25 °C: leu %.2f", (double)g_kecu.iat_c);
    CHECK(fabs((double)g_kecu.map_kpa - 55.0) < 0.5, "MAP 55 kPa: leu %.2f", (double)g_kecu.map_kpa);
    CHECK(fabs((double)g_kecu.tps_pct - 50.0) < 0.4, "TPS 50 %%: leu %.2f", (double)g_kecu.tps_pct);
    CHECK(fabs((double)g_kecu.vbat_v - 13.8) < 0.05, "bateria 13,8 V: leu %.3f", (double)g_kecu.vbat_v);
    CHECK(fabs((double)g_kecu.oleo_bar - 5.0) < 0.05, "óleo 5 bar: leu %.3f", (double)g_kecu.oleo_bar);
    CHECK(fabs((double)g_kecu.lambda - 1.0) < 0.01, "lambda 1,00: leu %.4f", (double)g_kecu.lambda);
    CHECK(g_kecu.erros == 0, "sensores saudáveis: nenhum erro (0x%04X)", g_kecu.erros);
    CHECK(mock_adc_leituras > 0 && mock_adc_leituras % CONVERSOES_POR_RODADA == 0,
          "cada canal faz 1 conversão descartada + %d de verdade (%d conversões)", AMOSTRAS_ADC, mock_adc_leituras);
    const int ciclos = mock_adc_leituras / CONVERSOES_POR_RODADA;
    CHECK(ciclos >= 12 && ciclos <= 17, "sensores lidos a cada 2 ms: %d rodadas em 30 ms", ciclos);
    CHECK(CONVERSOES_POR_RODADA * US_POR_CONVERSAO <= 2000, "uma rodada de sensores bloqueia o laço por ~%d us (14 conversões de ~112 us), cabendo nos 2 ms",
          CONVERSOES_POR_RODADA * US_POR_CONVERSAO);

    /* o mesmo valor aparece na telemetria */
    const uint8_t periodo[2] = {20, 0};
    CHECK(e_ack(comando(KECU_CMD_STREAM, periodo, 2)), "STREAM 20 ms aceito");
    mundo_por(100000);
    CHECK(g_tele_tem && g_tele_n >= 4 && g_tele_n <= 6, "telemetria a cada 20 ms: %d quadros em 100 ms", g_tele_n);
    if (g_tele_tem) {
        const int ect10 = (int16_t)(g_tele[8] | (g_tele[9] << 8));
        const int map10 = g_tele[12] | (g_tele[13] << 8);
        const int tps10 = g_tele[16] | (g_tele[17] << 8);
        CHECK(abs(ect10 - 900) <= 8 && abs(map10 - 550) <= 5 && abs(tps10 - 500) <= 4, "telemetria: água %.1f °C, MAP %.1f kPa, TPS %.1f %%",
              ect10 / 10.0, map10 / 10.0, tps10 / 10.0);
        const uint32_t t_ms = (uint32_t)(g_tele[0] | (g_tele[1] << 8) | (g_tele[2] << 16) | ((uint32_t)g_tele[3] << 24));
        CHECK(t_ms + 40u >= hal_millis() && t_ms <= hal_millis(), "carimbo de tempo da telemetria = hal_millis (%u, agora %u)", t_ms, hal_millis());
    }
    CHECK(g_crc_ruins == 0 && g_bytes_lixo == 0, "todos os quadros chegaram íntegros (%d CRC ruins, %d bytes soltos)", g_crc_ruins, g_bytes_lixo);
    CHECK(mock_serial_bloqueios == 0 && g_max_laco_us == 0, "e a serial nunca travou o laço (%d bloqueios)", mock_serial_bloqueios);

    /* falhas de sensor */
    mock_adc_mv[k_pino_adc[KECU_ADC_MAP]] = 0;                  /* fio solto */
    mock_adc_mv[k_pino_adc[KECU_ADC_TPS]] = pino_mv_5v(5000.0); /* em curto com +5 V */
    mock_adc_mv[k_pino_adc[KECU_ADC_ECT]] = KECU_VREF_MV;       /* NTC solto: o pull-up leva ao máximo */
    mock_adc_mv[k_pino_adc[KECU_ADC_LAMBDA]] = 0;
    mock_adc_mv[k_pino_adc[KECU_ADC_VBAT]] = 400; /* 1,2 V na bateria: fora de 6..18 V */
    mundo_por(20000);
    const uint16_t esperados = KECU_ERR_MAP | KECU_ERR_TPS | KECU_ERR_ECT | KECU_ERR_LAMBDA | KECU_ERR_BATERIA;
    CHECK((g_kecu.erros & esperados) == esperados, "falhas detectadas: erros 0x%04X (esperado ao menos 0x%04X)", g_kecu.erros, esperados);
    CHECK(g_kecu.tps_pct < 1.0f, "TPS em curto para +5 V não vira 100 %% de acelerador (%.1f)", (double)g_kecu.tps_pct);
    sensores_saudaveis();
    mundo_por(20000);
    CHECK((g_kecu.erros & esperados) == 0, "sensores de volta: erros somem (0x%04X)", g_kecu.erros);

    /* a referência do ADC é o AVcc: se ele cair para 4,8 V as leituras absolutas sobem ~4 % (por isso KECU_VREF_MV se mede) */
    mock_avcc_mv = 4800;
    mundo_por(400000);
    CHECK(g_kecu.vbat_v > 13.8f * 1.03f && g_kecu.vbat_v < 13.8f * 1.05f, "AVcc de 4,8 V: a bateria aparece ~4 %% acima (%.2f V): meça o 5 V e ajuste KECU_VREF_MV", (double)g_kecu.vbat_v);
    mock_avcc_mv = 5000;
}

/* ================================================================== 6) memória (EEPROM) */

static void teste_nvm(void) {
    secao("EEPROM: gravar (3 s), reiniciar, corromper, falhar, cão de guarda");
    ligar_placa_nova();
    CHECK(!(g_kecu.erros & KECU_ERR_NVM), "EEPROM vazia na primeira vez: usa os padrões, sem alarme");
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500, "limite de rotação padrão 6500");

    const quadro_t *r = comando(KECU_CMD_LER_PARAMS, NULL, 0);
    CHECK(r != NULL && r->tipo == KECU_RSP_PARAMS && r->n == KECU_PARAMS_BYTES, "LER_PARAMS devolve %d bytes", KECU_PARAMS_BYTES);
    if (r == NULL || r->tipo != KECU_RSP_PARAMS) {
        return;
    }
    uint8_t params[KECU_PARAMS_BYTES];
    memcpy(params, r->d, sizeof params);
    params[0] = 7000 & 0xFF;
    params[1] = 7000 >> 8;
    CHECK(e_ack(comando(KECU_CMD_ESCREVER_PARAMS, params, KECU_PARAMS_BYTES)), "ESCREVER_PARAMS aceito");
    uint8_t linha[2 + KECU_N];
    linha[0] = 0;
    linha[1] = 3;
    for (int i = 0; i < KECU_N; i++) {
        linha[2 + i] = (uint8_t)(40 + i * 7);
    }
    CHECK(e_ack(comando(KECU_CMD_ESCREVER_LINHA, linha, (uint8_t)(2 + KECU_N))), "ESCREVER_LINHA aceito");
    CHECK(mock_eeprom_gravacoes == 0, "escrever na RAM não grava na EEPROM");

    g_limite_comando_us = 10000000u; /* a gravação de um bloco novo leva ~3 s */
    const uint64_t t_gravar = mock_tempo_us;
    CHECK(e_ack(comando(KECU_CMD_GRAVAR, NULL, 0)), "GRAVAR aceito");
    const uint64_t duracao = mock_tempo_us - t_gravar;
    CHECK(mock_eeprom_usado() == 889u && mock_eeprom_gravacoes > 500 && mock_eeprom_gravacoes <= 889,
          "um bloco de 889 bytes; só os bytes que mudam são gravados (%d de 889; EEPROM usada até %u)", mock_eeprom_gravacoes,
          (unsigned)mock_eeprom_usado());
    CHECK(duracao >= (uint64_t)mock_eeprom_gravacoes * 3300u && duracao < (uint64_t)mock_eeprom_gravacoes * 3300u + 200000u,
          "a gravação levou %.2f s (%d bytes x 3,3 ms): o laço principal fica parado esse tempo", (double)duracao / 1e6, mock_eeprom_gravacoes);
#if KECU_AVR_WDT
    CHECK(duracao > 2000000u && mock_wdt_estouros == 0 && mock_wdt_max_intervalo_us < 100000u,
          "a gravação passa de 2 s e o cão de guarda NÃO reinicia a placa (alimentado a cada byte; maior intervalo %llu us)",
          (unsigned long long)mock_wdt_max_intervalo_us);
#endif

    reiniciar_placa();
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 7000, "depois do reset o limite de rotação continua 7000 (%u)", g_kecu.params.v[P_REV_LIMITE_RPM]);
    CHECK(memcmp(g_kecu.mapas.m[0][3], &linha[2], KECU_N) == 0, "e a linha do mapa também");
    CHECK(!(g_kecu.erros & KECU_ERR_NVM), "sem alarme de memória");
    r = comando(KECU_CMD_LER_LINHA, linha, 2);
    CHECK(r != NULL && r->tipo == KECU_RSP_LINHA && r->n == 2 + KECU_N && memcmp(&r->d[2], &linha[2], KECU_N) == 0, "LER_LINHA devolve o que foi gravado");

    /* gravar o mesmo bloco de novo não toca na EEPROM (poupa as ~100 mil gravações de vida de cada byte) */
    const int antes = mock_eeprom_gravacoes;
    CHECK(e_ack(comando(KECU_CMD_GRAVAR, NULL, 0)) && mock_eeprom_gravacoes == antes, "gravar o mesmo bloco de novo não grava nenhum byte (%d novos)",
          mock_eeprom_gravacoes - antes);

    /* pedidos que não cabem nos 4096 bytes da EEPROM (ou negativos) são recusados, sem ler nem gravar fora dela */
    {
        static uint8_t grande[8192];
        const int gravacoes_antes = mock_eeprom_gravacoes;
        CHECK(hal_nvm_ler(grande, 5000) != 0 && hal_nvm_gravar(grande, 5000) != 0, "pedido de 5000 bytes: recusado (a EEPROM tem 4096)");
        CHECK(hal_nvm_ler(grande, -1) != 0 && hal_nvm_gravar(grande, -1) != 0, "tamanho negativo: recusado");
        CHECK(mock_eeprom_gravacoes == gravacoes_antes, "e nenhum byte foi gravado");
    }

    mock_eeprom_corromper(100);
    reiniciar_placa();
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500, "EEPROM corrompida: volta aos padrões (%u)", g_kecu.params.v[P_REV_LIMITE_RPM]);
    CHECK(g_kecu.erros & KECU_ERR_NVM, "e acende o alarme de memória");
    mundo_por(20000);
    CHECK(g_kecu.erros & KECU_ERR_NVM, "o alarme não some sozinho com a leitura dos sensores");

    mock_eeprom_falhar = 1;
    CHECK(e_nack(comando(KECU_CMD_GRAVAR, NULL, 0), KECU_NACK_MEMORIA), "EEPROM recusa a gravação: NACK 5 (a leitura de volta não confere)");
    mock_eeprom_falhar = 0;
    CHECK(e_ack(comando(KECU_CMD_GRAVAR, NULL, 0)), "gravação normal volta a funcionar");
    mundo_por(20000);
    CHECK(!(g_kecu.erros & KECU_ERR_NVM), "e apaga o alarme de memória");
    g_limite_comando_us = 60000u;

    roda_iniciar(2000.0);
    mundo_por(800000);
    const int gravacoes = mock_eeprom_gravacoes;
    CHECK(g_kecu.motor_girando == 1, "motor girando para o teste");
    CHECK(e_nack(comando(KECU_CMD_GRAVAR, NULL, 0), KECU_NACK_MOTOR_GIRANDO), "GRAVAR com o motor girando: NACK 4");
    CHECK(mock_eeprom_gravacoes == gravacoes, "e a EEPROM não foi tocada");
    roda_parar();
    mundo_por(400000);

    ligar_placa_nova(); /* EEPROM apagada */
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500 && !(g_kecu.erros & KECU_ERR_NVM),
          "EEPROM apagada: padrões, sem alarme, sem restos da gravação anterior (limite %u)", g_kecu.params.v[P_REV_LIMITE_RPM]);
}

/* A serial do AVR: o laço principal alimenta o cão de guarda (se ligado) em TODA volta, e só escreve o que cabe. */
static void teste_serial_avr(void) {
#if KECU_AVR_WDT
    CHECK(mock_wdt_resets >= g_lacos && mock_wdt_estouros == 0, "o cão de guarda foi alimentado em cada volta do laço (%d alimentações, %d voltas, %d estouros)",
          mock_wdt_resets, g_lacos, mock_wdt_estouros);
#else
    CHECK(mock_wdt_resets == 0 && !mock_wdt_ativo, "modo sombra: o laço não mexe no cão de guarda (%d chamadas)", mock_wdt_resets);
#endif
    CHECK(mock_serial_rx_perdidos > 0, "a rajada de lixo maior que o buffer de 256 bytes perdeu bytes (%d), e mesmo assim o HELLO foi respondido",
          mock_serial_rx_perdidos);
}

/* ================================================================== modo sombra */

#if !KECU_ENABLE_OUTPUTS
static void teste_sombra(void) {
    secao("Modo sombra: motor girando, nenhum pino de saída mexe, ARMAR é recusado");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    mundo_por(2000000);
    CHECK(g_kecu.motor_girando == 1 && g_kecu.eventos_ignicao > 100, "motor girando, eventos planejados (%u)", g_kecu.eventos_ignicao);
    CHECK(mock_mudancas_n == 0, "nenhuma mudança de nível em pino algum (%d)", mock_mudancas_n);
    CHECK(mock_alarmes_programados == 0 && mock_alarmes_disparados == 0 && comparador_ocioso(),
          "o comparador do Timer1 nunca foi programado (%d programados, %d disparos)", mock_alarmes_programados, mock_alarmes_disparados);
    CHECK(kecu_sched_pendentes() == 0, "agendador vazio");
    CHECK(mock_porta_escritas == 0 && mock_ddra_escritas == 0, "PORTA e DDRA seguem intocados");
    CHECK(mock_iac_escritas == 0 && mock_pino_pinmodes[PIN_IAC] == 0 && (uint8_t)TCCR2B == _BV(CS22), "a válvula de marcha lenta e o Timer2 seguem intocados");
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
    const double tol = (double)lat_alarme_us + (double)custo_isr_us + 4.0;
    const medida_t m = medir(ini, rpm, 0.0, adv, dwell_esperado, inj_esperado, tol, (double)custo_isr_us);
    const double voltas = rpm / 60.0;
    CHECK(fabs((double)m.faltas_de_fagulha - 2.0 * voltas) <= 3.0, "%s: %d centelhas em 1 s (esperado %.0f)", rotulo, m.faltas_de_fagulha, 2.0 * voltas);
    CHECK(fabs((double)m.inj_eventos - voltas) <= 2.0, "%s: %d injeções em 1 s (esperado %.0f)", rotulo, m.inj_eventos, voltas);
    CHECK(m.max_err_fagulha < 0.6, "%s: centelha em PMS - %.1f graus, erro máximo %.3f graus", rotulo, adv, m.max_err_fagulha);
    CHECK(m.max_err_inj < 0.6 + (double)custo_isr_us / (60e6 / rpm) * 360.0, "%s: início da injeção no dente de referência, erro %.3f graus", rotulo,
          m.max_err_inj);
    CHECK(m.bobina_errada == 0, "%s: cada centelha na bobina certa (%d erradas)", rotulo, m.bobina_errada);
    CHECK(m.inj_dessincronizados == 0, "%s: os 4 bicos abrem juntos (%d diferenças)", rotulo, m.inj_dessincronizados);
    CHECK(m.dwell_errado == 0, "%s: tempo de carga da bobina %.0f us (%d fora)", rotulo, dwell_esperado, m.dwell_errado);
    CHECK(m.inj_largura_errada == 0, "%s: largura da injeção %.0f us (%d fora)", rotulo, inj_esperado, m.inj_largura_errada);
    CHECK(m.pino_estranho == 0, "%s: só os 6 pinos de saída mudam (%d mudanças em outros)", rotulo, m.pino_estranho);
    CHECK(kecu_sched_descartados() == 0 && kecu_sched_forcados() == 0, "%s: sem descartes nem saídas forçadas (%u / %u)", rotulo,
          kecu_sched_descartados(), kecu_sched_forcados());
    CHECK(mock_alarmes_no_passado == 0 && mock_alarme_margem_min_ticks >= ALARME_MIN_TICKS,
          "%s: o alarme nunca é escrito a menos de %d ticks do contador (menor margem %d ticks, %d no passado)", rotulo, ALARME_MIN_TICKS,
          mock_alarme_margem_min_ticks, mock_alarmes_no_passado);
    CHECK(mock_secao_desequilibrio == 0 && mock_secao_nivel == 0 && g_secao_vazou == 0, "%s: o bit I nunca fica desligado nem é religado dentro de interrupção", rotulo);
    CHECK(mock_escritas_fora_de_secao == g_base_escritas && mock_alarme_fora_de_secao == g_base_alarme,
          "%s: toda escrita na PORTA e todo alarme é feito com as interrupções desligadas (%d escritas e %d alarmes fora)", rotulo,
          mock_escritas_fora_de_secao - g_base_escritas, mock_alarme_fora_de_secao - g_base_alarme);
    CHECK(mock_pullup_conflitos == 0, "%s: sem briga de pull-up", rotulo);
    CHECK(mock_wdt_resets >= g_lacos && mock_wdt_estouros == 0, "%s: cão de guarda alimentado em cada volta", rotulo);
    if (custo_isr_us > 0 && m.dwell_encurtado > 0) {
        printf("    %s: %d de %d centelhas carregaram até %u us menos (a interrupção do dente atrasou o início)\n", rotulo, m.dwell_encurtado,
               m.faltas_de_fagulha, custo_isr_us);
    }
    return m;
}

static void teste_saidas_normal(void) {
    secao("Saídas: rotação 60-2 nos pinos do sensor, ângulo, largura e pino de cada pulso");
    char rotulo[48];
    static const double rpms[] = {800.0, 2000.0, 3000.0};
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

/* O AVR de 16 MHz divide por software (o planejador faz uma dezena ou mais de divisões de 32 bits por dente): a interrupção do dente pode demorar dezenas a centenas de us (NÃO medido). */
static void teste_isr_lenta(void) {
    secao("Saídas: interrupção do dente lenta (estilo AVR) e alarme com 3 us de atraso");
    static const double rpms[] = {800.0, 2000.0, 3000.0};
    static const uint32_t custos[] = {40, 120, 200};
    char rotulo[64];
    for (size_t i = 0; i < sizeof rpms / sizeof rpms[0]; i++) {
        snprintf(rotulo, sizeof rotulo, "%.0f rpm, ISR %u us", rpms[i], custos[i]);
        medir_armado(rpms[i], custos[i], 3, rotulo);
    }
}

/* A âncora no instante do dente mantém a centelha no ângulo mesmo com a interrupção durando 300 us a 800 rpm. */
static void teste_ancora(void) {
    secao("Saídas: a âncora do dente mantém a centelha no ângulo com a interrupção lenta (300 us a 800 rpm)");
    const medida_t m = medir_armado(800.0, 300, 3, "800 rpm, ISR 300 us");
    CHECK(m.fagulhas_acima_06 * 10 <= m.faltas_de_fagulha, "no máximo 10 %% das centelhas fora de 0,6 grau (%d de %d)", m.fagulhas_acima_06,
          m.faltas_de_fagulha);
}

/* hal_timer_armar nunca programa o alarme a menos de 8 ticks (4 us) do contador, mesmo para uma borda que vence em 1 us. */
static void teste_alarme_margem(void) {
    secao("Saídas: o alarme nunca é programado a menos de 4 us à frente, nem para bordas vencidas");
    static const uint32_t atrasos[] = {0, 1, 2, 3, 5, 40};
    for (size_t i = 0; i < sizeof atrasos / sizeof atrasos[0]; i++) {
        ligar_placa_nova();
        mundo_por(1000);
        mock_alarme_margem_min_ticks = INT32_MAX;
        const int ini = mock_mudancas_n;
        const uint64_t t0 = mock_tempo_us;
        kecu_sched_pulso(KECU_SAIDA_INJ1, atrasos[i], 500);
        CHECK(mock_alarme_margem_min_ticks >= ALARME_MIN_TICKS, "borda a %u us: o alarme fica a no mínimo %d ticks do contador (margem %d)", atrasos[i],
              ALARME_MIN_TICKS, mock_alarme_margem_min_ticks);
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
        CHECK(liga_us >= (int)atrasos[i] && liga_us <= (int)atrasos[i] + 5, "o bico liga a %u us (a +5 us no máximo): ligou a %d us", atrasos[i], liga_us);
        CHECK(desliga_us >= (int)atrasos[i] + 500 && desliga_us <= (int)atrasos[i] + 505, "e desliga a %u us: desligou a %d us", atrasos[i] + 500, desliga_us);
        CHECK(todas_saidas_desligadas() && kecu_sched_pendentes() == 0, "no fim tudo desligado");
    }
}

/* Esperas maiores que meia volta do contador de 16 bits: o alarme acorda a cada 16 ms e reprograma. */
static void teste_espera_longa(void) {
    secao("Saídas: espera de 100 ms (o contador de 16 bits dá a volta a cada 32,8 ms)");
    ligar_placa_nova();
    mundo_por(1000);
    const int ini = mock_mudancas_n;
    const uint64_t t0 = mock_tempo_us;
    mock_alarmes_disparados = 0;
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100000, 2000);
    mundo_por(120000);
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
    CHECK(liga_us >= 100000 && liga_us <= 100005, "o bico liga a 100000 us: ligou a %d us", liga_us);
    CHECK(desliga_us >= 102000 && desliga_us <= 102005, "e desliga a 102000 us: desligou a %d us", desliga_us);
    CHECK(mock_alarmes_disparados >= 6 && mock_alarmes_disparados <= 9, "o alarme acordou em passos de até 16 ms (%d disparos)", mock_alarmes_disparados);
    CHECK(todas_saidas_desligadas() && kecu_sched_pendentes() == 0 && comparador_ocioso(), "no fim tudo desligado e o comparador parado");
}

/* Cada bit da PORTA é independente: pulsos de saídas diferentes que se sobrepõem no tempo não se atrapalham. */
static void teste_saidas_sobrepostas(void) {
    secao("Saídas: pulsos de saídas diferentes que se sobrepõem no tempo não se atrapalham");
    ligar_placa_nova();
    mundo_por(1000);
    const int ini = mock_mudancas_n;
    const uint64_t t0 = mock_tempo_us;
    /* bico 1: 1000 a 4000 us; bico 2: 1500 a 6000; bobina 1: 2000 a 5000; bico 3: 3000 a 3500 (tudo ligado ao mesmo tempo entre 3000 e 3500) */
    kecu_sched_pulso(KECU_SAIDA_INJ1, 1000, 3000);
    kecu_sched_pulso(KECU_SAIDA_INJ2, 1500, 4500);
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 2000, 3000);
    kecu_sched_pulso(KECU_SAIDA_INJ3, 3000, 500);
    mundo_por(10000);
    struct esperado_t {
        kecu_saida_t saida;
        int liga_us;
        int desliga_us;
    };
    static const esperado_t esperados[] = {
        {KECU_SAIDA_INJ1, 1000, 4000}, {KECU_SAIDA_INJ2, 1500, 6000}, {KECU_SAIDA_BOBINA1, 2000, 5000}, {KECU_SAIDA_INJ3, 3000, 3500}};
    for (size_t k = 0; k < sizeof esperados / sizeof esperados[0]; k++) {
        int liga_us = -1;
        int desliga_us = -1;
        int transicoes = 0;
        for (int j = ini; j < mock_mudancas_n; j++) {
            if (mock_mudancas[j].pino != k_pino_saida[esperados[k].saida]) {
                continue;
            }
            transicoes++;
            if (mock_mudancas[j].nivel == NIVEL_LIGADO) {
                liga_us = (int)(mock_mudancas[j].t_us - t0);
            } else {
                desliga_us = (int)(mock_mudancas[j].t_us - t0);
            }
        }
        CHECK(transicoes == 2, "saída %d (pino %d): uma subida e uma descida (%d transições)", (int)esperados[k].saida, k_pino_saida[esperados[k].saida], transicoes);
        CHECK(liga_us >= esperados[k].liga_us && liga_us <= esperados[k].liga_us + 5, "liga a %d us (a +5 us no máximo): ligou a %d us", esperados[k].liga_us,
              liga_us);
        CHECK(desliga_us >= esperados[k].desliga_us && desliga_us <= esperados[k].desliga_us + 5, "desliga a %d us: desligou a %d us", esperados[k].desliga_us,
              desliga_us);
    }
    CHECK(todas_saidas_desligadas() && kecu_sched_pendentes() == 0 && comparador_ocioso(), "no fim tudo desligado e o comparador parado");
}

static void teste_iac_e_arme(void) {
    secao("Válvula de marcha lenta e ARMAR/desarmar: nada se move desarmada");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    mundo_por(1500000);
    CHECK(g_kecu.motor_girando == 1 && g_kecu.armado == 0, "motor girando, ECU desarmada");
    CHECK(mock_mudancas_n == 0, "desarmada: nenhum pino de saída mexe (%d mudanças)", mock_mudancas_n);
    CHECK(mock_alarmes_programados == 0 && mock_alarmes_disparados == 0 && comparador_ocioso(),
          "desarmada: o comparador do Timer1 nunca foi programado (%d programados, %d disparos)", mock_alarmes_programados, mock_alarmes_disparados);
    CHECK(g_kecu.eventos_ignicao > 50, "mas a ECU planeja os eventos (%u)", g_kecu.eventos_ignicao);
    CHECK(g_kecu.iac_pct > 1.0f && mock_iac_duty == 0u && mock_iac_escritas > 1,
          "desarmada: a ECU calcula a válvula (%.1f %%) mas o duty do pino fica em 0 (%u)", (double)g_kecu.iac_pct, mock_iac_duty);

    CHECK(armar(1) && g_kecu.armado == 1, "ARMAR(1) aceito");
    mundo_por(600000);
    const uint32_t duty_esperado = (uint32_t)((double)g_kecu.iac_pct * 255.0 / 100.0 + 0.5);
    CHECK(mock_iac_duty > 0 && (mock_iac_duty + 1 >= duty_esperado) && (mock_iac_duty <= duty_esperado + 1),
          "armada: duty da válvula = %.1f %% de 255 = %u (veio %u)", (double)g_kecu.iac_pct, duty_esperado, mock_iac_duty);
    int ini = mock_mudancas_n;
    mundo_por(300000);
    CHECK(mock_mudancas_n - ini > 100, "armada: os pinos passam a mexer (%d mudanças em 300 ms)", mock_mudancas_n - ini);

    /* a conversão de percentual para duty de 8 bits, chamada direto (sem tempo passar, o núcleo não a sobrescreve) */
    hal_iac_pwm(150.0f);
    CHECK(mock_iac_duty == 255u, "válvula: 150 %% é limitado a 100 %% (duty %u)", mock_iac_duty);
    hal_iac_pwm(-20.0f);
    CHECK(mock_iac_duty == 0u, "válvula: percentual negativo vira 0 (duty %u)", mock_iac_duty);
    hal_iac_pwm(50.0f);
    CHECK(mock_iac_duty == 128u, "válvula: 50 %% = 128 de 255 (duty %u)", mock_iac_duty);
    CHECK(mock_iac_pino == PIN_IAC, "no pino %d (veio %d)", PIN_IAC, mock_iac_pino);

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
        /* O desarme é imediato. A UART do Mega leva 87 us por byte, então o ACK de 8 bytes só termina de sair ~0,7 ms depois. */
        CHECK(g_kecu.armado == 0, "desarmou");
        CHECK(todas_saidas_desligadas(), "e todos os pinos foram para o nível desligado na hora (antes de o ACK sair pela UART)");
        mundo_por(3000);
        CHECK(achar_resposta(seq) != NULL && achar_resposta(seq)->tipo == KECU_RSP_ACK, "ARMAR(0) respondido com ACK");
    }
    CHECK(todas_saidas_desligadas(), "e continua tudo desligado depois do ACK");
    CHECK(comparador_ocioso() && kecu_sched_pendentes() == 0, "o comparador foi parado e a fila esvaziada");
    ini = mock_mudancas_n;
    mundo_por(10000);
    CHECK(mock_iac_duty == 0u, "a válvula volta a duty 0 em até um ciclo de controle (%u)", mock_iac_duty);
    mundo_por(400000);
    CHECK(mock_mudancas_n == ini, "desarmada, com o motor girando: nenhuma mudança de pino em 400 ms (%d)", mock_mudancas_n - ini);

    CHECK(armar(1), "ARMAR(1) de novo");
    mundo_por(300000);
    CHECK(mock_mudancas_n - ini > 50, "armar de novo retoma os pulsos (%d)", mock_mudancas_n - ini);
    CHECK(mock_iac_duty > 0u, "e a válvula");

    roda_parar(); /* o motor para com a ECU armada */
    mundo_por(500000);
    CHECK(todas_saidas_desligadas(), "motor parou: nenhum pino fica ligado");
    CHECK(kecu_sched_pendentes() == 0 && comparador_ocioso(), "agendador vazio, comparador parado");
    CHECK(kecu_sched_forcados() == 0, "sem precisar do vigia");
    CHECK(mock_secao_desequilibrio == 0 && g_secao_vazou == 0, "o bit I nunca ficou desligado");
    CHECK(mock_escritas_fora_de_secao == g_base_escritas && mock_alarme_fora_de_secao == g_base_alarme,
          "inclusive o desarme pelo protocolo mexe na PORTA e no alarme com as interrupções desligadas (%d escritas e %d alarmes fora)",
          mock_escritas_fora_de_secao - g_base_escritas, mock_alarme_fora_de_secao - g_base_alarme);
}

/* O casamento do comparador se perde de vez em quando: a rede de proteção do laço principal tem de cobrir. */
static void teste_alarme_perdido(void) {
    secao("Saídas: casamento do comparador perdido (1 a cada 4)");
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
          "alarmes perdidos: nenhum pulso fica ligado a mais que o normal + 0,6 ms (%llu us contra %llu us)", (unsigned long long)perdendo.max_ligado_us,
          (unsigned long long)normal.max_ligado_us);
    CHECK(perdendo.faltas_de_fagulha >= 95, "alarmes perdidos: ainda saem as centelhas (%d em 1 s)", perdendo.faltas_de_fagulha);
    CHECK(perdendo.max_err_fagulha < 8.0, "alarmes perdidos: erro máximo da centelha %.2f graus (a rede de proteção executou algumas bordas)",
          perdendo.max_err_fagulha);
    mock_alarme_perde_a_cada = 0;
    roda_parar();
    mundo_por(300000);
    CHECK(todas_saidas_desligadas() && kecu_sched_pendentes() == 0, "motor parou: tudo desligado depois de alarmes perdidos");
    CHECK(mock_alarmes_no_passado == 0, "nenhum alarme foi escrito no passado (o AVR só dispara se o contador PASSAR por OCR1A): %d", mock_alarmes_no_passado);
}

/* O contador de 32 bits de hal_micros() dá a volta (a cada 71 min) com a ECU armada e o motor girando. */
static void teste_volta_32_bits(void) {
    secao("Saídas: hal_micros() dá a volta dos 32 bits com a ECU armada");
    const double rpm = 3000.0;
    ligar_placa_nova();
    pular_ate_hal_micros(0u - 1900000u); /* 1,9 s antes da volta */
    roda_iniciar(rpm);
    mundo_por(900000);
    CHECK(g_kecu.sync == 1, "sincronizado antes da volta");
    CHECK(armar(1), "ARMAR(1)");
    mundo_por(600000); /* 0,4 s antes da volta */
    const uint32_t h0 = hal_micros();
    const int ini = mock_mudancas_n;
    mundo_por(1000000);
    CHECK(hal_micros() < h0, "a janela de medição cruza a volta dos 32 bits (%u -> %u)", h0, hal_micros());
    const double morto = 900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v);
    const double inj_esperado = ((double)g_kecu.pw_us - morto) / 2.0 + morto;
    const double dwell_esperado = (double)g_kecu.params.v[P_DWELL_US] * 14.0 / (double)g_kecu.vbat_v;
    const medida_t m = medir(ini, rpm, 0.0, (double)g_kecu.avanco_graus, dwell_esperado, inj_esperado, 4.0, 0.0);
    CHECK(m.faltas_de_fagulha >= 97 && m.max_err_fagulha < 0.6, "%d centelhas, erro máximo %.3f graus", m.faltas_de_fagulha, m.max_err_fagulha);
    CHECK(m.dwell_errado == 0 && m.inj_largura_errada == 0, "larguras certas dos dois lados da volta (%d / %d fora)", m.dwell_errado, m.inj_largura_errada);
    CHECK(kecu_sched_descartados() == 0 && kecu_sched_forcados() == 0, "sem descartes nem saídas forçadas");
    CHECK(mock_alarmes_no_passado == 0, "nenhum alarme no passado (%d)", mock_alarmes_no_passado);
    roda_parar();
    mundo_por(300000);
    CHECK(todas_saidas_desligadas(), "motor parou: tudo desligado");
}

/* Só os bits certos da PORTA e só o pino da válvula: nenhum outro pino é escrito. */
static void teste_pinos_nao_usados(void) {
    secao("Saídas: nenhum outro pino é escrito");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    mundo_por(1500000);
    armar(1);
    mundo_por(1000000);
    int estranhos = 0;
    for (int p = 0; p < MOCK_PINOS; p++) {
        if (p == PIN_IAC) {
            continue; /* a válvula: digitalWrite(LOW) uma vez no arranque */
        }
        if (mock_pino_escritas[p] != 0) {
            estranhos++;
            printf("    pino %d recebeu %d escritas\n", p, mock_pino_escritas[p]);
        }
    }
    CHECK(estranhos == 0, "nenhum digitalWrite fora do pino da válvula (%d pinos estranhos)", estranhos);
    CHECK(mock_pino_historia[28][0] == 0 && mock_pino_historia[29][0] == 0 && ((uint8_t)DDRA & 0xC0) == 0, "os bits 6 e 7 da PORTA seguem intocados");
    roda_parar();
}

/*
 * A interrupção do dente demora MAIS que o intervalo entre dentes (6000 rpm = 167 us por dente; ISR de 250 us): o AVR
 * perde dentes e a sincronia. O que importa aqui é a segurança: nenhuma saída pode ficar presa ligada.
 */
static void teste_sobrecarga(void) {
    secao("Saídas: interrupção do dente mais lenta que o intervalo entre dentes (AVR sobrecarregado)");
    ligar_placa_nova();
    mock_isr_custo_us = 250;
    roda_iniciar(6000.0);
    mundo_por(1500000);
    armar(1);
    const int ini = mock_mudancas_n;
    mundo_por(2000000);
    const medida_t m = medir(ini, 6000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    printf("    (informativo) perdas de sincronia: %u, centelhas: %d, injeções: %d, sync %d\n", g_kecu.perdas_sync, m.faltas_de_fagulha, m.inj_eventos, g_kecu.sync);
    CHECK(m.max_ligado_us <= 60000u + 5000u + 1000u, "nenhuma saída fica ligada além do limite do agendador (maior: %llu us)", (unsigned long long)m.max_ligado_us);
    CHECK(mock_secao_nivel == 0 && g_secao_vazou == 0 && mock_secao_desequilibrio == 0, "o bit I nunca ficou desligado");
    roda_parar();
    mock_isr_custo_us = 0;
    mundo_por(500000);
    CHECK(todas_saidas_desligadas() && kecu_sched_pendentes() == 0, "motor parou: tudo desligado e a fila vazia");
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
    printf("Teste do código do Arduino Mega 2560 (AVR) contra o simulador dos registradores\n");
    printf("saídas %s, %d bobina(s), saída ativa em nível %s, arma ao ligar %s, cão de guarda %s\n\n",
           KECU_ENABLE_OUTPUTS ? "HABILITADAS" : "desabilitadas (modo sombra)", KECU_BOBINAS, KECU_SAIDA_ATIVA_ALTO ? "alto" : "baixo",
           KECU_AUTO_ARMAR ? "sim" : "não", KECU_AVR_WDT ? "ligado" : "desligado");
#if KECU_ENABLE_OUTPUTS && KECU_AUTO_ARMAR
    teste_arranque();
    teste_auto_armar();
#else
    teste_arranque();
    teste_hello();
    teste_relogio();
    teste_relogio_corrida();
    teste_alarme_leitura_fresca();
    teste_secoes();
    teste_sensores();
    teste_chaves();
    teste_rotacao();
    teste_nvm();
    teste_serial();
    teste_serial_avr();
    teste_serial_fifo();
#if KECU_ENABLE_OUTPUTS
    teste_alarme_margem();
    teste_espera_longa();
    teste_saidas_sobrepostas();
    teste_saidas_normal();
    teste_isr_lenta();
    teste_ancora();
    teste_iac_e_arme();
    teste_alarme_perdido();
    teste_volta_32_bits();
    teste_pinos_nao_usados();
    teste_sobrecarga();
#else
    teste_sombra();
#endif
#endif
    printf("\n%d verificações OK, %d falhas\n", g_ok, g_falhas);
    return g_falhas == 0 ? 0 : 1;
}
