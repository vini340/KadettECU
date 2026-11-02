/*
 * kecu_proto.h - protocolo serial PC <-> ECU (versão 1), lado do firmware.
 * Os valores são os mesmos de kadettecu.protocolo.Protocolo (Java); a especificação completa
 * está em docs/PROTOCOLO.md.
 *
 * Quadro: A5 5A | LEN | TIPO | SEQ | PAYLOAD (LEN bytes) | CRC16 (little-endian)
 * O CRC-16/CCITT-FALSE cobre LEN, TIPO, SEQ e PAYLOAD.
 */
#ifndef KECU_PROTO_H
#define KECU_PROTO_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KECU_SOF1 0xA5u
#define KECU_SOF2 0x5Au
#define KECU_CABECALHO 5
#define KECU_TAM_CRC 2
#define KECU_PAYLOAD_MAX 240
#define KECU_TELEMETRIA_BYTES 36
#define KECU_INFO_BYTES 8

/* Comandos (PC -> ECU) */
#define KECU_CMD_HELLO 0x01u
#define KECU_CMD_STREAM 0x02u
#define KECU_CMD_LER_PARAMS 0x10u
#define KECU_CMD_ESCREVER_PARAMS 0x11u
#define KECU_CMD_LER_LINHA 0x20u
#define KECU_CMD_ESCREVER_LINHA 0x21u
#define KECU_CMD_LER_EIXOS 0x22u
#define KECU_CMD_ESCREVER_EIXOS 0x23u
#define KECU_CMD_GRAVAR 0x30u
#define KECU_CMD_PADROES 0x31u
#define KECU_CMD_ARMAR 0x40u
#define KECU_CMD_CHAVES 0x41u

/* Respostas (ECU -> PC) */
#define KECU_RSP_ACK 0x80u
#define KECU_RSP_INFO 0x81u
#define KECU_RSP_NACK 0x8Fu
#define KECU_RSP_PARAMS 0x90u
#define KECU_RSP_LINHA 0xA0u
#define KECU_RSP_EIXOS 0xA1u
#define KECU_RSP_TELEMETRIA 0xF0u

/* Códigos do NACK */
#define KECU_NACK_COMANDO 1u
#define KECU_NACK_TAMANHO 2u
#define KECU_NACK_FAIXA 3u
#define KECU_NACK_MOTOR_GIRANDO 4u
#define KECU_NACK_MEMORIA 5u
#define KECU_NACK_SAIDAS_DESABILITADAS 6u

/* Bits do campo "flags" da telemetria */
#define KECU_FLAG_SYNC 0x0001u
#define KECU_FLAG_MOTOR_GIRANDO 0x0002u
#define KECU_FLAG_CORTE 0x0004u
#define KECU_FLAG_TWO_STEP 0x0008u
#define KECU_FLAG_LAUNCH 0x0010u
#define KECU_FLAG_AE 0x0020u
#define KECU_FLAG_MARCHA_LENTA 0x0040u
#define KECU_FLAG_ARMADO 0x0080u
#define KECU_FLAG_CHAVE_TWO_STEP 0x0100u
#define KECU_FLAG_CHAVE_LAUNCH 0x0200u
#define KECU_FLAG_MODO_SOMBRA 0x0400u
#define KECU_FLAG_SIMULADOR 0x0800u

/* Capacidades (campo do HELLO) */
#define KECU_CAP_SAIDAS_REAIS 0x0001u
#define KECU_CAP_NVM 0x0002u

typedef struct {
    uint32_t quadros_validos;
    uint32_t erros_crc;
    uint32_t bytes_descartados;
    uint32_t telemetrias_enviadas;
    uint32_t telemetrias_perdidas; /* a fila de saída estava cheia */
    uint32_t respostas_perdidas;
} kecu_proto_stats_t;

/* CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). "123456789" -> 0x29B1. */
uint16_t kecu_crc16(const uint8_t *dados, uint16_t tamanho);

/* Monta um quadro completo em saida (precisa de 7 + n bytes); devolve o tamanho total. */
uint16_t kecu_proto_montar(uint8_t *saida, uint8_t tipo, uint8_t seq, const uint8_t *payload, uint8_t n);

/* Alimenta o decodificador com bytes recebidos (executa os comandos que completarem). */
void kecu_proto_alimentar(const uint8_t *dados, int n);

/* Preenche os 36 bytes da telemetria a partir do estado atual. */
void kecu_proto_telemetria(uint8_t *saida);

/* Copia dos contadores e leitura do período de telemetria (0 = desligada), úteis em testes e diagnóstico. */
void kecu_proto_stats(kecu_proto_stats_t *destino);
uint16_t kecu_proto_periodo_ms(void);

#ifdef __cplusplus
}
#endif

#endif
