/* mock_esp32.cpp - implementação do simulador do Arduino-ESP32. Veja mock_esp32.h. */
#include "mock_esp32.h"

#include <string.h>

#include <map>
#include <string>
#include <vector>

#include "Arduino.h"
#include "Preferences.h"
#include "esp_task_wdt.h"

/* ------------------------------------------------------------------ estado */

uint64_t mock_tempo_us;
uint32_t mock_lat_alarme_us;
int mock_alarme_passado_dispara = 1;
int mock_alarme_perde_a_cada;
int mock_timer_criado;
uint32_t mock_timer_divisor;
uint32_t mock_timer_freq_hz;
int mock_alarme_ativo;
uint64_t mock_alarme_valor;
int mock_alarmes_programados;
int mock_alarmes_no_passado;
int mock_alarmes_disparados;
int64_t mock_alarme_margem_min_us = INT64_MAX;
uint32_t mock_isr_custo_us;

uint8_t mock_pino_modo[MOCK_PINOS];
uint8_t mock_pino_nivel[MOCK_PINOS];
uint8_t mock_nivel_solto;
int mock_pino_escritas[MOCK_PINOS];
int mock_pino_pinmodes[MOCK_PINOS];
uint8_t mock_entrada_nivel[MOCK_PINOS];
uint32_t mock_adc_mv[MOCK_PINOS];
int mock_adc_leituras;
uint8_t mock_atenuacao[MOCK_PINOS];
mock_mudanca_t mock_mudancas[MOCK_MAX_MUDANCAS];
int mock_mudancas_n;
char mock_pino_historia[MOCK_PINOS][16];

void (*mock_isr_pino[MOCK_PINOS])(void);
int mock_isr_modo[MOCK_PINOS];

int mock_secao_nivel;
int mock_secao_max;
int mock_secao_desequilibrio;
int mock_escritas_fora_de_secao;
int mock_alarme_fora_de_secao;

uint32_t mock_serial_baud;
size_t mock_serial_rx_tam = 256;
int mock_serial_iniciada;
int mock_serial_tx_limite = -1;

int mock_nvs_falhar;
int mock_nvs_gravacoes;

int mock_iac_anexada;
uint32_t mock_iac_freq_hz;
uint32_t mock_iac_bits;
uint32_t mock_iac_duty;
int mock_iac_escritas;
int mock_iac_pino = -1;

uint32_t mock_wdt_timeout_ms;
int mock_wdt_panic;
int mock_wdt_tarefa_inscrita;
int mock_wdt_resets;

struct hw_timer_s {
    uint64_t offset; /* contador = mock_tempo_us + offset */
    void (*isr)(void);
};
static hw_timer_s s_timer;
static uint8_t s_latch[MOCK_PINOS];
static int s_na_isr;           /* dentro de mock_pulso_pino */
static int s_leituras_na_isr;  /* leituras do contador desde que a ISR começou */
static int s_contagem_perda;   /* alarmes disparados, para mock_alarme_perde_a_cada */
static int s_alarme_perdido;   /* o alarme atual foi escrito no passado e este hardware não dispara assim */

static std::map<std::string, std::vector<uint8_t> > &nvs(void) {
    static std::map<std::string, std::vector<uint8_t> > m;
    return m;
}

#define SERIAL_RX_CAP (1 << 16)
#define SERIAL_TX_CAP (1 << 22)
static uint8_t s_rx[SERIAL_RX_CAP];
static size_t s_rx_n;
static uint8_t *s_tx;
static size_t s_tx_n;
static double s_fifo_nivel; /* bytes no FIFO de TX da UART */
static uint64_t s_fifo_t;   /* instante da última atualização do FIFO */
int mock_serial_bloqueios;
uint64_t mock_serial_bloqueio_us;

/* ------------------------------------------------------------------ mundo */

