/*
 * test_sched.c - testes do agendador de bordas das saídas (lib/kecu_sched), no PC, com relógio virtual.
 *
 * A "placa" aqui é simulada por este arquivo: ele implementa hal_saida_escrever (registra cada
 * mudança de nível de cada pino, com o instante) e hal_timer_armar/parar (um alarme que o teste
 * dispara no instante certo, opcionalmente com atraso de interrupção). O resto do HAL (relógio,
 * ADC, serial, memória) vem de hal_host.c.
 *
 *   Parte 1 - unidade: fila, âncora, empates, limites, fila cheia, desarme, vigia, volta do relógio.
 *   Parte 2 - integração: planejador do núcleo + agendador + roda 60-2 simulada. Mede o ÂNGULO do
 *             virabrequim em que cada pino muda de nível, em várias rotações e atrasos de interrupção.
 *   Parte 3 - sistema: kecu_loop() completo; ARMAR / desarmar pelo protocolo, motor parando.
 *
 * Compila com KECU_ENABLE_OUTPUTS=1 (veja o Makefile), e também com KECU_BOBINAS=2.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "hal_host.h"
#include "kecu.h"
#include "kecu_proto.h"
#include "kecu_sched.h"

static int g_ok;
static int g_falhas;

#define CHECK(cond, ...)                                                                                       \
    do {                                                                                                       \
        if (cond) {                                                                                            \
            g_ok++;                                                                                            \
        } else {                                                                                               \
            g_falhas++;                                                                                        \
            printf("  FALHOU (linha %d): ", __LINE__);                                                         \
            printf(__VA_ARGS__);                                                                               \
            printf("\n");                                                                                      \
        }                                                                                                      \
    } while (0)

static void secao(const char *titulo) {
    printf("[%s]\n", titulo);
}

/* ================================================================== placa simulada: pinos e timer */

typedef struct {
    uint64_t t_us;
    uint8_t saida;
    uint8_t nivel;
} transicao_t;

#define MAX_TR 400000
static transicao_t g_tr[MAX_TR];
static int g_tr_n;
static uint8_t g_pino[KECU_SAIDAS];
static int g_escritas;       /* chamadas a hal_saida_escrever, mudando ou não o nível */
static int g_alarme_ativo;
static uint32_t g_alarme_us; /* valor de 32 bits, como o agendador o entrega */
static int g_timer_perdido;  /* gancho: 1 = o alarme nunca dispara (testa kecu_sched_verificar) */
static uint32_t g_lat_timer_us; /* atraso da interrupção do timer depois do instante programado */

void hal_saida_escrever(kecu_saida_t saida, uint8_t ligada) {
    const uint8_t novo = ligada != 0u ? 1u : 0u;
    g_escritas++;
    if (g_pino[saida] == novo) {
        return; /* o pino físico não muda */
    }
    g_pino[saida] = novo;
    if (g_tr_n < MAX_TR) {
        g_tr[g_tr_n].t_us = host_tempo_us();
        g_tr[g_tr_n].saida = (uint8_t)saida;
        g_tr[g_tr_n].nivel = novo;
        g_tr_n++;
    }
}

void hal_timer_armar(uint32_t instante_us) {
    g_alarme_ativo = 1;
    g_alarme_us = instante_us;
}

void hal_timer_parar(void) {
    g_alarme_ativo = 0;
}

static void tempo_para(uint64_t t) {
    if (t > host_tempo_us()) {
        host_tempo_definir_us(t);
    }
}

/* Instante absoluto (64 bits) do alarme, reconstruído do valor de 32 bits. */
static uint64_t alarme_abs_us(void) {
    const uint64_t agora = host_tempo_us();
    const int32_t d = (int32_t)(g_alarme_us - (uint32_t)agora);
    return d <= 0 ? agora : agora + (uint32_t)d;
}

/* Deixa o tempo correr até alvo_us, disparando o alarme do timer nos instantes certos. */
static void correr_ate(uint64_t alvo_us) {
    int guarda = 0;
    while (g_alarme_ativo && !g_timer_perdido && guarda++ < 1000000) {
        const uint64_t quando = alarme_abs_us() + g_lat_timer_us;
        if (quando > alvo_us) {
            break;
        }
        tempo_para(quando);
        g_alarme_ativo = 0; /* o alarme de hardware desarma ao disparar */
        kecu_sched_no_timer();
    }
    CHECK(guarda < 1000000, "o alarme do timer não ficou preso em laço infinito");
    tempo_para(alvo_us);
}

static void reiniciar(uint64_t t0_us) {
    host_tempo_real = 0;
    host_tempo_definir_us(t0_us);
    host_secao_nivel = 0;
    g_alarme_ativo = 0;
    g_timer_perdido = 0;
    g_lat_timer_us = 0;
    memset(g_pino, 0, sizeof g_pino);
    kecu_sched_init();
    g_tr_n = 0;
    g_escritas = 0;
}

static void imprimir_tr(void) {
    printf("    transições (%d):", g_tr_n);
    for (int i = 0; i < g_tr_n && i < 16; i++) {
        printf(" [%llu s%d=%d]", (unsigned long long)g_tr[i].t_us, (int)g_tr[i].saida, (int)g_tr[i].nivel);
    }
    printf("\n");
}

static int tr_igual(int i, uint64_t t, kecu_saida_t s, int nivel) {
    return i < g_tr_n && g_tr[i].t_us == t && g_tr[i].saida == (uint8_t)s && g_tr[i].nivel == (uint8_t)nivel;
}

