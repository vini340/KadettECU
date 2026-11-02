/* mock_avr.cpp - implementação do simulador do ATmega2560 / Arduino AVR. Veja mock_avr.h. */
#include "mock_avr.h"

#include <limits.h>
#include <string.h>

#include <vector>

#include "Arduino.h"
#include "EEPROM.h"
#include <avr/wdt.h>

/* Definidas em hal_avr.cpp com ISR(TIMER1_OVF_vect) e ISR(TIMER1_COMPA_vect). */
extern "C" void mock_vect_timer1_ovf(void);
extern "C" void mock_vect_timer1_compa(void);

/* ------------------------------------------------------------------ estado público */

uint64_t mock_tempo_us;
uint32_t mock_lat_alarme_us;
int mock_alarme_perde_a_cada;
uint32_t mock_isr_custo_us;
int mock_isr_leitura_do_custo = 2;
uint32_t mock_tcnt1_custo_us;

int mock_alarmes_programados;
int mock_alarmes_no_passado;
int mock_alarmes_disparados;
uint64_t mock_alarme_disparo_us;
int mock_ovf_disparos;
int mock_alarme_margem_min_ticks = INT_MAX;

uint8_t mock_pino_modo[MOCK_PINOS];
uint8_t mock_pino_nivel[MOCK_PINOS];
uint8_t mock_nivel_solto;
int mock_pino_escritas[MOCK_PINOS];
int mock_pino_pinmodes[MOCK_PINOS];
uint8_t mock_entrada_nivel[MOCK_PINOS];
uint32_t mock_adc_mv[MOCK_PINOS];
uint32_t mock_avcc_mv = 5000;
int mock_adc_leituras;
mock_mudanca_t mock_mudancas[MOCK_MAX_MUDANCAS];
int mock_mudancas_n;
char mock_pino_historia[MOCK_PINOS][16];
int mock_porta_escritas;
int mock_ddra_escritas;
int mock_pullup_conflitos;

void (*mock_isr_pino[MOCK_PINOS])(void);
int mock_isr_modo[MOCK_PINOS];

int mock_secao_nivel;
int mock_secao_max;
int mock_secao_desequilibrio;
int mock_escritas_fora_de_secao;
int mock_alarme_fora_de_secao;

uint32_t mock_serial_baud;
#ifdef SERIAL_RX_BUFFER_SIZE
#define RX_PADRAO SERIAL_RX_BUFFER_SIZE
#else
#define RX_PADRAO 64
#endif
#ifdef SERIAL_TX_BUFFER_SIZE
#define TX_PADRAO SERIAL_TX_BUFFER_SIZE
#else
#define TX_PADRAO 64
#endif
size_t mock_serial_rx_tam = RX_PADRAO;
size_t mock_serial_tx_tam = TX_PADRAO;
int mock_serial_iniciada;
int mock_serial_tx_limite = -1;
int mock_serial_bloqueios;
uint64_t mock_serial_bloqueio_us;
int mock_serial_rx_perdidos;

int mock_eeprom_falhar;
int mock_eeprom_gravacoes;
uint32_t mock_eeprom_us_por_byte = 3300;

uint32_t mock_iac_duty;
int mock_iac_escritas;
int mock_iac_pino = -1;

int mock_wdt_ativo;
uint32_t mock_wdt_timeout_ms;
int mock_wdt_resets;
int mock_wdt_estouros;
uint64_t mock_wdt_max_intervalo_us;

MockReg8<MOCK_REG_SREG> mock_SREG;
MockReg8<MOCK_REG_TIFR1> mock_TIFR1;
MockReg8<MOCK_REG_TIMSK1> mock_TIMSK1;
MockReg8<MOCK_REG_TCCR1A> mock_TCCR1A;
MockReg8<MOCK_REG_TCCR1B> mock_TCCR1B;
MockReg8<MOCK_REG_TCCR2B> mock_TCCR2B;
MockReg8<MOCK_REG_PORTA> mock_PORTA;
MockReg8<MOCK_REG_DDRA> mock_DDRA;
MockReg8<MOCK_REG_MCUSR> mock_MCUSR;
MockReg16<MOCK_REG16_TCNT1> mock_TCNT1;
MockReg16<MOCK_REG16_OCR1A> mock_OCR1A;