void mock_reiniciar_placa(void) {
    memset(mock_pino_modo, 0, sizeof mock_pino_modo);
    memset(mock_pino_nivel, mock_nivel_solto, sizeof mock_pino_nivel); /* depois do reset os pinos estão soltos */
    memset(mock_pino_escritas, 0, sizeof mock_pino_escritas);
    memset(mock_pino_pinmodes, 0, sizeof mock_pino_pinmodes);
    memset(mock_atenuacao, 0, sizeof mock_atenuacao);
    memset(mock_pino_historia, 0, sizeof mock_pino_historia);
    memset(s_latch, 0, sizeof s_latch);
    memset(mock_isr_pino, 0, sizeof mock_isr_pino);
    memset(mock_isr_modo, 0, sizeof mock_isr_modo);
    memset(&s_timer, 0, sizeof s_timer);
    mock_timer_criado = 0;
    mock_timer_divisor = 0;
    mock_timer_freq_hz = 0;
    mock_alarme_ativo = 0;
    mock_alarme_valor = 0;
    mock_alarmes_programados = 0;
    mock_alarmes_no_passado = 0;
    mock_alarmes_disparados = 0;
    mock_alarme_margem_min_us = INT64_MAX;
    s_na_isr = 0;
    s_leituras_na_isr = 0;
    s_contagem_perda = 0;
    s_alarme_perdido = 0;
    mock_adc_leituras = 0;
    mock_secao_nivel = 0;
    mock_secao_max = 0;
    mock_secao_desequilibrio = 0;
    mock_escritas_fora_de_secao = 0;
    mock_alarme_fora_de_secao = 0;
    mock_serial_baud = 0;
    mock_serial_rx_tam = 256;
    mock_serial_iniciada = 0;
    mock_serial_tx_limite = -1;
    s_rx_n = 0;
    s_tx_n = 0;
    s_fifo_nivel = 0.0;
    s_fifo_t = mock_tempo_us;
    mock_serial_bloqueios = 0;
    mock_serial_bloqueio_us = 0;
    mock_iac_anexada = 0;
    mock_iac_freq_hz = 0;
    mock_iac_bits = 0;
    mock_iac_duty = 0;
    mock_iac_escritas = 0;
    mock_iac_pino = -1;
    mock_wdt_timeout_ms = 0;
    mock_wdt_panic = 0;
    mock_wdt_tarefa_inscrita = 0;
    mock_wdt_resets = 0;
}

void mock_reiniciar_tudo(void) {
    mock_nivel_solto = 0;
    mock_reiniciar_placa();
    mock_tempo_us = 0;
    mock_lat_alarme_us = 0;
    mock_isr_custo_us = 0;
    mock_alarme_passado_dispara = 1;
    mock_alarme_perde_a_cada = 0;
    mock_mudancas_n = 0;
    mock_nvs_falhar = 0;
    mock_nvs_gravacoes = 0;
    nvs().clear();
    for (int i = 0; i < MOCK_PINOS; i++) {
        mock_entrada_nivel[i] = 1;
        mock_adc_mv[i] = 0;
    }
}

void mock_tempo_para(uint64_t t_us) {
    if (t_us > mock_tempo_us) {
        mock_tempo_us = t_us;
    }
}

void mock_correr_ate(uint64_t alvo_us) {
    int guarda = 0;
    /*
     * s_alarme_perdido: o alarme foi ESCRITO com um valor que o contador já tinha passado e o hardware (neste
     * modelo de pior caso, mock_alarme_passado_dispara == 0) não dispara assim: fica armado e nunca acontece.
     * Já um alarme escrito no futuro cujo instante passa enquanto a CPU está ocupada fica "pendente" e dispara
     * assim que a CPU volta, como o evento de comparação do timer faz no chip.
     */
    while (mock_alarme_ativo && !s_alarme_perdido && guarda++ < 1000000) {
        const uint64_t contador = mock_tempo_us + s_timer.offset;
        uint64_t quando = mock_tempo_us;
        if (mock_alarme_valor > contador) {
            quando += mock_alarme_valor - contador;
        }
        quando += mock_lat_alarme_us;
        if (quando > alvo_us) {
            break;
        }
        mock_tempo_para(quando);
        mock_alarme_ativo = 0; /* o alarme (sem autoreload) desarma ao disparar */
        mock_alarmes_disparados++;
        if (mock_alarme_perde_a_cada > 0 && ++s_contagem_perda % mock_alarme_perde_a_cada == 0) {
            continue; /* alarme perdido: nada acontece */
        }
        if (s_timer.isr != NULL) {
            s_timer.isr();
        }
    }
    mock_tempo_para(alvo_us);
}

