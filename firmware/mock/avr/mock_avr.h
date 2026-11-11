/*
 * mock_avr.h - SIMULADOR das partes do ATmega2560 (registradores do Timer1, PORTA, SREG, EEPROM, cão de guarda)
 * e da API do Arduino que hal_avr.cpp usa, para rodar a HAL no PC.
 *
 * O que isto garante: que o código de hal_avr.cpp faz o que deveria DADO o comportamento dos registradores como
 * este simulador os modela (o Timer1 conta a 2 MHz com prescaler 8, o comparador só dispara quando TCNT1 == OCR1A,
 * escrever 1 em TIFR1 apaga o aviso, o AVR não aninha interrupções, o ADC arrasta a carga do canal anterior...).
 * O que isto NÃO garante: que o ATmega2560 de verdade se comporta assim em cada detalhe, nem o TEMPO que o
 * avr-gcc leva para executar o núcleo. O simulador foi escrito a partir do datasheet e da avr-libc; ele não
 * substitui compilar com o avr-gcc e testar na placa.
 *
 * Os nomes de controle abaixo seguem os do simulador do ESP32 (mock_esp32.h) para o mesmo roteiro de teste
 * (harness_placa.h) servir às duas placas. O mundo é medido em microssegundos inteiros; o Timer1 conta 2 ticks
 * por microssegundo.
 */
#ifndef MOCK_AVR_H
#define MOCK_AVR_H

#include <stddef.h>
#include <stdint.h>

#define MOCK_PINOS 70 /* Mega: pinos digitais 0..53 e A0..A15 = 54..69 */

/* ------------------------------------------------------------------ mundo simulado */
void mock_reiniciar_tudo(void);   /* apaga TUDO, inclusive a EEPROM */
void mock_reiniciar_placa(void);  /* como apertar RESET: registradores, serial e interrupções voltam ao início; a EEPROM fica */

extern uint64_t mock_tempo_us;
void mock_tempo_para(uint64_t t_us); /* só avança (nunca volta); NÃO dispara interrupções */
/* Deixa o tempo correr até t_us disparando as interrupções do Timer1 nos instantes certos (se o bit I estiver ligado). */
void mock_correr_ate(uint64_t t_us);

extern uint32_t mock_lat_alarme_us;     /* atraso entre o casamento do comparador e a entrada da interrupção */
extern int mock_alarme_perde_a_cada;    /* N > 0: todo N-ésimo casamento do comparador se perde; 0 = nenhum */
/* Tempo que a interrupção de pino gasta entre ler o contador pela 1ª vez (instante do dente) e pela 2ª (começa a pedir pulsos). */
extern uint32_t mock_isr_custo_us;
/*
 * Em qual leitura de TCNT1 dentro da interrupção o custo é cobrado (padrão: a 2ª). O custo passa no primeiro acesso depois da
 * leitura anterior a essa: uma leitura de TCNT1 ou uma escrita em OCR1A. Com 3, o tempo passa entre a leitura de TCNT1 de
 * hal_timer_armar e a escrita de OCR1A (ou a leitura fresca que há entre as duas).
 */
extern int mock_isr_leitura_do_custo;
/*
 * Tempo que passa DEPOIS de cada leitura de TCNT1, em qualquer contexto (a leitura devolve o valor de antes). Modela a corrida
 * clássica do AVR: o contador dá a volta entre a leitura de TCNT1 e a leitura do resto do relógio (a contagem de voltas e o aviso
 * TOV1). Com valor > 0, se o bit I estiver ligado a interrupção de estouro é entregue logo em seguida, como o hardware faria.
 */
extern uint32_t mock_tcnt1_custo_us;

/* ------------------------------------------------------------------ Timer1 */
uint32_t mock_timer1_divisor(void);     /* divisor do prescaler (1, 8, 64, 256, 1024) ou 0 = contador parado */
int mock_timer1_modo_normal(void);      /* 1 se WGM1[3:0] == 0 (conta até 0xFFFF) */
uint16_t mock_timer1_contador(void);    /* TCNT1, sem efeitos colaterais */
int mock_timer1_comparador_ligado(void);/* OCIE1A */
extern int mock_alarmes_programados;    /* escritas em OCR1A */
extern int mock_alarmes_no_passado;     /* escritas com OCR1A a 1 tick ou menos à frente de TCNT1 (ou já passado) */
extern int mock_alarmes_disparados;     /* vezes em que a interrupção do comparador rodou */
extern uint64_t mock_alarme_disparo_us; /* instante da entrada da interrupção do comparador mais recente */
extern int mock_ovf_disparos;           /* vezes em que a interrupção de estouro rodou */
extern int mock_alarme_margem_min_ticks;/* menor (OCR1A - TCNT1) visto ao escrever; começa em INT32_MAX */