#define CHECK_TR(i, t, s, n)                                                                                   \
    do {                                                                                                       \
        if (tr_igual((i), (uint64_t)(t), (s), (n))) {                                                          \
            g_ok++;                                                                                            \
        } else {                                                                                               \
            g_falhas++;                                                                                        \
            printf("  FALHOU (linha %d): transição %d esperada {t=%llu saída=%d nível=%d}\n", __LINE__, (i),   \
                   (unsigned long long)(t), (int)(s), (int)(n));                                               \
            imprimir_tr();                                                                                     \
        }                                                                                                      \
    } while (0)

static int todos_desligados(void) {
    for (int i = 0; i < KECU_SAIDAS; i++) {
        if (g_pino[i] != 0u) {
            return 0;
        }
    }
    return 1;
}

/* ================================================================== parte 1: unidade */

static void teste_pulso_simples(void) {
    secao("Pulso simples: liga e desliga nos instantes pedidos");
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 500, 2000);
    CHECK(kecu_sched_pendentes() == 2, "duas bordas na fila (%u)", kecu_sched_pendentes());
    CHECK(g_alarme_ativo && g_alarme_us == 1500, "timer armado para a primeira borda (%u)", g_alarme_us);
    CHECK(g_pino[KECU_SAIDA_INJ1] == 0 && g_tr_n == 0, "nada acontece antes da hora");
    correr_ate(1499);
    CHECK(g_tr_n == 0, "1 us antes: ainda desligado");
    correr_ate(1500);
    CHECK_TR(0, 1500, KECU_SAIDA_INJ1, 1);
    CHECK(kecu_sched_saida_ligada(KECU_SAIDA_INJ1) == 1, "o agendador sabe que está ligada");
    CHECK(g_alarme_ativo && g_alarme_us == 3500, "timer reprogramado para o desligamento (%u)", g_alarme_us);
    correr_ate(3499);
    CHECK(g_tr_n == 1, "1 us antes do fim: ainda ligada");
    correr_ate(3500);
    CHECK_TR(1, 3500, KECU_SAIDA_INJ1, 0);
    CHECK(kecu_sched_pendentes() == 0 && !g_alarme_ativo, "fila vazia e timer parado");
    CHECK(kecu_sched_descartados() == 0 && kecu_sched_atrasados() == 0 && kecu_sched_forcados() == 0,
          "nenhum contador de problema");
}

static void teste_ancora(void) {
    secao("Âncora: o atraso conta a partir do dente, não de quando a interrupção terminou o cálculo");
    reiniciar(1000);
    tempo_para(1080); /* a interrupção só chegou a pedir o pulso 80 us depois do dente */
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ2, 500, 2000);
    correr_ate(10000);
    CHECK_TR(0, 1500, KECU_SAIDA_INJ2, 1);
    CHECK_TR(1, 3500, KECU_SAIDA_INJ2, 0);

    reiniciar(1000);
    kecu_sched_ancorar(1000);
    tempo_para(6000); /* âncora com 5 ms: não é do dente atual */
    kecu_sched_pulso(KECU_SAIDA_INJ2, 500, 2000);
    correr_ate(20000);
    CHECK_TR(0, 6500, KECU_SAIDA_INJ2, 1);
    CHECK_TR(1, 8500, KECU_SAIDA_INJ2, 0);

    reiniciar(1000);
    tempo_para(3000); /* sem âncora nenhuma */
    kecu_sched_pulso(KECU_SAIDA_INJ3, 100, 1000);
    correr_ate(20000);
    CHECK_TR(0, 3100, KECU_SAIDA_INJ3, 1);
    CHECK_TR(1, 4100, KECU_SAIDA_INJ3, 0);
}

static void teste_ja_vencido(void) {
    secao("Interrupção demorada: bobina mantém o instante da centelha, bico mantém a largura");
    /* bobina: o início já passou, então liga agora, mas desliga (a centelha) na hora original */
    reiniciar(1000);
    tempo_para(1300);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 100, 2000); /* devia ligar em 1100 e faiscar em 3100 */
    CHECK_TR(0, 1300, KECU_SAIDA_BOBINA1, 1);
    CHECK(kecu_sched_pendentes() == 1, "o desligamento continua agendado");
    correr_ate(5000);
    CHECK_TR(1, 3100, KECU_SAIDA_BOBINA1, 0); /* a centelha não anda */
    CHECK(kecu_sched_atrasados() == 0, "não conta como perdido");

    /* bobina: o pulso inteiro já passou: não liga fora de hora */
    reiniciar(1000);
    tempo_para(2500);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 100, 500); /* 1100 a 1600 */
    CHECK(g_tr_n == 0 && kecu_sched_pendentes() == 0, "bobina: nada é ligado fora de hora");
    CHECK(kecu_sched_atrasados() == 1, "contado como atrasado (%u)", kecu_sched_atrasados());

    /* bico: abre agora e mantém a largura (a quantidade de combustível é a mesma) */
    reiniciar(1000);
    tempo_para(1300);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 2000); /* devia abrir em 1100 */
    CHECK_TR(0, 1300, KECU_SAIDA_INJ1, 1);
    correr_ate(5000);
    CHECK_TR(1, 3300, KECU_SAIDA_INJ1, 0); /* 2000 us depois de abrir */

    /* bico: mesmo com o pulso original inteiro no passado, o combustível é entregue com a largura pedida */
    reiniciar(1000);
    tempo_para(2500);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 500); /* 1100 a 1600 */
    CHECK_TR(0, 2500, KECU_SAIDA_INJ1, 1);
    correr_ate(5000);
    CHECK_TR(1, 3000, KECU_SAIDA_INJ1, 0);
    CHECK(kecu_sched_atrasados() == 0, "bico nunca é contado como atrasado");

    /* o bico que ainda não venceu não é mexido */
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 2000);
    correr_ate(5000);
    CHECK_TR(0, 1100, KECU_SAIDA_INJ1, 1);
    CHECK_TR(1, 3100, KECU_SAIDA_INJ1, 0);
}