HardwareSerial Serial;
EEPROMClass EEPROM;

/* ------------------------------------------------------------------ estado interno (o "chip") */

static bool s_I = true;            /* bit I do SREG */
static bool s_em_isr;
static bool s_isr_de_pino;         /* a interrupção em curso é a de um pino (modelo de custo do dente) */
static int s_leituras_na_isr;      /* leituras de TCNT1 desde que ela começou */
static bool s_custo_pendente;      /* o custo de mock_isr_custo_us cai no PRÓXIMO acesso (leitura de TCNT1 ou escrita de OCR1A) */
static uint8_t s_tifr1;
static uint8_t s_timsk1;
static uint8_t s_tccr1a = 1;       /* o Arduino deixa o Timer1 em PWM de fase corrigida de 8 bits (WGM10) ... */
static uint8_t s_tccr1b = 3;       /* ... com prescaler 64 */
static uint8_t s_tccr2b = 4;       /* Timer2 com prescaler 64 */
static uint8_t s_mcusr;
static uint16_t s_tcnt1;
static uint16_t s_ocr1a;
static uint32_t s_resto;           /* ciclos de CPU acumulados que ainda não fecharam um tick */
static uint8_t s_porta;
static uint8_t s_ddra;
static uint8_t s_nivel_a[8];       /* nível efetivo anterior de cada bit da PORTA */
static bool s_pendente_pino[MOCK_PINOS];
static int s_casamentos;           /* casamentos do comparador com a interrupção ligada (para mock_alarme_perde_a_cada) */
static int s_adc_ultimo_pino = -1;
static uint32_t s_adc_ultimo_raw;
static int s_wdt_estourou;
static uint64_t s_wdt_ultimo_us;

static uint8_t s_eeprom[4096];
static size_t s_eeprom_usado;

#define US_POR_BYTE 87 /* 115200 baud, 8N1: 10 bits por byte = 86,8 us */
static uint8_t s_rx[8192];
static size_t s_rx_n;
struct TxByte {
    uint8_t b;
    uint64_t saida_us; /* instante em que o último bit do byte sai pelo fio */
};
static std::vector<TxByte> s_tx;
static uint64_t s_tx_fim_us;

/* ------------------------------------------------------------------ tempo, Timer1 e interrupções */

uint32_t mock_timer1_divisor(void) {
    switch (s_tccr1b & 7) {
    case 1:
        return 1;
    case 2:
        return 8;
    case 3:
        return 64;
    case 4:
        return 256;
    case 5:
        return 1024;
    default:
        return 0; /* parado (ou clock externo, que não existe aqui) */
    }
}

int mock_timer1_modo_normal(void) {
    return (s_tccr1a & 0x03) == 0 && (s_tccr1b & 0x18) == 0;
}

uint16_t mock_timer1_contador(void) {
    return s_tcnt1;
}

int mock_timer1_comparador_ligado(void) {
    return (s_timsk1 & _BV(OCIE1A)) != 0;
}

static void atualizar_secao(void) {
    mock_secao_nivel = (!s_I && !s_em_isr) ? 1 : 0;
}

static void wdt_conferir(void) {
    if (!mock_wdt_ativo) {
        return;
    }
    const uint64_t intervalo = mock_tempo_us - s_wdt_ultimo_us;
    if (intervalo > mock_wdt_max_intervalo_us) {
        mock_wdt_max_intervalo_us = intervalo;
    }
    if (intervalo > (uint64_t)mock_wdt_timeout_ms * 1000u && !s_wdt_estourou) {
        s_wdt_estourou = 1;
        mock_wdt_estouros++;
    }
}

