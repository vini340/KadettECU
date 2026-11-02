/*
 * test_core.c - testes automáticos do núcleo do firmware, rodando no PC com tempo virtual.
 *
 * O relógio é simulado: o teste avança o tempo em passos de 50 us, gera os dentes da roda
 * fônica nos instantes exatos e chama kecu_loop() como a placa faria. Assim os testes são
 * rápidos, repetíveis e capazes de medir ângulos de ignição com precisão de microssegundos.
 *
 * Compila em 3 variantes (veja o Makefile): saídas desabilitadas (modo sombra), habilitadas, e
 * habilitadas com duas bobinas. Alguns testes só existem onde fazem sentido.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "hal_host.h"
#include "kecu.h"
#include "kecu_proto.h"

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

#define CHECK_PERTO(valor, esperado, tol, nome)                                                                \
    do {                                                                                                       \
        const double v_ = (double)(valor);                                                                     \
        const double e_ = (double)(esperado);                                                                  \
        if (fabs(v_ - e_) <= (double)(tol)) {                                                                  \
            g_ok++;                                                                                            \
        } else {                                                                                               \
            g_falhas++;                                                                                        \
            printf("  FALHOU (linha %d): %s = %.4f, esperado %.4f +- %.4f\n", __LINE__, nome, v_, e_,          \
                   (double)(tol));                                                                             \
        }                                                                                                      \
    } while (0)

static void secao(const char *titulo) {
    printf("[%s]\n", titulo);
}

/* ================================================================== gerador de sinais (tempo virtual) */

#define PASSO_US 50u

static struct {
    double rpm;
    double prox_us;
    int slot;
    int dentes;
    int faltando;
    double vel_kmh;
    double prox_vss_us;
    int pular_slot; /* gancho: não emite o dente deste slot (queda de sinal)... */
    int pular_n;    /* ...n vezes */
    int extra_slot; /* gancho: depois do dente deste slot emite um dente extra (ruído)... */
    int extra_n;    /* ...n vezes */
    double t0_us;   /* instante do primeiro dente de índice 0 desde que o motor "ligou" */
    int tem_t0;
} cr;

static double slot_us_atual(void) {
    return 60e6 / (cr.rpm * (double)cr.dentes);
}

static void crank_roda(int dentes, int faltando) {
    cr.dentes = dentes;
    cr.faltando = faltando;
    cr.slot = 0;
}

/* Liga/ajusta a rotação. De zero para algo: começa no slot 0. Mudanças mantêm a fase (como um motor de verdade). */
static void crank_rpm(double rpm) {
    const int estava_parado = cr.rpm <= 0.0;
    const double slot_antigo = estava_parado ? 0.0 : slot_us_atual();
    cr.rpm = rpm;
    if (rpm > 0.0) {
        if (estava_parado) {
            cr.slot = 0;
            cr.tem_t0 = 0;
            cr.prox_us = (double)host_tempo_us() + slot_us_atual();
        } else {
            cr.prox_us = cr.prox_us - slot_antigo + slot_us_atual();
        }
    }
}

static void crank_vel(double kmh) {
    cr.vel_kmh = kmh;
    if (kmh > 0.0) {
        cr.prox_vss_us = (double)host_tempo_us() + 3.6e9 / (kmh * (double)g_kecu.params.v[P_VSS_PULSOS_KM]);
    }
}

static void emitir_dente(void) {
    const uint64_t t = (uint64_t)(cr.prox_us + 0.5);
    if (t > host_tempo_us()) {
        host_tempo_definir_us(t);
    }
    const double slot = slot_us_atual();
    if (cr.slot < cr.dentes - cr.faltando) {
        if (cr.pular_n > 0 && cr.slot == cr.pular_slot) {
            cr.pular_n--;
        } else {
            if (cr.slot == 0 && !cr.tem_t0) {
                cr.t0_us = cr.prox_us;
                cr.tem_t0 = 1;
            }
            kecu_engine_on_tooth((uint32_t)(t & 0xFFFFFFFFull));
            if (cr.extra_n > 0 && cr.slot == cr.extra_slot) {
                cr.extra_n--;
                const uint64_t te = (uint64_t)(cr.prox_us + slot / 2.0 + 0.5);
                host_tempo_definir_us(te);
                kecu_engine_on_tooth((uint32_t)(te & 0xFFFFFFFFull));
            }
        }
    }
    cr.slot = (cr.slot + 1) % cr.dentes;
    cr.prox_us += slot;
}

static void emitir_vss(void) {
    const uint64_t t = (uint64_t)(cr.prox_vss_us + 0.5);
    if (t > host_tempo_us()) {
        host_tempo_definir_us(t);
    }
    kecu_engine_on_vss((uint32_t)(t & 0xFFFFFFFFull));
    cr.prox_vss_us += 3.6e9 / (cr.vel_kmh * (double)g_kecu.params.v[P_VSS_PULSOS_KM]);
}

static void sim_us(uint64_t dur_us) {
    const uint64_t fim = host_tempo_us() + dur_us;
    while (host_tempo_us() < fim) {
        uint64_t alvo = host_tempo_us() + PASSO_US;
        if (alvo > fim) {
            alvo = fim;
        }
        for (;;) {
            const int tem_dente = cr.rpm > 0.0 && cr.prox_us <= (double)alvo;
            const int tem_vss = cr.vel_kmh > 0.0 && cr.prox_vss_us <= (double)alvo;
            if (!tem_dente && !tem_vss) {
                break;
            }
            if (tem_dente && (!tem_vss || cr.prox_us <= cr.prox_vss_us)) {
                emitir_dente();
            } else {
                emitir_vss();
            }
        }
        if (alvo > host_tempo_us()) {
            host_tempo_definir_us(alvo);
        }
        kecu_loop();
    }
}

static void sim_ms(double ms) {
    sim_us((uint64_t)(ms * 1000.0));
}

/* ================================================================== protocolo: montar comandos e ler respostas */

typedef struct {
    uint8_t tipo;
    uint8_t seq;
    uint8_t n;
    uint8_t d[KECU_PAYLOAD_MAX];
} quadro_t;

#define MAX_QUADROS 600
static quadro_t g_quadros[MAX_QUADROS];
static int g_quadros_n;
static int g_tele_total;
static int g_quadros_invalidos;
static uint8_t g_seq;

/* Lê tudo que a ECU enviou e separa em quadros (confere SOF, tamanho e CRC; qualquer defeito é contado). */
static void drenar_saida(void) {
    static uint8_t buf[1 << 16];
    int n = host_serial_coletar(buf, (int)sizeof buf);
    int pos = 0;
    g_quadros_n = 0;
    while (pos < n) {
        if (n - pos < 7 || buf[pos] != KECU_SOF1 || buf[pos + 1] != KECU_SOF2) {
            g_quadros_invalidos++;
            return;
        }
        const int len = buf[pos + 2];
        const int total = 7 + len;
        if (n - pos < total) {
            g_quadros_invalidos++;
            return;
        }
        const uint16_t crc = kecu_crc16(&buf[pos + 2], (uint16_t)(3 + len));
        const uint16_t crc_rx = (uint16_t)(buf[pos + 5 + len] | (buf[pos + 6 + len] << 8));
        if (crc != crc_rx) {
            g_quadros_invalidos++;
            return;
        }
        if (g_quadros_n < MAX_QUADROS) {
            quadro_t *q = &g_quadros[g_quadros_n++];
            q->tipo = buf[pos + 3];
            q->seq = buf[pos + 4];
            q->n = (uint8_t)len;
            memcpy(q->d, &buf[pos + 5], (size_t)len);
        }
        if (buf[pos + 3] == KECU_RSP_TELEMETRIA) {
            g_tele_total++;
        }
        pos += total;
    }
}

/* Envia um comando pela serial simulada e devolve a resposta (a que tem o mesmo seq e não é telemetria). */
static int comando(uint8_t tipo, const uint8_t *payload, int n, quadro_t *resp) {
    uint8_t f[300];
    g_seq++;
    const uint16_t tot = kecu_proto_montar(f, tipo, g_seq, payload, (uint8_t)n);
    host_serial_injetar(f, tot);
    kecu_loop();
    kecu_loop();
    drenar_saida();
    for (int i = 0; i < g_quadros_n; i++) {
        if (g_quadros[i].seq == g_seq && g_quadros[i].tipo != KECU_RSP_TELEMETRIA) {
            if (resp != NULL) {
                *resp = g_quadros[i];
            }
            return 1;
        }
    }
    return 0;
}

static int eh_ack(const quadro_t *q) {
    return q->tipo == KECU_RSP_ACK && q->n == 0;
}

static int eh_nack(const quadro_t *q, unsigned codigo) {
    return q->tipo == KECU_RSP_NACK && q->n == 1 && q->d[0] == codigo;
}

/* Comando que deve resultar em ACK/NACK; devolve 1 se a resposta foi a esperada. */
static int cmd_ack(uint8_t tipo, const uint8_t *p, int n) {
    quadro_t r;
    return comando(tipo, p, n, &r) && eh_ack(&r);
}

static int cmd_nack(uint8_t tipo, const uint8_t *p, int n, unsigned codigo) {
    quadro_t r;
    return comando(tipo, p, n, &r) && eh_nack(&r, codigo);
}

/* ================================================================== preparação */

static void sensores_padrao(void) {
    host_definir_sensores(90.0f, 25.0f, 40.0f, 0.0f, 13.8f, 3.0f, 1.0f);
}

static void reset_ecu_em(uint64_t t0_us, int apagar_nvm) {
    host_tempo_real = 0;
    host_tempo_definir_us(t0_us);
    host_serial_limpar();
    host_serial_limite = -1;
    host_pulsos_limpar();
    host_desligamentos = 0;
    host_nvm_falhar = 0;
    host_nvm_nao_guarda = 0;
    if (apagar_nvm) {
        host_nvm_apagar();
    }
    memset(&cr, 0, sizeof cr);
    cr.dentes = 60;
    cr.faltando = 2;
    sensores_padrao();
    host_definir_entrada(KECU_IN_TWO_STEP, 0);
    host_definir_entrada(KECU_IN_LAUNCH, 0);
    kecu_init();
    sim_ms(30);
}

static void reset_ecu(int apagar_nvm) {
    reset_ecu_em(5000000ull, apagar_nvm);
}

/* Muda parâmetros direto na RAM (só nos testes; no uso real é pelo protocolo). */
static void param(kecu_param_id_t id, unsigned valor) {
    g_kecu.params.v[id] = (uint16_t)valor;
}

/* Motor "ligado" a uma rotação constante, já sincronizado. */
static void ligar_motor(double rpm) {
    crank_rpm(rpm);
    sim_ms(400);
}

static void parar_motor(void) {
    crank_rpm(0.0);
    crank_vel(0.0);
    sim_ms(400);
}

/* ================================================================== testes: CRC, quadros, parâmetros, tabelas */

