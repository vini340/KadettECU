/*
 * Arduino.h (SIMULADO) - a fatia do Arduino AVR (Mega 2560) que hal_avr.cpp usa. Veja mock_avr.h.
 * Os valores das constantes e as assinaturas seguem o núcleo do Arduino AVR.
 */
#ifndef ARDUINO_H_MOCK_AVR
#define ARDUINO_H_MOCK_AVR

#include <stddef.h>
#include <stdint.h>

#include <avr/interrupt.h>
#include <avr/io.h>

#include "mock_avr.h"

#define LOW 0x0
#define HIGH 0x1
#define INPUT 0x0
#define OUTPUT 0x1
#define INPUT_PULLUP 0x2
#define CHANGE 1
#define FALLING 2
#define RISING 3

/* No Mega a interrupção externa 0 é o pino 21, a 4 é o pino 2 etc.; o simulador identifica a interrupção pelo pino. */
#define digitalPinToInterrupt(p) (p)

/* pinos analógicos do Mega */
static const uint8_t A0 = 54;
static const uint8_t A1 = 55;
static const uint8_t A2 = 56;
static const uint8_t A3 = 57;
static const uint8_t A4 = 58;
static const uint8_t A5 = 59;
static const uint8_t A6 = 60;
static const uint8_t A7 = 61;

void pinMode(uint8_t pino, uint8_t modo);
void digitalWrite(uint8_t pino, uint8_t nivel);
int digitalRead(uint8_t pino);
int analogRead(uint8_t pino);
void analogWrite(uint8_t pino, int valor);
void attachInterrupt(uint8_t interrupcao, void (*fn)(void), int modo);
unsigned long millis(void);

class HardwareSerial {
  public:
    void begin(unsigned long baud);
    int available(void);
    int read(void);
    int availableForWrite(void);
    size_t write(const uint8_t *origem, size_t tamanho);
};
extern HardwareSerial Serial;

#endif