/* Avança o relógio do mundo e o contador do Timer1, levantando os avisos (TOV1, OCF1A). NÃO entrega interrupções. */
static void avancar(uint64_t t_novo) {
    if (t_novo <= mock_tempo_us) {
        return;
    }
    const uint64_t dt = t_novo - mock_tempo_us;
    mock_tempo_us = t_novo;
    const uint32_t div = mock_timer1_divisor();
    if (div != 0) {
        const uint64_t ciclos = dt * 16u + s_resto; /* 16 MHz: 16 ciclos por microssegundo */
        const uint64_t ticks = ciclos / div;
        s_resto = (uint32_t)(ciclos % div);
        if (ticks != 0) {
            const uint64_t pos = (uint64_t)s_tcnt1 + ticks;
            if (pos >= 65536u) {
                s_tifr1 |= (uint8_t)_BV(TOV1);
            }
            /* o comparador só casa quando o contador PASSA por OCR1A: com OCR1A == TCNT1 agora, só na próxima volta */
            const uint32_t espera = (uint32_t)(uint16_t)(s_ocr1a - s_tcnt1 - 1u) + 1u; /* ticks até casar: 1..65536 */
            if (ticks >= espera) {
                if ((s_timsk1 & _BV(OCIE1A)) != 0) {
                    s_casamentos++;
                    if (!(mock_alarme_perde_a_cada > 0 && s_casamentos % mock_alarme_perde_a_cada == 0)) {
                        s_tifr1 |= (uint8_t)_BV(OCF1A);
                    }
                } else {
                    s_tifr1 |= (uint8_t)_BV(OCF1A);
                }
            }
            s_tcnt1 = (uint16_t)pos;
        }
    }
    wdt_conferir();
}

/* Instante do próximo aviso do Timer1 (estouro ou casamento), ou UINT64_MAX se o contador está parado. */
static uint64_t proximo_evento_us(void) {
    const uint32_t div = mock_timer1_divisor();
    if (div == 0) {
        return UINT64_MAX;
    }
    uint64_t ticks = 65536u - (uint64_t)s_tcnt1;
    if ((s_timsk1 & _BV(OCIE1A)) != 0) {
        uint32_t d = (uint16_t)(s_ocr1a - s_tcnt1);
        if (d == 0) {
            d = 65536u;
        }
        if (d < ticks) {
            ticks = d;
        }
    }
    const uint64_t ciclos = ticks * div - s_resto;
    return mock_tempo_us + (ciclos + 15u) / 16u;
}

static void rodar(void (*fn)(void), bool de_pino) {
    s_em_isr = true;
    s_I = false; /* o AVR limpa o bit I ao entrar */
    atualizar_secao();
    s_isr_de_pino = de_pino;
    s_leituras_na_isr = 0;
    s_custo_pendente = false;
    fn();
    s_custo_pendente = false;
    s_isr_de_pino = false;
    s_em_isr = false;
    s_I = true; /* reti */
    atualizar_secao();
}

/* Entrega as interrupções pendentes e habilitadas, uma por vez, na ordem de prioridade do ATmega2560 (INT4/INT5 < Timer1). */
static void entregar(void) {
    for (int guarda = 0; guarda < 100000; guarda++) {
        if (!s_I || s_em_isr) {
            return;
        }
        bool achou = false;
        for (int p = 0; p < MOCK_PINOS && !achou; p++) {
            if (s_pendente_pino[p]) {
                s_pendente_pino[p] = false;
                if (mock_isr_pino[p] != NULL) {
                    rodar(mock_isr_pino[p], true);
                }
                achou = true;
            }
        }
        if (achou) {
            continue;
        }
        if ((s_tifr1 & _BV(OCF1A)) != 0 && (s_timsk1 & _BV(OCIE1A)) != 0) {
            s_tifr1 &= (uint8_t)~_BV(OCF1A);
            if (mock_lat_alarme_us != 0) {
                avancar(mock_tempo_us + mock_lat_alarme_us);
            }
            mock_alarmes_disparados++;
            mock_alarme_disparo_us = mock_tempo_us;
            rodar(mock_vect_timer1_compa, false);
            continue;
        }
        if ((s_tifr1 & _BV(TOV1)) != 0 && (s_timsk1 & _BV(TOIE1)) != 0) {
            s_tifr1 &= (uint8_t)~_BV(TOV1);
            mock_ovf_disparos++;
            rodar(mock_vect_timer1_ovf, false);
            continue;
        }
        return;
    }
}

