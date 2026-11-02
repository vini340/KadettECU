/*
 * harness_placa.h - o roteiro de teste COMUM às placas (ESP32 e Arduino Mega): o mundo simulado (tempo, roda fônica 60-2,
 * pulsos de velocidade, laço principal), o PC que fala com a placa pela serial (quadros com CRC), as conversões
 * grandeza física -> tensão no pino dos sensores, ligar/reiniciar a placa e a medição do ângulo e da largura dos pulsos
 * nos pinos de saída. Cada teste de placa (test_hal_esp32.cpp, test_hal_avr.cpp) inclui este arquivo e acrescenta o
 * que é só dela. Usa a API de controle do simulador da placa (mock_esp32.h ou mock_avr.h, escolhido por
 * ARDUINO_ARCH_AVR); os dois simuladores têm os mesmos nomes para tudo que é usado aqui.
 */
#ifndef HARNESS_PLACA_H
#define HARNESS_PLACA_H

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "Arduino.h" /* só as constantes do simulador: INPUT, OUTPUT, INPUT_PULLUP, RISING... */
#include "kecu.h"
#include "kecu_proto.h"
#include "kecu_sched.h"
#if defined(ARDUINO_ARCH_AVR)
#include "mock_avr.h"
#else
#include "mock_esp32.h"
#endif
#include "pinagem.h"

void setup(void);
void loop(void);

/* Funções de teste que só certas variantes (modo sombra, saídas habilitadas, arma ao ligar) usam. */
#define SO_EM_ALGUMAS_VARIANTES __attribute__((unused))
#pragma GCC diagnostic ignored "-Wunused-function"

static int g_ok;
static int g_falhas;

#define CHECK(cond, ...)                                                                                       \
    do {                                                                                                       \
        if (cond) {                                                                                            \
            g_ok++;                                                                                            \
        } else {                                                                                               \
            g_falhas++;                                                                                        \
            printf("  FALHOU (linha %d): ", __LINE__);                                                         \
            printf(__VA_ARGS__);                                                                               \
            printf("\n");                                                                                      \
        }                                                                                                      \
    } while (0)

static void secao(const char *titulo) {
    printf("[%s]\n", titulo);
}

#define NIVEL_LIGADO (KECU_SAIDA_ATIVA_ALTO ? 1 : 0)
#define NIVEL_DESLIGADO (KECU_SAIDA_ATIVA_ALTO ? 0 : 1)

static const uint8_t k_pino_saida[KECU_SAIDAS] = PINOS_SAIDA;
static const uint8_t k_pino_adc[KECU_ADC_CANAIS] = PINOS_ADC;

static int saida_do_pino(int pino) {
    for (int i = 0; i < KECU_SAIDAS; i++) {
        if (k_pino_saida[i] == pino) {
            return i;
        }
    }
    return -1;
}

/* ================================================================== o mundo: tempo, roda, laço principal */

#define PERIODO_LACO_US 100u /* loop() é chamado a cada 100 us de tempo simulado */

typedef struct {
    double rpm; /* 0 = roda parada */
    double slot_us;
    double prox_us;
    int slot;
    double t0_us; /* instante do 1º dente depois da falha na 1ª volta: ângulo 0 do planejador */
    int tem_t0;
} roda_t;

static roda_t g_roda;
static double g_vss_periodo_us; /* 0 = sem sinal de velocidade */
static double g_vss_prox_us;
static uint64_t g_prox_laco_us;
static int g_lacos;       /* chamadas a loop() */
static int g_secao_vazou; /* vezes em que loop() terminou com seção crítica aberta */
static uint64_t g_max_laco_us; /* a volta de loop() que mais tempo simulado gastou (só passa tempo se a serial travar) */

/* ---- quadros que a placa manda para o PC ---- */
typedef struct {
    uint8_t tipo;
    uint8_t seq;
    uint8_t n;
    uint8_t d[KECU_PAYLOAD_MAX];
} quadro_t;

#define MAX_QUADROS 512
static quadro_t g_quadros[MAX_QUADROS]; /* respostas (tudo, menos telemetria) desde o último comando */
static int g_quadros_n;
static uint8_t g_fila[16384];
static int g_fila_n;
static int g_crc_ruins;
static int g_bytes_lixo;
static int g_tele_n;
static uint8_t g_tele[KECU_TELEMETRIA_BYTES];
static int g_tele_tem;