static void teste_crc_e_quadros(void) {
    secao("CRC-16 e montagem de quadros");
    static const uint8_t nove[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
    CHECK(kecu_crc16(nove, 9) == 0x29B1u, "CRC de \"123456789\" = 0x%04X", kecu_crc16(nove, 9));

    /* vetores calculados à parte, em Python (terceira implementação independente do mesmo CRC) */
    uint8_t q[32];
    static const uint8_t esp1[] = {0xA5, 0x5A, 0x00, 0x01, 0x07, 0x4A, 0x8F};
    CHECK(kecu_proto_montar(q, KECU_CMD_HELLO, 7, NULL, 0) == 7 && memcmp(q, esp1, 7) == 0, "quadro HELLO");
    static const uint8_t esp2[] = {0xA5, 0x5A, 0x02, 0x02, 0x02, 0x14, 0x00, 0x30, 0x19};
    const uint8_t p20[] = {0x14, 0x00};
    CHECK(kecu_proto_montar(q, KECU_CMD_STREAM, 2, p20, 2) == 9 && memcmp(q, esp2, 9) == 0, "quadro STREAM 20 ms");
    static const uint8_t esp3[] = {0xA5, 0x5A, 0x01, 0x40, 0x09, 0x01, 0x60, 0x45};
    const uint8_t p1[] = {1};
    CHECK(kecu_proto_montar(q, KECU_CMD_ARMAR, 9, p1, 1) == 8 && memcmp(q, esp3, 8) == 0, "quadro ARMAR 1");
}

static void teste_parametros(void) {
    secao("Tabela de parâmetros");
    kecu_params_t p;
    kecu_params_padrao(&p);
    CHECK(p.v[P_REV_LIMITE_RPM] == 6500 && p.v[P_REV_HISTERESE_RPM] == 150 && p.v[P_TIPO_CORTE] == 2
              && p.v[P_REQ_FUEL_US] == 8500 && p.v[P_TEMPO_MORTO_US] == 900 && p.v[P_DWELL_US] == 3000
              && p.v[P_CILINDROS] == 4 && p.v[P_DENTES_RODA] == 60 && p.v[P_DENTES_FALTANDO] == 2
              && p.v[P_AFR_ESTEQ_X10] == 130 && p.v[P_VSS_PULSOS_KM] == 4000,
          "valores padrão");

    int soma = 0;
    for (int i = 0; i < P_COUNT; i++) {
        soma += kecu_param_tab[i].bytes;
        CHECK(kecu_param_tab[i].padrao >= kecu_param_tab[i].min && kecu_param_tab[i].padrao <= kecu_param_tab[i].max,
              "padrão do campo %d dentro da faixa", i);
    }
    CHECK(soma == KECU_PARAMS_BYTES, "soma dos tamanhos = %d", soma);

    uint8_t b[KECU_PARAMS_BYTES];
    kecu_params_serializar(&p, b);
    kecu_params_t q;
    CHECK(kecu_params_desserializar(&q, b) == 0 && memcmp(&p, &q, sizeof p) == 0, "ida e volta");

    /* cada campo: min e max aceitos; min-1 e max+1 recusados sem mexer no destino */
    int aceitos = 0;
    int recusados = 0;
    int a_recusar = 0; /* quantos valores fora da faixa existem de fato (nem todo campo tem folga dos dois lados) */
    for (int i = 0; i < P_COUNT; i++) {
        kecu_params_t t = p;
        t.v[i] = kecu_param_tab[i].min;
        kecu_params_serializar(&t, b);
        kecu_params_t d = p;
        aceitos += kecu_params_desserializar(&d, b) == 0 && d.v[i] == kecu_param_tab[i].min;
        t.v[i] = kecu_param_tab[i].max;
        kecu_params_serializar(&t, b);
        aceitos += kecu_params_desserializar(&d, b) == 0 && d.v[i] == kecu_param_tab[i].max;
        if (kecu_param_tab[i].min > 0) {
            a_recusar++;
            t = p;
            t.v[i] = (uint16_t)(kecu_param_tab[i].min - 1u);
            kecu_params_serializar(&t, b);
            d = p;
            recusados += kecu_params_desserializar(&d, b) == 1 && memcmp(&d, &p, sizeof p) == 0;
        }
        if (kecu_param_tab[i].max < (kecu_param_tab[i].bytes == 1 ? 255u : 65535u)) {
            a_recusar++;
            t = p;
            t.v[i] = (uint16_t)(kecu_param_tab[i].max + 1u);
            kecu_params_serializar(&t, b);
            d = p;
            recusados += kecu_params_desserializar(&d, b) == 1 && memcmp(&d, &p, sizeof p) == 0;
        }
    }
    CHECK(aceitos == 2 * P_COUNT, "extremos válidos aceitos: %d de %d", aceitos, 2 * P_COUNT);
    CHECK(a_recusar > 40 && recusados == a_recusar, "fora da faixa recusados sem tocar no destino: %d de %d", recusados,
          a_recusar);

    kecu_params_t a = p;
    kecu_params_t c = p;
    c.v[P_DENTES_RODA] = 36;
    CHECK(kecu_params_mudou_campo_de_sinal(&a, &c) == 1, "mudança de campo de sinal detectada");
    c = p;
    c.v[P_REV_LIMITE_RPM] = 6000;
    CHECK(kecu_params_mudou_campo_de_sinal(&a, &c) == 0, "mudança de outro campo não é de sinal");
}

static void teste_tabelas(void) {
    secao("Eixos, tabelas e interpolação");
    kecu_eixos_t e;
    kecu_eixos_padrao(&e);
    CHECK(e.rpm[0] == 500 && e.rpm[15] == 7500 && e.carga[0] == 20 && e.carga[15] == 110, "eixos padrão");
    CHECK(kecu_eixos_validos(&e), "eixos padrão são crescentes");
    kecu_eixos_t ruim = e;
    ruim.rpm[5] = ruim.rpm[4];
    CHECK(!kecu_eixos_validos(&ruim), "eixo repetido é inválido");
    ruim = e;
    ruim.carga[9] = 10;
    CHECK(!kecu_eixos_validos(&ruim), "eixo decrescente é inválido");

    CHECK_PERTO(kecu_eixo_pos(e.rpm, 100), 0.0, 1e-6, "posição abaixo do eixo");
    CHECK_PERTO(kecu_eixo_pos(e.rpm, 9000), 15.0, 1e-6, "posição acima do eixo");
    CHECK_PERTO(kecu_eixo_pos(e.rpm, 3000), 6.0, 1e-6, "posição em nó");
    CHECK_PERTO(kecu_eixo_pos(e.rpm, 3250), 6.5, 1e-6, "posição no meio");
    CHECK_PERTO(kecu_eixo_pos(e.carga, 60), 40.0 / 6.0, 1e-5, "posição de carga");

    kecu_mapas_t m;
    kecu_mapas_padrao(&m);
    CHECK(m.m[MAPA_COMBUSTIVEL][0][0] == 40 && m.m[MAPA_COMBUSTIVEL][15][15] == 64 && m.m[MAPA_COMBUSTIVEL][0][8] == 65,
          "VE padrão (40, 64, 65)");
    CHECK_PERTO(kecu_mapa_fisico(MAPA_IGNICAO, m.m[MAPA_IGNICAO][0][0]), 14.0, 1e-6, "avanço [0][0]");
    CHECK_PERTO(kecu_mapa_fisico(MAPA_IGNICAO, m.m[MAPA_IGNICAO][15][15]), 26.0, 1e-6, "avanço [15][15]");
    CHECK_PERTO(kecu_mapa_fisico(MAPA_LAMBDA, m.m[MAPA_LAMBDA][0][0]), 1.0, 1e-6, "lambda alvo [0][0]");
    CHECK_PERTO(kecu_mapa_fisico(MAPA_LAMBDA, m.m[MAPA_LAMBDA][15][0]), 0.88, 1e-6, "lambda alvo [15][0]");

    int dentro = 1;
    for (int mp = 0; mp < KECU_NUM_MAPAS; mp++) {
        for (int l = 0; l < KECU_N; l++) {
            for (int c = 0; c < KECU_N; c++) {
                const float v = kecu_mapa_fisico((kecu_mapa_id_t)mp, m.m[mp][l][c]);
                if (mp == MAPA_COMBUSTIVEL && (v < 20.0f || v > 110.0f)) {
                    dentro = 0;
                }
                if (mp == MAPA_IGNICAO && (v < 0.0f || v > 45.0f)) {
                    dentro = 0;
                }
                if (mp == MAPA_LAMBDA && (v < 0.8f || v > 1.1f)) {
                    dentro = 0;
                }
            }
        }
    }
    CHECK(dentro, "valores padrão em faixas plausíveis");

    CHECK_PERTO(kecu_mapa_interp_raw(&m, MAPA_COMBUSTIVEL, 3.0f, 4.0f), m.m[MAPA_COMBUSTIVEL][4][3], 1e-4, "interp em nó");
    const double media = (m.m[MAPA_COMBUSTIVEL][4][3] + m.m[MAPA_COMBUSTIVEL][4][4] + m.m[MAPA_COMBUSTIVEL][5][3]
                          + m.m[MAPA_COMBUSTIVEL][5][4])
                         / 4.0;
    CHECK_PERTO(kecu_mapa_interp_raw(&m, MAPA_COMBUSTIVEL, 3.5f, 4.5f), media, 1e-3, "interp no centro da célula");
    CHECK_PERTO(kecu_mapa_interp_raw(&m, MAPA_COMBUSTIVEL, 15.0f, 15.0f), m.m[MAPA_COMBUSTIVEL][15][15], 1e-4,
                "interp na última célula");
}

/* ================================================================== testes: sensores */

static void teste_sensores(void) {
    secao("Sensores (conversão de tensão em grandezas físicas)");
    reset_ecu(1);
    host_definir_sensores(90.0f, 25.0f, 55.0f, 30.0f, 13.8f, 3.0f, 0.95f);
    sim_ms(400);
    CHECK_PERTO(g_kecu.ect_c, 90.0, 0.5, "ECT");
    CHECK_PERTO(g_kecu.iat_c, 25.0, 0.5, "IAT");
    CHECK_PERTO(g_kecu.map_kpa, 55.0, 0.4, "MAP");
    CHECK_PERTO(g_kecu.tps_pct, 30.0, 0.3, "TPS");
    CHECK_PERTO(g_kecu.vbat_v, 13.8, 0.05, "bateria");
    CHECK_PERTO(g_kecu.oleo_bar, 3.0, 0.05, "pressão de óleo");
    CHECK_PERTO(g_kecu.lambda, 0.95, 0.01, "lambda");
    CHECK(g_kecu.erros == 0, "sem erros com tudo ligado (erros=0x%X)", g_kecu.erros);

    host_definir_sensores(20.0f, 20.0f, 100.0f, 100.0f, 12.0f, 0.0f, 1.2f);
    sim_ms(2000); /* a temperatura tem filtro lento (constante de tempo de 200 ms): 70 graus de degrau levam ~1 s para assentar */
    CHECK_PERTO(g_kecu.ect_c, 20.0, 0.5, "ECT frio");
    CHECK_PERTO(g_kecu.map_kpa, 100.0, 0.5, "MAP 100 kPa");
    CHECK_PERTO(g_kecu.tps_pct, 100.0, 0.5, "TPS 100%%");
    CHECK_PERTO(g_kecu.vbat_v, 12.0, 0.05, "bateria 12 V");
    CHECK_PERTO(g_kecu.lambda, 1.2, 0.02, "lambda 1,2");
    CHECK(g_kecu.erros == 0, "leituras extremas mas válidas (MAP 100 kPa, TPS 100%%, lambda 1,2) não são erro (0x%X)", g_kecu.erros);

    /* fio solto / em curto: bandeira de erro e valor de emergência (a ECU continua funcionando) */
    host_definir_sensores(90.0f, 25.0f, 55.0f, 30.0f, 13.8f, 3.0f, 1.0f);
    sim_ms(100);
    host_definir_pino_mv(KECU_ADC_ECT, 0);
    host_definir_pino_mv(KECU_ADC_IAT, 3300);
    host_definir_pino_mv(KECU_ADC_MAP, 3300);
    host_definir_pino_mv(KECU_ADC_TPS, 0);
    host_definir_pino_mv(KECU_ADC_VBAT, 0);
    host_definir_pino_mv(KECU_ADC_LAMBDA, 0);
    sim_ms(2500);
    CHECK((g_kecu.erros & KECU_ERR_ECT) && (g_kecu.erros & KECU_ERR_IAT) && (g_kecu.erros & KECU_ERR_MAP)
              && (g_kecu.erros & KECU_ERR_TPS) && (g_kecu.erros & KECU_ERR_BATERIA)
              && (g_kecu.erros & KECU_ERR_LAMBDA),
          "erros sinalizados (0x%X)", g_kecu.erros);
    CHECK_PERTO(g_kecu.ect_c, KECU_ECT_EMERGENCIA_C, 1.0, "ECT de emergência");
    CHECK_PERTO(g_kecu.iat_c, KECU_IAT_EMERGENCIA_C, 1.0, "IAT de emergência");
    CHECK(g_kecu.vbat_v >= 5.9f && g_kecu.vbat_v <= 18.1f, "bateria limitada a 6..18 V (%.2f)", (double)g_kecu.vbat_v);
    CHECK_PERTO(g_kecu.tps_pct, 0.0, 0.5, "TPS com defeito vira 0");

    sensores_padrao();
    sim_ms(2500);
    CHECK(g_kecu.erros == 0, "erros somem quando os sensores voltam (0x%X)", g_kecu.erros);

    /*
     * Sensor de 5 V em curto para +5 V: com o divisor de tensão o PINO ainda fica dentro da faixa do ADC,
     * então a falha precisa ser reconhecida pela tensão do SENSOR (pino x razão do divisor).
     */
    const int curto_5v = (int)(5000.0f / KECU_RAZAO_5V + 0.5f);
    host_definir_pino_mv(KECU_ADC_MAP, curto_5v);
    host_definir_pino_mv(KECU_ADC_TPS, curto_5v);
    host_definir_pino_mv(KECU_ADC_LAMBDA, curto_5v);
    sim_ms(600);
    CHECK((g_kecu.erros & KECU_ERR_MAP) && (g_kecu.erros & KECU_ERR_TPS) && (g_kecu.erros & KECU_ERR_LAMBDA),
          "MAP, TPS e lambda em curto para +5 V (pino em %d mV) são sinalizados (0x%X)", curto_5v, g_kecu.erros);
    CHECK_PERTO(g_kecu.tps_pct, 0.0, 0.5, "TPS em curto para +5 V vira 0, não 100%%");
    sensores_padrao();
    sim_ms(2500);
    CHECK(g_kecu.erros == 0, "e some quando o fio volta ao normal (0x%X)", g_kecu.erros);
}

/* ================================================================== testes: leitura do sinal de rotação */

static void teste_rotacao(void) {
    secao("Roda fônica 60-2: sincronismo e rotação");
    static const double lista[] = {300, 600, 850, 1500, 3000, 6000, 7500};
    for (size_t i = 0; i < sizeof lista / sizeof lista[0]; i++) {
        reset_ecu(1);
        crank_rpm(lista[i]);
        sim_ms(lista[i] < 500 ? 900 : 400);
        CHECK(g_kecu.sync == 1, "%.0f rpm: sincronizou", lista[i]);
        CHECK(g_kecu.motor_girando == 1, "%.0f rpm: motor girando", lista[i]);
        CHECK_PERTO(g_kecu.rpm, lista[i], lista[i] * 0.01, "rotação medida");
        CHECK(g_kecu.perdas_sync == 0, "%.0f rpm: sem perda de sincronia (%u)", lista[i], g_kecu.perdas_sync);
        CHECK((g_kecu.erros & KECU_ERR_SEM_ROTACAO) == 0, "%.0f rpm: sem erro de sinal", lista[i]);
    }

    /* parar: depois do tempo limite, rpm zera e a sincronia some; ao religar, volta */
    reset_ecu(1);
    ligar_motor(3000);
    CHECK(g_kecu.motor_girando && g_kecu.sync, "ligado");
    crank_rpm(0.0);
    sim_ms(100);
    CHECK(g_kecu.motor_girando == 1, "100 ms sem dente ainda conta como girando");
    sim_ms(250);
    CHECK(g_kecu.motor_girando == 0 && g_kecu.rpm == 0.0f && g_kecu.sync == 0, "parou: rpm=%.1f", (double)g_kecu.rpm);
    crank_rpm(1200);
    sim_ms(400);
    CHECK(g_kecu.sync == 1 && fabs((double)g_kecu.rpm - 1200.0) < 12.0, "religou: rpm=%.1f", (double)g_kecu.rpm);

    secao("Outras rodas: 36-1 e pulsos uniformes");
    reset_ecu(1);
    param(P_DENTES_RODA, 36);
    param(P_DENTES_FALTANDO, 1);
    kecu_aplicar_parametros();
    crank_roda(36, 1);
    ligar_motor(2400);
    CHECK(g_kecu.sync == 1, "36-1 sincronizou");
    CHECK_PERTO(g_kecu.rpm, 2400.0, 24.0, "36-1: rotação");

    reset_ecu(1);
    param(P_MODO_SINAL, 1);
    param(P_DENTES_RODA, 4);
    kecu_aplicar_parametros();
    crank_roda(4, 0);
    ligar_motor(1800);
    CHECK_PERTO(g_kecu.rpm, 1800.0, 18.0, "pulsos uniformes: rotação");
    CHECK(g_kecu.sync == 1, "uniforme: marcado como pronto");
    sim_ms(1500);
    CHECK(g_kecu.eventos_ignicao == 0 && g_kecu.eventos_injecao == 0, "modo uniforme não planeja ignição/injeção");

    secao("Falhas no sinal: dente extra (ruído) e dente perdido");
    reset_ecu(1);
    ligar_motor(3000);
    CHECK(g_kecu.sync == 1 && g_kecu.perdas_sync == 0, "sincronizado antes do ruído");
    cr.extra_slot = 20;
    cr.extra_n = 1;
    sim_ms(40);
    CHECK(g_kecu.perdas_sync >= 1, "dente extra detectado (%u perdas)", g_kecu.perdas_sync);
    CHECK((g_kecu.erros & KECU_ERR_SEM_ROTACAO) != 0, "erro de sinal sinalizado");
    sim_ms(100);
    CHECK(g_kecu.sync == 1, "voltou a sincronizar sozinho");
    CHECK_PERTO(g_kecu.rpm, 3000.0, 60.0, "rotação continua coerente");
    sim_ms(2200);
    CHECK((g_kecu.erros & KECU_ERR_SEM_ROTACAO) == 0, "erro some depois de 2 s estável");

    reset_ecu(1);
    ligar_motor(3000);
    cr.pular_slot = 30;
    cr.pular_n = 1;
    sim_ms(60);
    CHECK(g_kecu.perdas_sync >= 1, "dente perdido detectado (%u perdas)", g_kecu.perdas_sync);
    sim_ms(120);
    CHECK(g_kecu.sync == 1, "ressincronizou depois de perder um dente");

    secao("Velocidade (sensor VSS)");
    reset_ecu(1);
    ligar_motor(2500);
    crank_vel(60.0);
    sim_ms(600);
    CHECK_PERTO(g_kecu.vel_kmh, 60.0, 1.0, "velocidade 60 km/h");
    crank_vel(110.0);
    sim_ms(600);
    CHECK_PERTO(g_kecu.vel_kmh, 110.0, 1.5, "velocidade 110 km/h");
    crank_vel(0.0);
    sim_ms(1800);
    CHECK_PERTO(g_kecu.vel_kmh, 0.0, 0.01, "sem pulsos de velocidade = 0");
}

/* ================================================================== testes: controle (limitador, two-step, launch, AE, marcha lenta) */

static uint16_t flags_da_telemetria(void) {
    uint8_t b[KECU_TELEMETRIA_BYTES];
    kecu_proto_telemetria(b);
    return (uint16_t)(b[32] | (b[33] << 8));
}

static void teste_limitador(void) {
    secao("Limitador de giro com histerese");
    reset_ecu(1);
    ligar_motor(6400);
    CHECK(!g_kecu.corte, "6400 rpm: sem corte");
    crank_rpm(6520);
    sim_ms(100);
    CHECK(g_kecu.corte, "6520 rpm: corta (limite 6500)");
    CHECK((flags_da_telemetria() & KECU_FLAG_CORTE) != 0, "flag CORTE na telemetria");
    crank_rpm(6400);
    sim_ms(100);
    CHECK(g_kecu.corte, "6400 rpm voltando: continua cortando (histerese 150)");
    crank_rpm(6360);
    sim_ms(100);
    CHECK(g_kecu.corte, "6360 rpm: ainda acima de 6350");
    crank_rpm(6330);
    sim_ms(100);
    CHECK(!g_kecu.corte, "6330 rpm: voltou a injetar");
    CHECK((flags_da_telemetria() & KECU_FLAG_CORTE) == 0, "flag CORTE apagada");

    /* parâmetros alterados valem na hora */
    param(P_REV_LIMITE_RPM, 5000);
    param(P_REV_HISTERESE_RPM, 400);
    crank_rpm(5100);
    sim_ms(100);
    CHECK(g_kecu.corte, "novo limite 5000: corta em 5100");
    crank_rpm(4700);
    sim_ms(100);
    CHECK(g_kecu.corte, "histerese 400: 4700 ainda corta");
    crank_rpm(4580);
    sim_ms(100);
    CHECK(!g_kecu.corte, "4580 volta (<= 4600)");
}

static void teste_tipos_de_corte(void) {
    secao("Tipos de corte: combustível, ignição, os dois");
    for (unsigned tipo = 0; tipo <= 2; tipo++) {
        reset_ecu(1);
        param(P_TIPO_CORTE, tipo);
        ligar_motor(6000);
        const uint32_t i0 = g_kecu.eventos_ignicao;
        const uint32_t j0 = g_kecu.eventos_injecao;
        sim_ms(200);
        CHECK(g_kecu.eventos_ignicao > i0 + 10 && g_kecu.eventos_injecao > j0 + 5 && g_kecu.pw_us > 1000,
              "tipo %u: abaixo do limite ignição e injeção funcionam (ign +%u, inj +%u)", tipo,
              g_kecu.eventos_ignicao - i0, g_kecu.eventos_injecao - j0);
        crank_rpm(6520);
        sim_ms(100);
        CHECK(g_kecu.corte, "tipo %u: corte ativo", tipo);
        const uint32_t i1 = g_kecu.eventos_ignicao;
        const uint32_t j1 = g_kecu.eventos_injecao;
        sim_ms(300);
        const int corta_comb = tipo == 0 || tipo == 2;
        const int corta_ign = tipo == 1 || tipo == 2;
        CHECK(corta_comb ? g_kecu.eventos_injecao == j1 : g_kecu.eventos_injecao > j1 + 20,
              "tipo %u: injeção %s durante o corte", tipo, corta_comb ? "para" : "continua");
        CHECK(corta_ign ? g_kecu.eventos_ignicao == i1 : g_kecu.eventos_ignicao > i1 + 40,
              "tipo %u: ignição %s durante o corte", tipo, corta_ign ? "para" : "continua");
        CHECK(corta_comb ? g_kecu.pw_us == 0 : g_kecu.pw_us > 1000, "tipo %u: pulso %s", tipo,
              corta_comb ? "zerado" : "mantido");
        crank_rpm(6200);
        sim_ms(150); /* 150 ms a 6200 rpm = cerca de 15 voltas = 15 injeções (uma por volta) */
        CHECK(!g_kecu.corte && g_kecu.eventos_injecao > j1 + 8, "tipo %u: tudo volta após o corte (injeções +%u)", tipo,
              g_kecu.eventos_injecao - j1);
    }
}

static void teste_two_step(void) {
    secao("Two-step");
    reset_ecu(1);
    param(P_HAB_TWO_STEP, 1);
    ligar_motor(4600);
    CHECK(!g_kecu.corte && !g_kecu.two_step_ativo, "sem a chave não corta em 4600");

    const uint8_t liga[] = {KECU_CHAVE_TWO_STEP};
    const uint8_t desliga[] = {0};
    CHECK(cmd_ack(KECU_CMD_CHAVES, liga, 1), "CHAVES(two-step) aceito");
    sim_ms(100);
    CHECK(g_kecu.corte && g_kecu.two_step_ativo && g_kecu.chave_two_step, "chave por software: corta em 4600");
    CHECK((flags_da_telemetria() & (KECU_FLAG_TWO_STEP | KECU_FLAG_CHAVE_TWO_STEP)) ==
              (KECU_FLAG_TWO_STEP | KECU_FLAG_CHAVE_TWO_STEP),
          "flags TWO_STEP e CHAVE_TWO_STEP");
    CHECK(cmd_ack(KECU_CMD_CHAVES, desliga, 1), "CHAVES(0) aceito");
    sim_ms(100);
    CHECK(!g_kecu.corte && !g_kecu.two_step_ativo, "soltou a chave: volta ao normal");

    host_definir_entrada(KECU_IN_TWO_STEP, 1);
    sim_ms(100);
    CHECK(g_kecu.corte && g_kecu.two_step_ativo, "chave física (entrada) também aciona");
    param(P_HAB_TWO_STEP, 0);
    sim_ms(100);
    CHECK(!g_kecu.corte && !g_kecu.two_step_ativo, "função desabilitada: ignora a chave");
}

static void teste_launch(void) {
    secao("Launch control");
    reset_ecu(1);
    param(P_HAB_LAUNCH, 1);
    host_definir_tps(100.0f);
    ligar_motor(3600);
    CHECK(!g_kecu.corte && !g_kecu.launch_ativo, "sem a chave: não atua");
    const double avanco_livre = (double)g_kecu.avanco_graus;

    host_definir_entrada(KECU_IN_LAUNCH, 1);
    sim_ms(150);
    CHECK(g_kecu.launch_ativo && g_kecu.corte, "chave + TPS 100%% + parado: limita em 3500");
    CHECK_PERTO(avanco_livre - (double)g_kecu.avanco_graus, 10.0, 0.25, "retardo do launch (graus)");
    CHECK((flags_da_telemetria() & KECU_FLAG_LAUNCH) != 0, "flag LAUNCH");

    host_definir_tps(50.0f);
    sim_ms(200);
    CHECK(!g_kecu.launch_ativo && !g_kecu.corte, "TPS 50%%: não é largada");
    host_definir_tps(100.0f);
    sim_ms(200);
    CHECK(g_kecu.launch_ativo, "TPS de volta a 100%%: atua de novo");
    crank_vel(30.0);
    sim_ms(700);
    CHECK(!g_kecu.launch_ativo && !g_kecu.corte, "carro a 30 km/h: launch desligado");
    crank_vel(0.0);
    sim_ms(1700);
    CHECK(g_kecu.launch_ativo, "parou: launch volta");
}

static void teste_enriquecimento(void) {
    secao("Enriquecimento de aceleração");
    reset_ecu(1);
    host_definir_tps(10.0f); /* o degrau de 0 a 10 % logo no início também conta como "pisada" ... */
    ligar_motor(2500);
    sim_ms(300); /* ... então espera o enriquecimento dele (400 ms) acabar antes de medir */
    CHECK_PERTO(g_kecu.ae_pct, 0.0, 0.01, "sem pisada: sem enriquecimento");
    host_definir_tps(60.0f);
    sim_ms(20);
    CHECK(g_kecu.ae_pct > 25.0f && g_kecu.ae_pct <= 30.01f, "pisada: enriquecimento ~30%% (%.1f)", (double)g_kecu.ae_pct);
    CHECK((flags_da_telemetria() & KECU_FLAG_AE) != 0, "flag AE");
    sim_ms(180);
    CHECK(g_kecu.ae_pct > 10.0f && g_kecu.ae_pct < 19.0f, "metade do tempo: cerca de metade (%.1f)", (double)g_kecu.ae_pct);
    sim_ms(260);
    CHECK_PERTO(g_kecu.ae_pct, 0.0, 0.01, "acabou o tempo: zero");

    /* desabilitado */
    param(P_HAB_AE, 0);
    host_definir_tps(10.0f);
    sim_ms(600);
    host_definir_tps(70.0f);
    sim_ms(30);
    CHECK_PERTO(g_kecu.ae_pct, 0.0, 0.01, "AE desabilitado: nada");

    /* subida lenta do pedal (30 %/s) não dispara */
    param(P_HAB_AE, 1);
    host_definir_tps(10.0f);
    sim_ms(600);
    float maximo = 0.0f;
    for (int i = 0; i < 150; i++) {
        host_definir_tps(10.0f + 0.2f * (float)i);
        sim_ms(7);
        if (g_kecu.ae_pct > maximo) {
            maximo = g_kecu.ae_pct;
        }
    }
    CHECK(maximo == 0.0f, "subida lenta do TPS não dispara AE (máx %.2f)", (double)maximo);

    /* limiar mais baixo: a mesma subida lenta passa a disparar */
    param(P_AE_LIMIAR, 10);
    host_definir_tps(10.0f);
    sim_ms(600);
    maximo = 0.0f;
    for (int i = 0; i < 150; i++) {
        host_definir_tps(10.0f + 0.2f * (float)i);
        sim_ms(7);
        if (g_kecu.ae_pct > maximo) {
            maximo = g_kecu.ae_pct;
        }
    }
    CHECK(maximo > 5.0f, "limiar de 10 %%/s: a mesma rampa dispara (máx %.2f)", (double)maximo);
}

static void teste_marcha_lenta(void) {
    secao("Marcha lenta (PI na válvula IAC)");
    reset_ecu(1);
    host_definir_tps(0.0f);
    ligar_motor(700);
    CHECK(g_kecu.marcha_lenta_ativa, "700 rpm com TPS 0: controle ativo");
    CHECK(g_kecu.iac_pct > 40.0f && g_kecu.iac_pct < 60.0f, "abaixo da meta (850): abre a válvula (%.1f%%)",
          (double)g_kecu.iac_pct);
    CHECK_PERTO(host_iac_pct, g_kecu.iac_pct, 0.001, "HAL recebeu o PWM da válvula");
    CHECK((flags_da_telemetria() & KECU_FLAG_MARCHA_LENTA) != 0, "flag MARCHA_LENTA");

    crank_rpm(1000);
    sim_ms(400);
    CHECK(g_kecu.marcha_lenta_ativa && g_kecu.iac_pct < 30.0f && g_kecu.iac_pct >= 0.0f,
          "acima da meta: fecha a válvula (%.1f%%)", (double)g_kecu.iac_pct);

    host_definir_tps(10.0f);
    sim_ms(100);
    CHECK(!g_kecu.marcha_lenta_ativa && fabs((double)g_kecu.iac_pct - 35.0) < 0.01, "com pedal: sai da marcha lenta, válvula na base");
    host_definir_tps(0.0f);
    crank_rpm(1400);
    sim_ms(300);
    CHECK(!g_kecu.marcha_lenta_ativa, "1400 rpm (meta+550): não controla");

    param(P_HAB_MARCHA_LENTA, 0);
    crank_rpm(700);
    sim_ms(300);
    CHECK(!g_kecu.marcha_lenta_ativa, "desabilitada: não controla");
    param(P_HAB_MARCHA_LENTA, 1);

    /* motor frio: meta maior */
    host_definir_sensores(20.0f, 25.0f, 40.0f, 0.0f, 13.8f, 3.0f, 1.0f);
    crank_rpm(1250);
    sim_ms(2500);
    CHECK(g_kecu.marcha_lenta_ativa && g_kecu.iac_pct > 35.0f, "motor frio: meta ~1300, abre a válvula (%.1f%%)",
          (double)g_kecu.iac_pct);

    parar_motor();
    CHECK(g_kecu.iac_pct == 0.0f && host_iac_pct == 0.0f, "motor parado: válvula em zero");
}

/* ================================================================== testes: pulso e avanço */

static double ve_interp(void) {
    return kecu_mapa_interp_raw(&g_kecu.mapas, MAPA_COMBUSTIVEL, g_kecu.pos_rpm, g_kecu.pos_carga);
}

static double lambda_alvo(void) {
    return kecu_mapa_fisico(MAPA_LAMBDA, kecu_mapa_interp_raw(&g_kecu.mapas, MAPA_LAMBDA, g_kecu.pos_rpm, g_kecu.pos_carga));
}

static void teste_pulso_e_avanco(void) {
    secao("Pulso do injetor e avanço");
    reset_ecu(1);
    host_definir_sensores(90.0f, 20.0f, 60.0f, 0.0f, 14.0f, 3.0f, 1.0f);
    ligar_motor(3000);
    /* conta feita à mão: VE=75,34%  MAP=60 kPa  lambda alvo=1,0  =>  8500 * 0,7534 * 0,60 + 900 = 4743 us */
    CHECK_PERTO(g_kecu.pw_us, 4743.0, 14.0, "pulso absoluto (us)");
    CHECK_PERTO(g_kecu.avanco_graus, 24.0, 0.2, "avanço no ponto 3000 rpm / 60 kPa");
    CHECK(g_kecu.ve_pct == 75, "VE arredondado = %u", g_kecu.ve_pct);
    CHECK_PERTO(g_kecu.pos_rpm, 6.0, 0.03, "posição no eixo de rpm");
    CHECK_PERTO(g_kecu.pos_carga, 6.668, 0.01, "posição no eixo de carga");

    /* a mesma conta refeita com os valores medidos de fato */
    const double pulso_modelo = 8500.0 * (ve_interp() / 100.0) * ((double)g_kecu.map_kpa / 100.0)
                                * (293.15 / (273.15 + (double)g_kecu.iat_c)) / lambda_alvo();
    const double morto = 900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v);
    CHECK_PERTO(g_kecu.pw_us, pulso_modelo + morto, 1.5, "pulso = modelo calculado no teste");
    uint8_t t[KECU_TELEMETRIA_BYTES];
    kecu_proto_telemetria(t);
    CHECK((uint16_t)(t[24] | (t[25] << 8)) == g_kecu.pw_us, "telemetria carrega o mesmo pulso");

    /* enriquecimento de motor frio: x1,3 a 20 C */
    const double base = (double)g_kecu.pw_us - morto;
    host_definir_sensores(20.0f, 20.0f, 60.0f, 0.0f, 14.0f, 3.0f, 1.0f);
    sim_ms(2500);
    const double frio = (double)g_kecu.pw_us - (900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v));
    CHECK_PERTO(frio / base, 1.3, 0.01, "enriquecimento a 20 C = 1,3x");

    /* ar mais quente = menos denso = menos combustível */
    host_definir_sensores(90.0f, 60.0f, 60.0f, 0.0f, 14.0f, 3.0f, 1.0f);
    sim_ms(2500);
    const double quente = (double)g_kecu.pw_us - (900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v));
    CHECK_PERTO(quente / base, 293.15 / 333.15, 0.01, "ar a 60 C: 0,88x");

    /* tempo morto cresce com a bateria fraca */
    host_definir_sensores(90.0f, 20.0f, 60.0f, 0.0f, 14.0f, 3.0f, 1.0f);
    sim_ms(2500);
    const double pw14 = (double)g_kecu.pw_us;
    host_definir_sensores(90.0f, 20.0f, 60.0f, 0.0f, 10.0f, 3.0f, 1.0f);
    sim_ms(2500);
    CHECK_PERTO((double)g_kecu.pw_us - pw14, 600.0, 15.0, "bateria 10 V: +600 us de tempo morto");

    /* partida: avanço fixo de 10 graus e pulso 40% do requerido */
    reset_ecu(1);
    host_definir_sensores(90.0f, 20.0f, 100.0f, 0.0f, 14.0f, 0.0f, 1.0f);
    crank_rpm(250);
    sim_ms(900);
    CHECK(g_kecu.motor_girando && g_kecu.rpm < 400.0f, "girando de partida (%.0f rpm)", (double)g_kecu.rpm);
    CHECK_PERTO(g_kecu.avanco_graus, 10.0, 0.01, "avanço na partida");
    CHECK_PERTO(g_kecu.pw_us, 8500.0 * 0.4 + 900.0, 2.0, "pulso de partida (motor quente)");
    host_definir_sensores(20.0f, 20.0f, 100.0f, 0.0f, 14.0f, 0.0f, 1.0f);
    sim_ms(2500);
    CHECK_PERTO(g_kecu.pw_us, 8500.0 * 0.4 * 1.3 + 900.0, 3.0, "pulso de partida a frio (x1,3)");

    /* mapa editado vale na hora (como a gravação "ao vivo" pelo aplicativo) */
    reset_ecu(1);
    host_definir_sensores(90.0f, 20.0f, 60.0f, 0.0f, 14.0f, 3.0f, 1.0f);
    ligar_motor(3000);
    uint8_t linha[2 + KECU_N];
    linha[0] = MAPA_COMBUSTIVEL;
    memset(&linha[2], 100, KECU_N);
    linha[1] = 6;
    CHECK(cmd_ack(KECU_CMD_ESCREVER_LINHA, linha, (int)sizeof linha), "ESCREVER_LINHA linha 6");
    linha[1] = 7;
    CHECK(cmd_ack(KECU_CMD_ESCREVER_LINHA, linha, (int)sizeof linha), "ESCREVER_LINHA linha 7");
    sim_ms(60);
    CHECK(g_kecu.ve_pct == 100, "VE 100%% nas duas linhas vizinhas (%u)", g_kecu.ve_pct);
}