void mock_tempo_para(uint64_t t_us) {
    avancar(t_us);
}

void mock_correr_ate(uint64_t alvo_us) {
    for (int guarda = 0; mock_tempo_us < alvo_us && guarda < 100000000; guarda++) {
        uint64_t prox = proximo_evento_us();
        if (prox > alvo_us) {
            prox = alvo_us;
        }
        avancar(prox);
        entregar();
    }
    entregar();
}

void mock_cli(void) {
    s_I = false;
    atualizar_secao();
}

void mock_sei(void) {
    if (s_em_isr) {
        mock_secao_desequilibrio++; /* sei() dentro de uma interrupção: o AVR passaria a aninhar interrupções */
    }
    s_I = true;
    atualizar_secao();
    entregar();
}

/* ------------------------------------------------------------------ pinos da PORTA */

static void historia(uint8_t pino, char c) {
    char *h = mock_pino_historia[pino];
    const size_t n = strlen(h);
    if (n < sizeof mock_pino_historia[0] - 1) {
        h[n] = c;
        h[n + 1] = 0;
    }
}

static void registrar_mudanca(uint8_t pino, uint8_t nivel) {
    if (mock_mudancas_n < MOCK_MAX_MUDANCAS) {
        mock_mudancas[mock_mudancas_n].t_us = mock_tempo_us;
        mock_mudancas[mock_mudancas_n].pino = pino;
        mock_mudancas[mock_mudancas_n].nivel = nivel;
        mock_mudancas_n++;
    }
}

static void recalcular_porta(uint8_t mudou_porta, uint8_t mudou_ddra) {
    for (int b = 0; b < 8; b++) {
        const uint8_t bit = (uint8_t)(1u << b);
        const uint8_t pino = (uint8_t)(22 + b);
        const uint8_t saida = (s_ddra & bit) != 0 ? 1 : 0;
        const uint8_t dado = (s_porta & bit) != 0 ? 1 : 0;
        if ((mudou_porta & bit) != 0) {
            historia(pino, dado ? 'h' : 'l');
        }
        if ((mudou_ddra & bit) != 0) {
            historia(pino, saida ? 'D' : 'd');
        }
        if (((mudou_porta | mudou_ddra) & bit) != 0 && !saida && dado && mock_nivel_solto == 0) {
            mock_pullup_conflitos++; /* pull-up interno ligado contra o pull-down externo do estágio de potência */
        }
        const uint8_t nivel = saida ? dado : mock_nivel_solto;
        if (nivel != s_nivel_a[b]) {
            s_nivel_a[b] = nivel;
            mock_pino_nivel[pino] = nivel;
            registrar_mudanca(pino, nivel);
        }
    }
}

/* ------------------------------------------------------------------ registradores */

uint8_t mock_reg_ler(int id) {
    switch (id) {
    case MOCK_REG_SREG:
        return s_I ? 0x80 : 0x00;
    case MOCK_REG_TIFR1:
        return s_tifr1;
    case MOCK_REG_TIMSK1:
        return s_timsk1;
    case MOCK_REG_TCCR1A:
        return s_tccr1a;
    case MOCK_REG_TCCR1B:
        return s_tccr1b;
    case MOCK_REG_TCCR2B:
        return s_tccr2b;
    case MOCK_REG_PORTA:
        return s_porta;
    case MOCK_REG_DDRA:
        return s_ddra;
    case MOCK_REG_MCUSR:
        return s_mcusr;
    default:
        return 0;
    }
}