static void quadros_extrair(void) {
    for (;;) {
        int ini = 0;
        while (ini + 1 < g_fila_n && !(g_fila[ini] == KECU_SOF1 && g_fila[ini + 1] == KECU_SOF2)) {
            ini++;
        }
        if (ini + 1 >= g_fila_n) {
            const int manter = (g_fila_n > 0 && g_fila[g_fila_n - 1] == KECU_SOF1) ? 1 : 0;
            g_bytes_lixo += g_fila_n - manter;
            if (manter) {
                g_fila[0] = (uint8_t)KECU_SOF1;
            }
            g_fila_n = manter;
            return;
        }
        if (ini > 0) {
            g_bytes_lixo += ini;
            memmove(g_fila, g_fila + ini, (size_t)(g_fila_n - ini));
            g_fila_n -= ini;
        }
        if (g_fila_n < KECU_CABECALHO) {
            return;
        }
        const int len = g_fila[2];
        const int total = KECU_CABECALHO + len + KECU_TAM_CRC;
        if (g_fila_n < total) {
            return;
        }
        const uint16_t crc_rx = (uint16_t)(g_fila[total - 2] | (g_fila[total - 1] << 8));
        if (crc_rx == kecu_crc16(&g_fila[2], (uint16_t)(3 + len))) {
            if (g_fila[3] == KECU_RSP_TELEMETRIA && len == KECU_TELEMETRIA_BYTES) {
                memcpy(g_tele, &g_fila[KECU_CABECALHO], KECU_TELEMETRIA_BYTES);
                g_tele_tem = 1;
                g_tele_n++;
            } else if (g_quadros_n < MAX_QUADROS) {
                quadro_t *q = &g_quadros[g_quadros_n++];
                q->tipo = g_fila[3];
                q->seq = g_fila[4];
                q->n = (uint8_t)len;
                memcpy(q->d, &g_fila[KECU_CABECALHO], (size_t)len);
            }
            memmove(g_fila, g_fila + total, (size_t)(g_fila_n - total));
            g_fila_n -= total;
        } else {
            g_crc_ruins++;
            memmove(g_fila, g_fila + 1, (size_t)(g_fila_n - 1));
            g_fila_n--;
        }
    }
}

static void serial_coletar(void) {
    uint8_t tmp[512];
    int n;
    while ((n = mock_serial_coletar(tmp, (int)sizeof tmp)) > 0) {
        if (g_fila_n + n > (int)sizeof g_fila) {
            g_fila_n = 0; /* só acontece se o teste ficar muito tempo sem ler */
        }
        memcpy(g_fila + g_fila_n, tmp, (size_t)n);
        g_fila_n += n;
    }
    quadros_extrair();
}

/* Uma volta do laço principal da placa: loop() do main.cpp e a leitura do que ela mandou pela serial. */
static void laco_agora(void) {
    const uint64_t t0 = mock_tempo_us;
    loop();
    if (mock_tempo_us - t0 > g_max_laco_us) {
        g_max_laco_us = mock_tempo_us - t0;
    }
    g_lacos++;
    if (mock_secao_nivel != 0) {
        g_secao_vazou++;
    }
    serial_coletar();
}

static void dente(void) {
    if (g_roda.slot < 58) { /* roda 60-2: os slots 58 e 59 não têm dente */
        if (g_roda.slot == 0 && !g_roda.tem_t0) {
            g_roda.t0_us = g_roda.prox_us;
            g_roda.tem_t0 = 1;
        }
        mock_pulso_pino(PIN_CKP);
    }
    g_roda.slot = (g_roda.slot + 1) % 60;
    g_roda.prox_us += g_roda.slot_us;
}

/* Deixa o mundo correr até alvo_us: laço principal, dentes da roda, pulsos do VSS e alarme do timer. */
static void mundo_ate(uint64_t alvo_us) {
    int guarda = 0;
    while (mock_tempo_us < alvo_us && guarda++ < 100000000) {
        const uint64_t t_laco = g_prox_laco_us;
        const uint64_t t_dente = g_roda.rpm > 0.0 ? (uint64_t)(g_roda.prox_us + 0.5) : UINT64_MAX;
        const uint64_t t_vss = g_vss_periodo_us > 0.0 ? (uint64_t)(g_vss_prox_us + 0.5) : UINT64_MAX;
        uint64_t t = t_laco;
        int quem = 0;
        if (t_dente < t) {
            t = t_dente;
            quem = 1;
        }
        if (t_vss < t) {
            t = t_vss;
            quem = 2;
        }
        if (t > alvo_us) {
            mock_correr_ate(alvo_us);
            return;
        }
        mock_correr_ate(t);
        if (quem == 0) {
            laco_agora();
            g_prox_laco_us = (mock_tempo_us > t_laco ? mock_tempo_us : t_laco) + PERIODO_LACO_US;
        } else if (quem == 1) {
            dente();
        } else {
            mock_pulso_pino(PIN_VSS);
            g_vss_prox_us += g_vss_periodo_us;
        }
    }
}