static void teste_sobreposicao(void) {
    secao("Dois pulsos sobrepostos na mesma saída: o pino fica ligado até o último desligar");
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 0, 3000); /* 1000 a 4000 */
    correr_ate(2000);
    kecu_sched_ancorar(2000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 0, 3000); /* 2000 a 5000 */
    correr_ate(4500);
    CHECK(g_pino[KECU_SAIDA_INJ1] == 1 && g_tr_n == 1, "o fim do 1º pulso (4000) não desliga o pino (%d transições)", g_tr_n);
    correr_ate(5000);
    CHECK_TR(0, 1000, KECU_SAIDA_INJ1, 1);
    CHECK_TR(1, 5000, KECU_SAIDA_INJ1, 0);
    CHECK(g_tr_n == 2, "só duas mudanças de nível no total (%d)", g_tr_n);
}

static void teste_empate(void) {
    secao("Empate de instante: desliga antes de liga (a centelha da bobina não pode sumir)");
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 1000, 3000); /* 2000 a 5000 */
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 4000, 2000); /* 5000 a 7000 */
    correr_ate(10000);
    CHECK(g_tr_n == 4, "4 mudanças (%d)", g_tr_n);
    CHECK_TR(0, 2000, KECU_SAIDA_BOBINA1, 1);
    CHECK_TR(1, 5000, KECU_SAIDA_BOBINA1, 0);
    CHECK_TR(2, 5000, KECU_SAIDA_BOBINA1, 1);
    CHECK_TR(3, 7000, KECU_SAIDA_BOBINA1, 0);

    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 4000, 2000); /* chegam na ordem inversa */
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 1000, 3000);
    correr_ate(10000);
    CHECK(g_tr_n == 4, "ordem inversa de chegada: 4 mudanças (%d)", g_tr_n);
    CHECK_TR(0, 2000, KECU_SAIDA_BOBINA1, 1);
    CHECK_TR(1, 5000, KECU_SAIDA_BOBINA1, 0);
    CHECK_TR(2, 5000, KECU_SAIDA_BOBINA1, 1);
    CHECK_TR(3, 7000, KECU_SAIDA_BOBINA1, 0);
}

static void teste_ordem_varias_saidas(void) {
    secao("Várias saídas intercaladas saem em ordem de tempo");
    const uint64_t t = 70000;
    reiniciar(t);
    kecu_sched_ancorar((uint32_t)t);
    kecu_sched_pulso(KECU_SAIDA_INJ4, 700, 300);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 200);
    kecu_sched_pulso(KECU_SAIDA_INJ3, 400, 100);
    kecu_sched_pulso(KECU_SAIDA_INJ2, 100, 50);
    correr_ate(t + 5000);
    CHECK(g_tr_n == 8, "8 mudanças (%d)", g_tr_n);
    CHECK_TR(0, t + 100, KECU_SAIDA_INJ1, 1);
    CHECK_TR(1, t + 100, KECU_SAIDA_INJ2, 1);
    CHECK_TR(2, t + 150, KECU_SAIDA_INJ2, 0);
    CHECK_TR(3, t + 300, KECU_SAIDA_INJ1, 0);
    CHECK_TR(4, t + 400, KECU_SAIDA_INJ3, 1);
    CHECK_TR(5, t + 500, KECU_SAIDA_INJ3, 0);
    CHECK_TR(6, t + 700, KECU_SAIDA_INJ4, 1);
    CHECK_TR(7, t + 1000, KECU_SAIDA_INJ4, 0);
}

static void teste_limites(void) {
    secao("Limites: largura 0, largura máxima, atraso absurdo, saída inexistente");
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 0);
    CHECK(kecu_sched_pendentes() == 0 && kecu_sched_descartados() == 0, "largura 0 é simplesmente ignorada");

    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 200000);
    correr_ate(100000);
    CHECK_TR(0, 1100, KECU_SAIDA_INJ1, 1);
    CHECK_TR(1, 1100 + KECU_SCHED_LARGURA_MAX_US, KECU_SAIDA_INJ1, 0);
    CHECK(g_tr_n == 2, "largura limitada a %u us (%d transições)", KECU_SCHED_LARGURA_MAX_US, g_tr_n);

    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, KECU_SCHED_ATRASO_MAX_US + 1u, 100);
    CHECK(kecu_sched_pendentes() == 0 && kecu_sched_descartados() == 1, "atraso acima do máximo é descartado e contado");
    kecu_sched_pulso(KECU_SAIDA_INJ1, KECU_SCHED_ATRASO_MAX_US, 100);
    CHECK(kecu_sched_pendentes() == 2, "atraso exatamente no máximo é aceito");

    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso((kecu_saida_t)KECU_SAIDAS, 0, 100);
    kecu_sched_pulso((kecu_saida_t)99, 0, 100);
    kecu_sched_pulso((kecu_saida_t)-1, 0, 100);
    CHECK(kecu_sched_pendentes() == 0 && g_escritas == 0, "saída inexistente não faz nada");
    CHECK(kecu_sched_saida_ligada((kecu_saida_t)KECU_SAIDAS) == 0, "consulta de saída inexistente devolve 0");
}

