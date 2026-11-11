/*
 * avr/io.h (SIMULADO) - os registradores do ATmega2560 que hal_avr.cpp usa. Veja mock_avr.h.
 * Cada registrador é um objeto que se comporta como o do chip (ler TCNT1 dá o contador, escrever 1 em TIFR1
 * apaga o aviso, SREG tem o bit I...). Os nomes dos bits são os da avr-libc.
 */
#ifndef MOCK_AVR_IO_H
#define MOCK_AVR_IO_H

#include <stdint.h>

#define _BV(bit) (1 << (bit))

#define TOV1 0
#define OCF1A 1
#define TOIE1 0
#define OCIE1A 1
#define CS10 0
#define CS11 1
#define CS12 2
#define CS20 0
#define CS21 1
#define CS22 2
#define WGM10 0

enum {
    MOCK_REG_SREG = 0,
    MOCK_REG_TIFR1,
    MOCK_REG_TIMSK1,
    MOCK_REG_TCCR1A,
    MOCK_REG_TCCR1B,
    MOCK_REG_TCCR2B,
    MOCK_REG_PORTA,
    MOCK_REG_DDRA,
    MOCK_REG_MCUSR
};
enum { MOCK_REG16_TCNT1 = 0, MOCK_REG16_OCR1A };

uint8_t mock_reg_ler(int id);
void mock_reg_escrever(int id, uint8_t valor);
uint16_t mock_reg16_ler(int id);
void mock_reg16_escrever(int id, uint16_t valor);

template <int ID>
struct MockReg8 {
    operator uint8_t() const { return mock_reg_ler(ID); }
    MockReg8 &operator=(uint8_t v) {
        mock_reg_escrever(ID, v);
        return *this;
    }
    MockReg8 &operator|=(uint8_t v) {
        mock_reg_escrever(ID, (uint8_t)(mock_reg_ler(ID) | v));
        return *this;
    }
    MockReg8 &operator&=(uint8_t v) {
        mock_reg_escrever(ID, (uint8_t)(mock_reg_ler(ID) & v));
        return *this;
    }
};

template <int ID>
struct MockReg16 {
    operator uint16_t() const { return mock_reg16_ler(ID); }
    MockReg16 &operator=(uint16_t v) {
        mock_reg16_escrever(ID, v);
        return *this;
    }
};

extern MockReg8<MOCK_REG_SREG> mock_SREG;
extern MockReg8<MOCK_REG_TIFR1> mock_TIFR1;
extern MockReg8<MOCK_REG_TIMSK1> mock_TIMSK1;
extern MockReg8<MOCK_REG_TCCR1A> mock_TCCR1A;
extern MockReg8<MOCK_REG_TCCR1B> mock_TCCR1B;
extern MockReg8<MOCK_REG_TCCR2B> mock_TCCR2B;
extern MockReg8<MOCK_REG_PORTA> mock_PORTA;
extern MockReg8<MOCK_REG_DDRA> mock_DDRA;
extern MockReg8<MOCK_REG_MCUSR> mock_MCUSR;
extern MockReg16<MOCK_REG16_TCNT1> mock_TCNT1;
extern MockReg16<MOCK_REG16_OCR1A> mock_OCR1A;

#define SREG mock_SREG
#define TIFR1 mock_TIFR1
#define TIMSK1 mock_TIMSK1
#define TCCR1A mock_TCCR1A
#define TCCR1B mock_TCCR1B
#define TCCR2B mock_TCCR2B
#define PORTA mock_PORTA
#define DDRA mock_DDRA
#define MCUSR mock_MCUSR
#define TCNT1 mock_TCNT1
#define OCR1A mock_OCR1A

#endif