static void mundo_por(uint64_t dur_us) {
    mundo_ate(mock_tempo_us + dur_us);
}

static void roda_iniciar(double rpm) {
    memset(&g_roda, 0, sizeof g_roda);
    g_roda.rpm = rpm;
    g_roda.slot_us = 1e6 / rpm; /* 60 slots por volta */
    g_roda.prox_us = (double)mock_tempo_us + g_roda.slot_us;
}

static void roda_parar(void) {
    g_roda.rpm = 0.0;
}

static void vss_iniciar(double kmh) {
    g_vss_periodo_us = 3.6e9 / ((double)g_kecu.params.v[P_VSS_PULSOS_KM] * kmh);
    g_vss_prox_us = (double)mock_tempo_us + g_vss_periodo_us;
}

/* ---- comandos pela serial ---- */
static uint8_t g_seq = 1;

static void injetar_comando(uint8_t seq, uint8_t tipo, const uint8_t *payload, uint8_t n) {
    uint8_t q[KECU_CABECALHO + KECU_PAYLOAD_MAX + KECU_TAM_CRC];
    const uint16_t tam = kecu_proto_montar(q, tipo, seq, payload, n);
    mock_serial_injetar(q, tam);
}

static const quadro_t *achar_resposta(uint8_t seq) {
    for (int i = g_quadros_n - 1; i >= 0; i--) {
        if (g_quadros[i].seq == seq) {
            return &g_quadros[i];
        }
    }
    return NULL;
}

static uint64_t g_limite_comando_us = 60000u; /* quanto tempo de mundo comando() espera pela resposta */

/* Manda um comando e deixa o mundo correr até a resposta chegar (por padrão, no máximo 60 ms). */
static const quadro_t *comando(uint8_t tipo, const uint8_t *payload, uint8_t n) {
    const uint8_t seq = g_seq++;
    g_quadros_n = 0;
    injetar_comando(seq, tipo, payload, n);
    const uint64_t limite = mock_tempo_us + g_limite_comando_us;
    while (mock_tempo_us < limite) {
        mundo_por(PERIODO_LACO_US);
        const quadro_t *r = achar_resposta(seq);
        if (r != NULL) {
            return r;
        }
    }
    return NULL;
}

static int e_ack(const quadro_t *r) {
    return r != NULL && r->tipo == KECU_RSP_ACK && r->n == 0;
}

static int e_nack(const quadro_t *r, uint8_t codigo) {
    return r != NULL && r->tipo == KECU_RSP_NACK && r->n == 1 && r->d[0] == codigo;
}

SO_EM_ALGUMAS_VARIANTES static int armar(uint8_t ligar) {
    return e_ack(comando(KECU_CMD_ARMAR, &ligar, 1)) ? 1 : 0;
}

/* ---- sensores: converte a grandeza física na tensão que o ADC vê no PINO (o inverso do que o núcleo faz) ---- */
static double linear(double x, double x1, double y1, double x2, double y2) {
    return y1 + (x - x1) * (y2 - y1) / (x2 - x1);
}

static uint32_t pino_mv_ntc(double celsius) {
    const double t1 = KECU_NTC_T1_C + 273.15;
    const double t2 = KECU_NTC_T2_C + 273.15;
    const double beta = log((double)KECU_NTC_R1_OHM / (double)KECU_NTC_R2_OHM) / (1.0 / t1 - 1.0 / t2);
    const double r = (double)KECU_NTC_R1_OHM * exp(beta * (1.0 / (celsius + 273.15) - 1.0 / t1));
    return (uint32_t)((double)KECU_VREF_MV * r / ((double)KECU_NTC_PULLUP_OHM + r) + 0.5);
}

static uint32_t pino_mv_5v(double sensor_mv) {
    return (uint32_t)(sensor_mv / (double)KECU_RAZAO_5V + 0.5);
}

