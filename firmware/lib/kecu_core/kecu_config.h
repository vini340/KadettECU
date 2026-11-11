/*
 * kecu_config.h - constantes de compilação do núcleo do firmware KadettECU.
 *
 * Tudo que depende do SEU hardware (sensores, divisores de tensão, placa) está aqui.
 * Os valores abaixo são EXEMPLOS típicos; meça os seus sensores e ajuste antes de ligar em um carro.
 * Veja docs/HARDWARE.md para o roteiro de calibração.
 */
#ifndef KECU_CONFIG_H
#define KECU_CONFIG_H

#define KECU_FW_MAIOR 1
#define KECU_FW_MENOR 0
#define KECU_PROTOCOLO_VERSAO 1

/* Placa (vai no HELLO): 0 = PC/simulador, 1 = ESP32, 2 = STM32, 3 = Arduino (AVR) */
#ifndef KECU_PLACA
#define KECU_PLACA 0
#endif

/*
 * SEGURANÇA - "modo sombra".
 * Com 0 (padrão) o firmware lê sensores, calcula pulso e avanço e fala com o PC, mas NUNCA
 * aciona bicos nem bobina: o comando ARMAR é recusado (NACK 6). Só mude para 1 depois de
 * testar em bancada, sem o motor, conforme docs/HARDWARE.md.
 */
#ifndef KECU_ENABLE_OUTPUTS
#define KECU_ENABLE_OUTPUTS 0
#endif

/*
 * Arma as saídas sozinho ao ligar? Com 0 (padrão) o PC precisa mandar ARMAR depois de cada
 * partida da placa. Com 1 (e KECU_ENABLE_OUTPUTS 1) a ECU arma ao ligar e roda o motor sem o
 * notebook (uso em estrada). Só use 1 depois de validar tudo em bancada e com o motor.
 */
#ifndef KECU_AUTO_ARMAR
#define KECU_AUTO_ARMAR 0
#endif

/* Tem memória não volátil? (EEPROM no AVR, NVS/flash no ESP32) */
#ifndef KECU_TEM_NVM
#define KECU_TEM_NVM 1
#endif

/* ---------------------------------------------------------------- ADC e sensores */

/* Tensão máxima que o ADC lê NO PINO, em mV (ESP32: 3300 com atenuação 11 dB; AVR: 5000). */
#ifndef KECU_VREF_MV
#define KECU_VREF_MV 3300
#endif

/* Razão (tensão do sensor) / (tensão no pino) dos divisores que baixam 5 V para o ADC. 1.0 = sem divisor. */
#ifndef KECU_RAZAO_5V
#define KECU_RAZAO_5V 1.6f   /* ex.: 12 k em série + 20 k para o GND: 5 V no sensor -> 3,1 V no pino */
#endif
#ifndef KECU_RAZAO_BATERIA
#define KECU_RAZAO_BATERIA 5.0f /* ex.: 40 k + 10 k: 15 V na bateria -> 3,0 V no pino */
#endif

/* Entradas fora de [KECU_ERRO_V_MIN_MV, VREF - KECU_ERRO_V_MIN_MV] indicam fio solto/em curto. */
#define KECU_ERRO_V_MIN_MV 80

/*
 * NTC (temperatura da água e do ar): resistor de pull-up para a tensão de referência do pino e
 * dois pontos medidos com multímetro (os padrões são típicos de sensor de água GM).
 */
#ifndef KECU_NTC_PULLUP_OHM
#define KECU_NTC_PULLUP_OHM 2490.0f
#endif
#define KECU_NTC_T1_C 20.0f
#define KECU_NTC_R1_OHM 3400.0f
#define KECU_NTC_T2_C 100.0f
#define KECU_NTC_R2_OHM 177.0f

/* TPS: potenciômetro, tensões do SENSOR (mV) com a borboleta fechada e toda aberta. */
#define KECU_TPS_V_FECHADO_MV 500
#define KECU_TPS_V_ABERTO_MV 4500

/* MAP: dois pontos (tensão do sensor em mV -> kPa absolutos). Exemplo: sensor tipo MPX4115. */
#define KECU_MAP_V1_MV 425
#define KECU_MAP_KPA1 20.0f
#define KECU_MAP_V2_MV 4700
#define KECU_MAP_KPA2 115.0f

/* Pressão de óleo: 0,5 V = 0 bar, 4,5 V = 10 bar (sensor resistivo/ativo típico de 10 bar). */
#define KECU_OLEO_V1_MV 500
#define KECU_OLEO_BAR1 0.0f
#define KECU_OLEO_V2_MV 4500
#define KECU_OLEO_BAR2 10.0f

/* Sonda banda larga 0-5 V (exemplo: saída "AFR 7,35 a 22,39"). lambda = AFR / KECU_LAMBDA_AFR_REF */
#define KECU_LAMBDA_V1_MV 0
#define KECU_LAMBDA_AFR1 7.35f
#define KECU_LAMBDA_V2_MV 5000
#define KECU_LAMBDA_AFR2 22.39f
#define KECU_LAMBDA_AFR_REF 14.7f

/* ---------------------------------------------------------------- tarefas (microssegundos) */
#define KECU_PERIODO_SENSORES_US 2000
#define KECU_PERIODO_CONTROLE_US 5000
#define KECU_TIMEOUT_MOTOR_US 250000 /* sem dente por este tempo = motor parado */
#define KECU_TIMEOUT_VSS_US 1500000

/* Limite de rotação a partir do qual consideramos "motor funcionando" (abaixo disso é a partida). */
#define KECU_RPM_PARTIDA_MAX 400

/* Em marcha lenta a MAP/TPS podem falhar; valores de emergência quando um sensor está com defeito. */
#define KECU_ECT_EMERGENCIA_C 80.0f
#define KECU_IAT_EMERGENCIA_C 30.0f
#define KECU_MAP_EMERGENCIA_KPA 60.0f

#endif
