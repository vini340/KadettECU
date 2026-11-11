# Protocolo serial PC ↔ ECU (versão 1)

Este documento descreve, byte a byte, a conversa entre o aplicativo Java (notebook) e a placa (ESP32 ou Arduino Mega).
O mesmo texto vale para o simulador em Java e para o firmware em C rodando no PC (`ecu_host`): os três falam exatamente
este protocolo, e os testes automáticos conferem isso (veja a seção 13).

Fontes que definem o protocolo (quando este texto e o código discordarem, o código vence, e o teste de integração acusa a diferença):

| Lado | Arquivos |
|------|----------|
| Firmware (C) | `firmware/lib/kecu_core/kecu_proto.h`, `kecu_proto.c`, `kecu_params.h`, `kecu_params.c`, `kecu_maps.h`, `kecu_store.c` |
| Aplicativo (Java) | `kadettecu/protocolo/Protocolo.java`, `Crc16.java`, `Pacote.java`, `DecodificadorPacotes.java`, `BufferLE.java`; `kadettecu/modelo/CampoParametro.java`, `Telemetria.java`, `TipoMapa.java` |

Todos os exemplos em hexadecimal deste documento foram capturados do firmware de verdade (o `ecu_host`) pelo script
`docs/exemplos/exemplo_quadros.py`: você pode repetir a captura (seção 12).

## 1. Camada física

- Porta serial USB da placa a **115200 baud, 8 bits de dados, sem paridade, 1 bit de parada (8N1)**, sem controle de fluxo.
- Todos os números de mais de um byte são **little-endian** (o byte menos significativo primeiro). Inteiros com sinal usam complemento de dois.
- O PC é o mestre: manda **comandos** e a placa devolve **uma resposta** para cada um (mesmo `SEQ`). Além disso, depois de ligada
  com o comando `STREAM`, a placa envia sozinha quadros de **telemetria**, sem que ninguém peça.
- Respostas e telemetria se misturam no mesmo fluxo. Quem lê precisa separar pelo campo `TIPO` (a telemetria é sempre `0xF0`).

## 2. O quadro

```
 offset   0     1     2     3      4     5 ........... 5+LEN-1    5+LEN   6+LEN
        +-----+-----+-----+------+-----+------------------------+--------+--------+
        | A5  | 5A  | LEN | TIPO | SEQ |        PAYLOAD         | CRC lo | CRC hi |
        +-----+-----+-----+------+-----+------------------------+--------+--------+
         SOF1  SOF2                      (LEN bytes, 0 a 240)       CRC-16 (little-endian)
                      \________ o CRC cobre estes bytes: LEN, TIPO, SEQ e PAYLOAD ________/
```

| Campo | Bytes | Descrição |
|-------|------:|-----------|
| `SOF1`, `SOF2` | 2 | Marcador de início: `0xA5 0x5A` (fixo). |
| `LEN` | 1 | Tamanho do payload, de 0 a **240**. Um valor maior invalida o quadro. |
| `TIPO` | 1 | Qual comando (PC→ECU) ou resposta (ECU→PC) é este quadro (seção 4). |
| `SEQ` | 1 | Número de sequência (0 a 255). Na resposta a um comando, a ECU **copia o SEQ do comando**. Na telemetria é um contador da própria ECU. |
| `PAYLOAD` | `LEN` | Dados do comando ou da resposta. |
| `CRC` | 2 | CRC-16/CCITT-FALSE de `LEN`, `TIPO`, `SEQ` e `PAYLOAD`, gravado com o byte baixo primeiro. |

Tamanho total do quadro: `7 + LEN` bytes (no máximo 247).

### 2.1. CRC-16/CCITT-FALSE

Polinômio `0x1021`, valor inicial `0xFFFF`, sem inversão de bits na entrada nem na saída, sem XOR final. Verificação padrão:
o CRC dos nove bytes ASCII `"123456789"` é **`0x29B1`**. Em C (é o código do firmware, `kecu_crc16`):

```c
uint16_t kecu_crc16(const uint8_t *dados, uint16_t tamanho) {
    uint16_t crc = 0xFFFF;
    for (uint16_t i = 0; i < tamanho; i++) {
        crc ^= (uint16_t)dados[i] << 8;
        for (int bit = 0; bit < 8; bit++) {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}
```

Exemplo completo, o comando `HELLO` com `SEQ = 1` (payload vazio): o CRC de `00 01 01` é `0xEF8C`, e o quadro fica

```
A5 5A 00 01 01 8C EF
```

### 2.2. Número de sequência (`SEQ`)

- O PC escolhe o `SEQ` de cada comando; a ECU o devolve na resposta. O aplicativo usa **um número novo a cada tentativa**
  (inclusive nos reenvios do mesmo comando). Assim, uma resposta atrasada de uma tentativa anterior, que chegasse depois
  do reenvio, tem outro `SEQ` e é ignorada, em vez de ser confundida com a resposta atual.
- A telemetria usa um contador próprio da ECU, que sobe de 1 em 1 a cada quadro e volta a 0 depois de 255. Serve para o PC
  perceber quadros perdidos; o aplicativo não depende dele.

