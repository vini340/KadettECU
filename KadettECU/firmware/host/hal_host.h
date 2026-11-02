/*
 * hal_host.h - HAL para PC (Linux): o mesmo núcleo do firmware roda aqui, sem a placa.
 * Serve a dois usos:
 *   - testes automáticos em C (tempo virtual, sensores e serial controlados pelo teste);
 *   - o programa "ecu_host", uma ECU virtual que fala o protocolo por stdin/stdout, para o
 *     aplicativo Java conversar com o firmware de verdade antes de existir a placa.
 */
#ifndef HAL_HOST_H
#define HAL_HOST_H

#include <stdint.h>

#include "kecu.h"

/* ------------------------------------------------------------------ relógio */
extern int host_tempo_real; /* 1 = relógio do sistema; 0 (padrão) = virtual */
void host_tempo_definir_us(uint64_t us);
void host_tempo_avancar_us(uint32_t us);
uint64_t host_tempo_us(void);

/* ------------------------------------------------------------------ sensores */
/* Valores FÍSICOS: a HAL calcula a tensão do pino invertendo a calibração de kecu_config.h. */
void host_definir_sensores(float ect, float iat, float map, float tps, float vbat, float oleo, float lambda);
void host_definir_tps(float tps);
void host_definir_map(float map);
/* Tensão crua no pino (mV): para simular fio solto/em curto. */
void host_definir_pino_mv(kecu_adc_canal_t canal, int mv);
void host_definir_entrada(kecu_in_canal_t canal, int ativo);

/* ------------------------------------------------------------------ serial */
extern int host_serial_stdio;  /* 1 = lê stdin / escreve stdout (modo ecu_host) */
extern int host_stdin_fim;     /* vira 1 quando o stdin fecha */
extern int host_serial_limite; /* bytes aceitos por chamada de escrita; -1 = sem limite, 0 = serial travada */
void host_serial_injetar(const uint8_t *dados, int n);
int host_serial_coletar(uint8_t *destino, int maximo);
void host_serial_limpar(void);

/* ------------------------------------------------------------------ memória não volátil */
extern const char *host_nvm_arquivo; /* se não for NULL, a memória é espelhada neste arquivo */
extern int host_nvm_falhar;          /* 1 = hal_nvm_gravar devolve erro */
extern int host_nvm_nao_guarda;      /* 1 = diz que gravou, mas não guarda (a conferência deve pegar) */
extern int host_nvm_gravacoes;
void host_nvm_carregar_arquivo(void);
void host_nvm_apagar(void);
void host_nvm_corromper(int posicao);

/* ------------------------------------------------------------------ saídas (bicos/bobina) */
typedef struct {
    kecu_saida_t saida;
    uint64_t instante_us; /* quando liga (tempo absoluto do relógio do host) */
    uint32_t atraso_us;
    uint32_t largura_us;
} host_pulso_t;

int host_pulsos_n(void);
host_pulso_t host_pulso(int i);
void host_pulsos_limpar(void);
extern int host_desligamentos;
extern float host_iac_pct;
extern int host_secao_nivel;

#endif
