/* hal_host.c - veja hal_host.h */
#define _POSIX_C_SOURCE 200809L

#include "hal_host.h"

#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------ relógio */

int host_tempo_real;
static uint64_t s_t64_us;
static uint64_t s_base_real_us;
static int s_base_real_ok;

static uint64_t agora_real_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    const uint64_t us = (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
    if (!s_base_real_ok) {
        s_base_real_us = us;
        s_base_real_ok = 1;
    }
    return us - s_base_real_us + 1000ull; /* nunca começa em zero */
}

uint64_t host_tempo_us(void) {
    return host_tempo_real ? agora_real_us() : s_t64_us;
}

void host_tempo_definir_us(uint64_t us) {
    s_t64_us = us;
}

void host_tempo_avancar_us(uint32_t us) {
    s_t64_us += us;
}

uint32_t hal_micros(void) {
    return (uint32_t)(host_tempo_us() & 0xFFFFFFFFull);
}

uint32_t hal_millis(void) {
    return (uint32_t)((host_tempo_us() / 1000ull) & 0xFFFFFFFFull);
}

/* ------------------------------------------------------------------ sensores */

static uint16_t s_pino_mv[KECU_ADC_CANAIS];
static uint8_t s_entrada[KECU_IN_CANAIS];

static uint16_t limitar_pino(float mv) {
    if (mv < 0.0f) {
        return 0;
    }
    if (mv > (float)KECU_VREF_MV) {
        return (uint16_t)KECU_VREF_MV;
    }
    return (uint16_t)(mv + 0.5f);
}

/* inverso exato de ntc_celsius (kecu_sensors.c) */
static float ntc_pino_mv(float celsius) {
    const float t1 = KECU_NTC_T1_C + 273.15f;
    const float t2 = KECU_NTC_T2_C + 273.15f;
    const float beta = logf(KECU_NTC_R1_OHM / KECU_NTC_R2_OHM) / (1.0f / t1 - 1.0f / t2);
    const float t = celsius + 273.15f;
    const float r = KECU_NTC_R1_OHM * expf(beta * (1.0f / t - 1.0f / t1));
    return (float)KECU_VREF_MV * r / (r + KECU_NTC_PULLUP_OHM);
}

static float linear_inversa(float y, float v1, float y1, float v2, float y2) {
    return v1 + (y - y1) * (v2 - v1) / (y2 - y1);
}

void host_definir_tps(float tps) {
    const float v = linear_inversa(tps, KECU_TPS_V_FECHADO_MV, 0.0f, KECU_TPS_V_ABERTO_MV, 100.0f);
    s_pino_mv[KECU_ADC_TPS] = limitar_pino(v / KECU_RAZAO_5V);
}

void host_definir_map(float map) {
    const float v = linear_inversa(map, KECU_MAP_V1_MV, KECU_MAP_KPA1, KECU_MAP_V2_MV, KECU_MAP_KPA2);
    s_pino_mv[KECU_ADC_MAP] = limitar_pino(v / KECU_RAZAO_5V);
}

void host_definir_sensores(float ect, float iat, float map, float tps, float vbat, float oleo, float lambda) {
    s_pino_mv[KECU_ADC_ECT] = limitar_pino(ntc_pino_mv(ect));
    s_pino_mv[KECU_ADC_IAT] = limitar_pino(ntc_pino_mv(iat));
    host_definir_map(map);
    host_definir_tps(tps);
    s_pino_mv[KECU_ADC_VBAT] = limitar_pino(vbat * 1000.0f / KECU_RAZAO_BATERIA);
    const float v_oleo = linear_inversa(oleo, KECU_OLEO_V1_MV, KECU_OLEO_BAR1, KECU_OLEO_V2_MV, KECU_OLEO_BAR2);
    s_pino_mv[KECU_ADC_OLEO] = limitar_pino(v_oleo / KECU_RAZAO_5V);
    const float v_lam = linear_inversa(lambda * KECU_LAMBDA_AFR_REF, KECU_LAMBDA_V1_MV, KECU_LAMBDA_AFR1,
                                       KECU_LAMBDA_V2_MV, KECU_LAMBDA_AFR2);
    s_pino_mv[KECU_ADC_LAMBDA] = limitar_pino(v_lam / KECU_RAZAO_5V);
}