void mock_reg_escrever(int id, uint8_t v) {
    switch (id) {
    case MOCK_REG_SREG: {
        const bool novo = (v & 0x80) != 0;
        if (novo && s_em_isr) {
            mock_secao_desequilibrio++; /* bit I religado dentro de uma interrupção */
        }
        s_I = novo;
        atualizar_secao();
        if (novo) {
            entregar();
        }
        break;
    }
    case MOCK_REG_TIFR1:
        s_tifr1 &= (uint8_t)~v; /* escrever 1 APAGA o aviso */
        break;
    case MOCK_REG_TIMSK1:
        if (((s_timsk1 ^ v) & _BV(OCIE1A)) != 0 && s_I && !s_em_isr) {
            mock_alarme_fora_de_secao++;
        }
        s_timsk1 = v;
        entregar(); /* ligar uma interrupção que já tem aviso pendente a dispara na hora */
        break;
    case MOCK_REG_TCCR1A:
        s_tccr1a = v;
        break;
    case MOCK_REG_TCCR1B: {
        const uint32_t antes = mock_timer1_divisor();
        s_tccr1b = v;
        if (antes == 0 && mock_timer1_divisor() != 0) {
            s_resto = 0; /* o contador acabou de partir */
        }
        break;
    }
    case MOCK_REG_TCCR2B:
        s_tccr2b = v;
        break;
    case MOCK_REG_PORTA: {
        mock_porta_escritas++;
        if (s_I && !s_em_isr && (s_ddra & 0x3F) != 0) {
            mock_escritas_fora_de_secao++;
        }
        const uint8_t mudou = (uint8_t)(s_porta ^ v);
        s_porta = v;
        recalcular_porta(mudou, 0);
        break;
    }
    case MOCK_REG_DDRA: {
        mock_ddra_escritas++;
        const uint8_t mudou = (uint8_t)(s_ddra ^ v);
        s_ddra = v;
        recalcular_porta(0, mudou);
        break;
    }
    case MOCK_REG_MCUSR:
        s_mcusr = v;
        break;
    default:
        break;
    }
}

/* O custo da interrupção de um pino (mock_isr_custo_us) é o tempo que o código gasta entre duas leituras do contador. Ele passa
 * no primeiro acesso depois da leitura anterior ao "mock_isr_leitura_do_custo"-ésimo: uma leitura de TCNT1 (que então já vê o
 * tempo novo) ou uma escrita em OCR1A (que então chega atrasada). Assim, sem a leitura fresca de hal_timer_armar, a escrita
 * cai no passado, como cairia no chip. */
static void consumir_custo_isr(void) {
    if (s_custo_pendente) {
        s_custo_pendente = false;
        avancar(mock_tempo_us + mock_isr_custo_us);
    }
}

uint16_t mock_reg16_ler(int id) {
    if (id == MOCK_REG16_TCNT1) {
        consumir_custo_isr();
        if (s_isr_de_pino && ++s_leituras_na_isr == mock_isr_leitura_do_custo - 1 && mock_isr_custo_us != 0) {
            s_custo_pendente = true; /* a 2ª leitura (padrão): onde o núcleo já terminou de decodificar o dente e pede pulsos */
        }
        const uint16_t valor = s_tcnt1;
        if (mock_tcnt1_custo_us != 0) {
            avancar(mock_tempo_us + mock_tcnt1_custo_us); /* o valor lido é o de ANTES: o contador pode dar a volta logo depois */
            entregar();
        }
        return valor;
    }
    return s_ocr1a;
}

