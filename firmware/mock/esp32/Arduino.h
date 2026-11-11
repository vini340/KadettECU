/*
 * Arduino.h (SIMULADO) - a fatia do Arduino-ESP32 que hal_esp32.cpp usa. Veja mock_esp32.h.
 * Os nomes e as assinaturas seguem o Arduino-ESP32 2.0.x; com -DMOCK_ESP_V3 seguem o 3.x.
 */
#ifndef ARDUINO_H_MOCK
#define ARDUINO_H_MOCK

#include <stddef.h>
#include <stdint.h>

#include "mock_esp32.h"

#ifdef MOCK_ESP_V3
#define ESP_ARDUINO_VERSION_MAJOR 3
#else
#define ESP_ARDUINO_VERSION_MAJOR 2
#endif

#define IRAM_ATTR
#define DRAM_ATTR

#define HIGH 0x1
#define LOW 0x0
#define INPUT 0x01
#define OUTPUT 0x03
#define INPUT_PULLUP 0x05
#define RISING 0x01
#define FALLING 0x02
#define CHANGE 0x03

#define digitalPinToInterrupt(p) (p)

typedef enum { ADC_0db = 0, ADC_2_5db, ADC_6db, ADC_11db, ADC_ATTENDB_MAX } adc_attenuation_t;

void pinMode(uint8_t pino, uint8_t modo);
void digitalWrite(uint8_t pino, uint8_t nivel);
int digitalRead(uint8_t pino);
void attachInterrupt(uint8_t pino, void (*fn)(void), int modo);

uint32_t analogReadMilliVolts(uint8_t pino);
void analogReadResolution(uint8_t bits);
void analogSetPinAttenuation(uint8_t pino, adc_attenuation_t atenuacao);

unsigned long millis(void);

/* ---- timer de hardware ---- */
typedef struct hw_timer_s hw_timer_t;
uint64_t timerRead(hw_timer_t *timer);
void timerWrite(hw_timer_t *timer, uint64_t valor);
#ifdef MOCK_ESP_V3
/* 3.x: não existe timerAlarmDisable/Enable/Write; o alarme é (re)programado com timerAlarm(). */
hw_timer_t *timerBegin(uint32_t frequencia);
void timerAttachInterrupt(hw_timer_t *timer, void (*fn)(void));
void timerAlarm(hw_timer_t *timer, uint64_t alarme, bool autoreload, uint64_t reload_count);
#else
hw_timer_t *timerBegin(uint8_t numero, uint16_t divisor, bool contaPraCima);
void timerAttachInterrupt(hw_timer_t *timer, void (*fn)(void), bool borda);
void timerAlarmWrite(hw_timer_t *timer, uint64_t alarme, bool autoreload);
void timerAlarmEnable(hw_timer_t *timer);
void timerAlarmDisable(hw_timer_t *timer);
#endif

/* ---- seção crítica (FreeRTOS/ESP-IDF) ---- */
typedef struct {
    int mock_livre;
} portMUX_TYPE;
#define portMUX_INITIALIZER_UNLOCKED {0}
void mock_secao_entrar(portMUX_TYPE *mux);
void mock_secao_sair(portMUX_TYPE *mux);
#define portENTER_CRITICAL(m) mock_secao_entrar(m)
#define portEXIT_CRITICAL(m) mock_secao_sair(m)
#define portENTER_CRITICAL_ISR(m) mock_secao_entrar(m)
#define portEXIT_CRITICAL_ISR(m) mock_secao_sair(m)

/* ---- PWM (LEDC) ---- */
#ifdef MOCK_ESP_V3
bool ledcAttach(uint8_t pino, uint32_t freq, uint8_t bits);
void ledcWrite(uint8_t pino, uint32_t duty);
#else
double ledcSetup(uint8_t canal, double freq, uint8_t bits);
void ledcAttachPin(uint8_t pino, uint8_t canal);
void ledcWrite(uint8_t canal, uint32_t duty);
#endif

/* ---- serial ---- */
class HardwareSerial {
  public:
    size_t setRxBufferSize(size_t tamanho);
    void begin(unsigned long baud);
    int available(void);
    size_t readBytes(uint8_t *destino, size_t tamanho);
    size_t write(const uint8_t *origem, size_t tamanho);
    int availableForWrite(void);
};
extern HardwareSerial Serial;

#endif
