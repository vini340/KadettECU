/* avr/interrupt.h (SIMULADO): cli(), sei() e ISR(). O AVR limpa o bit I ao entrar numa interrupção: ela não é aninhada. */
#ifndef MOCK_AVR_INTERRUPT_H
#define MOCK_AVR_INTERRUPT_H

#include <avr/io.h>

void mock_cli(void);
void mock_sei(void);

#define cli() mock_cli()
#define sei() mock_sei()

/* Os vetores viram funções comuns com nome conhecido, que o simulador chama. */
#define TIMER1_OVF_vect mock_vect_timer1_ovf
#define TIMER1_COMPA_vect mock_vect_timer1_compa
#define ISR(vetor) \
    extern "C" void vetor(void); \
    extern "C" void vetor(void)

#endif