## 3. Como o receptor remonta os quadros

A serial entrega bytes, não quadros: um quadro pode chegar partido em vários pedaços, ou dois podem chegar colados, ou pode
haver lixo no meio (ruído, ou os bytes de boot da placa). O algoritmo é o mesmo nos dois lados
(`DecodificadorPacotes.java` e `rx_extrair` em `kecu_proto.c`):

1. Procure o par `A5 5A`. Tudo o que vem antes é descartado (e contado).
2. Se ainda não há 5 bytes (cabeçalho completo), espere.
3. Se `LEN > 240`, aquele `A5 5A` era falso: descarte **1 byte** e volte ao passo 1.
4. Espere chegarem `7 + LEN` bytes no total.
5. Calcule o CRC. Se **bate**, o quadro é válido: entregue-o e remova os `7 + LEN` bytes.
6. Se **não bate**, conte um erro de CRC, descarte **apenas 1 byte** (o `A5` inicial) e volte ao passo 1. Descartar só um byte, e não o quadro
   inteiro, é o que permite achar um quadro verdadeiro que começou *dentro* de um falso.

Proteção extra no lado da ECU: se um quadro fica **pela metade por mais de 100 ms** (um quadro inteiro leva no máximo uns
20 ms a 115200 baud), o primeiro byte é descartado e a busca recomeça. Sem isso, um ruído com cara de `A5 5A 7F` faria a ECU
esperar 127 bytes que nunca chegam, escondendo os comandos verdadeiros que vieram logo depois.

Buffers na ECU: recepção de 256 bytes (cabe o maior quadro, 247); fila de saída de 512 bytes. A ECU lê no máximo 4 blocos de
64 bytes por volta do laço principal, para a comunicação nunca atrasar o controle do motor. Na saída, só sai o que a serial
da placa aceita, sem bloquear. Se a fila de saída estiver cheia, a **telemetria é descartada** (e contada); uma resposta de
comando só é perdida se a fila não tiver espaço para ela inteira (nunca sai meio quadro), e o PC reenvia depois de 700 ms.

## 4. Tipos de quadro

Comandos (PC → ECU):

| `TIPO` | Nome | Payload | Resposta de sucesso |
|-------:|------|---------|---------------------|
| `0x01` | `HELLO` | vazio | `INFO` (`0x81`) |
| `0x02` | `STREAM` | período em ms, `u16` | `ACK` |
| `0x10` | `LER_PARAMS` | vazio | `PARAMS` (`0x90`) |
| `0x11` | `ESCREVER_PARAMS` | 47 bytes | `ACK` |
| `0x20` | `LER_LINHA` | mapa `u8`, linha `u8` | `LINHA` (`0xA0`) |
| `0x21` | `ESCREVER_LINHA` | mapa `u8`, linha `u8`, 16 bytes | `ACK` |
| `0x22` | `LER_EIXOS` | vazio | `EIXOS` (`0xA1`) |
| `0x23` | `ESCREVER_EIXOS` | 64 bytes | `ACK` |
| `0x30` | `GRAVAR` | vazio | `ACK` |
| `0x31` | `PADROES` | vazio | `ACK` |
| `0x40` | `ARMAR` | `u8` (0 desarma, 1 arma) | `ACK` |
| `0x41` | `CHAVES` | máscara `u8` | `ACK` |

Respostas e quadros espontâneos (ECU → PC):

| `TIPO` | Nome | Payload |
|-------:|------|---------|
| `0x80` | `ACK` | vazio |
| `0x81` | `INFO` | 8 bytes (seção 5.1) |
| `0x8F` | `NACK` | 1 byte: código do erro (seção 6) |
| `0x90` | `PARAMS` | 47 bytes (seção 8) |
| `0xA0` | `LINHA` | mapa, linha, 16 bytes |
| `0xA1` | `EIXOS` | 64 bytes |
| `0xF0` | `TELEMETRIA` | 36 bytes (seção 7), enviada sozinha |

Qualquer outro `TIPO` recebido como comando é respondido com `NACK` código 1.

## 5. Os comandos

### 5.1. `HELLO` (0x01) → `INFO` (0x81)

Primeiro comando de toda conexão: pergunta quem está do outro lado. Payload do `INFO` (8 bytes):

| Offset | Bytes | Campo | Valor atual / significado |
|-------:|------:|-------|---------------------------|
| 0 | 1 | versão do protocolo | `1`. O aplicativo **recusa** a conexão se for diferente. |
| 1 | 1 | firmware, maior | `1` |
| 2 | 1 | firmware, menor | `0` |
| 3 | 1 | placa | `0` PC/simulador, `1` ESP32, `2` STM32, `3` Arduino (AVR) |
| 4 | 1 | tamanho das tabelas | `16` (tabelas 16×16). O aplicativo recusa se for diferente. |
| 5 | 1 | número de tabelas | `3` (combustível, ignição, lambda). O aplicativo recusa se for diferente. |
| 6 | 2 | capacidades (`u16`) | bit 0 (`0x0001`): **saídas reais compiladas** (`KECU_ENABLE_OUTPUTS=1`); bit 1 (`0x0002`): tem memória não volátil |