static void sensores_definir(double ect, double iat, double map_kpa, double tps, double vbat, double oleo, double lambda) {
    mock_adc_mv[k_pino_adc[KECU_ADC_ECT]] = pino_mv_ntc(ect);
    mock_adc_mv[k_pino_adc[KECU_ADC_IAT]] = pino_mv_ntc(iat);
    mock_adc_mv[k_pino_adc[KECU_ADC_MAP]] = pino_mv_5v(linear(map_kpa, KECU_MAP_KPA1, KECU_MAP_V1_MV, KECU_MAP_KPA2, KECU_MAP_V2_MV));
    mock_adc_mv[k_pino_adc[KECU_ADC_TPS]] = pino_mv_5v(linear(tps, 0.0, KECU_TPS_V_FECHADO_MV, 100.0, KECU_TPS_V_ABERTO_MV));
    mock_adc_mv[k_pino_adc[KECU_ADC_VBAT]] = (uint32_t)(vbat * 1000.0 / (double)KECU_RAZAO_BATERIA + 0.5);
    mock_adc_mv[k_pino_adc[KECU_ADC_OLEO]] = pino_mv_5v(linear(oleo, KECU_OLEO_BAR1, KECU_OLEO_V1_MV, KECU_OLEO_BAR2, KECU_OLEO_V2_MV));
    mock_adc_mv[k_pino_adc[KECU_ADC_LAMBDA]] = pino_mv_5v(
        linear(lambda * (double)KECU_LAMBDA_AFR_REF, KECU_LAMBDA_AFR1, KECU_LAMBDA_V1_MV, KECU_LAMBDA_AFR2, KECU_LAMBDA_V2_MV));
}

static void sensores_saudaveis(void) {
    sensores_definir(90.0, 25.0, 60.0, 20.0, 13.8, 3.0, 1.0);
}

/* ---- ligar / reiniciar a placa ---- */
static void harness_zerar(void) {
    memset(&g_roda, 0, sizeof g_roda);
    g_vss_periodo_us = 0.0;
    g_quadros_n = 0;
    g_fila_n = 0;
    g_crc_ruins = 0;
    g_bytes_lixo = 0;
    g_tele_n = 0;
    g_tele_tem = 0;
    g_lacos = 0;
    g_secao_vazou = 0;
    g_max_laco_us = 0;
    g_prox_laco_us = mock_tempo_us;
}

/* Contadores de "fora de seção crítica" no fim do arranque (o arranque roda antes de haver interrupções, sem seção). */
static int g_base_escritas;
static int g_base_alarme;

static void setup_da_placa(void) {
    setup();
    g_base_escritas = mock_escritas_fora_de_secao;
    g_base_alarme = mock_alarme_fora_de_secao;
}

/* Placa nova: flash apagada, nenhum pino em nível especial, sensores saudáveis. */
static void ligar_placa_nova(void) {
    mock_reiniciar_tudo();
    /* o resistor externo do estágio de potência segura o pino solto no nível DESLIGADO (pull-down se ativo em alto, pull-up se em baixo) */
    mock_nivel_solto = (uint8_t)NIVEL_DESLIGADO;
    mock_reiniciar_placa();
    mock_tempo_para(300000u); /* a placa gasta uns 300 ms (bootloader, init) até chegar ao setup() */
    sensores_saudaveis();
    harness_zerar();
    setup_da_placa();
}

/* Aperta RESET: tudo volta ao início, menos a flash. */
static void reiniciar_placa(void) {
    mock_reiniciar_placa();
    mock_tempo_para(mock_tempo_us + 300000u);
    harness_zerar();
    setup_da_placa();
}

SO_EM_ALGUMAS_VARIANTES static int todas_saidas_desligadas(void) {
    for (int i = 0; i < KECU_SAIDAS; i++) {
        if (mock_pino_nivel[k_pino_saida[i]] != NIVEL_DESLIGADO) {
            return 0;
        }
    }
    return 1;
}

SO_EM_ALGUMAS_VARIANTES static double ang_mod(double a) {
    const double r = fmod(a, 360.0);
    return r < 0.0 ? r + 360.0 : r;
}

SO_EM_ALGUMAS_VARIANTES static double ang_dif(double a, double b) {
    const double d = ang_mod(a - b);
    return d > 180.0 ? d - 360.0 : d;
}

/* ================================================================== medição dos pulsos nos pinos */

