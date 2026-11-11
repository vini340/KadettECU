/*
 * hal_avr.cpp - HAL do núcleo KadettECU para o Arduino Mega 2560 (ATmega2560, 16 MHz, 8 KB de RAM).
 *
 * O Uno/Nano NÃO servem: 2 KB de RAM não comportam o núcleo (veja docs/HARDWARE.md). O Mega serve para
 * bancada, aprendizado e rotações baixas; para o carro rodando use o ESP32 (hal_esp32.cpp). Motivo: o
 * planejador roda dentro da interrupção do dente (só com inteiros, sem ponto flutuante) e faz uma dezena ou
 * mais de divisões e restos de 32 bits por dente; o AVR não tem divisor em hardware e cada uma leva dezenas
 * de microssegundos (estimativa, NÃO medida). Numa roda 60-2 passam só ~154 us entre dentes a 6500 rpm.
 *
 * Como o tempo é tratado:
 *   - O Timer1 (16 bits) conta sem parar a 2 MHz (prescaler 8): 1 tick = 0,5 us. Uma interrupção de
 *     estouro (a cada 32,768 ms) conta as voltas, e assim hal_micros() tem 32 bits de microssegundos.
 *   - O comparador A do MESMO timer é o alarme das bordas das saídas (kecu_sched). Um relógio só:
 *     o instante lido no dente e o instante do alarme nunca divergem.
 *   - O AVR NÃO aninha interrupções (o bit I do SREG é limpo ao entrar), então a interrupção do dente
 *     e a do alarme nunca se interrompem, como o agendador exige (veja kecu_sched.h).
 *
 * Armadilhas do AVR tratadas aqui:
 *   - Alarme "no passado": o comparador só dispara quando TCNT1 == OCR1A. Se OCR1A for escrito com um
 *     valor que o contador já passou, o alarme só vem 32 ms depois (uma volta). Por isso hal_timer_armar
 *     nunca escreve a menos de KECU_ALARME_MIN_TICKS à frente de uma leitura FRESCA do contador.
 *   - Esperas longas: o alarme é programado em passos de no máximo 16 ms (menos que meia volta do
 *     contador); se a borda ainda não venceu, a interrupção reprograma.
 *   - Leitura de 32 bits do relógio: feita com interrupções desligadas, olhando também o aviso de
 *     estouro pendente (o mesmo truque do micros() do Arduino).
 *   - Escrever 1 em TIFR1 APAGA o aviso (não o liga).
 *   - Os 6 pinos de saída ficam na PORTA e mudam com UMA instrução de porta dentro das interrupções.
 *   - Cão de guarda: ao ligar o Mega o bootloader antigo pode deixar o WDT armado; a função em .init3
 *     o desliga antes de main().
 *
 * ATENÇÃO: este arquivo foi verificado no PC contra um simulador dos registradores do ATmega2560 e da
 * API do Arduino (firmware/mock/avr + firmware/teste_placas). NÃO foi compilado com o avr-gcc de
 * verdade nem testado em uma placa, e o tempo de execução do planejador em um AVR NÃO foi medido.
 * Veja docs/HARDWARE.md, "O que ainda precisa ser validado".
 */
#if defined(ARDUINO_ARCH_AVR)

#include <Arduino.h>
#include <EEPROM.h>
#include <avr/interrupt.h>
#include <avr/io.h>
#include <avr/wdt.h>
#include <string.h>

#include "hal_board.h"
#include "kecu.h"
#include "kecu_sched.h"
#include "pinagem.h"

/*
 * Conversões do ADC por canal que entram na média (cada conversão leva ~112 us). A primeira conversão
 * depois de trocar de canal é sempre descartada (o capacitor do ADC ainda tem a carga do canal
 * anterior). Com 1 amostra, ler os 7 canais = 14 conversões = ~1,6 ms por rodada do laço de sensores.
 */
#ifndef KECU_ADC_AMOSTRAS
#define KECU_ADC_AMOSTRAS 1
#endif

/* Leituras iguais e seguidas (uma a cada 5 ms) para aceitar a mudança de uma chave: antirruído de 20 ms. */
#ifndef KECU_DEBOUNCE_LEITURAS
#define KECU_DEBOUNCE_LEITURAS 4
#endif

/* O alarme nunca é programado a menos que isto à frente do contador (8 ticks = 4 us; a conta leva ~2 ticks). */
#ifndef KECU_ALARME_MIN_TICKS
#define KECU_ALARME_MIN_TICKS 8
#endif

