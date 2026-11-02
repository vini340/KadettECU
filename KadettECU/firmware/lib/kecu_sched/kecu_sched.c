/*
 * kecu_sched.c - agendador de bordas das saídas. Veja kecu_sched.h para o contrato e as regras.
 *
 * Estrutura de dados: um vetor de até KECU_SCHED_BORDAS bordas, SEMPRE ordenado por instante (a
 * primeira é a próxima a acontecer). Com poucas bordas em voo (normalmente 2 a 8), inserir e
 * remover deslocando o vetor é mais barato e mais fácil de provar correto do que um heap.
 *
 * Todas as comparações de tempo são "(int32_t)(a - b)", então o relógio de 32 bits pode dar a
 * volta (a cada ~71 min) sem quebrar nada.
 */
#include "kecu_sched.h"

typedef struct {
    uint32_t t_us;
    uint8_t saida;
    uint8_t liga; /* 1 = liga, 0 = desliga */
} borda_t;

static borda_t s_borda[KECU_SCHED_BORDAS];
static volatile uint8_t s_n;
static uint8_t s_abertos[KECU_SAIDAS];   /* "liga" já executados cujo "desliga" ainda não aconteceu */
static uint32_t s_desde_us[KECU_SAIDAS]; /* quando a saída passou de desligada para ligada */
static uint32_t s_ancora_us;
static uint8_t s_tem_ancora;
static uint32_t s_descartados;
static uint32_t s_atrasados;
static uint32_t s_forcados;

/* ------------------------------------------------------------------ fila */

/* Mantém a ordem por instante. Empate: "desliga" antes de "liga"; do mesmo tipo, ordem de chegada. */
static void inserir(uint32_t t_us, uint8_t saida, uint8_t liga) {
    uint8_t i = s_n;
    while (i > 0u) {
        const borda_t *e = &s_borda[i - 1u];
        const int32_t d = (int32_t)(e->t_us - t_us);
        if (d > 0 || (d == 0 && e->liga != 0u && liga == 0u)) {
            s_borda[i] = *e;
            i--;
        } else {
            break;
        }
    }
    s_borda[i].t_us = t_us;
    s_borda[i].saida = saida;
    s_borda[i].liga = liga;
    s_n++;
}

static void remover_primeira(void) {
    for (uint8_t i = 1u; i < s_n; i++) {
        s_borda[i - 1u] = s_borda[i];
    }
    s_n--;
}

/* ------------------------------------------------------------------ pinos */

static void ligar(uint8_t saida, uint32_t agora_us) {
    if (s_abertos[saida] < 255u) {
        s_abertos[saida]++;
    }
    if (s_abertos[saida] == 1u) {
        s_desde_us[saida] = agora_us;
        hal_saida_escrever((kecu_saida_t)saida, 1u);
    }
}

static void desligar(uint8_t saida) {
    if (s_abertos[saida] > 0u) {
        s_abertos[saida]--;
    }
    if (s_abertos[saida] == 0u) {
        hal_saida_escrever((kecu_saida_t)saida, 0u); /* repetir o "desliga" é sempre seguro */
    }
}

/* Executa tudo que já venceu, em ordem. */
static void processar_vencidas(void) {
    while (s_n > 0u) {
        const uint32_t agora = hal_micros();
        if ((int32_t)(agora - s_borda[0].t_us) < 0) {
            break;
        }
        const borda_t e = s_borda[0];
        remover_primeira();
        if (e.liga != 0u) {
            ligar(e.saida, agora);
        } else {
            desligar(e.saida);
        }
    }
}

static void rearmar(void) {
    if (s_n == 0u) {
        hal_timer_parar();
    } else {
        hal_timer_armar(s_borda[0].t_us);
    }
}

/* Desliga uma saída à força e tira da fila tudo que ainda era dela (sem "liga" órfão). */
static void forcar_desligada(uint8_t saida) {
    s_abertos[saida] = 0u;
    uint8_t j = 0u;
    for (uint8_t i = 0u; i < s_n; i++) {
        if (s_borda[i].saida != saida) {
            s_borda[j] = s_borda[i];
            j++;
        }
    }
    s_n = j;
    hal_saida_escrever((kecu_saida_t)saida, 0u);
    s_forcados++;
    rearmar();
}

/* ------------------------------------------------------------------ API */

static void desligar_tudo(void) {
    s_n = 0u;
    for (uint8_t i = 0u; i < (uint8_t)KECU_SAIDAS; i++) {
        s_abertos[i] = 0u;
        hal_saida_escrever((kecu_saida_t)i, 0u);
    }
    hal_timer_parar();
}

