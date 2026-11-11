/*
 * hal_esp32.cpp - HAL do núcleo KadettECU para o ESP32 (framework Arduino-ESP32).
 *
 * Escrito para o Arduino-ESP32 2.0.x (platform espressif32 6.x do PlatformIO). Os pontos em que a
 * API muda no 3.x (timer, LEDC, cão de guarda) têm um ramo próprio, escolhido por KECU_ESP_V3.
 *
 * Como o tempo é tratado:
 *   - UM timer de hardware (grupo 0, timer 0) conta microssegundos sem parar. Ele é a fonte de
 *     hal_micros() E da base do alarme que dispara as bordas das saídas (kecu_sched). Assim as
 *     duas coisas nunca divergem.
 *   - A interrupção do dente captura o instante, "ancora" o agendador nele e roda o decodificador
 *     e o planejador do núcleo. As bordas dos pinos saem da interrupção do alarme.
 *
 * Segurança:
 *   - Sem KECU_ENABLE_OUTPUTS os pinos de saída (e o da válvula de marcha lenta) nunca são
 *     configurados como saída.
 *   - Com saídas habilitadas, cada pino recebe primeiro o nível DESLIGADO e só depois vira saída.
 *   - Cão de guarda de 2 s no laço principal: se travar, a placa reinicia e os pull-downs do
 *     hardware mantêm os estágios de potência desligados.
 *
 * ATENÇÃO: este arquivo foi verificado no PC contra um simulador das APIs do Arduino-ESP32
 * (firmware/mock + firmware/teste_placas). Não foi compilado com a cadeia real do ESP32 nem testado
 * em uma placa. Veja docs/HARDWARE.md, "O que ainda precisa ser validado".
 */
#if defined(ARDUINO_ARCH_ESP32)

#include <Arduino.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include <string.h>

#include "hal_board.h"
#include "kecu.h"
#include "kecu_sched.h"
#include "pinagem.h"

#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
#define KECU_ESP_V3 1
#else
#define KECU_ESP_V3 0
#endif

/* Quantas leituras do ADC entram na média de cada canal (cada uma leva algumas dezenas de us). */
#ifndef KECU_ADC_AMOSTRAS
#define KECU_ADC_AMOSTRAS 4
#endif

/* Leituras iguais e seguidas (uma a cada 5 ms) para aceitar a mudança de uma chave: antirruído de 20 ms. */
#ifndef KECU_DEBOUNCE_LEITURAS
#define KECU_DEBOUNCE_LEITURAS 4
#endif

/* O alarme nunca é programado para menos que isto à frente do contador (o hardware precisa de uns 2 us). */
#ifndef KECU_ALARME_MIN_US
#define KECU_ALARME_MIN_US 3
#endif

#ifndef KECU_IAC_FREQ_HZ
#define KECU_IAC_FREQ_HZ 200
#endif
#define IAC_BITS 10
#define IAC_CANAL_LEDC 0

#ifndef KECU_WDT_SEGUNDOS
#define KECU_WDT_SEGUNDOS 2
#endif

#define NVM_NAMESPACE "kecu"
#define NVM_CHAVE "cal"

static hw_timer_t *s_timer;
static portMUX_TYPE s_mux = portMUX_INITIALIZER_UNLOCKED;
#if KECU_ENABLE_OUTPUTS
static uint8_t s_pino_saida[KECU_SAIDAS] = PINOS_SAIDA; /* não-const: fica na RAM, acessível da interrupção */
#endif
static const uint8_t k_pino_adc[KECU_ADC_CANAIS] = PINOS_ADC;
static Preferences s_prefs;
static bool s_nvm_ok;
static uint8_t s_in_estavel[KECU_IN_CANAIS];
static uint8_t s_in_conta[KECU_IN_CANAIS];

/* ------------------------------------------------------------------ relógio */

extern "C" uint32_t IRAM_ATTR hal_micros(void) {
    return (uint32_t)timerRead(s_timer);
}

extern "C" uint32_t hal_millis(void) {
    return (uint32_t)millis(); /* dá a volta só a cada ~49 dias */
}

/* ------------------------------------------------------------------ sensores */