/* Maior espera programada de uma vez, em us: 16 ms = 32000 ticks, menos que meia volta do contador de 16 bits. */
#define PASSO_MAX_US 16000

/* Cão de guarda de 2 s. Só vem ligado com as saídas habilitadas (o modo sombra não aciona nada). */
#ifndef KECU_AVR_WDT
#define KECU_AVR_WDT KECU_ENABLE_OUTPUTS
#endif

/* Onde o bloco de calibração começa na EEPROM (o Mega tem 4096 bytes; o bloco usa 889). */
#ifndef KECU_NVM_ENDERECO
#define KECU_NVM_ENDERECO 0
#endif

static volatile uint32_t s_voltas;      /* voltas completas do contador do Timer1 (cada uma vale 32768 us) */
static volatile uint8_t s_secao_nivel;  /* aninhamento de hal_secao_entrar */
static volatile uint8_t s_secao_sreg;   /* SREG de antes da seção mais externa */
static const uint8_t k_pino_adc[KECU_ADC_CANAIS] = PINOS_ADC;
static uint8_t s_in_estavel[KECU_IN_CANAIS];
static uint8_t s_in_conta[KECU_IN_CANAIS];

/* ------------------------------------------------------------------ relógio */

/* Microssegundos de 32 bits. Chamar com as interrupções desligadas (dentro de uma interrupção ou depois de cli()). */
static uint32_t micros_sem_cli(void) {
    const uint16_t ticks = TCNT1;
    uint32_t voltas = s_voltas;
    /* o contador deu a volta mas a interrupção de estouro ainda não rodou (estamos com as interrupções desligadas) */
    if ((TIFR1 & _BV(TOV1)) != 0 && ticks < 0x8000u) {
        voltas++;
    }
    return (voltas << 15) | (uint32_t)(ticks >> 1);
}

extern "C" uint32_t hal_micros(void) {
    const uint8_t sreg = SREG;
    cli();
    const uint32_t t = micros_sem_cli();
    SREG = sreg;
    return t;
}

extern "C" uint32_t hal_millis(void) {
    return (uint32_t)millis(); /* Timer0 do Arduino; dá a volta só a cada ~49 dias */
}

ISR(TIMER1_OVF_vect) {
    s_voltas++;
}

ISR(TIMER1_COMPA_vect) {
    kecu_sched_no_timer();
}

/* ------------------------------------------------------------------ sensores */

extern "C" uint16_t hal_adc_ler_mv(kecu_adc_canal_t canal) {
    const uint8_t pino = k_pino_adc[canal];
    (void)analogRead(pino); /* descartada: ainda carrega o canal anterior */
    uint32_t soma = 0;
    for (uint8_t i = 0; i < KECU_ADC_AMOSTRAS; i++) {
        soma += (uint32_t)analogRead(pino);
    }
    const uint32_t divisor = (uint32_t)KECU_ADC_AMOSTRAS * 1023u;
    return (uint16_t)((soma * (uint32_t)KECU_VREF_MV + divisor / 2u) / divisor); /* AVcc = KECU_VREF_MV (5000) */
}

extern "C" uint8_t hal_entrada_ativa(kecu_in_canal_t canal) {
    const uint8_t pino = canal == KECU_IN_TWO_STEP ? PIN_TWO_STEP : PIN_LAUNCH;
    const uint8_t leitura = digitalRead(pino) == LOW ? 1 : 0; /* chave para o GND: nível baixo = acionada */
    if (leitura == s_in_estavel[canal]) {
        s_in_conta[canal] = 0;
    } else if (++s_in_conta[canal] >= KECU_DEBOUNCE_LEITURAS) {
        s_in_estavel[canal] = leitura;
        s_in_conta[canal] = 0;
    }
    return s_in_estavel[canal];
}

/* ------------------------------------------------------------------ serial com o PC */

extern "C" int hal_serial_ler(uint8_t *destino, int maximo) {
    int n = 0;
    while (n < maximo && Serial.available() > 0) { /* só o que já chegou: nunca espera */
        const int c = Serial.read();
        if (c < 0) {
            break;
        }
        destino[n++] = (uint8_t)c;
    }
    return n;
}

extern "C" int hal_serial_escrever(const uint8_t *origem, int n) {
    const int livre = Serial.availableForWrite();
    if (livre <= 0) {
        return 0;
    }
    if (n > livre) {
        n = livre; /* Serial.write espera quando o buffer enche: só escreve o que cabe */
    }
    return (int)Serial.write(origem, (size_t)n);
}

/* ------------------------------------------------------------------ memória não volátil (EEPROM) */