static void teste_fila_cheia(void) {
    secao("Fila cheia: o pulso novo é descartado inteiro; os que estavam na fila terminam normalmente");
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    for (int i = 0; i < 12; i++) {
        kecu_sched_pulso((kecu_saida_t)(i % KECU_SAIDAS), 1000u + (uint32_t)i * 1000u, 500);
    }
    CHECK(kecu_sched_pendentes() == KECU_SCHED_BORDAS, "12 pulsos = %u bordas (%u)", KECU_SCHED_BORDAS, kecu_sched_pendentes());
    CHECK(kecu_sched_descartados() == 0, "nada descartado até aqui");
    kecu_sched_pulso(KECU_SAIDA_INJ1, 20000, 500);
    CHECK(kecu_sched_descartados() == 1 && kecu_sched_pendentes() == KECU_SCHED_BORDAS,
          "o 13º é descartado (%u) e a fila fica intacta (%u)", kecu_sched_descartados(), kecu_sched_pendentes());
    correr_ate(60000);
    CHECK(g_tr_n == 24, "os 12 pulsos terminaram: 24 mudanças (%d)", g_tr_n);
    CHECK(todos_desligados(), "tudo desligado no fim");
    int ligas = 0;
    int desligas = 0;
    for (int i = 0; i < g_tr_n; i++) {
        ligas += g_tr[i].nivel == 1u;
        desligas += g_tr[i].nivel == 0u;
    }
    CHECK(ligas == 12 && desligas == 12, "cada liga tem o seu desliga (%d/%d)", ligas, desligas);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 500);
    CHECK(kecu_sched_pendentes() == 2, "depois de esvaziar, volta a aceitar pulsos");
}

static void teste_cancelar(void) {
    secao("Desarme: cancelar limpa a fila e desliga tudo na hora");
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 0, 5000);       /* liga já */
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 1000, 3000); /* pendente */
    kecu_sched_pulso(KECU_SAIDA_INJ3, 2000, 1000);    /* pendente */
    CHECK(g_pino[KECU_SAIDA_INJ1] == 1 && kecu_sched_pendentes() == 5, "antes: INJ1 ligado, 5 bordas (%u)", kecu_sched_pendentes());
    kecu_sched_cancelar();
    CHECK(todos_desligados(), "todas as saídas no nível desligado");
    CHECK(kecu_sched_pendentes() == 0 && !g_alarme_ativo, "fila vazia, timer parado");
    CHECK(kecu_sched_saida_ligada(KECU_SAIDA_INJ1) == 0, "o agendador também sabe que desligou");
    CHECK(host_secao_nivel == 0, "seção crítica equilibrada (%d)", host_secao_nivel);
    const int n = g_tr_n;
    correr_ate(30000);
    CHECK(g_tr_n == n, "nada acontece depois do cancelamento (%d -> %d)", n, g_tr_n);
    kecu_sched_cancelar();
    CHECK(todos_desligados() && host_secao_nivel == 0, "cancelar de novo é inofensivo");

    kecu_sched_ancorar(30000);
    kecu_sched_pulso(KECU_SAIDA_INJ2, 100, 500);
    correr_ate(40000);
    CHECK_TR(n, 30100, KECU_SAIDA_INJ2, 1);
    CHECK_TR(n + 1, 30600, KECU_SAIDA_INJ2, 0);
}

static void teste_timer_perdido(void) {
    secao("Timer perdido: kecu_sched_verificar() no laço principal executa as bordas atrasadas");
    reiniciar(1000);
    g_timer_perdido = 1;
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 500, 2000); /* 1500 a 3500 */
    tempo_para(1400);
    kecu_sched_verificar();
    CHECK(g_tr_n == 0, "antes da hora, verificar não faz nada");
    tempo_para(1600);
    kecu_sched_verificar();
    CHECK_TR(0, 1600, KECU_SAIDA_INJ1, 1);
    tempo_para(3000);
    kecu_sched_verificar();
    CHECK(g_tr_n == 1, "ligada até a hora de desligar");
    tempo_para(3700);
    kecu_sched_verificar();
    CHECK_TR(1, 3700, KECU_SAIDA_INJ1, 0);
    CHECK(kecu_sched_pendentes() == 0 && host_secao_nivel == 0, "fila vazia, seção crítica equilibrada");
}

static void teste_vigia(void) {
    secao("Vigia: nenhuma saída fica ligada além do limite");
    reiniciar(1000);
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 0, KECU_SCHED_LARGURA_MAX_US); /* 1000 a 61000 */
    correr_ate(50000);
    kecu_sched_ancorar(50000);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 0, KECU_SCHED_LARGURA_MAX_US); /* 50000 a 110000, emendado no 1º */
    correr_ate(61500);
    CHECK(g_pino[KECU_SAIDA_INJ1] == 1, "o fim do 1º pulso não desliga: o 2º ainda vale");
    kecu_sched_verificar();
    CHECK(g_pino[KECU_SAIDA_INJ1] == 1 && kecu_sched_forcados() == 0, "ainda dentro do limite: o vigia não age");
    const uint64_t limite = 1000u + KECU_SCHED_LARGURA_MAX_US + KECU_SCHED_VIGIA_FOLGA_US;
    correr_ate(limite + 100);
    kecu_sched_verificar();
    CHECK(g_pino[KECU_SAIDA_INJ1] == 0 && kecu_sched_forcados() == 1, "passou do limite: desligado à força (forçados %u)",
          kecu_sched_forcados());
    CHECK(kecu_sched_pendentes() == 0, "e as bordas que sobravam dessa saída saíram da fila (%u)", kecu_sched_pendentes());
    CHECK(host_secao_nivel == 0, "seção crítica equilibrada");
    const int n = g_tr_n;
    correr_ate(130000);
    CHECK(g_tr_n == n, "depois disso, nenhuma mudança extra");
}

