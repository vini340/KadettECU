/*
 * kecu_hal.h - o "contrato" entre o núcleo (portátil, em C) e o hardware.
 *
 * O núcleo nunca toca em registrador nem em biblioteca de placa: tudo que depende do chip passa
 * por estas funções. Para portar para outra placa basta escrever um arquivo que as implemente
 * (veja src/hal_esp32.cpp, src/hal_avr.cpp e host/hal_host.c).
 */
#ifndef KECU_HAL_H
#define KECU_HAL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    KECU_ADC_ECT = 0,
    KECU_ADC_IAT,
    KECU_ADC_MAP,
    KECU_ADC_TPS,
    KECU_ADC_VBAT,
    KECU_ADC_OLEO,
    KECU_ADC_LAMBDA,
    KECU_ADC_CANAIS
} kecu_adc_canal_t;

typedef enum {
    KECU_IN_TWO_STEP = 0,
    KECU_IN_LAUNCH,
    KECU_IN_CANAIS
} kecu_in_canal_t;

typedef enum {
    KECU_SAIDA_INJ1 = 0,
    KECU_SAIDA_INJ2,
    KECU_SAIDA_INJ3,
    KECU_SAIDA_INJ4,
    KECU_SAIDA_BOBINA1,
    KECU_SAIDA_BOBINA2,
    KECU_SAIDAS
} kecu_saida_t;

/* Relógios livres: hal_micros dá a volta a cada ~71 min; sempre compare com (int32_t)(a - b). */
uint32_t hal_micros(void);
uint32_t hal_millis(void);

/* Tensão NO PINO do ADC, em mV, já com média de algumas amostras. */
uint16_t hal_adc_ler_mv(kecu_adc_canal_t canal);

/* 1 = chave acionada. A HAL cuida de pull-up e polaridade. */
uint8_t hal_entrada_ativa(kecu_in_canal_t canal);

/* Serial com o PC. Não pode bloquear na leitura. */
int hal_serial_ler(uint8_t *destino, int maximo);
int hal_serial_escrever(const uint8_t *origem, int n);

/* Memória não volátil: um bloco único de n bytes. Devolvem 0 se deu certo. */
int hal_nvm_ler(uint8_t *destino, int n);
int hal_nvm_gravar(const uint8_t *origem, int n);

/* Válvula de marcha lenta (PWM), 0 a 100 %. */
void hal_iac_pwm(float percentual);

/* Seção crítica (desliga interrupções) para compartilhar dados com a ISR do sensor de rotação. */
void hal_secao_entrar(void);
void hal_secao_sair(void);

/*
 * Só são chamadas se KECU_ENABLE_OUTPUTS == 1 e a ECU estiver armada:
 *  - hal_agendar_pulso: liga a saída daqui a atraso_us e a mantém ligada por largura_us.
 *  - hal_desligar_saidas: desliga tudo imediatamente (emergência/desarme).
 */
void hal_agendar_pulso(kecu_saida_t saida, uint32_t atraso_us, uint32_t largura_us);
void hal_desligar_saidas(void);

#ifdef __cplusplus
}
#endif

#endif