Exemplo real (firmware em C no PC, modo sombra, com memória): payload `01 01 00 00 10 03 02 00`.

```
PC  -> ECU : A5 5A 00 01 01 8C EF
ECU -> PC  : A5 5A 08 81 01 01 01 00 00 10 03 02 00 F4 CD
```

### 5.2. `STREAM` (0x02) → `ACK`

Liga, muda ou desliga a telemetria periódica. Payload: o **período em milissegundos**, `u16`.

- `0` desliga a telemetria.
- `5` a `1000` liga com esse período. O aplicativo usa **20 ms** (50 quadros por segundo).
- Qualquer outro valor: `NACK` código 3 (faixa). Payload com tamanho diferente de 2: `NACK` código 2.

A placa **não lembra** disso depois de reiniciar: ao ligar, a telemetria está desligada. Por isso o aplicativo, ao perceber que
a telemetria sumiu (estado `SEM_SINAL`), repete o `STREAM` a cada segundo até ela voltar.

```
PC  -> ECU : A5 5A 02 02 02 14 00 30 19          (período 0x0014 = 20 ms)
ECU -> PC  : A5 5A 00 80 02 46 F7                (ACK, SEQ 2)
```

### 5.3. `LER_PARAMS` (0x10) → `PARAMS` (0x90) e `ESCREVER_PARAMS` (0x11) → `ACK`

`PARAMS` e o payload de `ESCREVER_PARAMS` têm o mesmo formato: os **47 bytes** da tabela da seção 8, na ordem dela.

`ESCREVER_PARAMS` é **tudo ou nada**: a ECU valida todos os 32 campos antes de mexer em qualquer um.

| Situação | Resposta |
|----------|----------|
| payload com tamanho diferente de 47 | `NACK` 2 |
| algum campo fora da faixa | `NACK` 3 (nenhum campo muda) |
| motor girando e algum campo marcado "só com o motor parado" mudaria | `NACK` 4 (nenhum campo muda) |
| tudo certo | os parâmetros valem **na hora** (na RAM) e a resposta é `ACK` |

### 5.4. `LER_LINHA` (0x20) → `LINHA` (0xA0) e `ESCREVER_LINHA` (0x21) → `ACK`

Uma linha de uma das tabelas 16×16. O mapa é `0` (combustível/VE), `1` (ignição) ou `2` (alvo de lambda); a linha é de `0` a `15`
(índice de carga, a linha 0 é a de menor pressão no coletor). O payload da resposta `LINHA`, e o de `ESCREVER_LINHA`, é:

| Offset | Bytes | Conteúdo |
|-------:|------:|----------|
| 0 | 1 | mapa (0 a 2) |
| 1 | 1 | linha (0 a 15) |
| 2 | 16 | os 16 valores da linha, **do menor para o maior RPM** (coluna 0 a 15), um byte "bruto" (0 a 255) cada |

`LER_LINHA` leva só os dois primeiros bytes. Tamanho errado: `NACK` 2. Mapa ≥ 3 ou linha ≥ 16: `NACK` 3.
O valor escrito vale na hora, a partir do próximo passo de controle (até 5 ms depois); qualquer byte de 0 a 255 é aceito, porque
a ECU não conhece o seu motor: quem decide o que é razoável é o preparador.

Exemplo real: a linha 5 (carga 50 kPa) do mapa de combustível.

```
PC  -> ECU : A5 5A 02 20 03 00 05 34 6B
ECU -> PC  : A5 5A 12 A0 03 00 05 32 36 3A 3E 42 45 48 4A 4B 4A 48 44 3E 38 32 2C 3E 4D
                              |  |  \_ 16 valores: 50 54 58 62 66 69 72 74 75 74 72 68 62 56 50 44
                              |  \_ linha 5
                              \_ mapa 0
```

### 5.5. `LER_EIXOS` (0x22) → `EIXOS` (0xA1) e `ESCREVER_EIXOS` (0x23) → `ACK`

Os dois eixos compartilhados pelas três tabelas. Payload de 64 bytes: primeiro os **16 valores de RPM** (`u16` cada, das colunas, de 0 a 15),
depois as **16 cargas** em kPa (`u16` cada, das linhas, de 0 a 15). Na escrita, os dois eixos precisam ser **estritamente crescentes**;
senão `NACK` 3 e nada muda. Tamanho diferente de 64: `NACK` 2.

Padrão de fábrica: RPM `500 800 1100 1500 2000 2500 3000 3500 4000 4500 5000 5500 6000 6500 7000 7500`;
carga `20 26 32 ... 110` (de 6 em 6 kPa).

```
PC  -> ECU : A5 5A 00 22 04 9C EC
ECU -> PC  : A5 5A 40 A1 04 F4 01 20 03 4C 04 DC 05 D0 07 C4 09 B8 0B AC 0D A0 0F 94 11 88 13 7C 15 70 17 64 19
             58 1B 4C 1D 14 00 1A 00 20 00 26 00 2C 00 32 00 38 00 3E 00 44 00 4A 00 50 00 56 00 5C 00 62 00 68 00 6E 00 06 72
             (F4 01 = 500 rpm ... 4C 1D = 7500 rpm; depois 14 00 = 20 kPa ... 6E 00 = 110 kPa; os dois últimos bytes são o CRC)
```