static void teste_volta_do_relogio(void) {
    secao("Relógio de 32 bits dando a volta no meio dos pulsos");
    const uint64_t base = 0xFFFFFF00ull;
    reiniciar(base);
    kecu_sched_ancorar((uint32_t)base);
    kecu_sched_pulso(KECU_SAIDA_INJ1, 100, 1000); /* termina depois da volta */
    kecu_sched_pulso(KECU_SAIDA_INJ2, 600, 300);  /* começa depois da volta */
    correr_ate(base + 5000);
    CHECK(g_tr_n == 4, "4 mudanças (%d)", g_tr_n);
    CHECK_TR(0, base + 100, KECU_SAIDA_INJ1, 1);
    CHECK_TR(1, base + 600, KECU_SAIDA_INJ2, 1);
    CHECK_TR(2, base + 900, KECU_SAIDA_INJ2, 0);
    CHECK_TR(3, base + 1100, KECU_SAIDA_INJ1, 0);
}

static void teste_latencia_do_timer(void) {
    secao("Latência da interrupção do timer: as duas bordas atrasam igual, a largura se mantém");
    reiniciar(1000);
    g_lat_timer_us = 7;
    kecu_sched_ancorar(1000);
    kecu_sched_pulso(KECU_SAIDA_BOBINA1, 500, 2000);
    correr_ate(10000);
    CHECK_TR(0, 1507, KECU_SAIDA_BOBINA1, 1);
    CHECK_TR(1, 3507, KECU_SAIDA_BOBINA1, 0);
}

/* ================================================================== roda 60-2 simulada */

typedef struct {
    double rpm;
    double slot_us;
    double prox_us; /* instante (real, em ponto flutuante) do próximo slot da roda */
    int slot;
    double t0_us;   /* instante do 1º dente depois da falha na 1ª volta: ângulo 0 do planejador */
    int tem_t0;
    uint32_t lat_isr_us; /* tempo que a interrupção do dente leva entre capturar o dente e terminar de pedir os pulsos */
} roda_t;

static roda_t g_roda;

static void roda_iniciar(double rpm, uint32_t lat_isr_us) {
    memset(&g_roda, 0, sizeof g_roda);
    g_roda.rpm = rpm;
    g_roda.slot_us = 1e6 / rpm; /* 60 slots por volta */
    g_roda.prox_us = (double)host_tempo_us() + g_roda.slot_us;
    g_roda.lat_isr_us = lat_isr_us;
}

/* O que a interrupção do dente de uma placa faz: captura o instante, ancora o agendador, roda o decodificador/planejador. */
static void roda_um_slot(void) {
    const uint64_t t = (uint64_t)(g_roda.prox_us + 0.5);
    correr_ate(t); /* alarmes do timer que vencem antes deste dente */
    if (g_roda.slot < 58) {
        if (g_roda.slot == 0 && !g_roda.tem_t0) {
            g_roda.t0_us = g_roda.prox_us;
            g_roda.tem_t0 = 1;
        }
        tempo_para(t + g_roda.lat_isr_us); /* a interrupção demora a terminar o cálculo */
        kecu_sched_ancorar((uint32_t)t);
        kecu_engine_on_tooth((uint32_t)t);
    }
    g_roda.slot = (g_roda.slot + 1) % 60;
    g_roda.prox_us += g_roda.slot_us;
}

static void roda_correr_ate(uint64_t fim_us) {
    while (g_roda.rpm > 0.0 && g_roda.prox_us <= (double)fim_us) {
        roda_um_slot();
    }
    correr_ate(fim_us);
}

static double ang_mod(double a) {
    const double r = fmod(a, 360.0);
    return r < 0.0 ? r + 360.0 : r;
}

static double ang_dif(double a, double b) {
    const double d = ang_mod(a - b);
    return d > 180.0 ? d - 360.0 : d;
}

/* ================================================================== parte 2: integração (planejador + agendador) */

typedef struct {
    int faltas_de_fagulha;  /* centelhas (desligamentos de bobina) medidas */
    int por_alvo[2];
    int bobina_errada;
    int dwell_errado;
    int dwell_encurtado;    /* quantas vezes a bobina carregou menos que o pedido (início atrasado pela interrupção) */
    double max_err_fagulha; /* graus */
    int inj_eventos;        /* pulsos do bico 1 */
    int inj_errados_de_angulo;
    int inj_largura_errada;
    int inj_dessincronizados;
    double max_err_inj;
} medida_t;

#define MAX_INJ 4096

/*
 * Lê os pinos registrados de g_tr[ini..] e mede, no ângulo do virabrequim: a centelha (desligamento
 * da bobina) deve cair em PMS - avanço; o bico abre no dente de referência (ângulo 0).
 *
 * Larguras (0 = não confere): o bico mantém a largura pedida (tolerância tol_largura_us); a bobina
 * pode carregar até encurta_max_us A MENOS, quando a interrupção do dente chegou depois da hora de
 * ligar (a centelha, no entanto, não anda).
 */