typedef struct {
    int faltas_de_fagulha; /* centelhas (desligamentos de bobina) medidas */
    int por_alvo[2];
    int bobina_errada;
    int dwell_errado;
    int dwell_encurtado;
    int fagulhas_acima_06;  /* centelhas com erro de ângulo acima de 0,6 grau */
    double max_err_fagulha; /* graus */
    int inj_eventos;        /* pulsos do bico 1 */
    int inj_largura_errada;
    int inj_dessincronizados;
    double max_err_inj;
    int pino_estranho;       /* mudança em pino que não é de saída */
    int transicoes[KECU_SAIDAS];
    uint64_t max_ligado_us;  /* maior tempo seguido que qualquer saída ficou ligada */
} medida_t;

#define MAX_INJ 4096

SO_EM_ALGUMAS_VARIANTES static medida_t medir(int ini, double rpm, double pms_graus, double avanco_graus, double dwell_esperado_us,
                                              double inj_esperado_us, double tol_largura_us, double encurta_max_us) {
    medida_t m;
    memset(&m, 0, sizeof m);
    static uint64_t inj_on[4][MAX_INJ];
    int inj_n[4] = {0, 0, 0, 0};
    uint64_t bob_on[2] = {0, 0};
    int bob_on_ok[2] = {0, 0};
    uint64_t i_on[4] = {0, 0, 0, 0};
    int i_on_ok[4] = {0, 0, 0, 0};
    uint64_t desde[KECU_SAIDAS];
    int ligada[KECU_SAIDAS];
    memset(desde, 0, sizeof desde);
    memset(ligada, 0, sizeof ligada);
    const double rev_us = 60e6 / rpm;
    const double alvo[2] = {ang_mod(pms_graus - avanco_graus), ang_mod(pms_graus + 180.0 - avanco_graus)};

    for (int i = ini; i < mock_mudancas_n; i++) {
        const mock_mudanca_t x = mock_mudancas[i];
        const int saida = saida_do_pino(x.pino);
        if (saida < 0) {
            m.pino_estranho++;
            continue;
        }
        const int liga = x.nivel == NIVEL_LIGADO;
        m.transicoes[saida]++;
        if (liga) {
            desde[saida] = x.t_us;
            ligada[saida] = 1;
        } else if (ligada[saida]) {
            const uint64_t dur = x.t_us - desde[saida];
            if (dur > m.max_ligado_us) {
                m.max_ligado_us = dur;
            }
            ligada[saida] = 0;
        }
        if (saida == KECU_SAIDA_BOBINA1 || saida == KECU_SAIDA_BOBINA2) {
            const int c = saida == KECU_SAIDA_BOBINA1 ? 0 : 1;
            if (liga) {
                bob_on[c] = x.t_us;
                bob_on_ok[c] = 1;
            } else {
                const double ang = ang_mod(((double)x.t_us - g_roda.t0_us) / rev_us * 360.0);
                const double e0 = fabs(ang_dif(ang, alvo[0]));
                const double e1 = fabs(ang_dif(ang, alvo[1]));
                const int k = e0 <= e1 ? 0 : 1;
                const double e = k == 0 ? e0 : e1;
                m.faltas_de_fagulha++;
                m.por_alvo[k]++;
                if (e > m.max_err_fagulha) {
                    m.max_err_fagulha = e;
                }
                if (e > 0.6) {
                    m.fagulhas_acima_06++;
                }
                if (saida != KECU_SAIDA_BOBINA1 + (k % KECU_BOBINAS)) {
                    m.bobina_errada++;
                }
                if (bob_on_ok[c] && dwell_esperado_us > 0.0) {
                    const double dif = (double)(x.t_us - bob_on[c]) - dwell_esperado_us;
                    if (dif > tol_largura_us || dif < -(tol_largura_us + encurta_max_us)) {
                        if (m.dwell_errado < 3) {
                            printf("    dwell fora: liga %llu desliga %llu = %llu us (esperado %.1f, diferença %.1f)\n",
                                   (unsigned long long)bob_on[c], (unsigned long long)x.t_us,
                                   (unsigned long long)(x.t_us - bob_on[c]), dwell_esperado_us, dif);
                        }
                        m.dwell_errado++;
                    }
                    if (dif < -tol_largura_us) {
                        m.dwell_encurtado++;
                    }
                }
                bob_on_ok[c] = 0;
            }
        } else {
            const int b = saida - KECU_SAIDA_INJ1;
            if (liga) {
                i_on[b] = x.t_us;
                i_on_ok[b] = 1;
                if (inj_n[b] < MAX_INJ) {
                    inj_on[b][inj_n[b]++] = x.t_us;
                }
                if (b == 0) {
                    m.inj_eventos++;
                    const double ang = ang_dif(((double)x.t_us - g_roda.t0_us) / rev_us * 360.0, 0.0);
                    if (fabs(ang) > m.max_err_inj) {
                        m.max_err_inj = fabs(ang);
                    }
                }
            } else if (i_on_ok[b] && inj_esperado_us > 0.0
                       && fabs((double)(x.t_us - i_on[b]) - inj_esperado_us) > tol_largura_us) {
                m.inj_largura_errada++;
                i_on_ok[b] = 0;
            }
        }
    }
    for (int b = 1; b < 4; b++) {
        if (inj_n[b] != inj_n[0]) {
            m.inj_dessincronizados++;
            continue;
        }
        for (int j = 0; j < inj_n[0]; j++) {
            if (inj_on[b][j] != inj_on[0][j]) {
                m.inj_dessincronizados++;
            }
        }
    }
    return m;
}