/* ================================================================== testes: planejador de ignição e injeção */

#if KECU_ENABLE_OUTPUTS

static double ang_mod(double a) {
    const double r = fmod(a, 360.0);
    return r < 0.0 ? r + 360.0 : r;
}

static double ang_dif(double a, double b) {
    const double d = ang_mod(a - b);
    return d > 180.0 ? d - 360.0 : d;
}

static void armar(int ligar) {
    const uint8_t v[] = {(uint8_t)ligar};
    quadro_t r;
    (void)comando(KECU_CMD_ARMAR, v, 1, &r);
}

/*
 * Confere, com o relógio virtual, em que ÂNGULO do virabrequim a ECU mandou ligar cada bico e
 * cada bobina. A referência é o instante do 1º dente depois da falha (ângulo 0 do planejador).
 * A centelha acontece quando a bobina DESLIGA, ou seja, instante de ligar + tempo de carga.
 */
static void planejador_em(double rpm, unsigned pms_offset_x10) {
    reset_ecu(1);
    param(P_PMS_OFFSET_X10, pms_offset_x10);
    param(P_REV_LIMITE_RPM, 9000); /* senão o limitador padrão (6500) cortaria tudo no caso de 7000 rpm */
    kecu_aplicar_parametros();
    host_definir_sensores(90.0f, 25.0f, 60.0f, 20.0f, 13.8f, 3.0f, 1.0f);
    armar(1);
    CHECK(g_kecu.armado == 1, "%.0f rpm: armada", rpm);
    ligar_motor(rpm);
    sim_ms(600); /* o degrau de TPS do início dispara o enriquecimento de aceleração (400 ms): deixa passar */
    host_pulsos_limpar();
    sim_ms(1000);

    const double rev_us = 60e6 / rpm;
    const double adv = (double)g_kecu.avanco_graus;
    const double pms = (double)pms_offset_x10 / 10.0;
    const double alvo[2] = {ang_mod(pms - adv), ang_mod(pms + 180.0 - adv)};
    const double morto = 900.0 + 150.0 * (14.0 - (double)g_kecu.vbat_v);
    const double inj_esperado = ((double)g_kecu.pw_us - morto) / 2.0 + morto;
    const double dwell_esperado = 3000.0 * 14.0 / (double)g_kecu.vbat_v;

    int n_ign = 0;
    int n_inj = 0;
    int bobina_errada = 0;
    int dwell_errado = 0;
    int inj_largura_errada = 0;
    int inj_dessincronizados = 0;
    int por_alvo[2] = {0, 0};
    double max_err_ign = 0.0;
    double max_err_inj = 0.0;
    const int total = host_pulsos_n();
    for (int i = 0; i < total; i++) {
        const host_pulso_t p = host_pulso(i);
        if (p.saida == KECU_SAIDA_BOBINA1 || p.saida == KECU_SAIDA_BOBINA2) {
            n_ign++;
            const double centelha = (double)p.instante_us + (double)p.largura_us;
            const double ang = ang_mod((centelha - cr.t0_us) / rev_us * 360.0);
            const double e0 = fabs(ang_dif(ang, alvo[0]));
            const double e1 = fabs(ang_dif(ang, alvo[1]));
            const int k = e0 <= e1 ? 0 : 1;
            const double e = k == 0 ? e0 : e1;
            por_alvo[k]++;
            if (e > max_err_ign) {
                max_err_ign = e;
            }
            const kecu_saida_t esperada = (kecu_saida_t)(KECU_SAIDA_BOBINA1 + (k % KECU_BOBINAS));
            if (p.saida != esperada) {
                bobina_errada++;
            }
            if (fabs((double)p.largura_us - dwell_esperado) > 6.0) {
                dwell_errado++;
            }
        } else {
            n_inj++;
            const double ang = ang_mod(((double)p.instante_us - cr.t0_us) / rev_us * 360.0);
            const double e = fabs(ang_dif(ang, 0.0));
            if (e > max_err_inj) {
                max_err_inj = e;
            }
            if (fabs((double)p.largura_us - inj_esperado) > 4.0) {
                inj_largura_errada++;
            }
            if (p.saida == KECU_SAIDA_INJ1 && i + 3 < total) {
                const host_pulso_t b = host_pulso(i + 1);
                const host_pulso_t c = host_pulso(i + 2);
                const host_pulso_t d = host_pulso(i + 3);
                if (b.saida != KECU_SAIDA_INJ2 || c.saida != KECU_SAIDA_INJ3 || d.saida != KECU_SAIDA_INJ4
                    || b.instante_us != p.instante_us || c.instante_us != p.instante_us
                    || d.instante_us != p.instante_us) {
                    inj_dessincronizados++;
                }
            }
        }
    }
    const double revs = rpm / 60.0; /* voltas em 1 s */
    CHECK(fabs((double)n_ign - 2.0 * revs) <= 3.0, "%.0f rpm: %d centelhas em 1 s (esperado %.0f)", rpm, n_ign, 2.0 * revs);
    CHECK(fabs((double)n_inj - 4.0 * revs) <= 8.0, "%.0f rpm: %d pulsos de bico em 1 s (esperado %.0f)", rpm, n_inj,
          4.0 * revs);
    CHECK(por_alvo[0] > 0 && por_alvo[1] > 0 && abs(por_alvo[0] - por_alvo[1]) <= 2,
          "%.0f rpm: centelhas alternam entre os dois eventos (%d / %d)", rpm, por_alvo[0], por_alvo[1]);
    CHECK(max_err_ign < 0.6, "%.0f rpm: erro máximo do ângulo da centelha %.3f graus (avanço %.1f)", rpm, max_err_ign, adv);
    CHECK(max_err_inj < 0.6, "%.0f rpm: erro máximo do início da injeção %.3f graus", rpm, max_err_inj);
    CHECK(bobina_errada == 0, "%.0f rpm: cada centelha na bobina certa (%d erradas)", rpm, bobina_errada);
    CHECK(dwell_errado == 0, "%.0f rpm: tempo de carga da bobina = %.0f us (%d fora)", rpm, dwell_esperado, dwell_errado);
    CHECK(inj_largura_errada == 0, "%.0f rpm: largura de injeção %.0f us (%d fora)", rpm, inj_esperado, inj_largura_errada);
    CHECK(inj_dessincronizados == 0, "%.0f rpm: os 4 bicos disparam juntos", rpm);
}