void host_definir_pino_mv(kecu_adc_canal_t canal, int mv) {
    s_pino_mv[canal] = (uint16_t)(mv < 0 ? 0 : mv);
}

void host_definir_entrada(kecu_in_canal_t canal, int ativo) {
    s_entrada[canal] = (uint8_t)(ativo != 0);
}

uint16_t hal_adc_ler_mv(kecu_adc_canal_t canal) {
    return s_pino_mv[canal];
}

uint8_t hal_entrada_ativa(kecu_in_canal_t canal) {
    return s_entrada[canal];
}

/* ------------------------------------------------------------------ serial */

#define FILA_RX 8192
#define COLETA_TX (1 << 20)

int host_serial_stdio;
int host_stdin_fim;
int host_serial_limite = -1;

static uint8_t s_fila_rx[FILA_RX];
static int s_rx_ini;
static int s_rx_n;
static uint8_t s_coleta[COLETA_TX];
static int s_coleta_n;
static int s_stdio_preparado;

static void preparar_stdio(void) {
    if (s_stdio_preparado) {
        return;
    }
    s_stdio_preparado = 1;
    const int f0 = fcntl(0, F_GETFL, 0);
    if (f0 >= 0) {
        (void)fcntl(0, F_SETFL, f0 | O_NONBLOCK);
    }
    const int f1 = fcntl(1, F_GETFL, 0);
    if (f1 >= 0) {
        (void)fcntl(1, F_SETFL, f1 | O_NONBLOCK);
    }
}

void host_serial_injetar(const uint8_t *dados, int n) {
    for (int i = 0; i < n && s_rx_n < FILA_RX; i++) {
        s_fila_rx[(s_rx_ini + s_rx_n) % FILA_RX] = dados[i];
        s_rx_n++;
    }
}

int host_serial_coletar(uint8_t *destino, int maximo) {
    const int n = s_coleta_n < maximo ? s_coleta_n : maximo;
    memcpy(destino, s_coleta, (size_t)n);
    memmove(s_coleta, &s_coleta[n], (size_t)(s_coleta_n - n));
    s_coleta_n -= n;
    return n;
}

void host_serial_limpar(void) {
    s_rx_ini = 0;
    s_rx_n = 0;
    s_coleta_n = 0;
}

int hal_serial_ler(uint8_t *destino, int maximo) {
    if (host_serial_stdio && s_rx_n == 0 && !host_stdin_fim) {
        preparar_stdio();
        uint8_t tmp[256];
        const ssize_t r = read(0, tmp, sizeof tmp);
        if (r > 0) {
            host_serial_injetar(tmp, (int)r);
        } else if (r == 0) {
            host_stdin_fim = 1;
        }
    }
    int n = 0;
    while (n < maximo && s_rx_n > 0) {
        destino[n++] = s_fila_rx[s_rx_ini];
        s_rx_ini = (s_rx_ini + 1) % FILA_RX;
        s_rx_n--;
    }
    return n;
}

int hal_serial_escrever(const uint8_t *origem, int n) {
    int aceitar = n;
    if (host_serial_limite >= 0 && aceitar > host_serial_limite) {
        aceitar = host_serial_limite;
    }
    if (aceitar <= 0) {
        return 0;
    }
    if (host_serial_stdio) {
        preparar_stdio();
        const ssize_t w = write(1, origem, (size_t)aceitar);
        if (w < 0) {
            return 0; /* EAGAIN: o pipe está cheio; tenta de novo na próxima volta */
        }
        return (int)w;
    }
    if (s_coleta_n + aceitar > COLETA_TX) {
        aceitar = COLETA_TX - s_coleta_n;
    }
    memcpy(&s_coleta[s_coleta_n], origem, (size_t)aceitar);
    s_coleta_n += aceitar;
    return aceitar;
}