/* ================================================================== 4) chaves */

static void teste_chaves(void) {
    secao("Chaves two-step e launch: antirruído de 20 ms");
    ligar_placa_nova();
    mundo_por(40000);
    CHECK(!g_kecu.chave_two_step && !g_kecu.chave_launch, "chaves soltas: desligadas");
    mock_entrada_nivel[PIN_TWO_STEP] = 0; /* chave para o GND */
    mundo_por(8000);
    CHECK(!g_kecu.chave_two_step, "8 ms depois de apertar ainda não vale (antirruído)");
    mundo_por(40000);
    CHECK(g_kecu.chave_two_step == 1, "40 ms depois vale");
    CHECK(!g_kecu.chave_launch, "launch não mexeu");
    mock_entrada_nivel[PIN_TWO_STEP] = 1;
    mundo_por(8000);
    CHECK(g_kecu.chave_two_step == 1, "solta há 8 ms: ainda vale");
    mundo_por(40000);
    CHECK(!g_kecu.chave_two_step, "solta há 48 ms: desligou");

    mock_entrada_nivel[PIN_LAUNCH] = 0; /* pico de ruído de 7 ms: uma única leitura baixa */
    mundo_por(7000);
    mock_entrada_nivel[PIN_LAUNCH] = 1;
    mundo_por(60000);
    CHECK(!g_kecu.chave_launch, "um pico de 7 ms não aciona o launch");

    /* ruído em rajada: 7 ms baixo, 7 ms alto, por 280 ms. Nunca há 20 ms seguidos em nível baixo, então o launch não pode acionar
     * (o contador do antirruído zera sempre que uma leitura volta ao estado aceito; somar leituras baixas esparsas acionaria). */
    int rajada_acionou = 0;
    for (int i = 0; i < 20; i++) {
        mock_entrada_nivel[PIN_LAUNCH] = 0;
        mundo_por(7000);
        rajada_acionou += g_kecu.chave_launch;
        mock_entrada_nivel[PIN_LAUNCH] = 1;
        mundo_por(7000);
        rajada_acionou += g_kecu.chave_launch;
    }
    CHECK(rajada_acionou == 0, "ruído em rajada (7 ms baixo, 7 ms alto) nunca aciona o launch (%d amostras acionadas)", rajada_acionou);
    mundo_por(60000);

    mock_entrada_nivel[PIN_LAUNCH] = 0;
    mundo_por(60000);
    CHECK(g_kecu.chave_launch == 1, "launch apertado vale");
    mock_entrada_nivel[PIN_LAUNCH] = 1;
    mundo_por(60000);

    const uint8_t sw = KECU_CHAVE_TWO_STEP;
    CHECK(e_ack(comando(KECU_CMD_CHAVES, &sw, 1)), "chave por software aceita");
    mundo_por(20000);
    CHECK(g_kecu.chave_two_step == 1, "two-step por software vale (a chave física está solta)");
}

/* ================================================================== 5) rotação e velocidade */