static void teste_planejador(void) {
    secao("Planejador: ângulo de ignição e início da injeção (relógio virtual)");
    static const double lista[] = {1000, 3000, 5000, 7000};
    for (size_t i = 0; i < sizeof lista / sizeof lista[0]; i++) {
        planejador_em(lista[i], 0);
    }
    planejador_em(3000, 300); /* PMS deslocado 30 graus em relação à falha da roda */

    secao("Planejador: partida, corte e desarme");
    reset_ecu(1);
    host_definir_sensores(90.0f, 25.0f, 100.0f, 0.0f, 12.0f, 0.0f, 1.0f);
    armar(1);
    crank_rpm(250);
    sim_ms(1500);
    host_pulsos_limpar();
    sim_ms(2000);
    int ign = 0;
    double max_err = 0.0;
    for (int i = 0; i < host_pulsos_n(); i++) {
        const host_pulso_t p = host_pulso(i);
        if (p.saida == KECU_SAIDA_BOBINA1 || p.saida == KECU_SAIDA_BOBINA2) {
            ign++;
            const double ang = ang_mod(((double)p.instante_us + (double)p.largura_us - cr.t0_us) / (60e6 / 250.0) * 360.0);
            const double e = fmin(fabs(ang_dif(ang, 350.0)), fabs(ang_dif(ang, 170.0)));
            if (e > max_err) {
                max_err = e;
            }
        }
    }
    CHECK(ign >= 15 && ign <= 17, "partida (250 rpm): %d centelhas em 2 s", ign);
    CHECK(max_err < 0.6, "partida: avanço fixo de 10 graus (erro %.3f)", max_err);

    reset_ecu(1);
    host_definir_sensores(90.0f, 25.0f, 60.0f, 20.0f, 13.8f, 3.0f, 1.0f);
    armar(1);
    ligar_motor(80);
    host_pulsos_limpar();
    sim_ms(1500);
    CHECK(host_pulsos_n() == 0, "abaixo de 100 rpm o planejador não atua (%d pulsos)", host_pulsos_n());

    reset_ecu(1);
    host_definir_sensores(90.0f, 25.0f, 60.0f, 20.0f, 13.8f, 3.0f, 1.0f);
    armar(1);
    ligar_motor(6000);
    crank_rpm(6600);
    sim_ms(200);
    host_pulsos_limpar();
    sim_ms(400);
    CHECK(g_kecu.corte && host_pulsos_n() == 0, "corte total (tipo 2): nenhum pulso de bico nem de bobina (%d)",
          host_pulsos_n());
    crank_rpm(6000);
    sim_ms(200);
    host_pulsos_limpar();
    sim_ms(200);
    CHECK(host_pulsos_n() > 20, "depois do corte os pulsos voltam (%d)", host_pulsos_n());

    const int antes = host_desligamentos;
    armar(0);
    CHECK(g_kecu.armado == 0 && host_desligamentos > antes, "ARMAR(0) desarma e manda a HAL desligar tudo");
    host_pulsos_limpar();
    sim_ms(300);
    CHECK(host_pulsos_n() == 0, "desarmada: nenhum pulso (%d)", host_pulsos_n());
    CHECK(g_kecu.eventos_ignicao > 0 && g_kecu.eventos_injecao > 0, "o planejamento continua contando eventos");

    secao("Planejador: sem ARMAR nada sai, mesmo com as saídas habilitadas na compilação");
    reset_ecu(1);
    host_definir_sensores(90.0f, 25.0f, 60.0f, 20.0f, 13.8f, 3.0f, 1.0f);
    ligar_motor(3000);
    host_pulsos_limpar();
    sim_ms(500);
    CHECK(host_pulsos_n() == 0 && g_kecu.eventos_ignicao > 0, "desarmada de fábrica: %d pulsos, eventos planejados %u",
          host_pulsos_n(), g_kecu.eventos_ignicao);
}