void mock_reg16_escrever(int id, uint16_t v) {
    if (id == MOCK_REG16_TCNT1) {
        s_tcnt1 = v;
        return;
    }
    consumir_custo_isr();
    mock_alarmes_programados++;
    const int distancia = (int16_t)(uint16_t)(v - s_tcnt1); /* ticks à frente do contador (negativo = já passou) */
    if (distancia < mock_alarme_margem_min_ticks) {
        mock_alarme_margem_min_ticks = distancia;
    }
    if (distancia <= 1) {
        mock_alarmes_no_passado++;
    }
    if (s_I && !s_em_isr) {
        mock_alarme_fora_de_secao++;
    }
    s_ocr1a = v;
}

/* ------------------------------------------------------------------ API do Arduino */

void pinMode(uint8_t pino, uint8_t modo) {
    mock_pino_pinmodes[pino]++;
    mock_pino_modo[pino] = (uint8_t)(0x80 | modo);
}

void digitalWrite(uint8_t pino, uint8_t nivel) {
    mock_pino_escritas[pino]++;
    mock_pino_nivel[pino] = nivel ? 1 : 0;
}

int digitalRead(uint8_t pino) {
    return mock_entrada_nivel[pino] ? HIGH : LOW;
}

int analogRead(uint8_t pino) {
    mock_adc_leituras++;
    uint32_t raw = (mock_adc_mv[pino] * 1023u + mock_avcc_mv / 2u) / mock_avcc_mv;
    if (raw > 1023u) {
        raw = 1023u;
    }
    uint32_t resultado = raw;
    /* a 1ª conversão depois de trocar de canal ainda traz a carga do canal anterior (pior caso) */
    if (s_adc_ultimo_pino >= 0 && s_adc_ultimo_pino != (int)pino) {
        resultado = s_adc_ultimo_raw;
    }
    s_adc_ultimo_pino = (int)pino;
    s_adc_ultimo_raw = raw;
    return (int)resultado;
}

void analogWrite(uint8_t pino, int valor) {
    mock_iac_pino = pino;
    mock_iac_duty = (uint32_t)valor;
    mock_iac_escritas++;
}

void attachInterrupt(uint8_t interrupcao, void (*fn)(void), int modo) {
    mock_isr_pino[interrupcao] = fn;
    mock_isr_modo[interrupcao] = modo;
}

unsigned long millis(void) {
    return (unsigned long)(mock_tempo_us / 1000u);
}

int mock_pulso_pino(int pino) {
    if (pino < 0 || pino >= MOCK_PINOS || mock_isr_pino[pino] == NULL) {
        return 0;
    }
    s_pendente_pino[pino] = true;
    entregar();
    return 1;
}

/* ------------------------------------------------------------------ serial */

static size_t tx_pendentes(void) {
    size_t n = 0;
    for (size_t i = 0; i < s_tx.size(); i++) {
        if (s_tx[i].saida_us > mock_tempo_us) {
            n++;
        }
    }
    return n;
}

void HardwareSerial::begin(unsigned long baud) {
    mock_serial_baud = (uint32_t)baud;
    mock_serial_iniciada = 1;
}

int HardwareSerial::available(void) {
    return (int)s_rx_n;
}

int HardwareSerial::read(void) {
    if (s_rx_n == 0) {
        return -1;
    }
    const uint8_t c = s_rx[0];
    memmove(s_rx, s_rx + 1, s_rx_n - 1);
    s_rx_n--;
    return c;
}

int HardwareSerial::availableForWrite(void) {
    int livre = (int)mock_serial_tx_tam - (int)tx_pendentes();
    if (livre < 0) {
        livre = 0;
    }
    if (mock_serial_tx_limite >= 0 && livre > mock_serial_tx_limite) {
        livre = mock_serial_tx_limite;
    }
    return livre;
}