/* ------------------------------------------------------------------ pinos */

static void historia(uint8_t pino, char c) {
    char *h = mock_pino_historia[pino];
    const size_t n = strlen(h);
    if (n < sizeof mock_pino_historia[0] - 1) {
        h[n] = c;
        h[n + 1] = 0;
    }
}

static void mudar_nivel_fisico(uint8_t pino, uint8_t nivel) {
    if (mock_pino_nivel[pino] == nivel) {
        return;
    }
    mock_pino_nivel[pino] = nivel;
    if (mock_mudancas_n < MOCK_MAX_MUDANCAS) {
        mock_mudancas[mock_mudancas_n].t_us = mock_tempo_us;
        mock_mudancas[mock_mudancas_n].pino = pino;
        mock_mudancas[mock_mudancas_n].nivel = nivel;
        mock_mudancas_n++;
    }
}

void pinMode(uint8_t pino, uint8_t modo) {
    mock_pino_pinmodes[pino]++;
    mock_pino_modo[pino] = modo;
    historia(pino, modo == OUTPUT ? 'M' : 'm');
    /* saída passa a mostrar o nível do latch; entrada solta volta ao nível que o resistor externo impõe */
    mudar_nivel_fisico(pino, modo == OUTPUT ? s_latch[pino] : mock_nivel_solto);
}

void digitalWrite(uint8_t pino, uint8_t nivel) {
    mock_pino_escritas[pino]++;
    if (mock_pino_modo[pino] == OUTPUT && mock_secao_nivel == 0) {
        mock_escritas_fora_de_secao++;
    }
    historia(pino, nivel ? 'H' : 'L');
    s_latch[pino] = nivel ? 1 : 0;
    if (mock_pino_modo[pino] == OUTPUT) {
        mudar_nivel_fisico(pino, s_latch[pino]);
    }
}

int digitalRead(uint8_t pino) {
    return mock_entrada_nivel[pino] ? HIGH : LOW;
}

void attachInterrupt(uint8_t pino, void (*fn)(void), int modo) {
    mock_isr_pino[pino] = fn;
    mock_isr_modo[pino] = modo;
}

int mock_pulso_pino(int pino) {
    if (mock_isr_pino[pino] == NULL) {
        return 0;
    }
    s_na_isr = 1;
    s_leituras_na_isr = 0;
    mock_isr_pino[pino]();
    s_na_isr = 0;
    return 1;
}

uint32_t analogReadMilliVolts(uint8_t pino) {
    mock_adc_leituras++;
    return mock_adc_mv[pino];
}

void analogReadResolution(uint8_t bits) {
    (void)bits;
}

void analogSetPinAttenuation(uint8_t pino, adc_attenuation_t atenuacao) {
    mock_atenuacao[pino] = (uint8_t)atenuacao;
}

unsigned long millis(void) {
    return (unsigned long)(mock_tempo_us / 1000u);
}

/* ------------------------------------------------------------------ timer */

uint64_t timerRead(hw_timer_t *timer) {
    /* A ISR de pino gasta mock_isr_custo_us entre a 1ª leitura (instante do dente) e a 2ª (início do agendamento). */
    if (s_na_isr && ++s_leituras_na_isr == 2) {
        mock_tempo_us += mock_isr_custo_us;
    }
    return mock_tempo_us + timer->offset;
}

void timerWrite(hw_timer_t *timer, uint64_t valor) {
    timer->offset = valor - mock_tempo_us;
}

void mock_timer_definir_contador(uint64_t valor) {
    s_timer.offset = valor - mock_tempo_us;
}