static void teste_rotacao(void) {
    secao("Rotação e velocidade: interrupções dos pinos do sensor");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    vss_iniciar(60.0);
    mundo_por(1200000);
    CHECK(g_kecu.sync == 1 && g_kecu.motor_girando == 1, "sincronizou com a roda 60-2");
    CHECK(fabs((double)g_kecu.rpm - 3000.0) < 9.0, "3000 rpm: leu %.1f", (double)g_kecu.rpm);
    CHECK(fabs((double)g_kecu.vel_kmh - 60.0) < 0.6, "60 km/h: leu %.2f", (double)g_kecu.vel_kmh);
    CHECK(g_kecu.perdas_sync == 0, "nenhuma perda de sincronia (%u)", g_kecu.perdas_sync);
    CHECK(g_kecu.eventos_ignicao > 50 && g_kecu.eventos_injecao > 20, "eventos de ignição e injeção planejados (%u / %u)",
          g_kecu.eventos_ignicao, g_kecu.eventos_injecao);
    CHECK(mock_secao_desequilibrio == 0, "seções críticas equilibradas");

    roda_iniciar(6000.0);
    mundo_por(600000);
    CHECK(fabs((double)g_kecu.rpm - 6000.0) < 18.0 && g_kecu.sync == 1, "6000 rpm: leu %.1f", (double)g_kecu.rpm);
    roda_iniciar(800.0);
    mundo_por(1500000);
    CHECK(fabs((double)g_kecu.rpm - 800.0) < 3.0 && g_kecu.sync == 1, "800 rpm: leu %.1f", (double)g_kecu.rpm);

    roda_parar();
    g_vss_periodo_us = 0.0;
    mundo_por(400000);
    CHECK(!g_kecu.motor_girando && g_kecu.rpm == 0.0f && !g_kecu.sync, "sem dentes por 400 ms: motor parado");
    mundo_por(1500000);
    CHECK(g_kecu.vel_kmh == 0.0f, "sem pulsos de velocidade por 1,9 s: 0 km/h");
}

/* ================================================================== 7) serial: contrapressão e ruído */

static void teste_serial(void) {
    secao("Serial: telemetria contínua, porta cheia, ruído, comando picado");
    ligar_placa_nova();
    roda_iniciar(3000.0);
    mundo_por(1000000);
    const uint8_t periodo[2] = {20, 0};
    CHECK(e_ack(comando(KECU_CMD_STREAM, periodo, 2)), "STREAM 20 ms");
    g_tele_n = 0;
    mundo_por(1000000);
    CHECK(g_tele_n >= 48 && g_tele_n <= 52, "50 quadros de telemetria por segundo (%d)", g_tele_n);
    CHECK(g_crc_ruins == 0 && g_bytes_lixo == 0, "todos íntegros");

    /* o PC some: a serial não aceita nada. O laço principal não pode parar nem esperar. */
    mock_serial_tx_limite = 0;
    kecu_proto_stats_t antes;
    kecu_proto_stats(&antes);
    const int lacos0 = g_lacos;
    const uint32_t ciclos0 = g_kecu.ciclos_controle;
    mundo_por(2000000);
    kecu_proto_stats_t depois;
    kecu_proto_stats(&depois);
    CHECK(g_lacos - lacos0 >= 19900, "o laço principal seguiu rodando (%d voltas em 2 s)", g_lacos - lacos0);
    CHECK(g_kecu.ciclos_controle - ciclos0 >= 395 && g_kecu.ciclos_controle - ciclos0 <= 405,
          "o controle seguiu a cada 5 ms (%u ciclos em 2 s)", g_kecu.ciclos_controle - ciclos0);
    CHECK(depois.telemetrias_perdidas > antes.telemetrias_perdidas, "telemetria descartada com a fila cheia (%u perdidas)",
          depois.telemetrias_perdidas - antes.telemetrias_perdidas);
    CHECK(g_kecu.sync == 1 && fabs((double)g_kecu.rpm - 3000.0) < 9.0, "a rotação continua certa (%.1f)", (double)g_kecu.rpm);

    /* a serial volta: nada de meio quadro */
    mock_serial_tx_limite = -1;
    g_tele_n = 0;
    mundo_por(300000);
    CHECK(g_tele_n >= 12, "a telemetria volta (%d quadros em 300 ms)", g_tele_n);
    CHECK(g_crc_ruins == 0 && g_bytes_lixo == 0, "e os quadros que estavam na fila saem íntegros (%d CRC ruins, %d bytes soltos)", g_crc_ruins,
          g_bytes_lixo);

    /* a serial aceita só 7 bytes por chamada: quadros partidos em pedaços, remontados no PC */
    mock_serial_tx_limite = 7;
    g_tele_n = 0;
    mundo_por(1000000);
    CHECK(g_tele_n >= 48 && g_tele_n <= 52, "com a serial aceitando 7 bytes por vez: %d quadros por segundo", g_tele_n);
    CHECK(g_crc_ruins == 0 && g_bytes_lixo == 0, "e continuam íntegros (%d CRC ruins, %d bytes soltos)", g_crc_ruins, g_bytes_lixo);
    mock_serial_tx_limite = -1;

    /* desliga a telemetria */
    const uint8_t zero[2] = {0, 0};
    CHECK(e_ack(comando(KECU_CMD_STREAM, zero, 2)), "STREAM 0 desliga");
    mundo_por(100000);
    g_tele_n = 0;
    mundo_por(200000);
    CHECK(g_tele_n == 0, "sem telemetria depois de desligar (%d)", g_tele_n);

    /* rajada de lixo maior que o buffer de recepção: os bytes que não cabem se perdem, e a ECU continua entendendo */
    uint8_t lixo[1500];
    for (size_t i = 0; i < sizeof lixo; i++) {
        lixo[i] = (uint8_t)(i * 31u + 7u);
        if (lixo[i] == KECU_SOF1) {
            lixo[i] = 0x11;
        }
    }
    mock_serial_injetar(lixo, (int)sizeof lixo);
    mundo_por(50000);
    const quadro_t *r = comando(KECU_CMD_HELLO, NULL, 0);
    CHECK(r != NULL && r->tipo == KECU_RSP_INFO, "depois de 1500 bytes de lixo o HELLO ainda é respondido");

    /* comando picado: um byte por volta do laço */
    {
        const uint8_t seq = g_seq++;
        uint8_t q[KECU_CABECALHO + KECU_PAYLOAD_MAX + KECU_TAM_CRC];
        const uint16_t tam = kecu_proto_montar(q, KECU_CMD_HELLO, seq, NULL, 0);
        g_quadros_n = 0;
        for (uint16_t i = 0; i < tam; i++) {
            mock_serial_injetar(&q[i], 1);
            mundo_por(PERIODO_LACO_US * 2u);
        }
        mundo_por(2000);
        const quadro_t *p = achar_resposta(seq);
        CHECK(p != NULL && p->tipo == KECU_RSP_INFO, "comando entregue um byte por vez também funciona");
    }

    /* comando com CRC errado é ignorado e contado */
    {
        kecu_proto_stats_t s0;
        kecu_proto_stats(&s0);
        uint8_t q[KECU_CABECALHO + KECU_TAM_CRC];
        const uint16_t tam = kecu_proto_montar(q, KECU_CMD_HELLO, 200, NULL, 0);
        q[tam - 1] ^= 0xFF;
        g_quadros_n = 0;
        mock_serial_injetar(q, tam);
        mundo_por(5000);
        kecu_proto_stats_t s1;
        kecu_proto_stats(&s1);
        CHECK(achar_resposta(200) == NULL && s1.erros_crc > s0.erros_crc, "CRC errado: sem resposta e contado (%u)", s1.erros_crc - s0.erros_crc);
    }
    CHECK(mock_serial_bloqueios == 0 && g_max_laco_us == 0,
          "nenhuma chamada da serial travou o laço principal (%d bloqueios, %llu us parado na pior volta)", mock_serial_bloqueios,
          (unsigned long long)g_max_laco_us);
    roda_parar();
}

