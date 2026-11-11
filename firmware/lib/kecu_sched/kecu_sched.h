/*
 * kecu_sched.h - agendador de bordas (liga/desliga) das saídas de bico e bobina.
 *
 * O planejador do núcleo (kecu_engine.c) pede "ligue a saída X daqui a A microssegundos e mantenha
 * ligada por L microssegundos" (hal_agendar_pulso). Este módulo transforma cada pedido em DUAS
 * bordas (liga e desliga) numa fila ordenada por instante e usa UM timer de hardware, sempre
 * programado para a borda mais próxima. Assim o instante do pino não depende do que o laço
 * principal estiver fazendo, e o código é igual para ESP32, AVR e qualquer outra placa: cada placa
 * só implementa as três funções hal_saida_escrever / hal_timer_armar / hal_timer_parar.
 *
 * REGRAS DE SEGURANÇA (todas testadas em firmware/host/test_sched.c):
 *   1. A borda "liga" e a borda "desliga" entram juntas na fila, ou nenhuma entra. Nunca existe
 *      uma saída ligada sem a borda de desligamento já agendada.
 *   2. Largura máxima de KECU_SCHED_LARGURA_MAX_US por pulso; largura 0 é ignorada.
 *   3. Fila cheia: o pulso novo é descartado inteiro (e contado), a fila existente não é afetada.
 *   4. kecu_sched_verificar() (chamada a cada volta do laço principal) executa as bordas que o timer
 *      deixou passar e DESLIGA à força qualquer saída ligada há mais que o limite.
 *   5. kecu_sched_cancelar() zera a fila e coloca todas as saídas no nível desligado (desarme).
 *   6. Empate de instante: "desliga" antes de "liga". Para a bobina isso preserva a centelha (o
 *      desligamento É a centelha); para o bico, um vão de 1 us é irrelevante.
 *   7. Pulso que chega depois da hora (a interrupção do dente demorou): o BICO abre agora e mantém a
 *      largura pedida (o que importa é a quantidade de combustível); a BOBINA liga agora mas desliga
 *      no instante original (o que importa é o ângulo da centelha; só a carga fica um pouco menor).
 *
 * CONCORRÊNCIA: kecu_sched_ancorar/pulso rodam na interrupção do dente e kecu_sched_no_timer na
 * interrupção do timer; a placa deve garantir que as duas NÃO se interrompem (no AVR isso é o
 * padrão; no ESP32 use a mesma seção crítica nas duas). verificar/cancelar/init rodam no laço
 * principal e protegem-se sozinhas com hal_secao_entrar/hal_secao_sair.
 */
#ifndef KECU_SCHED_H
#define KECU_SCHED_H

#include <stdint.h>

#include "kecu_hal.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Bordas pendentes (2 por pulso). 24 = 12 pulsos em voo: sobra para 4 bicos + 2 bobinas. */
#ifndef KECU_SCHED_BORDAS
#define KECU_SCHED_BORDAS 24
#endif

/* Nenhum pulso fica ligado por mais que isto (bico ou bobina), aconteça o que acontecer no cálculo. */
#ifndef KECU_SCHED_LARGURA_MAX_US
#define KECU_SCHED_LARGURA_MAX_US 60000u
#endif

/* Atraso máximo aceito entre o dente e o início do pulso (o planejador nunca passa de poucas dezenas de ms). */
#ifndef KECU_SCHED_ATRASO_MAX_US
#define KECU_SCHED_ATRASO_MAX_US 250000u
#endif

/* Uma âncora mais velha que isto não é do dente atual: usa-se a hora de agora. */
#ifndef KECU_SCHED_ANCORA_MAX_US
#define KECU_SCHED_ANCORA_MAX_US 2000u
#endif

/* Folga (acima da largura máxima) para o vigia de saída ligada demais disparar. */
#ifndef KECU_SCHED_VIGIA_FOLGA_US
#define KECU_SCHED_VIGIA_FOLGA_US 5000u
#endif

/* ------------------------------------------------------------------ fornecido pela PLACA */

/* nivel 1 = driver LIGADO (bico aberto / bobina carregando); 0 = desligado. A polaridade do pino é da placa. */
void hal_saida_escrever(kecu_saida_t saida, uint8_t ligada);

/*
 * Pede a chamada de kecu_sched_no_timer() na interrupção do timer quando hal_micros() chegar a
 * instante_us (comparação com sinal, como em todo o núcleo). Se esse instante já passou ou está a
 * poucos microssegundos, a placa deve chamar o mais cedo possível (nunca deixar para a próxima
 * volta do contador do timer). Uma nova chamada substitui a anterior.
 */
void hal_timer_armar(uint32_t instante_us);
void hal_timer_parar(void);

/* ------------------------------------------------------------------ API do agendador */

void kecu_sched_init(void);

/* Chamar na interrupção do dente, ANTES de kecu_engine_on_tooth(t_us): os atrasos do planejador
 * são medidos a partir deste instante, não de quando a interrupção conseguiu terminar o cálculo. */
void kecu_sched_ancorar(uint32_t t_us);

/* Chamada por hal_agendar_pulso (veja kecu_sched_hal.c). */
void kecu_sched_pulso(kecu_saida_t saida, uint32_t atraso_us, uint32_t largura_us);

/* Interrupção do timer: executa as bordas vencidas e programa a próxima. */
void kecu_sched_no_timer(void);

/* Laço principal: rede de proteção (timer atrasado/perdido, saída ligada demais). */
void kecu_sched_verificar(void);

/* Desarme: limpa a fila e desliga todas as saídas agora. */
void kecu_sched_cancelar(void);

/* Diagnóstico. */
uint8_t kecu_sched_pendentes(void);
uint8_t kecu_sched_saida_ligada(kecu_saida_t saida);
uint32_t kecu_sched_descartados(void); /* fila cheia ou atraso absurdo */
uint32_t kecu_sched_atrasados(void);   /* pulso de BOBINA que já tinha terminado quando chegou */
uint32_t kecu_sched_forcados(void);    /* vezes em que o vigia desligou uma saída à força */

#ifdef __cplusplus
}
#endif

#endif