### 5.6. `GRAVAR` (0x30) → `ACK`

Grava a calibração que está **na RAM** (parâmetros, eixos e as três tabelas) na memória não volátil da placa (NVS no ESP32,
EEPROM no Mega). Só com o motor **parado**: com o motor girando, `NACK` 4. A ECU grava, **lê de volta e confere**; só então responde
`ACK`. Se a memória falhar ou não guardar, responde `NACK` 5 e acende o bit de erro `NVM` (seção 7.3), que apaga na próxima gravação boa.
Formato do bloco gravado: seção 10.

**Esta é a única resposta que pode demorar.** No Mega a EEPROM escreve em torno de 3,4 ms por byte que mudou, e um bloco totalmente novo
(889 bytes) ocupa a placa por cerca de **3 s**: nesse tempo o laço principal fica parado, a telemetria pausa (o aplicativo mostra
`SEM SINAL` por instantes e volta sozinho) e o `ACK` só sai no fim. Por isso o aplicativo espera o `ACK` do `GRAVAR` por **até 6 s, em 2
tentativas**, e não pelos 700 ms × 3 dos outros comandos (2,1 s, menos que o pior caso do Mega: o aplicativo mostraria erro numa gravação que
deu certo; o `TesteSistema` tem um teste para isso). Repetir o `GRAVAR` é inofensivo: regravar o mesmo bloco não muda nenhum byte. No ESP32 (NVS, flash) a gravação
é bem mais rápida.

### 5.7. `PADROES` (0x31) → `ACK`

Carrega os valores de fábrica na **RAM** (parâmetros, eixos e tabelas). Não grava na memória permanente: para manter, mande
`GRAVAR` depois. Só com o motor parado (`NACK` 4 se girando).

### 5.8. `ARMAR` (0x40) → `ACK`

Payload de 1 byte: `0` **desarma**, `1` **arma** as saídas reais (bicos e bobina).

- **Desarmar sempre é aceito, e vale imediatamente**: a ECU primeiro avisa o planejador da interrupção para parar de agendar pulsos,
  depois manda a placa cancelar o que já estava agendado e levar todas as saídas ao nível desligado. Só então responde `ACK`.
- **Armar** só é aceito em firmware compilado com `KECU_ENABLE_OUTPUTS=1`. Num firmware de **modo sombra** (o padrão), a resposta é
  `NACK` 6 ("saídas desabilitadas"): é impossível acionar bico ou bobina por engano com o firmware padrão.
- Em firmware com saídas, qualquer valor que não seja 0 nem 1 recebe `NACK` 3. Tamanho diferente de 1: `NACK` 2.
- A placa **sempre liga desarmada** (a menos que o firmware tenha sido compilado com `KECU_AUTO_ARMAR=1`): depois de cada reinício
  o PC precisa mandar `ARMAR` de novo.

```
PC  -> ECU : A5 5A 01 40 06 01 5E 55
ECU -> PC  : A5 5A 01 8F 06 06 7F 2F             (NACK código 6: firmware em modo sombra)
```

### 5.9. `CHAVES` (0x41) → `ACK`

As "chaves virtuais" da bancada: payload de 1 byte, bit 0 = **two-step**, bit 1 = **launch**. A ECU considera a chave ligada se a
entrada física estiver acionada **ou** o bit correspondente estiver ligado aqui (os outros bits são ignorados). Para desligar tudo, mande `0`.
É o que os botões TWO-STEP e LAUNCH do painel fazem. Tamanho diferente de 1: `NACK` 2.

## 6. `NACK` (0x8F)

Payload de 1 byte com o motivo da recusa. O aplicativo transforma o número em uma frase em português
(`Protocolo.descreverErro`).

| Código | Nome | Quando |
|-------:|------|--------|
| 1 | comando desconhecido | `TIPO` que a ECU não conhece |
| 2 | tamanho inválido | payload com tamanho diferente do esperado pelo comando |
| 3 | fora da faixa | valor inválido (parâmetro, mapa ou linha inexistente, eixo não crescente, período de telemetria inválido) |
| 4 | motor girando | operação que só vale com o motor parado |
| 5 | memória | a gravação na memória não volátil falhou |
| 6 | saídas desabilitadas | `ARMAR 1` em firmware compilado sem `KECU_ENABLE_OUTPUTS` |

```
PC  -> ECU : A5 5A 00 7E 07 2D 97                              (comando 0x7E não existe)
ECU -> PC  : A5 5A 01 8F 07 01 A9 6C                           (NACK 1)

PC  -> ECU : A5 5A 10 21 08 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 8C 34     (ESCREVER_LINHA com 16 bytes em vez de 18)
ECU -> PC  : A5 5A 01 8F 08 02 F4 4C                           (NACK 2)
```

## 7. Telemetria (0xF0, 36 bytes)

