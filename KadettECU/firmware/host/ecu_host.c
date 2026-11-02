/*
 * ecu_host - "ECU virtual" para PC: o MESMO núcleo do firmware, falando o protocolo por
 * stdin/stdout. Gera os dentes da roda fônica na rotação pedida e mantém os sensores nos
 * valores informados. Serve para testar o aplicativo Java contra o firmware real, sem placa.
 *
 * Uso:
 *   ecu_host [--rpm 3000] [--vel 80] [--tps 25] [--ect 85] [--iat 30] [--map 55]
 *            [--vbat 13.8] [--oleo 3.2] [--lambda 0.95] [--nvm arquivo.bin] [--ctl arquivo.txt]
 *
 * --ctl: arquivo de texto com linhas "chave=valor" (rpm, vel, tps, ect, iat, map, vbat, oleo,
 * lambda, two_step, launch) que é relido sempre que muda; assim dá para mexer nas entradas
 * com o programa rodando (por exemplo, subir o giro para ver o limitador agir).
 */
#define _POSIX_C_SOURCE 200809L

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include "hal_host.h"

#ifdef __APPLE__
#define MTIME_DE(st) ((st).st_mtimespec)
#else
#define MTIME_DE(st) ((st).st_mtim)
#endif

typedef struct {
    double rpm;
    double vel;
    float ect;
    float iat;
    float map;
    float tps;
    float vbat;
    float oleo;
    float lambda;
    int two_step;
    int launch;
} entradas_t;

static int aplicar_chave(entradas_t *e, const char *chave, double v) {
    if (strcmp(chave, "rpm") == 0) {
        e->rpm = v;
    } else if (strcmp(chave, "vel") == 0) {
        e->vel = v;
    } else if (strcmp(chave, "ect") == 0) {
        e->ect = (float)v;
    } else if (strcmp(chave, "iat") == 0) {
        e->iat = (float)v;
    } else if (strcmp(chave, "map") == 0) {
        e->map = (float)v;
    } else if (strcmp(chave, "tps") == 0) {
        e->tps = (float)v;
    } else if (strcmp(chave, "vbat") == 0) {
        e->vbat = (float)v;
    } else if (strcmp(chave, "oleo") == 0) {
        e->oleo = (float)v;
    } else if (strcmp(chave, "lambda") == 0) {
        e->lambda = (float)v;
    } else if (strcmp(chave, "two_step") == 0) {
        e->two_step = v != 0.0;
    } else if (strcmp(chave, "launch") == 0) {
        e->launch = v != 0.0;
    } else {
        return 0;
    }
    return 1;
}

static void ler_ctl(const char *caminho, entradas_t *e) {
    FILE *f = fopen(caminho, "r");
    if (f == NULL) {
        return;
    }
    char texto[512];
    const size_t n = fread(texto, 1, sizeof texto - 1, f);
    fclose(f);
    texto[n] = '\0';
    for (char *tok = strtok(texto, " \t\r\n,;"); tok != NULL; tok = strtok(NULL, " \t\r\n,;")) {
        char *igual = strchr(tok, '=');
        if (igual != NULL) {
            *igual = '\0';
            (void)aplicar_chave(e, tok, atof(igual + 1));
        }
    }
}

static void aplicar_entradas(const entradas_t *e) {
    host_definir_sensores(e->ect, e->iat, e->map, e->tps, e->vbat, e->oleo, e->lambda);
    host_definir_entrada(KECU_IN_TWO_STEP, e->two_step);
    host_definir_entrada(KECU_IN_LAUNCH, e->launch);
}

static const char *arg_txt(int argc, char **argv, const char *nome) {
    for (int i = 1; i + 1 < argc; i++) {
        if (strcmp(argv[i], nome) == 0) {
            return argv[i + 1];
        }
    }
    return NULL;
}