/* ------------------------------------------------------------------ memória não volátil */

#define NVM_TAM 4096

const char *host_nvm_arquivo;
int host_nvm_falhar;
int host_nvm_nao_guarda;
int host_nvm_gravacoes;
static uint8_t s_nvm[NVM_TAM];
static int s_nvm_iniciada;

static void nvm_iniciar(void) {
    if (!s_nvm_iniciada) {
        memset(s_nvm, 0xFF, sizeof s_nvm); /* memória apagada */
        s_nvm_iniciada = 1;
    }
}

void host_nvm_apagar(void) {
    s_nvm_iniciada = 0;
    nvm_iniciar();
}

void host_nvm_corromper(int posicao) {
    nvm_iniciar();
    s_nvm[posicao % NVM_TAM] ^= 0x5Au;
}

void host_nvm_carregar_arquivo(void) {
    nvm_iniciar();
    if (host_nvm_arquivo == NULL) {
        return;
    }
    FILE *f = fopen(host_nvm_arquivo, "rb");
    if (f != NULL) {
        const size_t lidos = fread(s_nvm, 1, sizeof s_nvm, f);
        (void)lidos;
        fclose(f);
    }
}

int hal_nvm_ler(uint8_t *destino, int n) {
    nvm_iniciar();
    if (n > NVM_TAM) {
        return 1;
    }
    memcpy(destino, s_nvm, (size_t)n);
    return 0;
}

int hal_nvm_gravar(const uint8_t *origem, int n) {
    nvm_iniciar();
    if (host_nvm_falhar || n > NVM_TAM) {
        return 1;
    }
    host_nvm_gravacoes++;
    if (host_nvm_nao_guarda) {
        return 0;
    }
    memcpy(s_nvm, origem, (size_t)n);
    if (host_nvm_arquivo != NULL) {
        FILE *f = fopen(host_nvm_arquivo, "wb");
        if (f == NULL) {
            return 1;
        }
        const size_t escritos = fwrite(s_nvm, 1, (size_t)n, f);
        fclose(f);
        if (escritos != (size_t)n) {
            return 1;
        }
    }
    return 0;
}

/* ------------------------------------------------------------------ saídas */

#define MAX_PULSOS 40000

static host_pulso_t s_pulsos[MAX_PULSOS];
static int s_pulsos_n;
int host_desligamentos;
float host_iac_pct;
int host_secao_nivel;

int host_pulsos_n(void) {
    return s_pulsos_n;
}

host_pulso_t host_pulso(int i) {
    return s_pulsos[i];
}

void host_pulsos_limpar(void) {
    s_pulsos_n = 0;
}

/*
 * As duas funções abaixo são "fracas" (weak): ao ligar kecu_sched_hal.c (o agendador de bordas das
 * placas reais) junto com este arquivo, as versões dele assumem o lugar, e o teste do agendador
 * (test_sched.c) roda o planejador do núcleo contra o agendador de verdade.
 */
#define HOST_FRACA __attribute__((weak))

HOST_FRACA void hal_agendar_pulso(kecu_saida_t saida, uint32_t atraso_us, uint32_t largura_us) {
    if (s_pulsos_n < MAX_PULSOS) {
        host_pulso_t *p = &s_pulsos[s_pulsos_n++];
        p->saida = saida;
        p->atraso_us = atraso_us;
        p->largura_us = largura_us;
        p->instante_us = host_tempo_us() + atraso_us;
    }
}

HOST_FRACA void hal_desligar_saidas(void) {
    host_desligamentos++;
}

void hal_iac_pwm(float percentual) {
    host_iac_pct = percentual;
}

void hal_secao_entrar(void) {
    host_secao_nivel++;
}

void hal_secao_sair(void) {
    host_secao_nivel--;
}