Enviada sozinha depois do `STREAM`. Todos os valores já saem na unidade indicada, em inteiros (a "escala" é por quanto o valor
físico foi multiplicado; o PC divide). Valores que não cabem no tipo são **saturados** (nunca "dão a volta").

### 7.1. Campos

| Offset | Bytes | Campo | Tipo | Escala | Observação |
|-------:|------:|-------|------|--------|------------|
| 0 | 4 | tempo | `u32` | 1 ms | `millis()` da placa; dá a volta a cada ~49 dias |
| 4 | 2 | rotação | `u16` | 1 rpm | |
| 6 | 2 | velocidade | `u16` | ×10 | km/h |
| 8 | 2 | temperatura da água (ECT) | `s16` | ×10 | °C |
| 10 | 2 | temperatura do ar (IAT) | `s16` | ×10 | °C |
| 12 | 2 | pressão no coletor (MAP) | `u16` | ×10 | kPa absolutos |
| 14 | 2 | pressão do óleo | `u16` | ×10 | bar |
| 16 | 2 | acelerador (TPS) | `u16` | ×10 | % |
| 18 | 2 | lambda | `u16` | ×1000 | 1,000 = estequiométrico |
| 20 | 2 | bateria | `u16` | ×1000 | volts |
| 22 | 2 | avanço de ignição | `s16` | ×10 | graus antes do PMS |
| 24 | 2 | pulso de injeção | `u16` | 1 µs | pulso do ciclo, **com** o tempo morto |
| 26 | 1 | eficiência volumétrica (VE) | `u8` | 1 % | valor da tabela no ponto de operação |
| 27 | 1 | enriquecimento de aceleração | `u8` | 1 % | valor atual (decai até zero) |
| 28 | 1 | abertura da válvula de marcha lenta | `u8` | 1 % | |
| 29 | 1 | posição na tabela, RPM | `u8` | ÷16 | índice fracionário da coluna (0 a 15); o aplicativo divide por 16 |
| 30 | 1 | posição na tabela, carga | `u8` | ÷16 | índice fracionário da linha (0 a 15) |
| 31 | 1 | reservado | `u8` | | sempre 0 |
| 32 | 2 | flags | `u16` | | seção 7.2 |
| 34 | 2 | erros | `u16` | | seção 7.3 |

As posições na tabela (bytes 29 e 30) valem `índice × 16`: por exemplo `0x60` = 96 = índice 6,00. É com elas que o aplicativo
desenha o ponto de operação (a célula que a ECU está lendo agora) na tabela, na superfície 3D e nas curvas.

Exemplo real: motor a 3000 rpm, TPS 25 %, água 85 °C, MAP 55 kPa (firmware em C no PC).

```
A5 5A 24 F0 95 33 0E 00 00 B8 0B 00 00 52 03 2C 01 26 02 00 00 FA 00 E8 03 E8 35 F2 00 A4 10 4A 00 23 60 5D 00 03 0C 00 00 A7 C6
```

Decodificado: tempo 3635 ms, 3000 rpm, 0,0 km/h, água 85,0 °C, ar 30,0 °C, MAP 55,0 kPa, óleo 0,0 bar, TPS 25,0 %, lambda 1,000,
bateria 13,800 V, avanço 24,2°, pulso 4260 µs, VE 74 %, AE 0 %, IAC 35 %, posição 6,00 (rpm) e 5,81 (carga), flags `0x0C03`, erros `0x0000`.

### 7.2. Flags (`u16`)

| Bit | Máscara | Nome | Significado |
|----:|--------:|------|-------------|
| 0 | `0x0001` | `SYNC` | o decodificador achou a falha da roda e está sincronizado |
| 1 | `0x0002` | `MOTOR_GIRANDO` | há sinal de rotação |
| 2 | `0x0004` | `CORTE` | o limitador está cortando agora |
| 3 | `0x0008` | `TWO_STEP` | o two-step está ativo (habilitado **e** chave acionada) |
| 4 | `0x0010` | `LAUNCH` | o launch control está ativo (habilitado, chave acionada, TPS igual ou acima do mínimo e velocidade igual ou abaixo do máximo) |
| 5 | `0x0020` | `AE` | enriquecimento de aceleração em andamento |
| 6 | `0x0040` | `MARCHA_LENTA` | o controle de marcha lenta está regulando |
| 7 | `0x0080` | `ARMADO` | as saídas reais estão armadas |
| 8 | `0x0100` | `CHAVE_TWO_STEP` | a chave de two-step está acionada (física ou virtual) |
| 9 | `0x0200` | `CHAVE_LAUNCH` | a chave de launch está acionada (física ou virtual) |
| 10 | `0x0400` | `MODO_SOMBRA` | firmware compilado **sem** saídas reais |
| 11 | `0x0800` | `SIMULADOR` | quem responde é o simulador ou o firmware no PC, não uma placa |

### 7.3. Erros (`u16`)

