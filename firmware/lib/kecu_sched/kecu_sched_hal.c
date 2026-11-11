/*
 * kecu_sched_hal.c - liga o núcleo ao agendador: o planejador chama hal_agendar_pulso e
 * hal_desligar_saidas (veja kecu_hal.h), e aqui elas viram pedidos ao agendador de bordas.
 *
 * Fica num arquivo separado de propósito: quem quiser agendar pelos canais de comparação de saída
 * do próprio chip (por exemplo, no STM32) simplesmente não usa este arquivo e implementa as duas
 * funções à sua maneira. As placas ESP32 e AVR deste projeto usam este.
 */
#include "kecu_sched.h"

void hal_agendar_pulso(kecu_saida_t saida, uint32_t atraso_us, uint32_t largura_us) {
    kecu_sched_pulso(saida, atraso_us, largura_us);
}

void hal_desligar_saidas(void) {
    kecu_sched_cancelar();
}