static medida_t medir(int ini, double rpm, double pms_graus, double avanco_graus, double dwell_esperado_us,
                      double inj_esperado_us, double tol_largura_us, double encurta_max_us) {
    medida_t m;
    memset(&m, 0, sizeof m);
    static uint64_t inj_on[4][MAX_INJ];
    int inj_n[4] = {0, 0, 0, 0};
    uint64_t bob_on[2] = {0, 0};
    int bob_on_ok[2] = {0, 0};
    uint64_t i_on[4] = {0, 0, 0, 0};
    int i_on_ok[4] = {0, 0, 0, 0};
    const double rev_us = 60e6 / rpm;
    const double alvo[2] = {ang_mod(pms_graus - avanco_graus), ang_mod(pms_graus + 180.0 - avanco_graus)};

    for (int i = ini; i < g_tr_n; i++) {
        const transicao_t x = g_tr[i];
        if (x.saida == KECU_SAIDA_BOBINA1 || x.saida == KECU_SAIDA_BOBINA2) {
            const int c = x.saida == KECU_SAIDA_BOBINA1 ? 0 : 1;
            if (x.nivel == 1u) {
                bob_on[c] = x.t_us;
                bob_on_ok[c] = 1;
            } else {
                const double ang = ang_mod(((double)x.t_us - g_roda.t0_us) / rev_us * 360.0);
                const double e0 = fabs(ang_dif(ang, alvo[0]));
                const double e1 = fabs(ang_dif(ang, alvo[1]));
                const int k = e0 <= e1 ? 0 : 1;
                const double e = k == 0 ? e0 : e1;
                m.faltas_de_fagulha++;
                m.por_alvo[k]++;
                if (e > m.max_err_fagulha) {
                    m.max_err_fagulha = e;
                }
                if (x.saida != (uint8_t)(KECU_SAIDA_BOBINA1 + (k % KECU_BOBINAS))) {
                    m.bobina_errada++;
                }
                if (bob_on_ok[c] && dwell_esperado_us > 0.0) {
                    const double dif = (double)(x.t_us - bob_on[c]) - dwell_esperado_us;
                    if (dif > tol_largura_us || dif < -(tol_largura_us + encurta_max_us)) {
                        if (m.dwell_errado < 3) {
                            printf("    dwell fora: liga %llu desliga %llu = %llu us (esperado %.1f, diferença %.1f)\n",
                                   (unsigned long long)bob_on[c], (unsigned long long)x.t_us,
                                   (unsigned long long)(x.t_us - bob_on[c]), dwell_esperado_us, dif);
                        }
                        m.dwell_errado++;
                    }
                    if (dif < -tol_largura_us) {
                        m.dwell_encurtado++;
                    }
                }
                bob_on_ok[c] = 0;
            }
        } else {
            const int b = (int)x.saida - (int)KECU_SAIDA_INJ1;
            if (x.nivel == 1u) {
                i_on[b] = x.t_us;
                i_on_ok[b] = 1;
                if (inj_n[b] < MAX_INJ) {
                    inj_on[b][inj_n[b]++] = x.t_us;
                }
                if (b == 0) {
                    m.inj_eventos++;
                    const double ang = ang_dif(((double)x.t_us - g_roda.t0_us) / rev_us * 360.0, 0.0);
                    if (fabs(ang) > m.max_err_inj) {
                        m.max_err_inj = fabs(ang);
                    }
                }
            } else if (i_on_ok[b] && inj_esperado_us > 0.0
                       && fabs((double)(x.t_us - i_on[b]) - inj_esperado_us) > tol_largura_us) {
                m.inj_largura_errada++;
                i_on_ok[b] = 0;
            }
        }
    }
    for (int b = 1; b < 4; b++) {
        if (inj_n[b] != inj_n[0]) {
            m.inj_dessincronizados++;
            continue;
        }
        for (int j = 0; j < inj_n[0]; j++) {
            if (inj_on[b][j] != inj_on[0][j]) {
                m.inj_dessincronizados++;
            }
        }
    }
    return m;
}