uint64_t mock_timer_contador(void) {
    return mock_tempo_us + s_timer.offset;
}

#ifndef MOCK_ESP_V3
void timerAlarmDisable(hw_timer_t *timer) {
    (void)timer;
    if (mock_secao_nivel == 0) {
        mock_alarme_fora_de_secao++;
    }
    mock_alarme_ativo = 0;
    s_alarme_perdido = 0;
}
#endif

static void alarme_escrever(hw_timer_t *timer, uint64_t valor) {
    const uint64_t contador = mock_tempo_us + timer->offset; /* sem passar por timerRead: não é uma leitura da ISR */
    if (mock_secao_nivel == 0) {
        mock_alarme_fora_de_secao++;
    }
    mock_alarme_valor = valor;
    s_alarme_perdido = (valor <= contador && !mock_alarme_passado_dispara) ? 1 : 0;
    mock_alarmes_programados++;
    const int64_t margem = (int64_t)(valor - contador);
    if (margem < mock_alarme_margem_min_us) {
        mock_alarme_margem_min_us = margem;
    }
    if (valor <= contador + 2u) {
        mock_alarmes_no_passado++;
    }
}

#ifdef MOCK_ESP_V3
hw_timer_t *timerBegin(uint32_t frequencia) {
    mock_timer_criado = 1;
    mock_timer_freq_hz = frequencia;
    s_timer.offset = 0u - mock_tempo_us; /* o contador começa em zero */
    return &s_timer;
}

void timerAttachInterrupt(hw_timer_t *timer, void (*fn)(void)) {
    timer->isr = fn;
}

void timerAlarm(hw_timer_t *timer, uint64_t alarme, bool autoreload, uint64_t reload_count) {
    (void)autoreload;
    (void)reload_count;
    alarme_escrever(timer, alarme);
    mock_alarme_ativo = 1;
}
#else
hw_timer_t *timerBegin(uint8_t numero, uint16_t divisor, bool contaPraCima) {
    (void)numero;
    (void)contaPraCima;
    mock_timer_criado = 1;
    mock_timer_divisor = divisor;
    mock_timer_freq_hz = 80000000u / divisor;
    s_timer.offset = 0u - mock_tempo_us;
    return &s_timer;
}

void timerAttachInterrupt(hw_timer_t *timer, void (*fn)(void), bool borda) {
    (void)borda;
    timer->isr = fn;
}

void timerAlarmWrite(hw_timer_t *timer, uint64_t alarme, bool autoreload) {
    (void)autoreload;
    alarme_escrever(timer, alarme);
}

void timerAlarmEnable(hw_timer_t *timer) {
    (void)timer;
    if (mock_secao_nivel == 0) {
        mock_alarme_fora_de_secao++;
    }
    mock_alarme_ativo = 1;
}
#endif

/* ------------------------------------------------------------------ seção crítica */

void mock_secao_entrar(portMUX_TYPE *mux) {
    (void)mux;
    mock_secao_nivel++;
    if (mock_secao_nivel > mock_secao_max) {
        mock_secao_max = mock_secao_nivel;
    }
}

void mock_secao_sair(portMUX_TYPE *mux) {
    (void)mux;
    if (mock_secao_nivel == 0) {
        mock_secao_desequilibrio++;
    } else {
        mock_secao_nivel--;
    }
}

/* ------------------------------------------------------------------ PWM */

#ifdef MOCK_ESP_V3
bool ledcAttach(uint8_t pino, uint32_t freq, uint8_t bits) {
    mock_iac_anexada = 1;
    mock_iac_pino = pino;
    mock_iac_freq_hz = freq;
    mock_iac_bits = bits;
    return true;
}

void ledcWrite(uint8_t pino, uint32_t duty) {
    (void)pino;
    mock_iac_duty = duty;
    mock_iac_escritas++;
}
#else
double ledcSetup(uint8_t canal, double freq, uint8_t bits) {
    (void)canal;
    mock_iac_freq_hz = (uint32_t)freq;
    mock_iac_bits = bits;
    return freq;
}