#else

static void teste_modo_sombra(void) {
    secao("Modo sombra: nenhuma saída pode ser acionada");
    reset_ecu(1);
    host_definir_sensores(90.0f, 25.0f, 60.0f, 20.0f, 13.8f, 3.0f, 1.0f);
    const uint8_t um[] = {1};
    CHECK(cmd_nack(KECU_CMD_ARMAR, um, 1, KECU_NACK_SAIDAS_DESABILITADAS), "ARMAR(1) recusado com NACK 6");
    CHECK(g_kecu.armado == 0, "continua desarmada");
    ligar_motor(3000);
    sim_ms(1000);
    CHECK(host_pulsos_n() == 0 && host_desligamentos == 0, "nenhum pulso pedido à HAL (%d)", host_pulsos_n());
    CHECK(g_kecu.eventos_ignicao > 80 && g_kecu.eventos_injecao > 40, "mas os eventos são planejados e contados (%u / %u)",
          g_kecu.eventos_ignicao, g_kecu.eventos_injecao);
    CHECK((flags_da_telemetria() & KECU_FLAG_MODO_SOMBRA) != 0 && (flags_da_telemetria() & KECU_FLAG_ARMADO) == 0,
          "telemetria avisa: modo sombra, não armada");
}

#endif

/* ================================================================== testes: protocolo */

static void escrever_eixos_bytes(const kecu_eixos_t *e, uint8_t *b) {
    for (int i = 0; i < KECU_N; i++) {
        b[2 * i] = (uint8_t)(e->rpm[i] & 0xFFu);
        b[2 * i + 1] = (uint8_t)(e->rpm[i] >> 8);
        b[2 * KECU_N + 2 * i] = (uint8_t)(e->carga[i] & 0xFFu);
        b[2 * KECU_N + 2 * i + 1] = (uint8_t)(e->carga[i] >> 8);
    }
}

static int injetar_e_rodar(const uint8_t *b, int n) {
    host_serial_injetar(b, n);
    for (int i = 0; i < 4; i++) {
        kecu_loop();
    }
    drenar_saida();
    return g_quadros_n;
}

static int contar_tipo(uint8_t tipo) {
    int c = 0;
    for (int i = 0; i < g_quadros_n; i++) {
        c += g_quadros[i].tipo == tipo;
    }
    return c;
}

static void teste_protocolo_comandos(void) {
    secao("Protocolo: HELLO, STREAM e telemetria");
    reset_ecu(1);
    quadro_t r;
    CHECK(comando(KECU_CMD_HELLO, NULL, 0, &r) && r.tipo == KECU_RSP_INFO && r.n == KECU_INFO_BYTES,
          "HELLO responde INFO de 8 bytes");
    CHECK(r.d[0] == KECU_PROTOCOLO_VERSAO && r.d[1] == KECU_FW_MAIOR && r.d[2] == KECU_FW_MENOR && r.d[3] == KECU_PLACA
              && r.d[4] == KECU_N && r.d[5] == KECU_NUM_MAPAS,
          "INFO: versão, firmware, placa, 16x16, 3 mapas");
    const unsigned caps = (unsigned)(r.d[6] | (r.d[7] << 8));
    CHECK(caps == ((KECU_ENABLE_OUTPUTS ? KECU_CAP_SAIDAS_REAIS : 0u) | (KECU_TEM_NVM ? KECU_CAP_NVM : 0u)),
          "INFO: capacidades 0x%X", caps);
    CHECK(cmd_nack(0x7F, NULL, 0, KECU_NACK_COMANDO), "comando desconhecido: NACK 1");

    const uint8_t s_curto[] = {20};
    CHECK(cmd_nack(KECU_CMD_STREAM, s_curto, 1, KECU_NACK_TAMANHO), "STREAM com 1 byte: NACK 2");
    const uint8_t s3[] = {3, 0};
    const uint8_t s1001[] = {0xE9, 0x03};
    const uint8_t s5[] = {5, 0};
    const uint8_t s1000[] = {0xE8, 0x03};
    const uint8_t s0[] = {0, 0};
    CHECK(cmd_nack(KECU_CMD_STREAM, s3, 2, KECU_NACK_FAIXA), "STREAM 3 ms: NACK 3");
    CHECK(cmd_nack(KECU_CMD_STREAM, s1001, 2, KECU_NACK_FAIXA), "STREAM 1001 ms: NACK 3");
    CHECK(cmd_ack(KECU_CMD_STREAM, s5, 2) && kecu_proto_periodo_ms() == 5, "STREAM 5 ms aceito");
    CHECK(cmd_ack(KECU_CMD_STREAM, s1000, 2) && kecu_proto_periodo_ms() == 1000, "STREAM 1000 ms aceito");
    CHECK(cmd_ack(KECU_CMD_STREAM, s0, 2) && kecu_proto_periodo_ms() == 0, "STREAM 0 desliga");

    /* 20 ms: cerca de 50 quadros por segundo, com sequência contínua e tempo coerente */
    const uint8_t s20[] = {20, 0};
    CHECK(cmd_ack(KECU_CMD_STREAM, s20, 2), "STREAM 20 ms aceito");
    ligar_motor(3000);
    host_serial_limpar();
    sim_ms(1000);
    drenar_saida();
    const int n = contar_tipo(KECU_RSP_TELEMETRIA);
    CHECK(n >= 48 && n <= 52, "cerca de 50 quadros de telemetria em 1 s (%d)", n);
    int seq_ok = 1;
    int tamanho_ok = 1;
    double soma_dt = 0.0;
    int n_dt = 0;
    long t_ant = -1;
    int seq_ant = -1;
    for (int i = 0; i < g_quadros_n; i++) {
        if (g_quadros[i].tipo != KECU_RSP_TELEMETRIA) {
            continue;
        }
        tamanho_ok &= g_quadros[i].n == KECU_TELEMETRIA_BYTES;
        if (seq_ant >= 0 && g_quadros[i].seq != ((seq_ant + 1) & 0xFF)) {
            seq_ok = 0;
        }
        seq_ant = g_quadros[i].seq;
        const long t = (long)(g_quadros[i].d[0] | (g_quadros[i].d[1] << 8) | (g_quadros[i].d[2] << 16)
                              | ((long)g_quadros[i].d[3] << 24));
        if (t_ant >= 0) {
            soma_dt += (double)(t - t_ant);
            n_dt++;
        }
        t_ant = t;
    }
    CHECK(tamanho_ok && seq_ok, "telemetria: 36 bytes e sequência contínua");
    CHECK_PERTO(soma_dt / (double)(n_dt > 0 ? n_dt : 1), 20.0, 1.0, "intervalo médio entre quadros (ms)");

    secao("Protocolo: telemetria carrega os valores certos");
    host_definir_sensores(-10.0f, 33.0f, 71.5f, 42.0f, 13.2f, 4.5f, 0.97f);
    ligar_motor(3000);
    crank_vel(88.0);
    sim_ms(2800);
    uint8_t t[KECU_TELEMETRIA_BYTES];
    kecu_proto_telemetria(t);
#define U16(o) ((unsigned)(t[o] | (t[(o) + 1] << 8)))
#define S16(o) ((int)(int16_t)(t[o] | (t[(o) + 1] << 8)))
    CHECK_PERTO(U16(4), g_kecu.rpm, 0.51, "rpm");
    CHECK_PERTO(U16(6), 880.0, 12.0, "velocidade x10");
    CHECK_PERTO(S16(8), -100.0, 8.0, "ECT x10 (negativo, complemento de dois)");
    CHECK_PERTO(S16(10), 330.0, 8.0, "IAT x10");
    CHECK_PERTO(U16(12), 715.0, 5.0, "MAP x10");
    CHECK_PERTO(U16(14), 45.0, 1.5, "óleo x10");
    CHECK_PERTO(U16(16), 420.0, 4.0, "TPS x10");
    CHECK_PERTO(U16(18), 970.0, 15.0, "lambda x1000");
    CHECK_PERTO(U16(20), 13200.0, 60.0, "bateria x1000");
    CHECK_PERTO(S16(22), (double)g_kecu.avanco_graus * 10.0, 0.51, "avanço x10");
    CHECK(U16(24) == g_kecu.pw_us && t[26] == g_kecu.ve_pct, "pulso e VE");
    CHECK_PERTO(t[29], g_kecu.pos_rpm * 16.0, 0.51, "posição rpm x16");
    CHECK_PERTO(t[30], g_kecu.pos_carga * 16.0, 0.51, "posição carga x16");
    CHECK(t[31] == 0, "byte reservado = 0");
    CHECK((U16(32) & KECU_FLAG_SYNC) && (U16(32) & KECU_FLAG_MOTOR_GIRANDO), "flags: sync e motor girando");
    CHECK(U16(34) == g_kecu.erros, "campo de erros");
#undef U16
#undef S16
}