static void integracao_em(double rpm, unsigned pms_x10, uint32_t lat_isr_us, uint32_t lat_timer_us, int checar_larguras) {
    reiniciar(5000000ull);
    g_lat_timer_us = lat_timer_us;
    kecu_engine_init();
    kecu_engine_cfg_t cfg;
    cfg.modo_uniforme = 0;
    cfg.dentes = 60;
    cfg.faltando = 2;
    cfg.cilindros = 4;
    cfg.pms_offset_x10 = (uint16_t)pms_x10;
    cfg.vss_pulsos_km = 4000;
    kecu_engine_config(&cfg);
    kecu_plano_t plano;
    plano.avanco_x10 = 150;
    plano.dwell_us = 3000;
    plano.inj_us = 4000;
    plano.corte_comb = 0;
    plano.corte_ign = 0;
    plano.ativo = 1;
    plano.armado = 1;
    kecu_engine_set_plano(&plano);

    roda_iniciar(rpm, lat_isr_us);
    const uint64_t um_seg = 1000000ull;
    roda_correr_ate(host_tempo_us() + um_seg / 2); /* sincroniza */
    const int ini = g_tr_n;
    roda_correr_ate(host_tempo_us() + um_seg);

    const double rev_us = 60e6 / rpm;
    const double max_dwell_us = rev_us * 0.425; /* o planejador limita o dwell a 85 % do espaço entre centelhas */
    const double dwell_esp = fmin(3000.0, max_dwell_us);
    const medida_t m = medir(ini, rpm, (double)pms_x10 / 10.0, 15.0, checar_larguras ? dwell_esp : 0.0,
                             checar_larguras ? 4000.0 : 0.0, (double)lat_timer_us + 1.0, (double)lat_isr_us);
    const double voltas = rpm / 60.0;
    CHECK(fabs((double)m.faltas_de_fagulha - 2.0 * voltas) <= 3.0, "%.0f rpm: %d centelhas em 1 s (esperado %.0f)", rpm,
          m.faltas_de_fagulha, 2.0 * voltas);
    CHECK(fabs((double)m.inj_eventos - voltas) <= 2.0, "%.0f rpm: %d injeções em 1 s (esperado %.0f)", rpm, m.inj_eventos, voltas);
    CHECK(m.por_alvo[0] > 0 && m.por_alvo[1] > 0 && abs(m.por_alvo[0] - m.por_alvo[1]) <= 2,
          "%.0f rpm: centelhas alternam entre os dois eventos (%d / %d)", rpm, m.por_alvo[0], m.por_alvo[1]);
    CHECK(m.max_err_fagulha < 0.5, "%.0f rpm (PMS %.1f, isr %u us, timer %u us): erro da centelha %.3f graus", rpm,
          (double)pms_x10 / 10.0, lat_isr_us, lat_timer_us, m.max_err_fagulha);
    CHECK(m.max_err_inj < 0.5 + (double)lat_isr_us / rev_us * 360.0, "%.0f rpm: erro do início da injeção %.3f graus", rpm,
          m.max_err_inj);
    CHECK(m.bobina_errada == 0, "%.0f rpm: cada centelha na bobina certa (%d erradas)", rpm, m.bobina_errada);
    CHECK(m.inj_dessincronizados == 0, "%.0f rpm: os 4 bicos abrem juntos (%d diferenças)", rpm, m.inj_dessincronizados);
    if (checar_larguras) {
        CHECK(m.dwell_errado == 0, "%.0f rpm (isr %u us, timer %u us): tempo de carga %.0f us (%d fora)", rpm, lat_isr_us,
              lat_timer_us, dwell_esp, m.dwell_errado);
        CHECK(m.inj_largura_errada == 0, "%.0f rpm (isr %u us, timer %u us): largura de injeção 4000 us (%d fora)", rpm,
              lat_isr_us, lat_timer_us, m.inj_largura_errada);
        if (lat_isr_us == 0u) {
            CHECK(m.dwell_encurtado == 0, "%.0f rpm: sem atraso de interrupção a bobina carrega o tempo todo (%d curtas)", rpm,
                  m.dwell_encurtado);
        } else {
            printf("    %5.0f rpm, interrupção %3u us: %d de %d centelhas carregaram menos (no máximo %u us a menos)\n", rpm,
                   lat_isr_us, m.dwell_encurtado, m.faltas_de_fagulha, lat_isr_us);
        }
    }

    /* motor para: todos os pulsos em voo terminam e nada fica ligado */
    g_roda.rpm = 0.0;
    correr_ate(host_tempo_us() + 100000);
    CHECK(todos_desligados() && kecu_sched_pendentes() == 0, "%.0f rpm: depois que o motor para, tudo desliga (pendentes %u)", rpm,
          kecu_sched_pendentes());
    CHECK(kecu_sched_descartados() == 0 && kecu_sched_forcados() == 0 && kecu_sched_atrasados() == 0,
          "%.0f rpm: sem descartes, forçados nem atrasos (%u/%u/%u)", rpm, kecu_sched_descartados(), kecu_sched_forcados(),
          kecu_sched_atrasados());
}

static void teste_integracao(void) {
    secao("Integração: ângulo de cada pino com o planejador do núcleo (roda 60-2 simulada)");
    static const double rpms[] = {800, 2000, 4000, 6500};
    for (size_t i = 0; i < sizeof rpms / sizeof rpms[0]; i++) {
        integracao_em(rpms[i], 0, 0, 0, 1);
        integracao_em(rpms[i], 0, 25, 3, 1);
    }
    integracao_em(3000, 300, 25, 3, 1); /* PMS deslocado 30 graus da falha */
    integracao_em(5000, 1234, 25, 3, 1);

    secao("Integração: interrupção do dente lenta (estilo AVR, 120 us): centelha no ângulo, bico com a largura certa");
    integracao_em(800, 0, 120, 4, 1);
    integracao_em(2000, 0, 120, 4, 1);
    integracao_em(4000, 0, 120, 4, 1);
}

/* ================================================================== parte 3: sistema (kecu_loop + protocolo) */

static uint8_t g_seq;

static void enviar(uint8_t tipo, const uint8_t *payload, uint8_t n) {
    uint8_t q[KECU_CABECALHO + KECU_PAYLOAD_MAX + KECU_TAM_CRC];
    const uint16_t tam = kecu_proto_montar(q, tipo, g_seq++, payload, n);
    host_serial_injetar(q, tam);
    kecu_loop();
    kecu_loop();
    uint8_t lixo[512];
    while (host_serial_coletar(lixo, (int)sizeof lixo) > 0) {
    }
}

static void armar_pelo_protocolo(uint8_t ligar) {
    enviar(KECU_CMD_ARMAR, &ligar, 1);
}

static void sistema_correr_us(uint64_t dur_us) {
    const uint64_t fim = host_tempo_us() + dur_us;
    uint8_t lixo[512];
    while (host_tempo_us() < fim) {
        uint64_t alvo = host_tempo_us() + 50u;
        if (alvo > fim) {
            alvo = fim;
        }
        while (g_roda.rpm > 0.0 && g_roda.prox_us <= (double)alvo) {
            roda_um_slot();
        }
        correr_ate(alvo);
        kecu_sched_verificar();
        kecu_loop();
        while (host_serial_coletar(lixo, (int)sizeof lixo) > 0) {
        }
    }
}