| Bit | Máscara | Nome | Significado |
|----:|--------:|------|-------------|
| 0 | `0x0001` | `ECT` | tensão do sensor de água fora da janela (fio solto ou em curto); a ECU usa 80 °C |
| 1 | `0x0002` | `IAT` | idem para o ar; a ECU usa 30 °C |
| 2 | `0x0004` | `MAP` | tensão do sensor de pressão fora da janela; a ECU usa um valor de emergência |
| 3 | `0x0008` | `TPS` | tensão do acelerador fora da janela; a ECU assume 0 % |
| 4 | `0x0010` | `BATERIA` | tensão abaixo de 6 V ou acima de 18 V |
| 5 | `0x0020` | `SEM_ROTACAO` | perdeu a sincronia da roda com o motor girando, nos últimos 2 s |
| 6 | `0x0040` | `NVM` | memória corrompida ao ligar (valem os padrões) ou falha ao gravar |
| 7 | `0x0080` | `LAMBDA` | tensão da sonda fora da janela; a ECU assume lambda 1,0 |

Os sensores de 5 V (MAP, TPS, óleo, lambda) são julgados pela tensão **do sensor** (a do pino multiplicada pela razão do divisor), de modo
que um fio em curto para +5 V acende o erro mesmo que o divisor deixe o pino "dentro da faixa" do conversor. Não existe bit de erro
para o sensor de óleo: fora da janela, a pressão aparece como 0,0 bar.

## 8. Parâmetros (47 bytes)

Ordem, tamanhos e faixas idênticos em `kecu_params.c` (C) e `CampoParametro.java` (Java); o teste de integração confere campo por
campo. Os valores de 2 bytes são `u16` little-endian. "Só parado" significa: com o motor girando, `ESCREVER_PARAMS` que
mude esse campo recebe `NACK` 4 (mudar a roda ou o número de cilindros com o motor girando derrubaria a sincronia).

| # | Nome | Rótulo no aplicativo | Offset | Bytes | Faixa | Padrão | Unidade | Só parado |
|---|------|----------------------|-------:|------:|-------|-------:|---------|:---:|
| 0 | `REV_LIMITE_RPM` | Limite de rotação | 0 | 2 | 1500 a 12000 | 6500 | rpm | não |
| 1 | `REV_HISTERESE_RPM` | Histerese do corte | 2 | 2 | 0 a 2000 | 150 | rpm | não |
| 2 | `TIPO_CORTE` | Tipo de corte (0 combustível, 1 ignição, 2 os dois) | 4 | 1 | 0 a 2 | 2 | — | não |
| 3 | `HAB_TWO_STEP` | Two-step habilitado | 5 | 1 | 0 a 1 | 0 | — | não |
| 4 | `TWO_STEP_RPM` | Rotação do two-step | 6 | 2 | 1000 a 12000 | 4500 | rpm | não |
| 5 | `HAB_LAUNCH` | Launch control habilitado | 8 | 1 | 0 a 1 | 0 | — | não |
| 6 | `LAUNCH_RPM` | Rotação de largada | 9 | 2 | 1000 a 12000 | 3500 | rpm | não |
| 7 | `LAUNCH_TPS_MIN` | TPS mínimo para armar | 11 | 1 | 0 a 100 | 90 | % | não |
| 8 | `LAUNCH_VEL_MAX` | Velocidade máxima para armar | 12 | 1 | 0 a 100 | 10 | km/h | não |
| 9 | `LAUNCH_RETARDO` | Retardo de ignição | 13 | 1 | 0 a 30 | 10 | ° | não |
| 10 | `HAB_AE` | Enriquecimento habilitado | 14 | 1 | 0 a 1 | 1 | — | não |
| 11 | `AE_ENRIQUECIMENTO` | Enriquecimento máximo | 15 | 1 | 0 a 200 | 30 | % | não |
| 12 | `AE_LIMIAR` | Limiar de variação do TPS | 16 | 2 | 0 a 2000 | 150 | %/s | não |
| 13 | `AE_DURACAO_MS` | Duração do enriquecimento | 18 | 2 | 0 a 2000 | 400 | ms | não |
| 14 | `HAB_MARCHA_LENTA` | Controle de marcha lenta | 20 | 1 | 0 a 1 | 1 | — | não |
| 15 | `ML_RPM_QUENTE` | Rotação alvo (motor quente) | 21 | 2 | 400 a 2000 | 850 | rpm | não |
| 16 | `ML_RPM_FRIO` | Rotação alvo (motor frio) | 23 | 2 | 400 a 2500 | 1300 | rpm | não |
| 17 | `ML_TPS_MAX` | TPS máximo para marcha lenta | 25 | 1 | 0 a 20 | 4 | % | não |
| 18 | `ML_KP` | Ganho proporcional (valor real = bruto ÷ 100) | 26 | 1 | 0 a 100 | 5 | %/rpm | não |
| 19 | `ML_KI` | Ganho integral (valor real = bruto ÷ 100) | 27 | 1 | 0 a 100 | 2 | %/rpm·s | não |
| 20 | `ML_DUTY_BASE` | Abertura base da válvula (IAC) | 28 | 1 | 0 a 100 | 35 | % | não |
| 21 | `REQ_FUEL_US` | Pulso base (reqFuel) | 29 | 2 | 500 a 30000 | 8500 | µs | não |
| 22 | `TEMPO_MORTO_US` | Tempo morto do injetor (a 14 V) | 31 | 2 | 0 a 5000 | 900 | µs | não |
| 23 | `TEMPO_MORTO_COEF` | Correção do tempo morto | 33 | 2 | 0 a 1000 | 150 | µs/V | não |
| 24 | `AFR_ESTEQ_X10` | AFR estequiométrico (valor real = bruto ÷ 10) | 35 | 2 | 80 a 180 | 130 | :1 | não |
| 25 | `DWELL_US` | Carga da bobina (dwell) | 37 | 2 | 500 a 8000 | 3000 | µs | não |
| 26 | `CILINDROS` | Cilindros | 39 | 1 | 1 a 8 | 4 | — | **sim** |
| 27 | `DENTES_RODA` | Dentes da roda (pulsos por volta) | 40 | 1 | 2 a 120 | 60 | — | **sim** |
| 28 | `DENTES_FALTANDO` | Dentes faltando | 41 | 1 | 0 a 4 | 2 | — | **sim** |
| 29 | `MODO_SINAL` | Sinal de rotação (0 roda fônica, 1 pulsos uniformes) | 42 | 1 | 0 a 1 | 0 | — | **sim** |
| 30 | `PMS_OFFSET_X10` | Offset do PMS (valor real = bruto ÷ 10) | 43 | 2 | 0 a 3600 | 0 | ° | **sim** |
| 31 | `VSS_PULSOS_KM` | Sensor de velocidade (VSS) | 45 | 2 | 100 a 20000 | 4000 | pulsos/km | não |