int main(int argc, char **argv) {
    entradas_t e = {0, 0, 85.0f, 30.0f, 100.0f, 0.0f, 13.8f, 0.0f, 1.0f, 0, 0};
    static const char *const chaves[] = {"rpm", "vel", "ect", "iat", "map", "tps", "vbat", "oleo", "lambda"};
    for (size_t i = 0; i < sizeof chaves / sizeof chaves[0]; i++) {
        char opcao[32];
        snprintf(opcao, sizeof opcao, "--%s", chaves[i]);
        const char *v = arg_txt(argc, argv, opcao);
        if (v != NULL) {
            (void)aplicar_chave(&e, chaves[i], atof(v));
        }
    }
    const char *ctl = arg_txt(argc, argv, "--ctl");
    if (ctl != NULL) {
        ler_ctl(ctl, &e);
    }

    host_serial_stdio = 1;
    host_tempo_real = 1;
    host_nvm_arquivo = arg_txt(argc, argv, "--nvm");
    host_nvm_carregar_arquivo();
    aplicar_entradas(&e);
    kecu_init();
    fprintf(stderr, "ecu_host: firmware %d.%d, saidas %s, pronto\n", KECU_FW_MAIOR, KECU_FW_MENOR,
            KECU_ENABLE_OUTPUTS ? "HABILITADAS" : "em modo sombra");

    double prox_dente = 0.0; /* instantes em microssegundos COM fração: senão o período arredondado erra a rotação em até 0,6 % */
    double prox_vss = 0.0;
    uint64_t prox_ctl = 0;
    struct timespec mtime_ctl = {0, 0};
    int slot = 0;

    while (!host_stdin_fim) {
        const uint64_t agora = host_tempo_us();

        if (ctl != NULL && agora >= prox_ctl) {
            prox_ctl = agora + 50000ull;
            struct stat st;
            /* compara segundos E nanossegundos: duas alterações no mesmo segundo também são percebidas */
            if (stat(ctl, &st) == 0 && (MTIME_DE(st).tv_sec != mtime_ctl.tv_sec || MTIME_DE(st).tv_nsec != mtime_ctl.tv_nsec)) {
                mtime_ctl = MTIME_DE(st);
                ler_ctl(ctl, &e);
                aplicar_entradas(&e);
                /*
                 * NÃO reinicie prox_dente nem prox_vss aqui. O relógio dos dentes continua de onde estava e só o período muda
                 * (slot_us é recalculado a cada volta, logo abaixo). Reiniciar o relógio punha o primeiro dente novo em
                 * "agora + um período": o intervalo entre o último dente antigo e ele saía até 70 % maior que o normal, o
                 * filtro de rotação do firmware via um tranco de até 15 % e o limitador soltava o corte (ou cortava) fora da
                 * hora. Num motor de verdade não existe esse tranco; era um defeito só deste simulador de bancada, e fazia
                 * o TesteIntegracaoFirmware falhar de vez em quando. Quando a rotação vai a zero e volta, o ramo "else" abaixo
                 * já zera o relógio.
                 */
            }
        }

        const kecu_params_t *p = &g_kecu.params;
        const int dentes = (int)p->v[P_DENTES_RODA];
        const int faltando = p->v[P_MODO_SINAL] == 1u ? 0 : (int)p->v[P_DENTES_FALTANDO];

        if (e.rpm > 0.0) {
            const double slot_us = 60e6 / (e.rpm * dentes);
            if (prox_dente == 0.0 || (double)agora > prox_dente + 100000.0) {
                prox_dente = (double)agora + slot_us;
            }
            while (prox_dente <= (double)agora) {
                if (slot < dentes - faltando) {
                    kecu_engine_on_tooth((uint32_t)((uint64_t)(prox_dente + 0.5) & 0xFFFFFFFFull));
                }
                slot = (slot + 1) % dentes;
                prox_dente += slot_us;
            }
        } else {
            prox_dente = 0.0;
        }
        if (e.vel > 0.0) {
            const double periodo_us = 3.6e9 / (e.vel * p->v[P_VSS_PULSOS_KM]);
            if (prox_vss == 0.0 || (double)agora > prox_vss + 100000.0) {
                prox_vss = (double)agora + periodo_us;
            }
            while (prox_vss <= (double)agora) {
                kecu_engine_on_vss((uint32_t)((uint64_t)(prox_vss + 0.5) & 0xFFFFFFFFull));
                prox_vss += periodo_us;
            }
        } else {
            prox_vss = 0.0;
        }

        kecu_loop();

        const struct timespec descanso = {0, 100000L}; /* 100 us */
        nanosleep(&descanso, NULL);
    }
    return 0;
}