static void teste_protocolo_dados(void) {
    secao("Protocolo: parâmetros");
    reset_ecu(1);
    quadro_t r;
    uint8_t b[KECU_PARAMS_BYTES];
    uint8_t esperado[KECU_PARAMS_BYTES];
    kecu_params_serializar(&g_kecu.params, esperado);
    CHECK(comando(KECU_CMD_LER_PARAMS, NULL, 0, &r) && r.tipo == KECU_RSP_PARAMS && r.n == KECU_PARAMS_BYTES
              && memcmp(r.d, esperado, KECU_PARAMS_BYTES) == 0,
          "LER_PARAMS devolve a tabela (47 bytes)");

    kecu_params_t p = g_kecu.params;
    p.v[P_REV_LIMITE_RPM] = 7000;
    kecu_params_serializar(&p, b);
    CHECK(cmd_ack(KECU_CMD_ESCREVER_PARAMS, b, KECU_PARAMS_BYTES) && g_kecu.params.v[P_REV_LIMITE_RPM] == 7000,
          "ESCREVER_PARAMS aceito e aplicado");
    CHECK(cmd_nack(KECU_CMD_ESCREVER_PARAMS, b, KECU_PARAMS_BYTES - 1, KECU_NACK_TAMANHO), "tamanho errado: NACK 2");
    p.v[P_REV_LIMITE_RPM] = 100;
    kecu_params_serializar(&p, b);
    CHECK(cmd_nack(KECU_CMD_ESCREVER_PARAMS, b, KECU_PARAMS_BYTES, KECU_NACK_FAIXA)
              && g_kecu.params.v[P_REV_LIMITE_RPM] == 7000,
          "valor fora da faixa: NACK 3 e nada muda");

    /* motor girando: campos do sinal ficam travados; os demais seguem livres */
    ligar_motor(3000);
    p = g_kecu.params;
    p.v[P_DENTES_RODA] = 36;
    kecu_params_serializar(&p, b);
    CHECK(cmd_nack(KECU_CMD_ESCREVER_PARAMS, b, KECU_PARAMS_BYTES, KECU_NACK_MOTOR_GIRANDO)
              && g_kecu.params.v[P_DENTES_RODA] == 60,
          "girando: mudar a roda dá NACK 4 e nada muda");
    p = g_kecu.params;
    p.v[P_DWELL_US] = 3200;
    kecu_params_serializar(&p, b);
    CHECK(cmd_ack(KECU_CMD_ESCREVER_PARAMS, b, KECU_PARAMS_BYTES) && g_kecu.params.v[P_DWELL_US] == 3200,
          "girando: outros parâmetros podem mudar");
    CHECK(g_kecu.sync == 1, "e a sincronia não se perde com isso");

    /* parado: a roda pode mudar e o decodificador acompanha */
    parar_motor();
    p = g_kecu.params;
    p.v[P_DENTES_RODA] = 36;
    p.v[P_DENTES_FALTANDO] = 1;
    kecu_params_serializar(&p, b);
    CHECK(cmd_ack(KECU_CMD_ESCREVER_PARAMS, b, KECU_PARAMS_BYTES), "parado: troca de roda aceita");
    crank_roda(36, 1);
    ligar_motor(2400);
    CHECK(g_kecu.sync == 1 && fabs((double)g_kecu.rpm - 2400.0) < 24.0, "decodificador passou para 36-1 (%.0f rpm)",
          (double)g_kecu.rpm);

    secao("Protocolo: linhas dos mapas e eixos");
    reset_ecu(1);
    uint8_t q[2 + KECU_N];
    q[0] = MAPA_IGNICAO;
    q[1] = 3;
    CHECK(comando(KECU_CMD_LER_LINHA, q, 2, &r) && r.tipo == KECU_RSP_LINHA && r.n == 2 + KECU_N && r.d[0] == MAPA_IGNICAO
              && r.d[1] == 3 && memcmp(&r.d[2], g_kecu.mapas.m[MAPA_IGNICAO][3], KECU_N) == 0,
          "LER_LINHA devolve [mapa, linha, 16 bytes]");
    for (int i = 0; i < KECU_N; i++) {
        q[2 + i] = (uint8_t)(i % 2 == 0 ? 0xA5 : 0x5A); /* o SOF dentro do payload não pode confundir o decodificador */
    }
    q[0] = MAPA_LAMBDA;
    q[1] = 15;
    CHECK(cmd_ack(KECU_CMD_ESCREVER_LINHA, q, (int)sizeof q), "ESCREVER_LINHA com A5 5A dentro do payload");
    CHECK(memcmp(g_kecu.mapas.m[MAPA_LAMBDA][15], &q[2], KECU_N) == 0, "linha gravada intacta");
    uint8_t lq[2] = {MAPA_LAMBDA, 15};
    CHECK(comando(KECU_CMD_LER_LINHA, lq, 2, &r) && memcmp(&r.d[2], &q[2], KECU_N) == 0, "e lida de volta igual");
    uint8_t ruim[2] = {3, 0};
    CHECK(cmd_nack(KECU_CMD_LER_LINHA, ruim, 2, KECU_NACK_FAIXA), "mapa 3: NACK 3");
    ruim[0] = 0;
    ruim[1] = 16;
    CHECK(cmd_nack(KECU_CMD_LER_LINHA, ruim, 2, KECU_NACK_FAIXA), "linha 16: NACK 3");
    CHECK(cmd_nack(KECU_CMD_LER_LINHA, ruim, 1, KECU_NACK_TAMANHO), "LER_LINHA com 1 byte: NACK 2");
    q[0] = 7;
    q[1] = 0;
    CHECK(cmd_nack(KECU_CMD_ESCREVER_LINHA, q, (int)sizeof q, KECU_NACK_FAIXA), "ESCREVER_LINHA mapa 7: NACK 3");
    CHECK(cmd_nack(KECU_CMD_ESCREVER_LINHA, q, (int)sizeof q - 1, KECU_NACK_TAMANHO), "ESCREVER_LINHA curta: NACK 2");

    uint8_t eb[4 * KECU_N];
    kecu_eixos_t e;
    kecu_eixos_padrao(&e);
    escrever_eixos_bytes(&e, eb);
    CHECK(comando(KECU_CMD_LER_EIXOS, NULL, 0, &r) && r.tipo == KECU_RSP_EIXOS && r.n == 4 * KECU_N
              && memcmp(r.d, eb, 4 * KECU_N) == 0,
          "LER_EIXOS devolve 16 rpm + 16 cargas");
    e.rpm[15] = 8000;
    escrever_eixos_bytes(&e, eb);
    CHECK(cmd_ack(KECU_CMD_ESCREVER_EIXOS, eb, 4 * KECU_N) && g_kecu.eixos.rpm[15] == 8000, "ESCREVER_EIXOS aceito");
    e.rpm[7] = e.rpm[6];
    escrever_eixos_bytes(&e, eb);
    CHECK(cmd_nack(KECU_CMD_ESCREVER_EIXOS, eb, 4 * KECU_N, KECU_NACK_FAIXA) && g_kecu.eixos.rpm[7] == 3500,
          "eixo não crescente: NACK 3 e nada muda");
    CHECK(cmd_nack(KECU_CMD_ESCREVER_EIXOS, eb, 4 * KECU_N - 2, KECU_NACK_TAMANHO), "eixos curtos: NACK 2");

    secao("Protocolo: ARMAR e CHAVES");
    const uint8_t dois[] = {1, 1};
    CHECK(cmd_nack(KECU_CMD_ARMAR, dois, 2, KECU_NACK_TAMANHO), "ARMAR com 2 bytes: NACK 2");
    const uint8_t zero[] = {0};
    CHECK(cmd_ack(KECU_CMD_ARMAR, zero, 1) && g_kecu.armado == 0, "ARMAR(0) sempre aceito");
    const uint8_t um[] = {1};
#if KECU_ENABLE_OUTPUTS
    CHECK(cmd_ack(KECU_CMD_ARMAR, um, 1) && g_kecu.armado == 1, "ARMAR(1) aceito (saídas habilitadas)");
    CHECK((flags_da_telemetria() & KECU_FLAG_ARMADO) != 0 && (flags_da_telemetria() & KECU_FLAG_MODO_SOMBRA) == 0,
          "flag ARMADO, sem MODO_SOMBRA");
    const uint8_t tres[] = {3};
    CHECK(cmd_nack(KECU_CMD_ARMAR, tres, 1, KECU_NACK_FAIXA), "ARMAR(3): NACK 3");
    CHECK(cmd_ack(KECU_CMD_ARMAR, zero, 1) && g_kecu.armado == 0, "desarma");
#else
    CHECK(cmd_nack(KECU_CMD_ARMAR, um, 1, KECU_NACK_SAIDAS_DESABILITADAS) && g_kecu.armado == 0,
          "ARMAR(1): NACK 6 (modo sombra)");
#endif
    const uint8_t k3[] = {3};
    const uint8_t kff[] = {0xFF};
    CHECK(cmd_nack(KECU_CMD_CHAVES, NULL, 0, KECU_NACK_TAMANHO), "CHAVES vazio: NACK 2");
    CHECK(cmd_ack(KECU_CMD_CHAVES, k3, 1) && g_kecu.chaves_sw == 3, "CHAVES(3) aceito");
    CHECK(cmd_ack(KECU_CMD_CHAVES, kff, 1) && g_kecu.chaves_sw == 3, "bits desconhecidos são ignorados");
}

static void teste_protocolo_framing(void) {
    secao("Protocolo: quadros quebrados, ruído e ressincronização");
    reset_ecu(1);
    uint8_t f[64];
    uint8_t buf[256];
    kecu_proto_stats_t st0;
    kecu_proto_stats_t st1;

    /* lixo antes do quadro (sem um falso início "A5 5A") */
    static const uint8_t lixo[] = {0x00, 0xFF, 0xA5, 0xA5, 0x12, 0x34, 0x5A};
    memcpy(buf, lixo, sizeof lixo);
    int n = (int)sizeof lixo;
    n += kecu_proto_montar(&buf[n], KECU_CMD_HELLO, 11, NULL, 0);
    injetar_e_rodar(buf, n);
    CHECK(contar_tipo(KECU_RSP_INFO) == 1, "lixo antes do quadro: HELLO ainda é atendido");

    /* um byte por vez */
    const uint16_t tam = kecu_proto_montar(f, KECU_CMD_HELLO, 12, NULL, 0);
    for (int i = 0; i < tam; i++) {
        host_serial_injetar(&f[i], 1);
        kecu_loop();
    }
    drenar_saida();
    CHECK(contar_tipo(KECU_RSP_INFO) == 1, "quadro chegando byte a byte");

    /* dois quadros colados */
    n = kecu_proto_montar(buf, KECU_CMD_HELLO, 13, NULL, 0);
    const uint8_t liga[] = {KECU_CHAVE_TWO_STEP};
    n += kecu_proto_montar(&buf[n], KECU_CMD_CHAVES, 14, liga, 1);
    injetar_e_rodar(buf, n);
    CHECK(contar_tipo(KECU_RSP_INFO) == 1 && contar_tipo(KECU_RSP_ACK) == 1, "dois quadros colados: duas respostas");

    /* CRC errado seguido de quadro bom */
    kecu_proto_stats(&st0);
    n = kecu_proto_montar(buf, KECU_CMD_HELLO, 15, NULL, 0);
    buf[n - 1] ^= 0xFF;
    n += kecu_proto_montar(&buf[n], KECU_CMD_HELLO, 16, NULL, 0);
    injetar_e_rodar(buf, n);
    kecu_proto_stats(&st1);
    CHECK(contar_tipo(KECU_RSP_INFO) == 1 && g_quadros[0].seq == 16, "CRC errado ignorado; o seguinte atendido");
    CHECK(st1.erros_crc > st0.erros_crc, "erro de CRC contado");

    /* tamanho absurdo no cabeçalho */
    n = 0;
    buf[n++] = (uint8_t)KECU_SOF1;
    buf[n++] = (uint8_t)KECU_SOF2;
    buf[n++] = 241;
    buf[n++] = 0x01;
    buf[n++] = 0x01;
    n += kecu_proto_montar(&buf[n], KECU_CMD_HELLO, 17, NULL, 0);
    injetar_e_rodar(buf, n);
    CHECK(contar_tipo(KECU_RSP_INFO) == 1, "LEN=241 descartado; o quadro seguinte atendido");

    /* falso cabeçalho (ruído "A5 5A 12 34") antes de um HELLO: o decodificador espera 25 bytes que não vêm... */
    n = 0;
    buf[n++] = (uint8_t)KECU_SOF1;
    buf[n++] = (uint8_t)KECU_SOF2;
    buf[n++] = 0x12;
    buf[n++] = 0x34;
    n += kecu_proto_montar(&buf[n], KECU_CMD_HELLO, 18, NULL, 0);
    injetar_e_rodar(buf, n);
    CHECK(contar_tipo(KECU_RSP_INFO) == 0, "falso cabeçalho: o HELLO fica preso atrás dele por enquanto");
    /* ...até o tempo limite de quadro incompleto (100 ms) ressincronizar sozinho */
    sim_ms(150);
    drenar_saida();
    CHECK(contar_tipo(KECU_RSP_INFO) == 1 && g_quadros[0].seq == 18, "passado o tempo limite, o HELLO preso é atendido");

    /* o mesmo ruído, mas com mais quadros chegando logo depois: resolve na hora, sem esperar */
    n = 0;
    buf[n++] = (uint8_t)KECU_SOF1;
    buf[n++] = (uint8_t)KECU_SOF2;
    buf[n++] = 10;
    buf[n++] = 0x01;
    buf[n++] = 0x01;
    n += kecu_proto_montar(&buf[n], KECU_CMD_HELLO, 19, NULL, 0);
    n += kecu_proto_montar(&buf[n], KECU_CMD_HELLO, 20, NULL, 0);
    injetar_e_rodar(buf, n);
    CHECK(contar_tipo(KECU_RSP_INFO) == 2, "falso cabeçalho de 10 bytes seguido de 2 HELLO: os dois são atendidos (%d)",
          contar_tipo(KECU_RSP_INFO));

    /* e um byte A5 solto no fim não derruba o quadro seguinte */
    buf[0] = 0xA5;
    n = 1 + kecu_proto_montar(&buf[1], KECU_CMD_HELLO, 21, NULL, 0);
    injetar_e_rodar(buf, n);
    CHECK(contar_tipo(KECU_RSP_INFO) == 1, "A5 solto antes de um quadro");

    CHECK(g_quadros_invalidos == 0, "tudo que a ECU enviou era quadro válido");
    g_quadros_invalidos = 0;
}