void kecu_sched_init(void) {
    desligar_tudo();
    for (uint8_t i = 0u; i < (uint8_t)KECU_SAIDAS; i++) {
        s_desde_us[i] = 0u;
    }
    s_ancora_us = 0u;
    s_tem_ancora = 0u;
    s_descartados = 0u;
    s_atrasados = 0u;
    s_forcados = 0u;
}

void kecu_sched_ancorar(uint32_t t_us) {
    s_ancora_us = t_us;
    s_tem_ancora = 1u;
}

void kecu_sched_pulso(kecu_saida_t saida, uint32_t atraso_us, uint32_t largura_us) {
    if ((unsigned)saida >= (unsigned)KECU_SAIDAS || largura_us == 0u) {
        return;
    }
    if (atraso_us > KECU_SCHED_ATRASO_MAX_US) {
        s_descartados++;
        return;
    }
    if (largura_us > KECU_SCHED_LARGURA_MAX_US) {
        largura_us = KECU_SCHED_LARGURA_MAX_US;
    }

    const uint32_t agora = hal_micros();
    const uint32_t base =
        (s_tem_ancora != 0u && (uint32_t)(agora - s_ancora_us) <= KECU_SCHED_ANCORA_MAX_US) ? s_ancora_us : agora;
    uint32_t t_liga = base + atraso_us;
    uint32_t t_desliga = t_liga + largura_us;

    if ((unsigned)saida < (unsigned)KECU_SAIDA_BOBINA1) {
        /*
         * BICO: o que importa é a largura (a quantidade de combustível), não o instante exato em que
         * abre. Se a interrupção demorou e o início já passou (o planejador manda abrir NO dente de
         * referência, atraso 0, então isso acontece em todo ciclo), abre agora e mantém a largura.
         */
        if ((int32_t)(agora - t_liga) > 0) {
            t_liga = agora;
            t_desliga = agora + largura_us;
        }
    } else if ((int32_t)(agora - t_desliga) >= 0) {
        /* BOBINA: o que importa é o instante do desligamento (a centelha). Pulso que já acabou: perde-se. */
        s_atrasados++;
        return;
    }
    if ((unsigned)s_n + 2u > (unsigned)KECU_SCHED_BORDAS) {
        s_descartados++; /* regra 3: sem lugar para as DUAS bordas, não entra nenhuma */
        return;
    }

    inserir(t_liga, (uint8_t)saida, 1u);
    inserir(t_desliga, (uint8_t)saida, 0u); /* regra 1: as duas entram juntas */
    processar_vencidas();                   /* se o "liga" já venceu (interrupção demorada), liga agora */
    rearmar();
}

void kecu_sched_no_timer(void) {
    processar_vencidas();
    rearmar();
}

void kecu_sched_verificar(void) {
    /* 1) bordas que o timer deixou passar (alarme perdido, interrupção atrasada) */
    if (s_n > 0u && (int32_t)(hal_micros() - s_borda[0].t_us) >= 0) {
        hal_secao_entrar();
        processar_vencidas();
        rearmar();
        hal_secao_sair();
    }

    /* 2) vigia: nenhuma saída fica ligada além do limite, mesmo que algo tenha dado errado acima */
    const uint32_t limite = KECU_SCHED_LARGURA_MAX_US + KECU_SCHED_VIGIA_FOLGA_US;
    for (uint8_t i = 0u; i < (uint8_t)KECU_SAIDAS; i++) {
        if (s_abertos[i] != 0u && (uint32_t)(hal_micros() - s_desde_us[i]) > limite) {
            hal_secao_entrar();
            /* confere de novo já protegido: uma interrupção pode ter desligado a saída nesse meio-tempo */
            if (s_abertos[i] != 0u && (uint32_t)(hal_micros() - s_desde_us[i]) > limite) {
                forcar_desligada(i);
            }
            hal_secao_sair();
        }
    }
}

void kecu_sched_cancelar(void) {
    hal_secao_entrar();
    desligar_tudo();
    hal_secao_sair();
}

uint8_t kecu_sched_pendentes(void) {
    return s_n;
}

uint8_t kecu_sched_saida_ligada(kecu_saida_t saida) {
    return (unsigned)saida < (unsigned)KECU_SAIDAS && s_abertos[saida] != 0u ? 1u : 0u;
}

uint32_t kecu_sched_descartados(void) {
    return s_descartados;
}

uint32_t kecu_sched_atrasados(void) {
    return s_atrasados;
}

uint32_t kecu_sched_forcados(void) {
    return s_forcados;
}