static void teste_sistema(void) {
    secao("Sistema: núcleo completo + agendador, ARMAR/desarmar pelo protocolo, motor parando");
    reiniciar(5000000ull);
    g_lat_timer_us = 3;
    host_serial_limpar();
    host_serial_limite = -1;
    host_nvm_apagar();
    host_definir_entrada(KECU_IN_TWO_STEP, 0);
    host_definir_entrada(KECU_IN_LAUNCH, 0);
    host_definir_sensores(90.0f, 25.0f, 60.0f, 20.0f, 13.8f, 3.0f, 1.0f);
    kecu_init();
    g_kecu.params.v[P_REV_LIMITE_RPM] = 9000;
    kecu_aplicar_parametros();
    g_seq = 1;

    roda_iniciar(3000.0, 20);
    sistema_correr_us(1500000);
    CHECK(g_kecu.sync == 1 && g_kecu.motor_girando == 1, "motor sincronizado a %.0f rpm", (double)g_kecu.rpm);
    CHECK(g_tr_n == 0 && g_escritas == 0, "sem ARMAR: nenhum pino mexe (%d mudanças)", g_tr_n);
    CHECK(g_kecu.eventos_ignicao > 0 && g_kecu.eventos_injecao > 0, "mas os eventos são planejados e contados");

    armar_pelo_protocolo(1);
    CHECK(g_kecu.armado == 1, "ARMAR(1) armou");
    sistema_correr_us(600000); /* o degrau de TPS do início dispara o enriquecimento de aceleração (400 ms) */
    const int ini = g_tr_n;
    sistema_correr_us(1000000);
    CHECK(g_tr_n > ini + 100, "armada: os pinos passam a mexer (%d mudanças em 1 s)", g_tr_n - ini);
    const double adv = (double)g_kecu.avanco_graus;
    const double morto = 900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v);
    const double inj_esperado = ((double)g_kecu.pw_us - morto) / 2.0 + morto;
    const double dwell_esperado = 3000.0 * 14.0 / (double)g_kecu.vbat_v;
    /* o alarme do timer também espera a interrupção do dente terminar (20 us aqui): cada borda pode atrasar até isso */
    const medida_t m = medir(ini, 3000.0, 0.0, adv, dwell_esperado, inj_esperado, 6.0 + 20.0, 20.0);
    CHECK(m.max_err_fagulha < 0.6, "ângulo da centelha = PMS - %.1f graus (erro %.3f)", adv, m.max_err_fagulha);
    CHECK(m.dwell_errado == 0, "tempo de carga da bobina %.0f us +-26 us (%d fora)", dwell_esperado, m.dwell_errado);
    CHECK(m.inj_largura_errada == 0, "largura da injeção %.0f us (%d fora)", inj_esperado, m.inj_largura_errada);
    CHECK(m.inj_dessincronizados == 0, "os 4 bicos abrem juntos");
    CHECK(kecu_sched_descartados() == 0 && kecu_sched_forcados() == 0, "nenhum descarte nem pino forçado");

    /* desarma exatamente com a bobina carregando */
    int guarda = 0;
    while (g_pino[KECU_SAIDA_BOBINA1] == 0 && guarda++ < 20000) {
        sistema_correr_us(50);
    }
    CHECK(g_pino[KECU_SAIDA_BOBINA1] == 1, "pegou a bobina carregando para desarmar");
    armar_pelo_protocolo(0);
    CHECK(g_kecu.armado == 0, "ARMAR(0) desarmou");
    CHECK(todos_desligados() && kecu_sched_pendentes() == 0, "e todos os pinos foram para o nível desligado na hora");
    const int n_desarme = g_tr_n;
    sistema_correr_us(400000);
    CHECK(g_tr_n == n_desarme, "desarmada, com o motor girando: nenhuma mudança de pino (%d)", g_tr_n - n_desarme);

    armar_pelo_protocolo(1);
    sistema_correr_us(300000);
    CHECK(g_tr_n > n_desarme + 50, "armar de novo retoma os pulsos (%d)", g_tr_n - n_desarme);

    /* o motor para com a ECU armada */
    g_roda.rpm = 0.0;
    sistema_correr_us(500000);
    CHECK(todos_desligados() && kecu_sched_pendentes() == 0, "motor parou: nada fica ligado");
    CHECK(kecu_sched_forcados() == 0, "sem precisar do vigia");
}

int main(void) {
    printf("Testes do agendador de bordas KadettECU\n");
    printf("%d bobina(s), %u bordas na fila, largura máxima %u us\n\n", KECU_BOBINAS, KECU_SCHED_BORDAS,
           KECU_SCHED_LARGURA_MAX_US);
    teste_pulso_simples();
    teste_ancora();
    teste_ja_vencido();
    teste_sobreposicao();
    teste_empate();
    teste_ordem_varias_saidas();
    teste_limites();
    teste_fila_cheia();
    teste_cancelar();
    teste_timer_perdido();
    teste_vigia();
    teste_volta_do_relogio();
    teste_latencia_do_timer();
    teste_integracao();
    teste_sistema();
    printf("\n%d verificações OK, %d falhas\n", g_ok, g_falhas);
    return g_falhas == 0 ? 0 : 1;
}