Total: 47 bytes. Observações que o código impõe e a tabela não mostra:

- **O planejador de ignição e injeção só funciona com `CILINDROS = 4`** e com roda fônica (`MODO_SINAL = 0`). Com outro valor, ou no modo de pulsos
  uniformes (que só mede a rotação), a ECU continua lendo sensores, calculando e informando tudo, mas **não aciona nenhuma saída**.
- `AFR_ESTEQ_X10` só é usado para mostrar o AFR no aplicativo (AFR = lambda × estequiométrico). O cálculo do pulso não o usa:
  ele entra no pulso através do seu `REQ_FUEL_US` (veja `docs/HARDWARE.md`, seção de calibração).
- A conversão da sonda banda larga (AFR → lambda) divide por 14,7, uma constante de compilação, porque os controladores
  de banda larga costumam entregar o AFR já referenciado à gasolina pura, qualquer que seja o combustível.

## 9. Tabelas e eixos

Três tabelas de 16×16 bytes, `m[mapa][linha][coluna]`: a **linha** é o índice de carga (MAP, do eixo de carga) e a **coluna** é o
índice de rotação (do eixo de RPM). Os bytes viajam "brutos" (0 a 255); a conversão para a grandeza física é a mesma no firmware
(`kecu_mapa_fisico`) e no aplicativo (`TipoMapa`):

| Mapa | Id | Grandeza | Conversão |
|------|---:|----------|-----------|
| Combustível | 0 | eficiência volumétrica (VE), em % | `VE = bruto` |
| Ignição | 1 | avanço, em graus antes do PMS | `avanço = -10 + 0,5 × bruto` (de -10° a +117,5°, passo de 0,5°) |
| Alvo de lambda | 2 | lambda desejado | `lambda = 0,60 + 0,005 × bruto` (de 0,600 a 1,875, passo de 0,005) |

A ECU **interpola bilinearmente** entre as quatro células vizinhas do ponto de operação (rotação e MAP medidos, transformados em
índices fracionários pelos eixos). Fora dos eixos, vale a célula da ponta. As tabelas de fábrica são genéricas e conservadoras (avanço de 6° a 35° conforme rotação e carga,
VE de 34 % a 95 %, lambda-alvo 1,00 nas cargas baixas e médias e 0,98 a 0,88 nas cargas altas) e **não representam o seu motor**: servem só
como ponto de partida para a bancada. Calibre antes de acionar qualquer saída (`docs/HARDWARE.md`).

## 10. Bloco na memória não volátil

`GRAVAR` escreve **um bloco único de 889 bytes** (no ESP32, uma chave `cal` do espaço `kecu` do NVS; no Mega, da posição 0 da EEPROM):

| Offset | Bytes | Conteúdo |
|-------:|------:|----------|
| 0 | 4 | assinatura ASCII `KECU` |
| 4 | 1 | versão do bloco (`1`) |
| 5 | 1 | reservado (`0`) |
| 6 | 2 | tamanho da carga útil, `u16` (`879`) |
| 8 | 47 | parâmetros (a mesma ordem da seção 8) |
| 55 | 64 | eixos (16 RPM `u16`, depois 16 cargas `u16`) |
| 119 | 768 | as três tabelas, na ordem combustível, ignição, lambda; cada uma com 16 linhas de 16 bytes |
| 887 | 2 | CRC-16/CCITT-FALSE dos 887 bytes anteriores, byte baixo primeiro |

