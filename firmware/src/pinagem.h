/*
 * pinagem.h - quais pinos da placa fazem o quê. MUDE AQUI se a sua ligação for outra.
 *
 * Regras que valem para as duas placas:
 *  - Todo sinal que vem do carro passa antes por um circuito de condicionamento (divisor de tensão,
 *    diodo/zener de proteção, filtro). NUNCA ligue 12 V, nem 5 V (no ESP32), direto em um pino.
 *    Veja docs/HARDWARE.md.
 *  - Toda saída (bico, bobina) vai para um estágio de potência (MOSFET/driver) com resistor de
 *    10 k para o GND no gate: se a placa reiniciar ou travar, o pino fica solto e o resistor
 *    mantém o estágio DESLIGADO. Por isso o nível ativo padrão é ALTO.
 *  - Com KECU_ENABLE_OUTPUTS == 0 (modo sombra, o padrão) os pinos de saída NÃO são configurados
 *    como saída: ficam como entrada (alta impedância).
 */
#ifndef PINAGEM_H
#define PINAGEM_H

#include <stdint.h>

#include "kecu_hal.h"

/* 1 = o estágio de potência liga com nível ALTO no pino (gate com pull-down: o padrão seguro). 0 = liga com nível baixo. */
#ifndef KECU_SAIDA_ATIVA_ALTO
#define KECU_SAIDA_ATIVA_ALTO 1
#endif

/* Borda do sinal de rotação que conta como "dente" (a mesma, sempre). */
#ifndef KECU_CKP_BORDA_SUBIDA
#define KECU_CKP_BORDA_SUBIDA 1
#endif

#if defined(ARDUINO_ARCH_ESP32)

/* ---------------------------------------------------------------- ESP32 DevKit (30 ou 38 pinos) */
#define PIN_CKP 18      /* roda fônica, já condicionada para 3,3 V */
#define PIN_VSS 19      /* velocidade do veículo, 3,3 V */
#define PIN_TWO_STEP 16 /* chave para o GND (pull-up interno) */
#define PIN_LAUNCH 17   /* chave para o GND (pull-up interno) */
#define PIN_IAC 4       /* PWM da válvula de marcha lenta (via driver) */

/* Ordem de kecu_saida_t: INJ1, INJ2, INJ3, INJ4, BOBINA1, BOBINA2. */
#define PINOS_SAIDA {21, 22, 23, 13, 25, 26}

/* Ordem de kecu_adc_canal_t: ECT, IAT, MAP, TPS, VBAT, OLEO, LAMBDA. Cinco pinos do ADC1 e um do ADC2 (GPIO 27). */
#define PINOS_ADC {36, 39, 34, 35, 32, 33, 27}

#elif defined(ARDUINO_ARCH_AVR)

/* ---------------------------------------------------------------- Arduino Mega 2560 */
#define PIN_CKP 2       /* INT4 (PE4): roda fônica, 5 V */
#define PIN_VSS 3       /* INT5 (PE5): velocidade, 5 V */
#define PIN_TWO_STEP 30 /* chave para o GND (pull-up interno) */
#define PIN_LAUNCH 31
#define PIN_IAC 9       /* OC2B (Timer2). NÃO use os pinos 11, 12 e 13: são do Timer1, que o agendador ocupa. */

/*
 * As seis saídas ficam todas na PORTA (pinos 22 a 27) para ligar/desligar com UMA instrução de porta
 * dentro da interrupção: bit 0..3 = INJ1..INJ4, bit 4 = BOBINA1, bit 5 = BOBINA2.
 */
#define MASCARA_SAIDAS 0x3F

/* Os mesmos seis pinos, em números do Arduino (PA0 = pino 22 ... PA5 = pino 27): só documentação e testes. */
#define PINOS_SAIDA {22, 23, 24, 25, 26, 27}

/* Ordem de kecu_adc_canal_t: A0 a A6 (5 V, ADC de 10 bits referenciado ao AVcc: use KECU_VREF_MV=5000). */
#define PINOS_ADC {A0, A1, A2, A3, A4, A5, A6}

#endif

#endif