extern "C" int hal_nvm_ler(uint8_t *destino, int n) {
    if (n < 0 || n > (int)EEPROM.length() - KECU_NVM_ENDERECO) {
        return 1;
    }
    for (int i = 0; i < n; i++) {
        destino[i] = EEPROM.read(KECU_NVM_ENDERECO + i);
    }
    return 0;
}

extern "C" int hal_nvm_gravar(const uint8_t *origem, int n) {
    if (n < 0 || n > (int)EEPROM.length() - KECU_NVM_ENDERECO) {
        return 1;
    }
    /* update() só grava o byte que mudou; cada gravação leva ~3,4 ms, então um bloco novo leva ~3 s */
    for (int i = 0; i < n; i++) {
        EEPROM.update(KECU_NVM_ENDERECO + i, origem[i]);
#if KECU_AVR_WDT
        wdt_reset(); /* a gravação passa de 2 s: alimenta o cão de guarda a cada byte */
#endif
    }
    return 0;
}

/* ------------------------------------------------------------------ válvula de marcha lenta */

#if KECU_ENABLE_OUTPUTS
static void iac_iniciar(void) {
    digitalWrite(PIN_IAC, LOW);
    pinMode(PIN_IAC, OUTPUT);
    /* Timer2 em PWM de fase corrigida: prescaler 128 dá 16 MHz / (128 x 510) = 245 Hz (o padrão do Arduino é 490 Hz) */
    TCCR2B = (uint8_t)((TCCR2B & (uint8_t)~(_BV(CS22) | _BV(CS21) | _BV(CS20))) | _BV(CS22) | _BV(CS20));
    analogWrite(PIN_IAC, 0);
}
#endif

extern "C" void hal_iac_pwm(float percentual) {
#if KECU_ENABLE_OUTPUTS
    /* desarmada, a válvula fica sem comando (duty 0): a ECU só mexe nos atuadores quando armada */
    float p = g_kecu.armado ? percentual : 0.0f;
    if (p < 0.0f) {
        p = 0.0f;
    }
    if (p > 100.0f) {
        p = 100.0f;
    }
    analogWrite(PIN_IAC, (int)(p * 255.0f / 100.0f + 0.5f));
#else
    (void)percentual; /* modo sombra: o pino da válvula nem é configurado */
#endif
}

/* ------------------------------------------------------------------ seção crítica */

extern "C" void hal_secao_entrar(void) {
    const uint8_t sreg = SREG;
    cli();
    if (s_secao_nivel == 0) {
        s_secao_sreg = sreg; /* dentro de uma interrupção o bit I já vem limpo: a saída então não reabilita nada */
    }
    s_secao_nivel++;
}

extern "C" void hal_secao_sair(void) {
    if (s_secao_nivel > 0 && --s_secao_nivel == 0) {
        SREG = s_secao_sreg;
    }
}

/* ------------------------------------------------------------------ pinos de saída e alarme (kecu_sched) */

extern "C" void hal_saida_escrever(kecu_saida_t saida, uint8_t ligada) {
#if KECU_ENABLE_OUTPUTS
    /* sempre dentro de uma interrupção ou de uma seção crítica: ler-modificar-escrever na PORTA é seguro */
    const uint8_t bit = (uint8_t)(1u << (unsigned)saida);
    if ((ligada != 0) == (KECU_SAIDA_ATIVA_ALTO != 0)) {
        PORTA |= bit;
    } else {
        PORTA &= (uint8_t)~bit;
    }
#else
    (void)saida; /* modo sombra: nenhum pino de saída é tocado */
    (void)ligada;
#endif
}

extern "C" void hal_timer_armar(uint32_t instante_us) {
    const uint8_t sreg = SREG;
    cli();
    const uint16_t ticks = TCNT1;
    uint32_t voltas = s_voltas;
    if ((TIFR1 & _BV(TOV1)) != 0 && ticks < 0x8000u) {
        voltas++;
    }
    const uint32_t agora_us = (voltas << 15) | (uint32_t)(ticks >> 1);

    int32_t d_us = (int32_t)(instante_us - agora_us);
    if (d_us > (int32_t)PASSO_MAX_US) {
        d_us = (int32_t)PASSO_MAX_US; /* espera longa: acorda no meio do caminho e reprograma */
    }
    int32_t d_ticks = d_us * 2;
    if (d_ticks < (int32_t)KECU_ALARME_MIN_TICKS) {
        d_ticks = (int32_t)KECU_ALARME_MIN_TICKS; /* já venceu ou está colado: o mais cedo possível */
    }
    uint16_t alvo = (uint16_t)(ticks + (uint16_t)d_ticks);

    /* a conta acima gastou alguns ticks: confere contra uma leitura fresca para nunca escrever no passado */
    const uint16_t fresco = TCNT1;
    if ((int16_t)(alvo - fresco) < (int16_t)KECU_ALARME_MIN_TICKS) {
        alvo = (uint16_t)(fresco + (uint16_t)KECU_ALARME_MIN_TICKS);
    }
    OCR1A = alvo;
    TIFR1 = _BV(OCF1A); /* apaga um aviso antigo (escrever 1 apaga) */
    TIMSK1 |= _BV(OCIE1A);
    SREG = sreg;
}