static void teste_gravar_e_padroes(void) {
    secao("Protocolo: GRAVAR, PADROES e memória não volátil");
    reset_ecu(1);
    CHECK((g_kecu.erros & KECU_ERR_NVM) == 0, "memória vazia (primeira vez) não é erro");

    /* calibração nova: parâmetro, linha de mapa e eixo */
    uint8_t b[KECU_PARAMS_BYTES];
    kecu_params_t p = g_kecu.params;
    p.v[P_REV_LIMITE_RPM] = 7100;
    p.v[P_REQ_FUEL_US] = 9100;
    kecu_params_serializar(&p, b);
    CHECK(cmd_ack(KECU_CMD_ESCREVER_PARAMS, b, KECU_PARAMS_BYTES), "parâmetros novos");
    uint8_t linha[2 + KECU_N];
    linha[0] = MAPA_COMBUSTIVEL;
    linha[1] = 9;
    for (int i = 0; i < KECU_N; i++) {
        linha[2 + i] = (uint8_t)(50 + 3 * i);
    }
    CHECK(cmd_ack(KECU_CMD_ESCREVER_LINHA, linha, (int)sizeof linha), "linha nova");
    kecu_eixos_t e;
    kecu_eixos_padrao(&e);
    e.rpm[15] = 7800;
    uint8_t eb[4 * KECU_N];
    escrever_eixos_bytes(&e, eb);
    CHECK(cmd_ack(KECU_CMD_ESCREVER_EIXOS, eb, 4 * KECU_N), "eixo novo");

    ligar_motor(2000);
    CHECK(cmd_nack(KECU_CMD_GRAVAR, NULL, 0, KECU_NACK_MOTOR_GIRANDO) && host_nvm_gravacoes == 0,
          "girando: GRAVAR recusado (NACK 4) e nada é escrito");
    CHECK(cmd_nack(KECU_CMD_PADROES, NULL, 0, KECU_NACK_MOTOR_GIRANDO) && g_kecu.params.v[P_REV_LIMITE_RPM] == 7100,
          "girando: PADROES recusado (NACK 4)");
    parar_motor();
    CHECK(cmd_ack(KECU_CMD_GRAVAR, NULL, 0) && host_nvm_gravacoes == 1, "parado: GRAVAR aceito e escreve uma vez");

    /* formato do bloco: "KECU", versão 1, tamanho da carga, CRC-16 no fim */
    uint8_t blk[889];
    CHECK(hal_nvm_ler(blk, (int)sizeof blk) == 0 && memcmp(blk, "KECU", 4) == 0 && blk[4] == 1
              && (blk[6] | (blk[7] << 8)) == 879,
          "bloco gravado: cabeçalho KECU, versão 1, carga de 879 bytes");
    CHECK((uint16_t)(blk[887] | (blk[888] << 8)) == kecu_crc16(blk, 887), "bloco gravado: CRC confere");

    /* desligar e ligar: kecu_init de novo, sem apagar a memória */
    kecu_init();
    sim_ms(30);
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 7100 && g_kecu.params.v[P_REQ_FUEL_US] == 9100,
          "religou: parâmetros voltaram");
    CHECK(g_kecu.mapas.m[MAPA_COMBUSTIVEL][9][5] == 65 && g_kecu.eixos.rpm[15] == 7800,
          "religou: linha de mapa e eixo voltaram");
    CHECK((g_kecu.erros & KECU_ERR_NVM) == 0, "religou sem erro de memória");

    /* PADROES só mexe na RAM: o VE padrão em [9][5] é 40 + (9*30)/15 + 19 = 77 */
    CHECK(cmd_ack(KECU_CMD_PADROES, NULL, 0), "PADROES aceito");
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500 && g_kecu.mapas.m[MAPA_COMBUSTIVEL][9][5] == 77
              && g_kecu.eixos.rpm[15] == 7500,
          "RAM voltou ao padrão de fábrica (limite %u, VE %u)", g_kecu.params.v[P_REV_LIMITE_RPM],
          g_kecu.mapas.m[MAPA_COMBUSTIVEL][9][5]);
    kecu_init();
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 7100, "sem GRAVAR, ao religar volta o que estava na memória");

    /* byte corrompido: padrões + aviso; gravar de novo conserta */
    host_nvm_corromper(100);
    kecu_init();
    sim_ms(30);
    CHECK((g_kecu.erros & KECU_ERR_NVM) != 0, "bloco corrompido sinaliza ERR_NVM");
    CHECK(g_kecu.params.v[P_REV_LIMITE_RPM] == 6500, "e a ECU parte com os padrões");
    CHECK(cmd_ack(KECU_CMD_GRAVAR, NULL, 0), "GRAVAR aceito");
    CHECK((g_kecu.erros & KECU_ERR_NVM) == 0, "e o aviso some");
    sim_ms(30);
    CHECK((g_kecu.erros & KECU_ERR_NVM) == 0, "e continua sem aviso");
    kecu_init();
    CHECK((g_kecu.erros & KECU_ERR_NVM) == 0, "religa sem aviso depois de regravar");

    /* CRC certo mas conteúdo inválido (parâmetro fora da faixa) também é rejeitado */
    CHECK(hal_nvm_ler(blk, (int)sizeof blk) == 0, "lê a memória crua");
    blk[8] = 100; /* REV_LIMITE_RPM = 100 (o mínimo é 1500) */
    blk[9] = 0;
    const uint16_t crc = kecu_crc16(blk, 887);
    blk[887] = (uint8_t)(crc & 0xFF);
    blk[888] = (uint8_t)(crc >> 8);
    CHECK(hal_nvm_gravar(blk, (int)sizeof blk) == 0, "regrava com conteúdo inválido");
    kecu_init();
    CHECK((g_kecu.erros & KECU_ERR_NVM) != 0 && g_kecu.params.v[P_REV_LIMITE_RPM] == 6500,
          "conteúdo fora da faixa é rejeitado mesmo com CRC certo");

    /* falhas de gravação */
    host_nvm_falhar = 1;
    CHECK(cmd_nack(KECU_CMD_GRAVAR, NULL, 0, KECU_NACK_MEMORIA) && (g_kecu.erros & KECU_ERR_NVM) != 0,
          "memória com defeito: NACK 5 e ERR_NVM");
    host_nvm_falhar = 0;
    host_nvm_nao_guarda = 1;
    CHECK(cmd_nack(KECU_CMD_GRAVAR, NULL, 0, KECU_NACK_MEMORIA),
          "diz que gravou mas não guarda: a conferência pega (NACK 5)");
    host_nvm_nao_guarda = 0;
    CHECK(cmd_ack(KECU_CMD_GRAVAR, NULL, 0) && (g_kecu.erros & KECU_ERR_NVM) == 0,
          "memória boa de novo: aceita e limpa o aviso");
}

/* ================================================================== testes: relógio, serial travada, entrada aleatória */

static void teste_relogio_32bits(void) {
    secao("Relógio de 32 bits dando a volta");
    /* o contador de microssegundos dá a volta 300 ms depois de o motor começar a girar */
    reset_ecu_em((1ull << 32) - 330000ull, 1);
    crank_rpm(3000);
    int giro_ok = 1;
    int sync_ok = 1;
    double rpm_min = 1e9;
    double rpm_max = 0.0;
    for (int i = 0; i < 70; i++) {
        sim_ms(10);
        if (i >= 12) {
            giro_ok &= g_kecu.motor_girando == 1;
            sync_ok &= g_kecu.sync == 1;
            if ((double)g_kecu.rpm < rpm_min) {
                rpm_min = (double)g_kecu.rpm;
            }
            if ((double)g_kecu.rpm > rpm_max) {
                rpm_max = (double)g_kecu.rpm;
            }
        }
    }
    CHECK((host_tempo_us() >> 32) == 1, "o relógio de 32 bits deu a volta durante o teste");
    CHECK(giro_ok, "motor nunca foi dado como parado na volta do relógio");
    CHECK(sync_ok && g_kecu.perdas_sync == 0, "sem perda de sincronia (%u)", g_kecu.perdas_sync);
    CHECK(rpm_min > 2940.0 && rpm_max < 3060.0, "rotação contínua: %.0f a %.0f rpm", rpm_min, rpm_max);

    /* o contador de milissegundos (usado nas durações) dá a volta no meio de um enriquecimento de aceleração */
    reset_ecu_em(4294967296000ull - 1000000ull, 1);
    host_definir_tps(10.0f);
    sim_ms(800);
    host_definir_tps(60.0f);
    sim_ms(20);
    CHECK(g_kecu.ae_pct > 25.0f, "AE disparado pouco antes da volta (%.1f%%)", (double)g_kecu.ae_pct);
    sim_ms(100);
    CHECK(g_kecu.ae_pct > 17.0f && g_kecu.ae_pct < 25.0f, "ainda decaindo (%.1f%%)", (double)g_kecu.ae_pct);
    sim_ms(100);
    CHECK(g_kecu.ae_pct > 10.0f && g_kecu.ae_pct < 17.0f, "depois da volta do relógio continua decaindo (%.1f%%)",
          (double)g_kecu.ae_pct);
    sim_ms(400);
    CHECK_PERTO(g_kecu.ae_pct, 0.0, 0.01, "e termina no tempo certo");
}

static void teste_fila_de_saida(void) {
    secao("Serial travada: o controle do motor não pode parar");
    reset_ecu(1);
    ligar_motor(3000);
    host_serial_limpar();
    host_serial_limite = 0; /* o PC parou de ler */
    uint8_t f[16];
    const uint8_t s5[] = {5, 0};
    g_seq++;
    const uint8_t seq_stream = g_seq;
    uint16_t tam = kecu_proto_montar(f, KECU_CMD_STREAM, seq_stream, s5, 2);
    host_serial_injetar(f, tam);
    kecu_loop();
    for (int i = 0; i < 80; i++) {
        g_seq++;
        tam = kecu_proto_montar(f, KECU_CMD_HELLO, g_seq, NULL, 0);
        host_serial_injetar(f, tam);
        kecu_loop();
    }
    const uint32_t c0 = g_kecu.ciclos_controle;
    sim_ms(500);
    kecu_proto_stats_t st;
    kecu_proto_stats(&st);
    CHECK(st.respostas_perdidas > 0, "respostas perdidas contadas (%u)", st.respostas_perdidas);
    CHECK(st.telemetrias_perdidas > 50, "telemetrias descartadas em vez de travar (%u)", st.telemetrias_perdidas);
    const uint32_t ciclos = g_kecu.ciclos_controle - c0;
    CHECK(ciclos >= 97 && ciclos <= 103, "o laço de controle continuou a 200 Hz: %u ciclos em 500 ms", ciclos);
    CHECK(g_kecu.sync == 1 && fabs((double)g_kecu.rpm - 3000.0) < 30.0, "e a leitura de rotação segue normal");

    host_serial_limite = -1; /* o PC voltou */
    for (int i = 0; i < 6; i++) {
        kecu_loop();
    }
    drenar_saida();
    CHECK(g_quadros_invalidos == 0, "só quadros inteiros e válidos foram enviados (inválidos: %d)", g_quadros_invalidos);
    CHECK(g_quadros_n > 10 && g_quadros[0].tipo == KECU_RSP_ACK && g_quadros[0].seq == seq_stream,
          "a fila preserva a ordem: o primeiro é o ACK do STREAM");
    sim_ms(100);
    drenar_saida();
    CHECK(contar_tipo(KECU_RSP_TELEMETRIA) >= 15, "a telemetria volta quando o PC volta (%d quadros em 100 ms)",
          contar_tipo(KECU_RSP_TELEMETRIA));
    g_quadros_invalidos = 0;
}