/* FIFO de 128 bytes que esvazia a 115200 baud: o laço escreve só o que cabe, em partes, sem esperar. */
static void teste_serial_fifo(void) {
    secao("Serial: FIFO de 128 bytes da UART (a 115200 baud) e telemetria rápida");
    ligar_placa_nova();
    const uint8_t periodo[2] = {5, 0}; /* 5 ms: 43 bytes a cada 5 ms = 8600 B/s, perto do limite da linha (11520 B/s) */
    CHECK(e_ack(comando(KECU_CMD_STREAM, periodo, 2)), "STREAM 5 ms");
    g_tele_n = 0;
    mundo_por(2000000);
    CHECK(g_tele_n >= 390 && g_tele_n <= 402, "200 quadros de telemetria por segundo (%d em 2 s)", g_tele_n);
    CHECK(g_crc_ruins == 0 && g_bytes_lixo == 0, "todos íntegros (%d CRC ruins, %d bytes soltos)", g_crc_ruins, g_bytes_lixo);
    CHECK(mock_serial_bloqueios == 0 && g_max_laco_us == 0, "o laço nunca esperou o FIFO esvaziar (%d bloqueios, pior volta %llu us)",
          mock_serial_bloqueios, (unsigned long long)g_max_laco_us);
    kecu_proto_stats_t st;
    kecu_proto_stats(&st);
    CHECK(st.telemetrias_perdidas == 0, "e nenhuma telemetria foi descartada (%u)", st.telemetrias_perdidas);
}


#endif /* HARNESS_PLACA_H */