extern "C" void hal_timer_parar(void) {
    const uint8_t sreg = SREG;
    cli();
    TIMSK1 &= (uint8_t)~_BV(OCIE1A);
    TIFR1 = _BV(OCF1A);
    SREG = sreg;
}

/* ------------------------------------------------------------------ interrupções dos sensores de rotação */

/* O Arduino chama estas funções com as interrupções desligadas e o AVR não aninha: nada as interrompe. */
static void isr_dente(void) {
    const uint32_t t = micros_sem_cli(); /* primeira coisa: o instante do dente */
    kecu_sched_ancorar(t);
    kecu_engine_on_tooth(t);
}

static void isr_vss(void) {
    kecu_engine_on_vss(micros_sem_cli());
}

/* ------------------------------------------------------------------ chamadas do main.cpp */

void placa_iniciar(void) {
    /* 1) saídas: nível DESLIGADO no registrador de dados ANTES de a direção virar saída (e só com KECU_ENABLE_OUTPUTS) */
#if KECU_ENABLE_OUTPUTS
    if (KECU_SAIDA_ATIVA_ALTO) {
        PORTA &= (uint8_t)~MASCARA_SAIDAS; /* porta ainda é entrada: só garante que o pull-up interno está desligado */
    } else {
        PORTA |= MASCARA_SAIDAS;
    }
    DDRA |= MASCARA_SAIDAS;
#endif

    /* 2) entradas */
    pinMode(PIN_TWO_STEP, INPUT_PULLUP);
    pinMode(PIN_LAUNCH, INPUT_PULLUP);
    pinMode(PIN_CKP, INPUT);
    pinMode(PIN_VSS, INPUT);

    /* 3) Timer1: modo normal, prescaler 8 (2 MHz), só a interrupção de estouro. O Arduino o deixa em PWM de 8 bits. */
    {
        const uint8_t sreg = SREG;
        cli();
        TCCR1A = 0;
        TCCR1B = 0; /* para o contador enquanto mexe nele */
        TIMSK1 = 0;
        TIFR1 = 0xFF;
        s_voltas = 0;
        TCNT1 = 2000; /* 1000 us: o núcleo não gosta de começar do zero */
        TCCR1B = (uint8_t)_BV(CS11);
        TIMSK1 = (uint8_t)_BV(TOIE1);
        SREG = sreg;
    }

    /* 4) válvula de marcha lenta */
#if KECU_ENABLE_OUTPUTS
    iac_iniciar();
#endif

    /* 5) serial com o PC (os buffers de 256/128 bytes vêm do platformio.ini) */
    Serial.begin(115200);

    /* 6) agendador (depois do timer e dos pinos) */
    kecu_sched_init();

    /* 7) cão de guarda do laço principal */
#if KECU_AVR_WDT
    wdt_enable(WDTO_2S);
#endif
}

void placa_ligar_interrupcoes(void) {
    attachInterrupt(digitalPinToInterrupt(PIN_CKP), isr_dente, KECU_CKP_BORDA_SUBIDA ? RISING : FALLING);
    attachInterrupt(digitalPinToInterrupt(PIN_VSS), isr_vss, RISING);
}

void placa_laco(void) {
#if KECU_AVR_WDT
    wdt_reset();
#endif
    kecu_sched_verificar();
}

/*
 * Depois de um reset do cão de guarda o WDT continua armado com o menor tempo (16 ms) e reinicia a placa
 * de novo antes de o setup() chegar: um laço infinito. Esta função roda antes de main() e o desliga
 * (o padrão recomendado pela avr-libc). Não existe no simulador do PC.
 */
#if defined(__AVR__)
void kecu_wdt_desligar_no_boot(void) __attribute__((naked, used, section(".init3")));
void kecu_wdt_desligar_no_boot(void) {
    MCUSR = 0;
    wdt_disable();
}
#endif

#endif /* ARDUINO_ARCH_AVR */