void ledcAttachPin(uint8_t pino, uint8_t canal) {
    (void)canal;
    mock_iac_anexada = 1;
    mock_iac_pino = pino;
}

void ledcWrite(uint8_t canal, uint32_t duty) {
    (void)canal;
    mock_iac_duty = duty;
    mock_iac_escritas++;
}
#endif

/* ------------------------------------------------------------------ serial */

HardwareSerial Serial;

size_t HardwareSerial::setRxBufferSize(size_t tamanho) {
    if (mock_serial_iniciada) {
        return 0; /* como no ESP32 real: depois do begin() não muda mais */
    }
    mock_serial_rx_tam = tamanho;
    return tamanho;
}

void HardwareSerial::begin(unsigned long baud) {
    mock_serial_baud = (uint32_t)baud;
    mock_serial_iniciada = 1;
}

int HardwareSerial::available(void) {
    return (int)s_rx_n;
}

/*
 * readBytes: o Stream::readBytes do Arduino ESPERA até o timeout (1 s) pelos bytes que faltam. Quem pede mais do
 * que available() trava a placa; o simulador faz o tempo passar e conta o bloqueio.
 */
size_t HardwareSerial::readBytes(uint8_t *destino, size_t tamanho) {
    if (tamanho > s_rx_n) {
        mock_serial_bloqueios++;
        mock_serial_bloqueio_us += 1000000u;
        mock_tempo_us += 1000000u;
    }
    size_t n = tamanho < s_rx_n ? tamanho : s_rx_n;
    memcpy(destino, s_rx, n);
    memmove(s_rx, s_rx + n, s_rx_n - n);
    s_rx_n -= n;
    return n;
}

/*
 * FIFO de transmissão da UART: 128 bytes, esvazia à velocidade da linha (10 bits por byte). Com o driver sem buffer
 * de TX (o padrão do Arduino-ESP32) write() ESPERA o FIFO ter espaço: a placa fica parada. availableForWrite()
 * diz quantos bytes cabem agora sem esperar.
 */
#define UART_FIFO 128.0

static double us_por_byte(void) {
    return mock_serial_baud != 0u ? 1e7 / (double)mock_serial_baud : 86.8;
}

static void fifo_atualizar(void) {
    if (mock_tempo_us > s_fifo_t) {
        const double drenado = (double)(mock_tempo_us - s_fifo_t) / us_por_byte();
        s_fifo_nivel = s_fifo_nivel > drenado ? s_fifo_nivel - drenado : 0.0;
    }
    s_fifo_t = mock_tempo_us;
}

int HardwareSerial::availableForWrite(void) {
    if (mock_serial_tx_limite >= 0) {
        return mock_serial_tx_limite;
    }
    fifo_atualizar();
    return (int)(UART_FIFO - s_fifo_nivel);
}

size_t HardwareSerial::write(const uint8_t *origem, size_t tamanho) {
    if (s_tx == NULL) {
        s_tx = new uint8_t[SERIAL_TX_CAP];
    }
    size_t n = tamanho;
    if (mock_serial_tx_limite >= 0) {
        /* o PC não está lendo: uma placa de verdade esperaria para sempre; aqui só se conta e se corta */
        if (n > (size_t)mock_serial_tx_limite) {
            mock_serial_bloqueios++;
            n = (size_t)mock_serial_tx_limite;
        }
    } else {
        fifo_atualizar();
        const double livre = UART_FIFO - s_fifo_nivel;
        if ((double)n > livre) {
            const uint64_t espera = (uint64_t)(((double)n - livre) * us_por_byte() + 0.5);
            mock_serial_bloqueios++;
            mock_serial_bloqueio_us += espera;
            mock_tempo_us += espera; /* a placa fica esperando o FIFO esvaziar */
            fifo_atualizar();
        }
        s_fifo_nivel += (double)n;
        if (s_fifo_nivel > UART_FIFO) {
            s_fifo_nivel = UART_FIFO;
        }
    }
    if (s_tx_n + n > SERIAL_TX_CAP) {
        n = SERIAL_TX_CAP - s_tx_n;
    }
    memcpy(s_tx + s_tx_n, origem, n);
    s_tx_n += n;
    return n;
}