size_t HardwareSerial::write(const uint8_t *origem, size_t tamanho) {
    for (size_t i = 0; i < tamanho; i++) {
        if ((int)mock_serial_tx_tam - (int)tx_pendentes() <= 0) {
            /* buffer cheio: o write() verdadeiro ESPERA um byte sair pela linha. Isto trava o laço principal. */
            mock_serial_bloqueios++;
            uint64_t libera = UINT64_MAX;
            for (size_t k = 0; k < s_tx.size(); k++) {
                if (s_tx[k].saida_us > mock_tempo_us && s_tx[k].saida_us < libera) {
                    libera = s_tx[k].saida_us;
                }
            }
            mock_serial_bloqueio_us += libera - mock_tempo_us;
            mock_correr_ate(libera);
        }
        const uint64_t base = s_tx_fim_us > mock_tempo_us ? s_tx_fim_us : mock_tempo_us;
        s_tx_fim_us = base + US_POR_BYTE;
        TxByte x;
        x.b = origem[i];
        x.saida_us = s_tx_fim_us;
        s_tx.push_back(x);
    }
    return tamanho;
}

void mock_serial_injetar(const uint8_t *dados, int n) {
    for (int i = 0; i < n; i++) {
        if (s_rx_n < mock_serial_rx_tam && s_rx_n < sizeof s_rx) {
            s_rx[s_rx_n++] = dados[i];
        } else {
            mock_serial_rx_perdidos++;
        }
    }
}

int mock_serial_coletar(uint8_t *destino, int maximo) {
    int n = 0;
    size_t i = 0;
    while (i < s_tx.size() && n < maximo && s_tx[i].saida_us <= mock_tempo_us) {
        destino[n++] = s_tx[i].b;
        i++;
    }
    s_tx.erase(s_tx.begin(), s_tx.begin() + (long)i);
    return n;
}

/* ------------------------------------------------------------------ EEPROM */

uint8_t EEPROMClass::read(int endereco) {
    return (endereco >= 0 && endereco < (int)sizeof s_eeprom) ? s_eeprom[endereco] : 0xFF;
}

void EEPROMClass::update(int endereco, uint8_t valor) {
    if (endereco < 0 || endereco >= (int)sizeof s_eeprom || s_eeprom[endereco] == valor) {
        return;
    }
    if (!mock_eeprom_falhar) {
        s_eeprom[endereco] = valor;
        mock_eeprom_gravacoes++;
        if ((size_t)endereco + 1u > s_eeprom_usado) {
            s_eeprom_usado = (size_t)endereco + 1u;
        }
    }
    mock_correr_ate(mock_tempo_us + mock_eeprom_us_por_byte); /* a gravação é lenta; as interrupções seguem rodando */
}

uint16_t EEPROMClass::length(void) {
    return (uint16_t)sizeof s_eeprom;
}

void mock_eeprom_corromper(size_t posicao) {
    if (posicao < sizeof s_eeprom) {
        s_eeprom[posicao] ^= 0x5A;
    }
}

size_t mock_eeprom_usado(void) {
    return s_eeprom_usado;
}

/* ------------------------------------------------------------------ cão de guarda */

void mock_wdt_enable(uint8_t tempo) {
    static const uint32_t ms[] = {15, 30, 60, 120, 250, 500, 1000, 2000, 4000, 8000};
    mock_wdt_ativo = 1;
    mock_wdt_timeout_ms = tempo < 10 ? ms[tempo] : 8000;
    s_wdt_ultimo_us = mock_tempo_us;
    s_wdt_estourou = 0;
}

void mock_wdt_reset(void) {
    mock_wdt_resets++;
    if (mock_wdt_ativo) {
        wdt_conferir();
        s_wdt_ultimo_us = mock_tempo_us;
        s_wdt_estourou = 0;
    }
}

void mock_wdt_disable(void) {
    mock_wdt_ativo = 0;
}

/* ------------------------------------------------------------------ reinício */