static uint32_t g_rng = 2463534242u;

static uint32_t rnd(void) {
    g_rng ^= g_rng << 13;
    g_rng ^= g_rng >> 17;
    g_rng ^= g_rng << 5;
    return g_rng;
}

static unsigned rnd_faixa(unsigned minimo, unsigned maximo) {
    return minimo + rnd() % (maximo - minimo + 1u);
}

/* Carga de um comando: metade das vezes com formato válido, metade lixo do tamanho certo ou errado. */
static int carga_aleatoria(uint8_t tipo, uint8_t *c) {
    int n = 0;
    switch (tipo) {
    case KECU_CMD_ESCREVER_PARAMS:
        if (rnd() % 2 == 0) {
            kecu_params_t p;
            for (int i = 0; i < P_COUNT; i++) {
                p.v[i] = (uint16_t)rnd_faixa(kecu_param_tab[i].min, kecu_param_tab[i].max);
            }
            kecu_params_serializar(&p, c);
            return KECU_PARAMS_BYTES;
        }
        n = rnd() % 3 == 0 ? KECU_PARAMS_BYTES : (int)rnd_faixa(0, 80);
        break;
    case KECU_CMD_ESCREVER_EIXOS:
        if (rnd() % 2 == 0) {
            kecu_eixos_t e;
            unsigned v = rnd_faixa(0, 600);
            for (int i = 0; i < KECU_N; i++) {
                v += rnd_faixa(1, 900);
                e.rpm[i] = (uint16_t)v;
            }
            v = rnd_faixa(0, 30);
            for (int i = 0; i < KECU_N; i++) {
                v += rnd_faixa(1, 20);
                e.carga[i] = (uint16_t)v;
            }
            escrever_eixos_bytes(&e, c);
            return 4 * KECU_N;
        }
        n = rnd() % 3 == 0 ? 4 * KECU_N : (int)rnd_faixa(0, 80);
        break;
    case KECU_CMD_ESCREVER_LINHA:
    case KECU_CMD_LER_LINHA:
        n = tipo == KECU_CMD_LER_LINHA ? 2 : 2 + KECU_N;
        if (rnd() % 8 == 0) {
            n = (int)rnd_faixa(0, 30);
        }
        for (int i = 0; i < n; i++) {
            c[i] = (uint8_t)rnd();
        }
        if (n >= 2) {
            c[0] = (uint8_t)rnd_faixa(0, 3);
            c[1] = (uint8_t)rnd_faixa(0, 16);
        }
        return n;
    case KECU_CMD_STREAM:
        n = rnd() % 8 == 0 ? (int)rnd_faixa(0, 4) : 2;
        {
            const unsigned ms = rnd_faixa(0, 1100);
            c[0] = (uint8_t)(ms & 0xFFu);
            c[1] = (uint8_t)(ms >> 8);
            for (int i = 2; i < n; i++) {
                c[i] = (uint8_t)rnd();
            }
            if (n == 1) {
                c[0] = (uint8_t)rnd();
            }
        }
        return n;
    default:
        n = rnd() % 4 == 0 ? (int)rnd_faixa(0, KECU_PAYLOAD_MAX) : (int)rnd_faixa(0, 3);
        break;
    }
    for (int i = 0; i < n; i++) {
        c[i] = (uint8_t)rnd();
    }
    return n;
}

static int estado_sano(void) {
    const kecu_t *k = &g_kecu;
    return isfinite((double)k->rpm) && isfinite((double)k->ect_c) && isfinite((double)k->iat_c)
           && isfinite((double)k->map_kpa) && isfinite((double)k->tps_pct) && isfinite((double)k->vbat_v)
           && isfinite((double)k->oleo_bar) && isfinite((double)k->lambda) && isfinite((double)k->avanco_graus)
           && isfinite((double)k->ae_pct) && isfinite((double)k->iac_pct) && isfinite((double)k->pos_rpm)
           && isfinite((double)k->pos_carga) && k->iac_pct >= 0.0f && k->iac_pct <= 100.0f && k->ae_pct >= 0.0f
           && k->pos_rpm >= 0.0f && k->pos_rpm <= 15.0f && k->pos_carga >= 0.0f && k->pos_carga <= 15.0f;
}

static void teste_entrada_aleatoria(void) {
    secao("Entrada aleatória: nada pode travar nem corromper o estado");
    reset_ecu(1);
    g_quadros_invalidos = 0;

    /* A) bytes aleatórios, com SOF salpicado para exercitar o decodificador */
    for (int i = 0; i < 3000; i++) {
        uint8_t b[100];
        const int n = (int)rnd_faixa(1, 100);
        for (int k = 0; k < n; k++) {
            b[k] = (uint8_t)rnd();
        }
        if (rnd() % 3 == 0 && n >= 2) {
            b[0] = (uint8_t)KECU_SOF1;
            b[1] = (uint8_t)KECU_SOF2;
        }
        host_serial_injetar(b, n);
        kecu_loop();
        if (i % 25 == 0) {
            drenar_saida();
        }
    }
    drenar_saida();
    sim_ms(300); /* o tempo limite de quadro incompleto limpa o que sobrou */
    drenar_saida();
    quadro_t r;
    CHECK(comando(KECU_CMD_HELLO, NULL, 0, &r) && r.tipo == KECU_RSP_INFO, "depois de 3000 blocos de lixo, HELLO ainda responde");

    /* B) quadros VÁLIDOS (CRC certo) de qualquer tipo, com cargas aleatórias */
    static const uint8_t tipos[] = {KECU_CMD_HELLO,        KECU_CMD_STREAM,        KECU_CMD_LER_PARAMS,
                                    KECU_CMD_ESCREVER_PARAMS, KECU_CMD_LER_LINHA,  KECU_CMD_ESCREVER_LINHA,
                                    KECU_CMD_LER_EIXOS,    KECU_CMD_ESCREVER_EIXOS, KECU_CMD_GRAVAR,
                                    KECU_CMD_PADROES,      KECU_CMD_ARMAR,         KECU_CMD_CHAVES,
                                    0x00,                  0x7F,                   0xFF};
    uint8_t carga[KECU_PAYLOAD_MAX];
    uint8_t quadro[KECU_PAYLOAD_MAX + 7];
    for (int i = 0; i < 6000; i++) {
        const uint8_t tipo = tipos[rnd() % (sizeof tipos / sizeof tipos[0])];
        const int n = carga_aleatoria(tipo, carga);
        const uint16_t tam = kecu_proto_montar(quadro, tipo, (uint8_t)rnd(), carga, (uint8_t)n);
        host_serial_injetar(quadro, tam);
        kecu_loop();
        if (i % 16 == 0) {
            drenar_saida();
        }
    }
    drenar_saida();
    CHECK(g_quadros_invalidos == 0, "tudo que a ECU respondeu era quadro válido (inválidos: %d)", g_quadros_invalidos);

    int faixa_ok = 1;
    for (int i = 0; i < P_COUNT; i++) {
        if (g_kecu.params.v[i] < kecu_param_tab[i].min || g_kecu.params.v[i] > kecu_param_tab[i].max) {
            faixa_ok = 0;
        }
    }
    CHECK(faixa_ok, "todos os parâmetros continuam dentro das faixas");
    CHECK(kecu_eixos_validos(&g_kecu.eixos), "eixos continuam estritamente crescentes");
    CHECK(g_kecu.armado == (KECU_ENABLE_OUTPUTS ? g_kecu.armado : 0), "sem saídas habilitadas, nunca fica armada");

    /* C) o motor roda normalmente com a configuração aleatória que sobrou (roda, cilindros e mapas estranhos) */
    host_definir_sensores(80.0f, 30.0f, 70.0f, 15.0f, 13.5f, 3.0f, 1.0f);
    crank_rpm(3000);
    int sano = 1;
    for (int i = 0; i < 60; i++) {
        sim_ms(10);
        sano &= estado_sano();
    }
    CHECK(sano, "valores finitos e dentro dos limites com a configuração aleatória");
    CHECK(comando(KECU_CMD_HELLO, NULL, 0, &r) && r.tipo == KECU_RSP_INFO, "e a comunicação segue de pé");
    g_quadros_invalidos = 0;
}

static void teste_ciclo_aleatorio(void) {
    secao("Ciclo de condução aleatório: 30 s de rotação, pedal, MAP, bateria e chaves variando");
    reset_ecu(1);
    param(P_HAB_TWO_STEP, 1);
    param(P_HAB_LAUNCH, 1);
#if KECU_ENABLE_OUTPUTS
    armar(1);
#endif
    double rpm = 900.0;
    double tps = 0.0;
    int two_step = 0;
    int launch = 0;
    crank_rpm(rpm);
    sim_ms(500);
    int ruins = 0;
    unsigned pw_max = 0;
    int cortes = 0;
    int aes = 0;
    double alvo_rpm = rpm;
    for (int i = 0; i < 1500; i++) {
        /* a cada 1,5 s escolhe um destino: de cada 3, um é "pé embaixo" (6400 a 7400 rpm, passa do limitador);
         * a rotação vai até lá em rampa de 20 a 100 rpm por passo (de 1000 a 5000 rpm/s) */
        if (i % 75 == 0) {
            alvo_rpm = ((i / 75) % 3 == 2) ? (double)rnd_faixa(6400, 7400) : (double)rnd_faixa(800, 6000);
        }
        const double passo = (double)rnd_faixa(20, 100);
        const double falta = alvo_rpm - rpm;
        rpm += falta > 0.0 ? (falta < passo ? falta : passo) : (-falta < passo ? falta : -passo);
        if (rnd() % 10 == 0) {
            tps = (double)rnd_faixa(0, 100);
        } else {
            tps += ((double)(int)rnd_faixa(0, 200) - 100.0) / 100.0;
            if (tps < 0.0) {
                tps = 0.0;
            }
            if (tps > 100.0) {
                tps = 100.0;
            }
        }
        if (rnd() % 40 == 0) {
            two_step = !two_step;
        }
        if (rnd() % 60 == 0) {
            launch = !launch;
        }
        host_definir_sensores(90.0f, 30.0f, (float)rnd_faixa(25, 100), (float)tps, (float)rnd_faixa(110, 145) / 10.0f,
                              3.0f, 1.0f);
        host_definir_entrada(KECU_IN_TWO_STEP, two_step);
        host_definir_entrada(KECU_IN_LAUNCH, launch);
        crank_rpm(rpm);
        sim_ms(20);
        if (!estado_sano() || g_kecu.pw_us > 60000) {
            ruins++;
        }
        if (g_kecu.pw_us > pw_max) {
            pw_max = g_kecu.pw_us;
        }
        cortes += g_kecu.corte;
        aes += g_kecu.ae_pct > 0.5f;
        host_pulsos_limpar();
    }
    CHECK(ruins == 0, "%d passos com valor não finito ou fora dos limites (pulso máximo %u us)", ruins, pw_max);
    CHECK(g_kecu.perdas_sync == 0, "o decodificador nunca perdeu a sincronia (%u perdas)", g_kecu.perdas_sync);
    CHECK(cortes > 0 && aes > 0, "o ciclo exercitou corte (%d passos) e enriquecimento (%d passos)", cortes, aes);
}

int main(void) {
    printf("Testes do núcleo do firmware KadettECU\n");
    printf("saídas %s, %d bobina(s)\n\n", KECU_ENABLE_OUTPUTS ? "HABILITADAS" : "desabilitadas (modo sombra)", KECU_BOBINAS);
    teste_crc_e_quadros();
    teste_parametros();
    teste_tabelas();
    teste_sensores();
    teste_rotacao();
    teste_limitador();
    teste_tipos_de_corte();
    teste_two_step();
    teste_launch();
    teste_enriquecimento();
    teste_marcha_lenta();
    teste_pulso_e_avanco();
#if KECU_ENABLE_OUTPUTS
    teste_planejador();
#else
    teste_modo_sombra();
#endif
    teste_protocolo_comandos();
    teste_protocolo_dados();
    teste_protocolo_framing();
    teste_gravar_e_padroes();
    teste_relogio_32bits();
    teste_fila_de_saida();
    teste_entrada_aleatoria();
    teste_ciclo_aleatorio();
    printf("\n%d verificações OK, %d falhas\n", g_ok, g_falhas);
    return g_falhas == 0 ? 0 : 1;
}
