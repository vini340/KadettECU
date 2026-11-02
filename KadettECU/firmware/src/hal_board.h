/*
 * hal_board.h - o que cada placa (hal_esp32.cpp, hal_avr.cpp) oferece ao main.cpp, além das
 * funções hal_* do núcleo (kecu_hal.h) e das três do agendador (kecu_sched.h).
 *
 * Ordem de chamada no setup():
 *   placa_iniciar();            pinos em estado seguro, serial, memória, relógio e timer
 *   kecu_init();                carrega a calibração guardada e prepara o núcleo
 *   placa_ligar_interrupcoes(); só agora o sinal de rotação pode chamar o núcleo
 * e no loop():
 *   kecu_loop();
 *   placa_laco();               rede de proteção das saídas e cão de guarda
 */
#ifndef HAL_BOARD_H
#define HAL_BOARD_H

void placa_iniciar(void);
void placa_ligar_interrupcoes(void);
void placa_laco(void);

#endif
