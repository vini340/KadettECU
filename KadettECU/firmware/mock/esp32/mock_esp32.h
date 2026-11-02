/*
 * mock_esp32.h - SIMULADOR das partes do Arduino-ESP32 que hal_esp32.cpp usa, para rodar a HAL no PC.
 *
 * O que isto garante: que o código de hal_esp32.cpp faz o que deveria DADO o comportamento das APIs
 * como este simulador as modela (o timer conta, o alarme dispara, o pino muda de nível, a memória
 * guarda). O que isto NÃO garante: que o Arduino-ESP32 de verdade se comporta assim em cada detalhe.
 * O simulador foi escrito a partir da documentação e dos fontes da API 2.0.x/3.x; ele não substitui
 * compilar com a cadeia real e testar na placa.
 *
 * Este arquivo é a API de CONTROLE usada pelos testes (fora dela, tudo é a API fingida do Arduino).
 */
#ifndef MOCK_ESP32_H
#define MOCK_ESP32_H

#include <stddef.h>
#include <stdint.h>

#define MOCK_PINOS 40

/* ------------------------------------------------------------------ mundo simulado */
void mock_reiniciar_tudo(void);   /* apaga TUDO, inclusive a memória (como tirar a placa da tomada e zerar a flash) */
void mock_reiniciar_placa(void);  /* como apertar RESET: pinos, timer, serial e interrupções voltam ao início; a flash (NVS) fica */

/* ------------------------------------------------------------------ tempo e timer */
extern uint64_t mock_tempo_us;              /* relógio do mundo, em us */
void mock_tempo_para(uint64_t t_us);        /* só avança (nunca volta) */
/* Deixa o tempo correr até t_us disparando o alarme do timer nos instantes certos. */
void mock_correr_ate(uint64_t t_us);
extern uint32_t mock_lat_alarme_us;         /* atraso da interrupção do alarme depois do instante programado */
extern int mock_alarme_passado_dispara;     /* 1 = alarme ESCRITO já vencido dispara na hora; 0 = nunca dispara (pior caso do hardware) */
extern int mock_alarme_perde_a_cada;        /* N > 0: todo N-ésimo alarme se perde (testa a rede de proteção); 0 = nenhum */
extern int mock_timer_criado;
extern uint32_t mock_timer_divisor;
extern uint32_t mock_timer_freq_hz;
extern int mock_alarme_ativo;
extern uint64_t mock_alarme_valor;
extern int mock_alarmes_programados;
extern int mock_alarmes_no_passado;         /* alarmes escritos com valor <= contador + 2 us (o hardware pode perder) */
extern int mock_alarmes_disparados;
extern int64_t mock_alarme_margem_min_us;   /* menor (valor do alarme - contador) visto ao programar; começa em INT64_MAX */
/* Define o valor do contador do timer de hardware (para testar a volta dos 32 bits de hal_micros). */
void mock_timer_definir_contador(uint64_t valor);
uint64_t mock_timer_contador(void);         /* valor atual do contador de 64 bits */
/*
 * Tempo que a interrupção de pino gasta entre capturar o instante do dente (1ª leitura do contador) e
 * começar a pedir os pulsos (2ª leitura). O relógio do mundo anda esse tanto na 2ª leitura.
 */
extern uint32_t mock_isr_custo_us;

/* ------------------------------------------------------------------ pinos */
extern uint8_t mock_pino_modo[MOCK_PINOS];      /* 0 = nunca configurado; senão o valor de INPUT/OUTPUT/INPUT_PULLUP */
extern uint8_t mock_pino_nivel[MOCK_PINOS];     /* nível de saída */
/* Nível que o resistor externo impõe a um pino solto (entrada): 0 = pull-down (estágios de potência ativos em nível alto), 1 = pull-up. */
extern uint8_t mock_nivel_solto;
extern int mock_pino_escritas[MOCK_PINOS];      /* chamadas a digitalWrite */
extern int mock_pino_pinmodes[MOCK_PINOS];      /* chamadas a pinMode */
extern uint8_t mock_entrada_nivel[MOCK_PINOS];  /* o que digitalRead devolve (padrão 1 = alto, com pull-up) */
extern uint32_t mock_adc_mv[MOCK_PINOS];        /* o que analogReadMilliVolts devolve */
extern int mock_adc_leituras;
extern uint8_t mock_atenuacao[MOCK_PINOS];

typedef struct {
    uint64_t t_us;
    uint8_t pino;
    uint8_t nivel;
} mock_mudanca_t;

#define MOCK_MAX_MUDANCAS 400000
extern mock_mudanca_t mock_mudancas[MOCK_MAX_MUDANCAS];
extern int mock_mudancas_n;                     /* mudanças de nível dos pinos configurados como SAÍDA, em ordem */
/* Para cada pino, a ordem das chamadas pinMode/digitalWrite: 'M' = pinMode(OUTPUT), 'm' = outro pinMode, 'L' = write LOW, 'H' = write HIGH. */
extern char mock_pino_historia[MOCK_PINOS][16];

/* ------------------------------------------------------------------ interrupções de pino */
extern void (*mock_isr_pino[MOCK_PINOS])(void);
extern int mock_isr_modo[MOCK_PINOS];
int mock_pulso_pino(int pino);                  /* chama a ISR registrada (1 = chamou, 0 = nenhuma) */

/* ------------------------------------------------------------------ seção crítica */
extern int mock_secao_nivel;
extern int mock_secao_max;
extern int mock_secao_desequilibrio;            /* saídas sem entrada correspondente */
/* Contratos de concorrência do agendador: escritas em pino de SAÍDA e (re)programações do alarme têm de acontecer
 * dentro de uma seção crítica (na ISR ou no laço principal). Estes contadores contam as que aconteceram fora. */
extern int mock_escritas_fora_de_secao;
extern int mock_alarme_fora_de_secao;

/* ------------------------------------------------------------------ serial */
extern uint32_t mock_serial_baud;
extern size_t mock_serial_rx_tam;
extern int mock_serial_iniciada;
extern int mock_serial_tx_limite;               /* bytes aceitos por chamada de write; < 0 = FIFO de 128 bytes que esvazia à velocidade da linha */
extern int mock_serial_bloqueios;               /* chamadas que teriam TRAVADO a placa: write sem espaço, readBytes pedindo mais que available() */
extern uint64_t mock_serial_bloqueio_us;        /* tempo que a placa ficou parada nelas (o relógio do mundo avança esse tanto) */
void mock_serial_injetar(const uint8_t *dados, int n);
int mock_serial_coletar(uint8_t *destino, int maximo);

/* ------------------------------------------------------------------ memória (NVS) */
extern int mock_nvs_falhar;                     /* 1 = putBytes devolve 0 */
extern int mock_nvs_gravacoes;
void mock_nvs_corromper(size_t posicao);        /* troca bits de um byte do blob guardado */
size_t mock_nvs_tamanho(void);

/* ------------------------------------------------------------------ válvula de marcha lenta (LEDC) */
extern int mock_iac_anexada;
extern uint32_t mock_iac_freq_hz;
extern uint32_t mock_iac_bits;
extern uint32_t mock_iac_duty;
extern int mock_iac_escritas;
extern int mock_iac_pino;

/* ------------------------------------------------------------------ cão de guarda */
extern uint32_t mock_wdt_timeout_ms;
extern int mock_wdt_panic;
extern int mock_wdt_tarefa_inscrita;
extern int mock_wdt_resets;

#endif