void mock_serial_injetar(const uint8_t *dados, int n) {
    for (int i = 0; i < n; i++) {
        if (s_rx_n < mock_serial_rx_tam && s_rx_n < SERIAL_RX_CAP) {
            s_rx[s_rx_n++] = dados[i]; /* estourou o buffer: o byte se perde, como no hardware */
        }
    }
}

int mock_serial_coletar(uint8_t *destino, int maximo) {
    const size_t n = s_tx_n < (size_t)maximo ? s_tx_n : (size_t)maximo;
    if (n > 0) {
        memcpy(destino, s_tx, n);
        memmove(s_tx, s_tx + n, s_tx_n - n);
        s_tx_n -= n;
    }
    return (int)n;
}

/* ------------------------------------------------------------------ NVS */

static std::string chave_completa(const char *nome, const char *chave) {
    return std::string(nome) + "/" + chave;
}

bool Preferences::begin(const char *nome, bool somenteLeitura) {
    strncpy(nome_, nome, sizeof nome_ - 1);
    aberto_ = true;
    somenteLeitura_ = somenteLeitura;
    return true;
}

void Preferences::end(void) {
    aberto_ = false;
}

size_t Preferences::putBytes(const char *chave, const void *valor, size_t tamanho) {
    if (!aberto_ || somenteLeitura_ || mock_nvs_falhar) {
        return 0;
    }
    const uint8_t *p = static_cast<const uint8_t *>(valor);
    nvs()[chave_completa(nome_, chave)] = std::vector<uint8_t>(p, p + tamanho);
    mock_nvs_gravacoes++;
    return tamanho;
}

size_t Preferences::getBytes(const char *chave, void *buffer, size_t tamanhoMax) {
    if (!aberto_) {
        return 0;
    }
    std::map<std::string, std::vector<uint8_t> >::iterator it = nvs().find(chave_completa(nome_, chave));
    if (it == nvs().end()) {
        return 0;
    }
    const size_t n = it->second.size() < tamanhoMax ? it->second.size() : tamanhoMax;
    memcpy(buffer, &it->second[0], n);
    return n;
}

size_t Preferences::getBytesLength(const char *chave) {
    if (!aberto_) {
        return 0;
    }
    std::map<std::string, std::vector<uint8_t> >::iterator it = nvs().find(chave_completa(nome_, chave));
    return it == nvs().end() ? 0 : it->second.size();
}

void mock_nvs_corromper(size_t posicao) {
    for (std::map<std::string, std::vector<uint8_t> >::iterator it = nvs().begin(); it != nvs().end(); ++it) {
        if (!it->second.empty()) {
            it->second[posicao % it->second.size()] ^= 0x5A;
        }
    }
}

size_t mock_nvs_tamanho(void) {
    size_t total = 0;
    for (std::map<std::string, std::vector<uint8_t> >::iterator it = nvs().begin(); it != nvs().end(); ++it) {
        total += it->second.size();
    }
    return total;
}

/* ------------------------------------------------------------------ cão de guarda */

#ifdef MOCK_ESP_V3
esp_err_t esp_task_wdt_reconfigure(const esp_task_wdt_config_t *config) {
    mock_wdt_timeout_ms = config->timeout_ms;
    mock_wdt_panic = config->trigger_panic ? 1 : 0;
    return ESP_OK;
}
#else
esp_err_t esp_task_wdt_init(uint32_t timeout_s, bool panic) {
    mock_wdt_timeout_ms = timeout_s * 1000u;
    mock_wdt_panic = panic ? 1 : 0;
    return ESP_OK;
}
#endif

esp_err_t esp_task_wdt_add(void *tarefa) {
    (void)tarefa;
    mock_wdt_tarefa_inscrita = 1;
    return ESP_OK;
}

esp_err_t esp_task_wdt_reset(void) {
    mock_wdt_resets++;
    return ESP_OK;
}