extern "C" uint16_t hal_adc_ler_mv(kecu_adc_canal_t canal) {
    uint32_t soma = 0;
    for (int i = 0; i < KECU_ADC_AMOSTRAS; i++) {
        soma += analogReadMilliVolts(k_pino_adc[canal]); /* já usa a calibração gravada no chip */
    }
    return (uint16_t)(soma / KECU_ADC_AMOSTRAS);
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
    int n = Serial.available();
    if (n <= 0) {
        return 0;
    }
    if (n > maximo) {
        n = maximo;
    }
    return (int)Serial.readBytes(destino, (size_t)n); /* só pede o que já chegou: não espera */
}

extern "C" int hal_serial_escrever(const uint8_t *origem, int n) {
    const int livre = Serial.availableForWrite();
    if (livre <= 0) {
        return 0;
    }
    if (n > livre) {
        n = livre;
    }
    return (int)Serial.write(origem, (size_t)n);
}

/* ------------------------------------------------------------------ memória não volátil (NVS) */

extern "C" int hal_nvm_ler(uint8_t *destino, int n) {
    memset(destino, 0xFF, (size_t)n); /* memória "apagada": o núcleo não acha a assinatura e usa os padrões */
    if (!s_nvm_ok) {
        return 1;
    }
    if ((int)s_prefs.getBytesLength(NVM_CHAVE) != n) {
        return 0; /* nada guardado (ou guardado com outro tamanho) */
    }
    return s_prefs.getBytes(NVM_CHAVE, destino, (size_t)n) == (size_t)n ? 0 : 1;
}

extern "C" int hal_nvm_gravar(const uint8_t *origem, int n) {
    if (!s_nvm_ok) {
        return 1;
    }
    return s_prefs.putBytes(NVM_CHAVE, origem, (size_t)n) == (size_t)n ? 0 : 1;
}

/* ------------------------------------------------------------------ válvula de marcha lenta */

#if KECU_ENABLE_OUTPUTS
static void iac_iniciar(void) {
#if KECU_ESP_V3
    ledcAttach(PIN_IAC, KECU_IAC_FREQ_HZ, IAC_BITS);
    ledcWrite(PIN_IAC, 0);
#else
    ledcSetup(IAC_CANAL_LEDC, KECU_IAC_FREQ_HZ, IAC_BITS);
    ledcWrite(IAC_CANAL_LEDC, 0);
    ledcAttachPin(PIN_IAC, IAC_CANAL_LEDC);
#endif
}

static void iac_escrever(uint32_t duty) {
#if KECU_ESP_V3
    ledcWrite(PIN_IAC, duty);
#else
    ledcWrite(IAC_CANAL_LEDC, duty);
#endif
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
    iac_escrever((uint32_t)(p * (float)((1u << IAC_BITS) - 1u) / 100.0f + 0.5f));
#else
    (void)percentual; /* modo sombra: o pino da válvula nem é configurado */
#endif
}

/* ------------------------------------------------------------------ seção crítica */

extern "C" void hal_secao_entrar(void) {
    portENTER_CRITICAL(&s_mux); /* aceita aninhamento no mesmo núcleo */
}

extern "C" void hal_secao_sair(void) {
    portEXIT_CRITICAL(&s_mux);
}

/* ------------------------------------------------------------------ pinos de saída e timer (kecu_sched) */

extern "C" void IRAM_ATTR hal_saida_escrever(kecu_saida_t saida, uint8_t ligada) {
#if KECU_ENABLE_OUTPUTS
    const bool alto = (ligada != 0) == (KECU_SAIDA_ATIVA_ALTO != 0);
    digitalWrite(s_pino_saida[saida], alto ? HIGH : LOW);
#else
    (void)saida; /* modo sombra: nenhum pino de saída é tocado */
    (void)ligada;
#endif
}

static void IRAM_ATTR isr_timer(void) {
    portENTER_CRITICAL_ISR(&s_mux);
    kecu_sched_no_timer();
    portEXIT_CRITICAL_ISR(&s_mux);
}

extern "C" void IRAM_ATTR hal_timer_armar(uint32_t instante_us) {
    const uint64_t agora = timerRead(s_timer);
    int32_t d = (int32_t)(instante_us - (uint32_t)agora);
    if (d < KECU_ALARME_MIN_US) {
        d = KECU_ALARME_MIN_US; /* instante já passado ou colado: dispara o mais cedo possível */
    }
    const uint64_t alvo = agora + (uint64_t)(uint32_t)d;
#if KECU_ESP_V3
    timerAlarm(s_timer, alvo, false, 0);
#else
    timerAlarmWrite(s_timer, alvo, false);
    timerAlarmEnable(s_timer);
#endif
}