Ao ligar, a ECU lê o bloco e só o aceita se **tudo** conferir: assinatura, versão, tamanho, CRC, todos os parâmetros dentro da faixa e
os dois eixos crescentes. Memória vazia (primeira vez): usa os valores de fábrica, sem alarme. Bloco que existe mas não confere: usa os valores
de fábrica **e acende o erro `NVM`**. Como a placa liga sempre com o que foi gravado, uma calibração boa gravada com o motor parado
é o que garante que a ECU volte igual depois de um desligamento.

## 11. Fluxo de uma conexão e temporizações

O que o aplicativo (`ConexaoECU.java`) faz, e os tempos que usa:

1. Abre a porta (115200 8N1). Placas com reinício automático ao abrir a serial (ESP32 e Arduino) levam cerca de 2 s para subir: o aplicativo manda
   `HELLO` até **6 vezes, uma a cada 500 ms**.
2. Confere o `INFO`: versão do protocolo (1), tabelas 16×16 e 3 tabelas. Se não conferir, desconecta com uma mensagem clara.
3. Manda `STREAM 20 ms` e passa ao estado `CONECTADO`.
4. Cada comando seguinte espera a resposta por **700 ms, com até 3 tentativas**; só um comando fica "no ar" por vez. A exceção é o `GRAVAR`
   (até 6 s, 2 tentativas: seção 5.6). `NACK` não é repetido: vira um erro mostrado ao usuário.
5. Se ficar **mais de 1 s sem telemetria**, o estado passa a `SEM_SINAL`; a cada segundo o aplicativo repete o `STREAM` (a placa pode ter reiniciado e voltado
   com a telemetria desligada). Quando o primeiro quadro volta, o estado volta a `CONECTADO`.
6. Se a leitura da porta falhar (cabo USB removido), o estado vira `ERRO` e a porta é fechada.
7. Ao desconectar, manda `STREAM 0` (melhor esforço) e fecha a porta.

## 12. Banda e como repetir os exemplos

A 115200 baud cada byte leva 10 bits (início, 8 de dados, parada), ou seja, cerca de 87 µs. Um quadro de telemetria tem 43 bytes (7 + 36), ou 3,7 ms
no fio; a 20 ms por quadro isso ocupa cerca de 19 % da serial, e a 5 ms (o mínimo permitido) cerca de 75 %. Os comandos de leitura e escrita de
linha (9 e 25 bytes) levam cerca de 3 ms no fio; na prática o que domina é a latência do adaptador USB-serial. Ler ou enviar a calibração
inteira são 50 pedidos (parâmetros, eixos e 3 × 16 linhas), feitos um de cada vez, com a barra de progresso do aplicativo.
Estes números são contas feitas a partir do tamanho dos quadros, não medidas em uma placa de verdade.

Para repetir a captura dos exemplos acima (Linux, macOS ou WSL, com Python 3 e `gcc`):

```
make -C firmware/host
python3 -I docs/exemplos/exemplo_quadros.py firmware/host/build/ecu_host
```

O script abre o `ecu_host` (o núcleo em C do firmware, sem placa), manda `HELLO`, `STREAM`, leituras, comandos recusados e imprime os quadros que
voltam. Serve também de ponto de partida para escrever suas próprias ferramentas de bancada em Python.

## 13. Como o projeto garante que os dois lados concordam

- `firmware/host/test_core.c` testa o núcleo em C (quadros, ruído, comandos, regras de motor parado, memória, limitador...), em três variantes de compilação.
- `TesteSistema.java` testa o CRC, o decodificador (ruído, quadros partidos e colados), o simulador em Java e a conexão com as suas falhas.
- `TesteIntegracaoFirmware.java` liga o aplicativo (`ConexaoECU`) ao **firmware de verdade compilado para o PC** e confere: valores padrão e faixas de cada
  parâmetro, regras com o motor parado e girando, memória (o que `GRAVAR` guarda sobrevive ao reinício), e um **teste diferencial**: o simulador em Java
  e o firmware em C recebem os mesmos comandos e precisam responder igual (inclusive pulso, avanço e histerese do limitador).
- `firmware/teste_placas/` roda o mesmo protocolo contra a camada de cada placa (ESP32 e AVR) em simuladores no PC.

### Para acrescentar algo ao protocolo

- **Um parâmetro novo:** acrescente-o no fim de `kecu_param_id_t` (`kecu_params.h`), na tabela `kecu_param_tab` (`kecu_params.c`) e em `KECU_PARAMS_BYTES`;
  acrescente a constante correspondente em `CampoParametro.java` (na mesma posição, com a mesma faixa e o mesmo padrão). A tela de parâmetros
  é gerada a partir do enum: o campo novo aparece sozinho. Aumente a versão do bloco da memória (`VERSAO_BLOCO` em `kecu_store.c`) porque o tamanho do bloco muda.
  O `TesteIntegracaoFirmware` acusa qualquer diferença entre os dois lados.
- **Um comando novo:** código em `kecu_proto.h` e `Protocolo.java`, tratamento em `tratar()` de `kecu_proto.c`, método em `ConexaoECU.java`, e atualize este documento.
  Se mudar o significado de algo que já existe, aumente `KECU_PROTOCOLO_VERSAO` e `Protocolo.VERSAO`: o aplicativo recusa placa de outra versão.