/* ------------------------------------------------------------------ pinos */
extern uint8_t mock_pino_modo[MOCK_PINOS];      /* 0 = nunca configurado; senão 0x80 | (INPUT, OUTPUT ou INPUT_PULLUP do pinMode): no AVR INPUT vale 0 */
extern uint8_t mock_pino_nivel[MOCK_PINOS];     /* nível visto no pino (PORTA: nível efetivo do registrador/solto) */
/* Nível que o resistor externo impõe a um pino solto: 0 = pull-down (estágio de potência ativo em nível alto), 1 = pull-up. */
extern uint8_t mock_nivel_solto;
extern int mock_pino_escritas[MOCK_PINOS];      /* chamadas a digitalWrite */
extern int mock_pino_pinmodes[MOCK_PINOS];      /* chamadas a pinMode */
extern uint8_t mock_entrada_nivel[MOCK_PINOS];  /* o que digitalRead devolve (padrão 1 = alto, com pull-up) */
extern uint32_t mock_adc_mv[MOCK_PINOS];        /* tensão NO PINO que analogRead converte */
extern uint32_t mock_avcc_mv;                   /* AVcc, a referência do ADC (padrão 5000) */
extern int mock_adc_leituras;                   /* conversões feitas */

typedef struct {
    uint64_t t_us;
    uint8_t pino;
    uint8_t nivel;
} mock_mudanca_t;

#define MOCK_MAX_MUDANCAS 400000
extern mock_mudanca_t mock_mudancas[MOCK_MAX_MUDANCAS];
extern int mock_mudancas_n;                     /* mudanças de nível efetivo dos pinos da PORTA (22..29), em ordem */
/* PORTA: ordem das operações em cada pino: 'l'/'h' = PORTA bit para 0/1, 'D'/'d' = DDRA bit para saída/entrada. */
extern char mock_pino_historia[MOCK_PINOS][16];
extern int mock_porta_escritas;                 /* escritas em PORTA depois do reset */
extern int mock_ddra_escritas;                  /* escritas em DDRA depois do reset */
extern int mock_pullup_conflitos;               /* pino de saída da PORTA com pull-up interno ligado contra um pull-down externo */

/* ------------------------------------------------------------------ interrupções de pino */
extern void (*mock_isr_pino[MOCK_PINOS])(void);
extern int mock_isr_modo[MOCK_PINOS];
int mock_pulso_pino(int pino);                  /* levanta a interrupção do pino e a entrega se o bit I estiver ligado */

/* ------------------------------------------------------------------ bit I (interrupções globais) */
extern int mock_secao_nivel;                    /* 1 enquanto o código principal está com as interrupções desligadas */
extern int mock_secao_max;                      /* (sem uso no AVR; mantido por compatibilidade) */
extern int mock_secao_desequilibrio;            /* sei()/SREG com bit I ligado DENTRO de uma interrupção: o AVR passaria a aninhar */
/* Escritas em pino de SAÍDA da PORTA e (re)programações do alarme com as interrupções LIGADAS no código principal. */
extern int mock_escritas_fora_de_secao;
extern int mock_alarme_fora_de_secao;

/* ------------------------------------------------------------------ serial */
extern uint32_t mock_serial_baud;
extern size_t mock_serial_rx_tam;               /* SERIAL_RX_BUFFER_SIZE (padrão do Arduino: 64) */
extern size_t mock_serial_tx_tam;               /* SERIAL_TX_BUFFER_SIZE (padrão do Arduino: 64) */
extern int mock_serial_iniciada;
extern int mock_serial_tx_limite;               /* máximo que availableForWrite() informa; < 0 = o espaço livre do buffer */
extern int mock_serial_bloqueios;               /* write() que teria TRAVADO a placa (buffer sem espaço) */
extern uint64_t mock_serial_bloqueio_us;        /* tempo parado nelas */
extern int mock_serial_rx_perdidos;             /* bytes recebidos com o buffer de recepção cheio */
void mock_serial_injetar(const uint8_t *dados, int n);
int mock_serial_coletar(uint8_t *destino, int maximo);

/* ------------------------------------------------------------------ EEPROM */
extern int mock_eeprom_falhar;                  /* 1 = update() não grava (EEPROM com defeito) */
extern int mock_eeprom_gravacoes;               /* bytes realmente gravados (só os que mudam) */
extern uint32_t mock_eeprom_us_por_byte;        /* duração de uma gravação (3300 us) */
void mock_eeprom_corromper(size_t posicao);
size_t mock_eeprom_usado(void);                 /* maior endereço escrito + 1 */

/* ------------------------------------------------------------------ válvula de marcha lenta (analogWrite) */
extern uint32_t mock_iac_duty;
extern int mock_iac_escritas;
extern int mock_iac_pino;

/* ------------------------------------------------------------------ cão de guarda */
extern int mock_wdt_ativo;
extern uint32_t mock_wdt_timeout_ms;
extern int mock_wdt_resets;                     /* chamadas a wdt_reset() */
extern int mock_wdt_estouros;                   /* vezes em que o cão de guarda teria reiniciado a placa */
extern uint64_t mock_wdt_max_intervalo_us;      /* maior tempo entre dois wdt_reset() (ou entre o enable e o primeiro) */

#endif