extern "C" void IRAM_ATTR hal_timer_parar(void) {
#if KECU_ESP_V3
    /* O 3.x não tem timerAlarmDisable: empurra o alarme para daqui a ~50 min. Se ele chegar a disparar, a
     * interrupção acha a fila vazia e volta a empurrar: é inofensivo. */
    timerAlarm(s_timer, timerRead(s_timer) + 3000000000ull, false, 0);
#else
    timerAlarmDisable(s_timer);
#endif
}

/* ------------------------------------------------------------------ interrupções dos sensores de rotação */

static void IRAM_ATTR isr_dente(void) {
    const uint32_t t = hal_micros(); /* primeira coisa: o instante do dente */
    portENTER_CRITICAL_ISR(&s_mux);
    kecu_sched_ancorar(t);
    kecu_engine_on_tooth(t);
    portEXIT_CRITICAL_ISR(&s_mux);
}

static void IRAM_ATTR isr_vss(void) {
    const uint32_t t = hal_micros();
    portENTER_CRITICAL_ISR(&s_mux);
    kecu_engine_on_vss(t);
    portEXIT_CRITICAL_ISR(&s_mux);
}

/* ------------------------------------------------------------------ chamadas do main.cpp */

void placa_iniciar(void) {
    /* 1) saídas: nível DESLIGADO primeiro, só depois viram saída (e só com KECU_ENABLE_OUTPUTS) */
#if KECU_ENABLE_OUTPUTS
    for (int i = 0; i < KECU_SAIDAS; i++) {
        digitalWrite(s_pino_saida[i], KECU_SAIDA_ATIVA_ALTO ? LOW : HIGH);
        pinMode(s_pino_saida[i], OUTPUT);
    }
#endif

    /* 2) entradas */
    pinMode(PIN_TWO_STEP, INPUT_PULLUP);
    pinMode(PIN_LAUNCH, INPUT_PULLUP);
    pinMode(PIN_CKP, INPUT);
    pinMode(PIN_VSS, INPUT);

    /* 3) ADC: 12 bits, atenuação de 11 dB (lê até cerca de 3,1 V no pino) */
    analogReadResolution(12);
    for (int i = 0; i < KECU_ADC_CANAIS; i++) {
        analogSetPinAttenuation(k_pino_adc[i], ADC_11db);
    }

    /* 4) relógio de microssegundos e alarme das saídas */
#if KECU_ESP_V3
    s_timer = timerBegin(1000000);
    timerAttachInterrupt(s_timer, &isr_timer);
#else
    s_timer = timerBegin(0, 80, true); /* 80 MHz / 80 = 1 MHz */
    timerAttachInterrupt(s_timer, &isr_timer, true);
#endif
    timerWrite(s_timer, 1000); /* o núcleo não gosta de começar do zero */

    /* 5) válvula de marcha lenta */
#if KECU_ENABLE_OUTPUTS
    iac_iniciar();
#endif

    /* 6) serial com o PC: 115200 baud, buffer de recepção folgado para os comandos de 240 bytes */
    Serial.setRxBufferSize(1024);
    Serial.begin(115200);

    /* 7) memória não volátil */
    s_nvm_ok = s_prefs.begin(NVM_NAMESPACE, false);

    /* 8) agendador (depois do timer e dos pinos) */
    kecu_sched_init();

    /* 9) cão de guarda do laço principal */
#if KECU_ESP_V3
    const esp_task_wdt_config_t wdt = {KECU_WDT_SEGUNDOS * 1000u, 0u, true};
    esp_task_wdt_reconfigure(&wdt);
#else
    esp_task_wdt_init(KECU_WDT_SEGUNDOS, true);
#endif
    esp_task_wdt_add(NULL);
}

void placa_ligar_interrupcoes(void) {
    attachInterrupt(digitalPinToInterrupt(PIN_CKP), isr_dente, KECU_CKP_BORDA_SUBIDA ? RISING : FALLING);
    attachInterrupt(digitalPinToInterrupt(PIN_VSS), isr_vss, RISING);
}

void placa_laco(void) {
    esp_task_wdt_reset();
    kecu_sched_verificar();
}

#endif /* ARDUINO_ARCH_ESP32 */