void mock_reiniciar_placa(void) {
    /* os pinos da PORTA que eram saída voltam a ser entrada: o resistor externo os leva ao nível "solto" */
    for (int b = 0; b < 8; b++) {
        if ((s_ddra & (1u << b)) != 0 && s_nivel_a[b] != mock_nivel_solto) {
            registrar_mudanca((uint8_t)(22 + b), mock_nivel_solto);
        }
        s_nivel_a[b] = mock_nivel_solto;
    }
    s_I = true; /* o init() do Arduino liga as interrupções antes do setup() */
    s_em_isr = false;
    s_isr_de_pino = false;
    s_leituras_na_isr = 0;
    s_custo_pendente = false;
    s_tifr1 = 0;
    s_timsk1 = 0;
    s_tccr1a = 1;
    s_tccr1b = 3;
    s_tccr2b = 4;
    s_mcusr = 0;
    s_tcnt1 = 0;
    s_ocr1a = 0;
    s_resto = 0;
    s_porta = 0;
    s_ddra = 0;
    memset(s_pendente_pino, 0, sizeof s_pendente_pino);
    s_casamentos = 0;
    s_adc_ultimo_pino = -1;
    s_adc_ultimo_raw = 0;
    s_wdt_estourou = 0;
    s_wdt_ultimo_us = mock_tempo_us;
    atualizar_secao();

    memset(mock_pino_modo, 0, sizeof mock_pino_modo);
    memset(mock_pino_nivel, mock_nivel_solto, sizeof mock_pino_nivel);
    memset(mock_pino_escritas, 0, sizeof mock_pino_escritas);
    memset(mock_pino_pinmodes, 0, sizeof mock_pino_pinmodes);
    memset(mock_pino_historia, 0, sizeof mock_pino_historia);
    memset(mock_isr_pino, 0, sizeof mock_isr_pino);
    memset(mock_isr_modo, 0, sizeof mock_isr_modo);
    mock_adc_leituras = 0;
    mock_porta_escritas = 0;
    mock_ddra_escritas = 0;
    mock_pullup_conflitos = 0;
    mock_alarmes_programados = 0;
    mock_alarmes_no_passado = 0;
    mock_alarmes_disparados = 0;
    mock_alarme_disparo_us = 0;
    mock_ovf_disparos = 0;
    mock_alarme_margem_min_ticks = INT_MAX;
    mock_secao_max = 0;
    mock_secao_desequilibrio = 0;
    mock_escritas_fora_de_secao = 0;
    mock_alarme_fora_de_secao = 0;

    mock_serial_baud = 0;
    mock_serial_rx_tam = RX_PADRAO;
    mock_serial_tx_tam = TX_PADRAO;
    mock_serial_iniciada = 0;
    mock_serial_tx_limite = -1;
    mock_serial_bloqueios = 0;
    mock_serial_bloqueio_us = 0;
    mock_serial_rx_perdidos = 0;
    s_rx_n = 0;
    s_tx.clear();
    s_tx_fim_us = mock_tempo_us;

    mock_iac_duty = 0;
    mock_iac_escritas = 0;
    mock_iac_pino = -1;

    mock_wdt_ativo = 0;
    mock_wdt_timeout_ms = 0;
    mock_wdt_resets = 0;
    mock_wdt_estouros = 0;
    mock_wdt_max_intervalo_us = 0;
}

void mock_reiniciar_tudo(void) {
    mock_nivel_solto = 0;
    s_ddra = 0; /* mundo novo: nenhum pino estava em saída */
    mock_reiniciar_placa();
    mock_tempo_us = 0;
    mock_lat_alarme_us = 0;
    mock_isr_custo_us = 0;
    mock_isr_leitura_do_custo = 2;
    mock_tcnt1_custo_us = 0;
    mock_alarme_perde_a_cada = 0;
    mock_mudancas_n = 0;
    mock_eeprom_falhar = 0;
    mock_eeprom_gravacoes = 0;
    mock_eeprom_us_por_byte = 3300;
    memset(s_eeprom, 0xFF, sizeof s_eeprom);
    s_eeprom_usado = 0;
    mock_avcc_mv = 5000;
    for (int i = 0; i < MOCK_PINOS; i++) {
        mock_entrada_nivel[i] = 1;
        mock_adc_mv[i] = 0;
    }
    s_wdt_ultimo_us = 0;
    s_tx_fim_us = 0;
}
