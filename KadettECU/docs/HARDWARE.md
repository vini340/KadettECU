# KadettECU — hardware: ligação, calibração e segurança

Este documento explica como ligar a placa (ESP32 ou Arduino Mega) ao Kadett, como conferir cada etapa **antes** de acionar bico ou bobina
e como calibrar a ECU. Ele continua o `README.md` (visão geral), o `docs/PROTOCOLO.md` (a conversa serial byte a byte) e o
`docs/EXPLICACAO.md` (o que foi feito no código e por quê).

> **AVISO — leia antes de soldar qualquer fio.**
>
> - O firmware foi escrito e testado **no PC**. O núcleo em C roda contra simuladores, e o código das duas placas (ESP32 e Mega) foi
>   verificado contra *simuladores das bibliotecas de cada placa*. Ele **nunca foi compilado com as cadeias de ferramentas reais**
>   (xtensa-gcc, avr-gcc) e **nunca rodou em uma placa, em um sensor, em um bico, em uma bobina nem em um motor**. A seção 14 lista tudo
>   que ainda precisa ser validado, e a primeira compilação de verdade pode apontar erros.
> - Os circuitos deste guia são **circuitos de referência**, com as contas feitas, para você **revisar, simular e testar em bancada**. Não
>   são um projeto certificado nem um produto. Valores de componentes são exemplos: confira cada datasheet.
> - Combustível, faísca e 12 V no mesmo lugar podem causar **incêndio, queimadura e acidente**, e uma ECU que desliga o motor numa via pública
>   é um risco para outras pessoas. Enquanto nada disso estiver validado, **o carro continua com a injeção original** e este projeto roda
>   na bancada ou em modo sombra (seção 3.4).

## Sumário

1. [Segurança: leia primeiro](#1-segurança-leia-primeiro)
2. [Visão geral e limites do firmware](#2-visão-geral-e-limites-do-firmware)
3. [O seu carro e o que o firmware exige](#3-o-seu-carro-e-o-que-o-firmware-exige)
4. [Placas suportadas](#4-placas-suportadas)
5. [Pinagem](#5-pinagem)
6. [Alimentação, terra e proteção](#6-alimentação-terra-e-proteção)
7. [Entradas digitais: rotação, velocidade e chaves](#7-entradas-digitais-rotação-velocidade-e-chaves)
8. [Entradas analógicas: sensores](#8-entradas-analógicas-sensores)
9. [Saídas de potência: bicos, bobina e marcha lenta](#9-saídas-de-potência-bicos-bobina-e-marcha-lenta)
10. [Compilar, gravar e configurar](#10-compilar-gravar-e-configurar)
11. [Teste de bancada, passo a passo](#11-teste-de-bancada-passo-a-passo)
12. [Roteiro de calibração (da primeira partida à estrada)](#12-roteiro-de-calibração-da-primeira-partida-à-estrada)
13. [Armadilhas conhecidas](#13-armadilhas-conhecidas)
14. [O que ainda precisa ser validado](#14-o-que-ainda-precisa-ser-validado)
15. [Fontes consultadas](#15-fontes-consultadas)

---

## 1. Segurança: leia primeiro

### 1.1. As camadas de proteção do projeto (e o que elas não cobrem)

O projeto foi desenhado para que o erro mais provável (um bug, um fio solto, uma placa que reinicia) **não ligue um bico nem a bobina**:

| Camada | Onde está | O que faz |
|--------|-----------|-----------|
| **Modo sombra** (padrão) | `KECU_ENABLE_OUTPUTS=0` em `kecu_config.h` e nos ambientes `esp32dev` e `megaatmega2560` | O firmware lê sensores, calcula pulso e avanço e fala com o aplicativo, mas **nenhum pino de saída é configurado** (ficam como entrada, em alta impedância). `ARMAR` recebe `NACK 6`. |
| **Armar** | comando `ARMAR` (botão do aplicativo, com confirmação) | Mesmo com saídas compiladas, nada é acionado até alguém armar. Ao ligar, a ECU está **desarmada** (a menos que você compile com `KECU_AUTO_ARMAR=1`). Desarmar vale na hora e cancela os pulsos já agendados. |
| **Resistor de 10 kΩ no gate** | seu hardware (seção 9) | Se a placa reiniciar, travar ou ainda estiver iniciando, o pino fica solto e o resistor mantém o MOSFET/IGBT **desligado**. Por isso o nível ativo padrão é **alto**. |
| **Limites do agendador** | `kecu_sched.h` | Nenhum pulso passa de 60 ms (`KECU_SCHED_LARGURA_MAX_US`); um vigia no laço principal desliga à força qualquer saída ligada por mais tempo que isso; fila cheia descarta pulso novo, nunca mantém saída ligada. |
| **Cão de guarda** (2 s) | `hal_esp32.cpp`, `hal_avr.cpp` | Se o laço principal travar, a placa reinicia (e o pull-down desliga os estágios). No Mega, o cão só liga junto com as saídas habilitadas. |
| **Faixas e NACK** | `kecu_params.c`, `kecu_proto.c` | Parâmetro fora da faixa é recusado inteiro; os campos da roda, do offset do PMS e do número de cilindros só mudam com o motor parado. |

**Nada disso substitui** fusível, chave geral, extintor e bom senso. Em particular, o firmware **não sabe** se o seu estágio de potência
está em curto, se a bobina está superaquecendo, se há vazamento de combustível, ou se a bomba de gasolina está ligada com o motor parado.

### 1.2. Regras práticas

1. **Bateria desligada** (negativo desconectado) sempre que for soldar, crimpar ou mudar a ligação.
2. **Fusível** no positivo, perto da bateria, para cada ramo (eletrônica da ECU, bicos, bobina). Dimensione pela corrente somada de verdade, não "pelo que tinha na gaveta".
3. **Chave geral** (corta o positivo do estágio de potência) ao alcance do motorista, além do botão DESARMAR do aplicativo.
4. **Bomba de combustível**: este projeto **não a controla** (seção 9.5). Ela precisa de um relé com corte de segurança (só funciona com o motor girando, ou por poucos segundos ao ligar a ignição) e, de preferência, de um interruptor de inércia.
5. **Primeiro teste de tudo com carga falsa** (lâmpada no lugar do bico; faísqueiro de bancada no lugar do motor), **sem combustível** por perto.
6. Extintor de pó ABC, ventilação (nunca em garagem fechada), nada de faísca perto de vapor de gasolina, óculos e luvas.
7. **Nunca** use este projeto como único sistema de um carro que circula em via pública antes de concluir a seção 14 e um período de testes em pista fechada ou dinamômetro.
8. Modificar o sistema de injeção de um veículo pode ter consequências legais, de garantia e de seguro. Isso está fora do escopo deste texto: informe-se antes.

---

## 2. Visão geral e limites do firmware

```
 NOTEBOOK                                   PLACA (ESP32 ou Mega 2560)                             CARRO
┌───────────────────────┐   USB (serial    ┌────────────────────────────────────┐
│ KadettECU (Java)      │   115200 8N1,    │ firmware em C                      │◄── CKP (roda fônica) ◄── condicionamento ◄── sensor Hall/VR
│  painel, mapas,       │◄───protocolo────►│  decodifica a roda, lê sensores,   │◄── VSS (velocidade)
│  parâmetros, registro │   binário        │  calcula pulso e avanço, agenda    │◄── ECT, IAT, MAP, TPS, bateria, óleo, lambda (ADC)
└───────────────────────┘                  │  as bordas dos pinos               │◄── chaves two-step e launch
                                           │                                    │──► INJ1..INJ4 ──► driver MOSFET ──► bicos
                                           │                                    │──► BOBINA1..2 ──► driver de ignição ──► bobina
                                           │                                    │──► IAC (PWM)  ──► driver ──► válvula de marcha lenta
                                           └────────────────────────────────────┘
```

O aplicativo e a placa conversam pelo protocolo do `docs/PROTOCOLO.md`. Depois de **armada** (ou compilada com `KECU_AUTO_ARMAR=1`), a ECU trabalha
**sozinha**: o notebook serve para ver e calibrar, não para controlar o motor, e se o cabo USB sair (com a placa alimentada pelo carro) o motor continua.
O contrário é que preocupa: **abrir a porta serial pode reiniciar a placa** (seção 13.1).

### 2.1. O que está implementado e o que não está

| Função | Situação |
|--------|----------|
| Leitura de ECT, IAT, MAP, TPS, bateria, pressão de óleo e lambda (banda larga), com filtro e detecção de fio solto/em curto | **sim** |
| Rotação por roda fônica (N dentes, de 0 a 4 faltando; ex.: 60-2, 36-1) e por pulsos uniformes (só mede rotação) | **sim** |
| Velocidade do veículo (VSS) | **sim** |
| Mapas 16×16 de combustível (VE), ignição e lambda-alvo, com interpolação bilinear; eixos de RPM e MAP editáveis | **sim** |
| Pulso do bico = `ReqFuel × VE × MAP × (densidade do ar) ÷ lambda-alvo × aquecimento × enriquecimento`, mais tempo morto corrigido pela bateria | **sim** |
| Enriquecimento de aceleração, enriquecimento de partida e de aquecimento (este último fixo no firmware: +0,5 % por °C abaixo de 80 °C) | **sim** |
| Limitador de rotação com histerese (corta combustível, ignição ou os dois), two-step e launch control | **sim** |
| Marcha lenta por válvula **PWM** (controle PI com alvo pela temperatura) | **sim** — só para válvula de PWM |
| Injeção **simultânea** (todos os bicos juntos), **uma vez por volta** do virabrequim (2 por ciclo de 4 tempos), em até 4 bicos | **sim** |
| Ignição de 4 cilindros: **uma bobina + distribuidor** (padrão) ou **duas bobinas de centelha perdida** (`-DKECU_BOBINAS=2`) | **sim** |
| Correção do dwell e do tempo morto pela tensão da bateria | **sim** |
| Injeção sequencial, sensor de fase (comando), bobina por cilindro | **não** |
| Controle de lambda em **malha fechada** (a lambda só é lida e mostrada) | **não** |
| Sensor de detonação | **não** |
| Motor de passo na marcha lenta (o do Multec) | **não** |
| Bico de **baixa impedância** com controle de corrente (*peak-and-hold*) | **não** (precisa de driver externo; seção 9.2) |
| Controle da bomba de combustível, ventoinha, ar-condicionado, turbo | **não** |
| CAN, Wi-Fi, Bluetooth | **não** |

---

## 3. O seu carro e o que o firmware exige

### 3.1. O que consegui apurar sobre o Kadett GL 2.0 de 1996

Não encontrei um documento de fábrica sobre o GL 2.0 de 1996. O que existe é indireto, e por isso **vale como pista, não como fato sobre o seu carro**:

- Um artigo de história do modelo diz que o Kadett GSi 2.0 de 1993 usava injeção **multiponto** (Bosch LE-Jetronic, ignição mapeada EZK), que a injeção **monoponto** (um único bico central, *TBI*) equipava as versões SL e SL/E, que em 1994 elas deram lugar às GL e GLS (sem dizer o sistema de injeção), que em **1996** o Sport mantinha a injeção **monoponto** no 2.0 e que só em **1997** a linha 2.0 passou à injeção multiponto digital (*Powertech*) em todas as versões, inclusive a GL [F1].
- Um catálogo de peças de reposição lista um bico injetor **monoponto** para o "Kadett 2.0 8v, de 1991 até 1996" [F2].
- Um texto de oficina sobre um Kadett 1.8 EFI de 1996 com sistema **Multec 700** diz que o **distribuidor** leva o sensor de rotação e o **módulo HEI**, que o módulo envia o sinal de rotação para a UCM (a central) e que a UCM tem terminais para um **motor de passo** (a marcha lenta) [F3].

Juntando as pistas, um GL 2.0 de 1996 **provavelmente** é monoponto (TBI) com Multec, distribuidor com módulo HEI e marcha lenta por motor de passo. Mas isso pode variar por lote, mercado ou
adaptação, então **confirme tudo no seu carro** (seção 3.2) antes de projetar qualquer ligação.

### 3.2. Como confirmar no carro (checklist)

| O que verificar | Como | Por que importa |
|-----------------|------|-----------------|
| Quantos bicos? | Um bico no corpo de borboleta (monoponto) ou quatro no coletor (multiponto) | Define quantas saídas de bico usar (seção 9.2) e o `ReqFuel` |
| **Resistência do bico** | Multímetro entre os dois terminais, bico **frio** e desligado: algo entre 8 e 16 Ω é *alta impedância*; entre 0,5 e 5 Ω é *baixa impedância* [F8] | Baixa impedância **não** pode ser ligada direto neste projeto (seção 9.2) |
| De onde vem o sinal de rotação? | Distribuidor (módulo HEI ou captador) ou um sensor olhando uma roda dentada na polia/volante | Decide se o firmware poderá acionar bico e bobina (seção 3.3) |
| Existe roda fônica no virabrequim? | Procure um sensor apontado para a polia ou o volante e conte os dentes / veja se há uma falha | idem |
| Marcha lenta | Motor de passo (vários fios, na carcaça do corpo de borboleta) ou válvula de duas vias (solenoide) | Só a válvula PWM é suportada (seção 9.4) |
| Sensores | MAP (mangueira de vácuo), TPS (potenciômetro, 3 fios), NTC de água e de ar (2 fios), sonda lambda | Cada sensor precisa de curva e circuito (seção 8) |
| Bobina e módulo de ignição | Bobina com distribuidor? Módulo HEI separado ou dentro do distribuidor? | Seção 9.3 |
| Diagrama elétrico e manual do **seu** carro | Manual de oficina do modelo e ano | Pinos, cores e valores de verdade |

### 3.3. A restrição que decide tudo: o que o firmware comanda e com qual sinal

O firmware só **aciona bico e bobina** quando enxerga uma **roda fônica no virabrequim** (com 4 cilindros). Isso vem do planejador (`kecu_engine.c`): ele
precisa saber o ângulo do virabrequim a cada dente para decidir quando abrir o bico e quando começar a carregar a bobina.

| Situação | O que o firmware faz | Bico e bobina |
|----------|----------------------|---------------|
| **A.** Só o sinal do distribuidor (`MODO_SINAL = 1`, `DENTES_RODA` = pulsos por volta; num 4 cilindros, 2) | Mede a rotação, lê todos os sensores, **calcula** pulso e avanço, mostra tudo no aplicativo | **Nenhuma saída é acionada**: serve como **registrador de dados** (modo sombra) |
| **B.** Roda fônica no virabrequim (60-2, 36-1 etc., `MODO_SINAL = 0`), 4 cilindros | Tudo acima, mais o comando de bicos e bobina | Injeção simultânea (1 injeção por volta do virabrequim, todos os bicos juntos); ignição por 1 bobina + distribuidor ou 2 bobinas (centelha perdida) |
| **C.** Injeção sequencial, sensor de fase, bobina por cilindro, mais ou menos que 4 cilindros | Não suportado | — |

Instalar uma roda fônica é um **trabalho mecânico sério** (a roda precisa ser concêntrica e estar firme, e o suporte do sensor precisa ser rígido e ter a folga certa). Se a ideia é acionar bico e bobina neste carro, esse é o primeiro grande passo físico do projeto.

### 3.4. Caminho recomendado (do mais seguro ao mais arriscado)

| Fase | O que fazer | Risco |
|------|-------------|-------|
| 0 | **Simulador** (já funciona no notebook, sem hardware): aprender o painel, os mapas e o protocolo | nenhum |
| 1 | **Bancada**: placa em modo sombra, com sinais gerados (gerador de função, ou outra placa imitando a roda fônica), potenciômetros no lugar dos sensores. Validar leituras, rotação, gravação e protocolo (seção 11) | baixo |
| 2 | **Estágio de potência na bancada com cargas falsas** (lâmpadas, resistores, faísqueiro) e firmware com saídas, **sem combustível e sem motor** | médio (12 V, calor) |
| 3 | **No carro, só leitura** (modo sombra), com a injeção original comandando: registrar rotação, bateria, MAP, TPS. Só use ligações de **alta impedância** (seguidor de tensão ou resistor em série) para não perturbar a ECU de fábrica; **não** ligue dois sensores no mesmo fio sem um buffer, nem use NTC compartilhado (a fórmula do firmware supõe o resistor de *pull-up* dele) | médio |
| 4 | Roda fônica instalada e sensor condicionado; conferir sincronia e `PMS_OFFSET` com **luz estroboscópica**, ainda com a injeção original | médio |
| 5 | **Troca da ECU**: saídas armadas, partida, calibração em pista fechada ou dinamômetro (seção 12) | **alto** |
| 6 | Uso na rua | só depois da seção 14 |

---

## 4. Placas suportadas

| | **ESP32** (DevKit de 30 ou 38 pinos) | **Arduino Mega 2560** |
|---|---|---|
| Núcleo | 2 × Xtensa, até 240 MHz, com unidade de ponto flutuante | AVR de 8 bits, 16 MHz, **sem** divisor nem ponto flutuante em hardware |
| RAM | 520 KB | 8 KB |
| Lógica | **3,3 V**. **Nunca** ligue 5 V ou 12 V direto num pino | 5 V |
| ADC | 12 bits, 0 a ~3,3 V (atenuação de 11 dB); sem calibração o erro entre chips é de **±6 %** [F5] | 10 bits, 0 a 5 V (referência = AVcc) |
| Memória permanente | NVS (flash): rápida (não medida), mas **bloqueia o laço** enquanto grava | EEPROM de 4 KB; um bloco novo leva **~3 s** |
| Papel | **recomendada para o carro** | bancada, aprendizado, rotações baixas |
| Estado da verificação | HAL testada contra um simulador das APIs do Arduino-ESP32 2.x e 3.x | HAL testada contra um simulador dos registradores do ATmega2560 |
| Ainda não verificado | compilação com a cadeia real; temporização com osciloscópio | compilação com a cadeia real; **tempo de execução do planejador** (veja abaixo) |

- **Arduino Uno e Nano não servem**: 2 KB de RAM não comportam o núcleo (calibração, mapas e buffers do protocolo).
- **Por que o Mega é só para bancada**: o planejador roda **dentro da interrupção do dente** (só com inteiros, sem ponto flutuante) e faz **uma dezena ou mais de
  divisões e restos de 32 bits por dente**. O AVR não tem divisor em hardware: cada divisão vira uma rotina de software que, por estimativa (**não medida**), leva dezenas de microssegundos.
  Já o intervalo entre dois dentes vizinhos de uma roda 60-2 é de 1250 µs a 800 rpm, 333 µs a 3000 rpm e 154 µs a 6500 rpm (conta na seção 7.1.2). Se a estimativa estiver certa, o Mega dá conta da marcha lenta
  e de rotações baixas, mas **pode não acompanhar os dentes acima de uns 2000 a 3000 rpm**. **Meça antes de confiar**: ligue um pino no começo da interrupção e desligue no fim e olhe no osciloscópio;
  a duração tem de ficar bem abaixo do menor intervalo entre dentes na rotação máxima do motor. Com uma roda 36-1 o intervalo é ~65 % maior que o de uma 60-2 (256 µs a 6500 rpm).
- **STM32 e outras placas**: o núcleo não depende de nenhuma placa; ele só chama as funções de `kecu_hal.h` (relógio, ADC, serial, memória, PWM, seção crítica, pulsos). Para portar, escreva um arquivo que implemente essas funções (use `hal_esp32.cpp`, `hal_avr.cpp` e `host/hal_host.c` como modelo). **Não há HAL de STM32 neste projeto.**

---

## 5. Pinagem

A pinagem fica em `firmware/src/pinagem.h`; **mude lá** se a sua ligação for outra. A ordem das saídas e dos canais analógicos é a dos enums
`kecu_saida_t` e `kecu_adc_canal_t` (`kecu_hal.h`).

### 5.1. ESP32 (DevKit de 30 ou 38 pinos, módulo WROOM-32)

| Função | GPIO | Tipo | Observação |
|--------|-----:|------|------------|
| CKP (rotação) | **18** | entrada, interrupção na borda de subida | 3,3 V, já condicionado (seção 7.1) |
| VSS (velocidade) | **19** | entrada, interrupção na borda de subida | 3,3 V (seção 7.2) |
| Chave *two-step* | **16** | entrada com pull-up interno; chave para o GND | **atenção ao WROVER** (veja abaixo) |
| Chave *launch* | **17** | idem | idem |
| Válvula de marcha lenta (PWM) | **4** | saída LEDC, 200 Hz, 10 bits | só com saídas habilitadas; sempre por driver |
| Bico 1, 2, 3, 4 | **21, 22, 23, 13** | saída digital | MOSFET com pull-down de 10 kΩ (seção 9.2) |
| Bobina 1, 2 | **25, 26** | saída digital | driver de ignição com pull-down (seção 9.3) |
| ECT (água) | **36** (VP) | ADC1, canal 0 | NTC (seção 8.4) |
| IAT (ar) | **39** (VN) | ADC1, canal 3 | NTC |
| MAP | **34** | ADC1, canal 6 | sensor de 5 V → divisor (seção 8.3) |
| TPS | **35** | ADC1, canal 7 | idem |
| Bateria | **32** | ADC1, canal 4 | divisor (seção 8.7) |
| Pressão de óleo | **33** | ADC1, canal 5 | idem |
| Lambda (banda larga) | **27** | ADC2, canal 7 | idem; o ADC2 não convive com o Wi-Fi (não usado aqui) |

Notas:

- **Pinos de strapping (GPIO0, 2, 5, 12 e 15) não são usados**: eles definem o modo de boot e, no caso do GPIO12, a tensão da flash — um nível alto no GPIO12 na partida
  coloca o VDD_SDIO em 1,8 V e pode impedir o boot [F4][F5]. Mantenha esses pinos livres de circuitos externos.
- GPIO6 a 11 são da flash; GPIO1 e 3 são a serial USB. Nunca use.
- GPIO34, 35, 36 e 39 só podem ser entrada e não têm pull-up nem pull-down internos (servem bem para o ADC).
- **GPIO16 e GPIO17 são da PSRAM nos módulos ESP32-WROVER e não podem ter outra função** [F6]. A pinagem padrão usa os dois para as chaves *two-step* e *launch*: se a sua placa for
  WROVER (ou qualquer módulo com PSRAM), troque `PIN_TWO_STEP` e `PIN_LAUNCH` em `pinagem.h`. As placas com WROOM-32 (as mais comuns) não têm o problema.
- Em **modo sombra** os pinos de saída (21, 22, 23, 13, 25, 26 e 4) **ficam como entrada**, soltos: com o resistor de 10 kΩ no gate eles leem 0 V.
- Ao ligar ou reiniciar, a ROM do ESP32 escreve texto na serial. O aplicativo não se importa: o decodificador descarta o que não começa pelo marcador `A5 5A`.

### 5.2. Arduino Mega 2560

| Função | Pino | Tipo | Observação |
|--------|-----:|------|------------|
| CKP (rotação) | **2** (INT4) | entrada, interrupção na borda de subida | 5 V |
| VSS (velocidade) | **3** (INT5) | entrada, interrupção na borda de subida | 5 V |
| Chave *two-step* | **30** | entrada com pull-up interno | chave para o GND |
| Chave *launch* | **31** | idem | |
| Válvula de marcha lenta (PWM) | **9** (OC2B, Timer2) | saída PWM, ~245 Hz | só com saídas habilitadas |
| Bico 1, 2, 3, 4 | **22, 23, 24, 25** (PA0 a PA3) | saída digital | MOSFET com pull-down de 10 kΩ |
| Bobina 1, 2 | **26, 27** (PA4, PA5) | saída digital | driver de ignição com pull-down |
| ECT, IAT, MAP, TPS, bateria, óleo, lambda | **A0 a A6** | ADC de 10 bits, referência AVcc | veja `KECU_VREF_MV` (seção 10) |

Notas:

- As seis saídas ficam todas na **PORTA** para mudar com **uma instrução de porta** dentro da interrupção. Se você trocar os pinos de saída do Mega, terá de mexer em `hal_avr.cpp`.
- **Não use os pinos 11, 12 e 13** (o Timer1 pertence ao agendador de pulsos) e **0 e 1** (serial USB). O pino 9 usa o Timer2; um `analogWrite` em outro pino do Timer1 ou do Timer2 mudaria a base de tempo.

### 5.3. Mudar a pinagem

Edite `pinagem.h` e recompile. Em qualquer placa, uma saída precisa de um pino que aceite ser saída, e as entradas de interrupção precisam de pinos com interrupção
externa (no Mega, só 2, 3, 18, 19, 20 e 21).

---

## 6. Alimentação, terra e proteção

### 6.1. Alimentação da placa

O 12 V de um carro não é "12 V": vai de **6 V ou menos durante a partida** (bateria fraca ou fria) a mais de **14 V com o alternador**, com picos de dezenas de volts quando uma carga
indutiva é desligada. A eletrônica precisa sobreviver a isso **e continuar funcionando durante a partida** (é justamente quando a ECU mais trabalha).

```
 +12 V da bateria, após a chave de ignição e o relé principal
   │
 [fusível 3 A]── [proteção contra inversão: diodo Schottky em série, ou P-MOSFET] ──┬── [TVS 600 W, ex. família SMBJ18A] ── GND
                                                                                    │
                                                              [filtro LC: indutor + capacitor eletrolítico + 100 nF]
                                                                                    │
                                                      [conversor buck automotivo de entrada larga → 5 V]   (não use 7805: precisa de ~7 V na entrada)
                                                                                    │
                       ┌────────────────────────────────────────────────┬───────────┴────────────────────┐
              5 V da placa (pino 5V/VIN do DevKit,                5 V dos sensores                (o ESP32 gera os 3,3 V
              ou o 5 V do Mega)                                   ("5V_SENS": regulador ou          com o regulador da própria
                                                                  filtro separado, limpo)           placa)
```

- O regulador da própria placa de desenvolvimento (o do DevKit, o do conector de energia da placa Mega) **não é de uso automotivo**: alimente a placa com 5 V já limpos e protegidos.
- Mantenha um **capacitor de reserva** (centenas de µF) depois do conversor, para atravessar quedas curtas na partida.
- A tensão que alimenta os sensores de 5 V (`5V_SENS`) é a **referência** de MAP, TPS e óleo: o firmware lê tensão absoluta, então um 5 V que oscila vira erro de leitura. No Mega, de preferência alimente os sensores do **mesmo** 5 V que é o AVcc: assim a leitura vira proporcional (a variação se cancela).
- Alimentação pela USB do notebook **só na bancada**. Se a placa tiver as duas fontes ligadas ao mesmo tempo (carro e USB), confira o esquema dela: sem um diodo de isolamento, o 5 V do carro pode voltar pela porta USB do notebook.

### 6.2. O notebook e o terra do carro

A USB liga o terra do notebook ao terra da placa, que é o terra do carro. Com o notebook no carregador (ligado à tomada, com fio terra) e a placa no chassi do carro podem circular correntes pelo cabo USB, e
o ruído do carro chega ao notebook. Preferências, da mais segura para a menos:

1. Notebook **na bateria** (sem carregador) enquanto estiver ligado à placa.
2. Um **isolador de USB** (com isolação galvânica) entre o notebook e a placa. Esse é o recomendado para uso com o motor funcionando.
3. Cabo USB curto, de boa qualidade, com ferrite, longe dos cabos de vela e dos bicos.

### 6.3. Terra e fiação

- **Terra em estrela**: um ponto de terra de sinal (sensores, ADC) e um ponto de terra de potência (bicos, bobina, válvula), unidos perto da placa e ligados ao negativo da bateria ou ao bloco do motor por fios **curtos e grossos** (≥ 1,5 mm² para a potência).
- O fio de retorno dos sensores vai **à ECU**, não ao chassi mais próximo: o chassi tem quedas de tensão que viram erro de leitura.
- Sinais de rotação (indutivo ou Hall) em **par trançado blindado**, com a malha aterrada **só na ponta da ECU**, longe dos cabos de vela, da bobina e dos bicos.
- Cabos de vela **resistivos** e, se o ruído insistir, caixa metálica aterrada para a eletrônica.
- Conectores automotivos (vedados, com trava) na fiação do motor. Um conector que afrouxa vira "sensor com defeito" ou, no estágio de potência, aquecimento.

---

## 7. Entradas digitais: rotação, velocidade e chaves

### 7.1. Sinal de rotação (CKP)

#### 7.1.1. O que o firmware espera

- No pino CKP chega uma **onda quadrada limpa** (0 a 3,3 V no ESP32, 0 a 5 V no Mega). O firmware conta **uma borda por dente**: a de subida (padrão) ou a de descida, com `-DKECU_CKP_BORDA_SUBIDA=0`.
  A borda usada é sempre a mesma, então a escolha só desloca o `PMS_OFFSET`, que se acerta na calibração.
- **Não há filtro de ruído no software** para esse pino (ele está numa interrupção): uma borda falsa vira um "dente a mais" e derruba a sincronia. O condicionamento tem de entregar um sinal limpo.
- Parâmetros da roda: `DENTES_RODA` (dentes **contando** os que faltam, por volta), `DENTES_FALTANDO` (0 a 4) e `MODO_SINAL` (0 = roda fônica, 1 = pulsos uniformes). Na **60-2**, 60 e 2; na **36-1**, 36 e 1.
- A **falha** é achada quando o intervalo entre dois dentes passa de 0,75 × (faltando + 1) × o intervalo anterior (na 60-2, mais de 2,25 vezes). O primeiro dente **depois** da falha é o dente 0, e o ângulo de tudo é contado a partir dele.
- A ECU só considera a sincronia depois de **ver a falha no lugar certo uma segunda vez** (cerca de duas voltas). O planejamento de bico e bobina exige sincronia e rotação de pelo menos 100 rpm.
- Sem nenhum dente por 250 ms, o motor é considerado parado.

#### 7.1.2. Quanto tempo a interrupção tem

O intervalo entre dois dentes vizinhos é `60 000 000 ÷ (rpm × dentes)` µs. É o tempo máximo que a interrupção do dente (decodificador + planejador) pode gastar:

| Roda | 800 rpm (marcha lenta) | 3000 rpm | 6500 rpm |
|------|-----------------------:|---------:|---------:|
| **60-2** (6° por dente) | 1250 µs (800 Hz) | 333 µs (3 kHz) | 154 µs (6,5 kHz) |
| **36-1** (10° por dente) | 2083 µs (480 Hz) | 556 µs (1,8 kHz) | 256 µs (3,9 kHz) |

No ESP32 isso não é problema. No Mega, **meça** (seção 4).

#### 7.1.3. A roda fônica

- A roda vai **no virabrequim** (polia ou volante), não no comando: o firmware conta 360° por volta, e o motor de quatro tempos faz 720° por ciclo. O distribuidor (ou a centelha perdida) resolve qual cilindro recebe a centelha.
- A **60-2** é a padrão do projeto; a 36-1 é igualmente comum e mais fácil para placas lentas. O firmware aceita de 2 a 120 dentes e de 0 a 4 faltando.
- Anote, na hora de montar, **o ângulo entre a falha e o PMS do cilindro 1**: é o ponto de partida do `PMS_OFFSET` (seção 12.2).
- A folga entre o sensor e os dentes é a do datasheet do sensor (costuma ficar em torno de 1 mm): folga demais perde o sinal em baixa rotação, folga de menos bate no dente.

#### 7.1.4. Sensor Hall (saída digital)

```
 +5 V "SENS" ─[ 4,7 kΩ ]─┐                       ESP32: o buffer é alimentado com 3,3 V; a entrada do 74LVC1G17 aceita 5 V.
                         ├─[ 1 kΩ ]─┬─ 74LVC1G17 ─►  GPIO18            Mega: pode ligar direto (com o buffer alimentado a 5 V
 saída do sensor ────────┘          │  (Schmitt)                          ou sem buffer).
 (coletor aberto)                [ 1 nF ]
                                    │
                                   GND
```

- O sensor Hall costuma ter saída de **coletor aberto**: precisa de um resistor de pull-up (4,7 kΩ a 10 kΩ) para o 5 V.
- O **Schmitt trigger** (por exemplo, o 74LVC1G17, que aceita 5 V na entrada mesmo alimentado com 3,3 V) limpa a borda e dá histerese contra ruído; o resistor de 1 kΩ com o capacitor de 1 nF forma um filtro de ~1 µs, que não atrapalha (o menor intervalo entre dentes é de 154 µs).
- Alimente o Hall com a tensão que o datasheet mandar (muitos aceitam 5 V). Se ele for de 12 V, **não** ligue a saída direto na placa: o pull-up vai ao 5 V, como acima.

#### 7.1.5. Sensor indutivo (VR, dois fios)

O sensor indutivo (relutância variável) é uma **bobina**: gera uma tensão alternada que cresce com a rotação, de algumas centenas de milivolts na partida a **dezenas de volts** em rotação alta, e com a polaridade
trocada se os dois fios forem invertidos. Um simples comparador ou um zener erra em baixa rotação e se destrói em alta. A solução é um **circuito integrado de interface** com limiar adaptativo ao pico.

- Exemplo: a família **MAX9924–MAX9927** (Analog Devices/Maxim): interface para sensores de relutância variável, com entrada diferencial e limiar de pico adaptativo, para virabrequim, comando e velocidade, de −40 a +125 °C no MAX9926U [F7]. **Siga o circuito típico de aplicação do datasheet** (valores dos resistores e capacitores de entrada dependem do seu sensor).
- A saída desses circuitos é lógica de 5 V: no ESP32 passe por um Schmitt alimentado com 3,3 V (74LVC1G17) ou por um divisor; no Mega, direto.
- Cabo **blindado**, malha aterrada só na ponta da ECU. Se a roda não sincronizar, **troque os fios do sensor** (a polaridade muda a borda que o circuito vê) e meça com o osciloscópio.
- Há também módulos prontos de condicionamento VR→digital: se usar um, meça a saída com osciloscópio antes de ligar na placa.

#### 7.1.6. Se o carro só tem o sinal do distribuidor (cenário A, seção 3.3)

- Configure `MODO_SINAL = 1` e `DENTES_RODA` = pulsos por volta do virabrequim (num 4 cilindros com um pulso por cilindro e por ciclo, **2**). O firmware **mede a rotação** e mostra tudo, mas não aciona bico nem bobina.
- **Não presuma** o formato do sinal do módulo HEI: meça com osciloscópio (nível, polaridade, largura do pulso) antes de ligar, e use sempre um resistor em série (10 kΩ) e o Schmitt, para não carregar a saída do módulo nem sobrecarregar o pino.

### 7.2. Velocidade do veículo (VSS)

- Descubra o tipo do sensor de velocidade do seu carro (Hall, interruptor de lâmina ou indutivo) medindo-o com multímetro em modo de frequência ou com osciloscópio; condicione-o como a CKP (seção 7.1.4 ou 7.1.5).
- `VSS_PULSOS_KM` (padrão **4000** pulsos por quilômetro; só um exemplo) transforma a frequência em km/h. Para calibrar, ande a velocidade constante com o GPS do celular e corrija: **novo = antigo × (velocidade mostrada ÷ velocidade real)**.
  Se o painel mostra mais que o GPS, o número de pulsos por km está pequeno demais.
- Sem pulso por 1,5 s, a velocidade vale 0.

### 7.3. Chaves *two-step* e *launch*

- Uma chave entre o pino e o **GND**. O pull-up interno mantém o pino em nível alto; **nível baixo = acionada**.
- O firmware exige quatro leituras iguais seguidas (uma a cada 5 ms, ou seja, 20 ms) para aceitar a mudança: não precisa de filtro externo contra repique.
- Na cabine os fios são longos e recebem ruído: ponha 1 kΩ em série, 100 nF para o GND no pino e, se a chave for de 12 V, use um optoacoplador.
- O aplicativo também aciona as duas chaves (`CHAVES`); a ECU usa **a chave física OU a do aplicativo**. O *launch* só atua com `HAB_LAUNCH` ligado, a chave acionada, TPS acima de `LAUNCH_TPS_MIN` **e** velocidade abaixo de `LAUNCH_VEL_MAX`; o *two-step* só atua com `HAB_TWO_STEP` ligado e a chave acionada.

---

## 8. Entradas analógicas: sensores

### 8.1. Como o firmware trata cada leitura

- A HAL entrega a **tensão no pino**, em mV, com média de algumas amostras (4 no ESP32, 1 no Mega); o núcleo multiplica pela razão do divisor e converte pela curva do sensor (`kecu_config.h`).
- **Detecção de fio solto ou em curto**: o NTC é "válido" com 80 mV a (referência − 80 mV) no pino; os sensores de 5 V, com 80 mV a 4920 mV **no sensor** (já considerando o divisor, para que um fio em curto com o 5 V não passe por "100 %"); a bateria, de 6 a 18 V. Fora disso a leitura é marcada com erro (`erros` da telemetria) e entra um valor de emergência.
- **Valores de emergência**: ECT 80 °C, IAT 30 °C, MAP 60 kPa + 0,4 × TPS, TPS 0 %, bateria limitada a 6–18 V, lambda 1,0. A pressão de óleo fora da janela vale 0 e **não** tem bit de erro.
- **Filtros** (a cada 2 ms, fator de suavização por amostra): ECT e IAT 0,01 (bem lentos), MAP 0,35, TPS 0,5, bateria e óleo 0,05, lambda 0,2.

### 8.2. Resumo dos canais

| Canal | Sensor | Circuito (ESP32) | Circuito (Mega) | Constantes em `kecu_config.h` |
|-------|--------|------------------|-----------------|-------------------------------|
| ECT | NTC de água | pull-up de 2,49 kΩ ao **3,3 V** | pull-up ao **5 V (AVcc)** | `KECU_NTC_PULLUP_OHM`, `KECU_NTC_T1_C/R1_OHM`, `KECU_NTC_T2_C/R2_OHM` |
| IAT | NTC de ar | idem | idem | as mesmas |
| MAP | sensor de pressão absoluta de 5 V | divisor 10 k / 10 k (razão 2,0) | direto (razão 1,0) | `KECU_MAP_V1_MV/KPA1`, `KECU_MAP_V2_MV/KPA2` |
| TPS | potenciômetro de 5 V | idem | idem | `KECU_TPS_V_FECHADO_MV`, `KECU_TPS_V_ABERTO_MV` |
| Bateria | divisor | 47 k / 10 k (razão 5,7) | veja a seção 8.7 | `KECU_RAZAO_BATERIA` |
| Óleo | transdutor de 0,5–4,5 V | divisor 10 k / 10 k | direto | `KECU_OLEO_V1_MV/BAR1`, `KECU_OLEO_V2_MV/BAR2` |
| Lambda | saída analógica de 0–5 V do controlador de banda larga | divisor 10 k / 10 k | direto | `KECU_LAMBDA_V1_MV/AFR1`, `KECU_LAMBDA_V2_MV/AFR2`, `KECU_LAMBDA_AFR_REF` |

As razões `KECU_RAZAO_5V` e `KECU_RAZAO_BATERIA` são definidas **por placa** no `platformio.ini` (2,0 e 5,7 no ESP32; 1,0 e 3,0 no Mega). Se você usar outros resistores, **mude esses números**.

### 8.3. Sensores de 5 V no ESP32 (MAP, TPS, óleo, lambda)

O ESP32 **não tolera 5 V** no pino. O divisor abaixo transforma 0–5 V em 0–2,5 V, bem dentro da faixa do ADC:

```
 saída do sensor ──[ 10 kΩ ]──┬──► pino ADC (ESP32)         razão = (10 k + 10 k) ÷ 10 k = 2,0
 (0 a 5 V)                    │                              (-DKECU_RAZAO_5V=2.0f)
                         [ 10 kΩ ]  [ 100 nF ]
                              │         │
                             GND       GND         proteção opcional: zener de 3,3 V (BZX84C3V3) do pino ao GND
```

- O capacitor de 100 nF perto do pino reduz o ruído da conversão [F5].
- O divisor carrega a saída do sensor com 20 kΩ: sem problema para MAP e TPS comuns; para uma fonte de alta impedância, use um seguidor de tensão (amplificador operacional *rail-to-rail*) antes do divisor.

### 8.4. Temperatura da água e do ar (NTC)

```
 3,3 V (ESP32)  ou  5 V (Mega) ── a MESMA tensão que a ref. do ADC
   │
[ 2,49 kΩ ]  ← KECU_NTC_PULLUP_OHM
   │
   ├────────────────► pino ADC          (100 nF para o GND no pino)
   │
 [ NTC ]
   │
  GND
```

- A fórmula do firmware supõe que o resistor de *pull-up* está ligado a uma tensão igual a `KECU_VREF_MV` (3300 no ESP32; 5000 no Mega). **Meça essa tensão** com o multímetro e, se for diferente, ajuste `-DKECU_VREF_MV`.
- O NTC é modelado por **dois pontos medidos** (modelo beta). Os valores padrão (3400 Ω a 20 °C e 177 Ω a 100 °C) são típicos de sensores de água da GM, mas o **seu** NTC pode ser outro. Meça a resistência dele (fora do carro) em duas temperaturas conhecidas, por exemplo, em água com gelo (≈ 0 °C) e em água quente com um termômetro de verdade, e ponha os valores em `KECU_NTC_T1_C/R1_OHM` e `KECU_NTC_T2_C/R2_OHM`.
- Tensão no pino, pelo modelo beta ajustado aos dois pontos padrão, com pull-up de 2,49 kΩ e 3,3 V (um NTC real difere um pouco): 3,05 V (−20 °C), 2,61 V (0 °C), 1,91 V (20 °C), 1,19 V (40 °C), 0,68 V (60 °C), 0,38 V (80 °C), 0,22 V (100 °C), 0,13 V (120 °C).
  Perto de 100 °C o sinal é pequeno e é onde o ADC do ESP32 é menos exato: se a precisão a quente importar, **diminua o pull-up** (por exemplo, 1 kΩ) e mude `KECU_NTC_PULLUP_OHM`.
- **Use um sensor próprio.** Se a ECU original continuar no carro, não ligue o mesmo fio nas duas centrais: o pull-up de uma altera a leitura da outra.
- A temperatura da água dirige o enriquecimento de aquecimento e a rotação-alvo da marcha lenta; a do ar, a densidade do ar no cálculo do pulso.

### 8.5. Posição da borboleta (TPS)

- Potenciômetro de três fios: 5 V de referência, GND e o cursor. Use o divisor da seção 8.3.
- **Meça** (com o multímetro, no fio do sensor, **antes** do divisor) a tensão com a borboleta **fechada** e **toda aberta**, e ponha em `KECU_TPS_V_FECHADO_MV` e `KECU_TPS_V_ABERTO_MV`. Os padrões (500 e 4500 mV) são típicos.
- Confira no aplicativo: 0 % fechado e 100 % aberto. Valor travado em 0 % com o erro `TPS` aceso indica fio solto ou em curto (fora de 80 a 4920 mV).

### 8.6. Pressão no coletor (MAP)

- A MAP é **absoluta**, em kPa. Os valores padrão (0,425 V = 20 kPa e 4,7 V = 115 kPa) seguem a curva de um sensor da família MPX4115; **use os do seu sensor**.
- **Calibração em dois pontos**: (1) com a chave ligada e o motor parado, a MAP é a **pressão atmosférica** do local (perto de 100 kPa ao nível do mar; menos em altitude); (2) com uma bomba de vácuo manual, aplique um vácuo conhecido e calcule a pressão absoluta (atmosférica − vácuo). Ponha as duas tensões e as duas pressões em `KECU_MAP_*`.
- A mangueira de vácuo deve sair de um ponto **depois** da borboleta, sem pontos baixos onde junte combustível. Pulsações fortes do coletor sujam a leitura; o firmware já filtra, e uma pequena restrição ou reservatório na mangueira ajuda.

### 8.7. Tensão da bateria

- O firmware usa a tensão da bateria para corrigir o **tempo morto** dos bicos e o **dwell** da bobina, então a leitura precisa ser boa (calibre com multímetro: **nova razão = razão atual × (tensão do multímetro ÷ tensão mostrada)**).
- **ESP32** (padrão, 47 kΩ + 10 kΩ, razão 5,7): 14,4 V viram 2,53 V no pino; 18 V (limite de erro do firmware), 3,16 V, no fim da faixa do ADC. Se quiser ficar com folga, use **56 kΩ + 10 kΩ** (razão 6,6) e `-DKECU_RAZAO_BATERIA=6.6f`: 16 V viram 2,42 V.
- **Mega**: o padrão do `platformio.ini` (20 kΩ + 10 kΩ, razão 3,0) **satura em 15 V** (o pino chega a 5 V) e passa de 5 V a partir de 15 V. O mais seguro é **33 kΩ + 10 kΩ** (razão 4,3, vai a 21,5 V) com `-DKECU_RAZAO_BATERIA=4.3f` e um zener de 5,1 V no pino.
- Proteja a entrada contra transientes do carro com 100 nF no pino e um zener de 3,3 V (ESP32) ou 5,1 V (Mega) em paralelo com o resistor de baixo.

### 8.8. Pressão de óleo

- Transdutor de 0,5–4,5 V, 0–10 bar (os padrões `KECU_OLEO_*`). Se o seu carro só tem o **interruptor** da luz de aviso, ele **não mede pressão**: só o transdutor dá valor em bar. A leitura de óleo é só informativa (o firmware não age sobre ela).

### 8.9. Lambda (sonda de banda larga)

- É preciso um **controlador de banda larga** (sonda Bosch LSU ou equivalente + controlador) com saída analógica de 0 a 5 V. Os padrões (`KECU_LAMBDA_*`: 0 V = AFR 7,35; 5 V = AFR 22,39) seguem a saída-padrão de controladores comuns: **confira no manual do seu**, e se a saída for outra, mude as constantes.
- `KECU_LAMBDA_AFR_REF` (14,7) converte AFR em lambda; controladores costumam entregar o AFR referenciado à gasolina pura, qualquer que seja o combustível.
- **A lambda só é lida e mostrada**: não há correção em malha fechada. Ela é a principal ferramenta de calibração (seção 12).
- A sonda estreita (0–1 V) do carro **não** serve para essa entrada.
- O terra do controlador e o terra de sinal da ECU precisam ser o mesmo ponto.

### 8.10. Calibrar o ADC

- **ESP32**: sem calibração, o erro entre chips é de ±6 % [F5]. A HAL usa `analogReadMilliVolts`, que aproveita a calibração gravada no chip quando existe. Mesmo assim, aplique 3 tensões conhecidas (por exemplo, 0,5 V, 1,5 V e 2,5 V, medidas com multímetro) a cada canal, compare com o aplicativo e, se o erro passar de 1 a 2 %, corrija a **razão** do divisor (é um ganho; não há correção de *offset* por canal no firmware).
- **Mega**: o ADC mede em relação ao AVcc. **Meça o 5 V** do Mega com multímetro e ponha o valor (em mV) em `-DKECU_VREF_MV` no `platformio.ini`; o erro entra direto na leitura da bateria e do NTC.

---

## 9. Saídas de potência: bicos, bobina e marcha lenta

As saídas da placa são **sinais lógicos** (3,3 V no ESP32, 5 V no Mega, poucos miliampères). **Nunca** ligue bico, bobina ou válvula direto num pino: cada saída precisa de um
estágio de potência. Os circuitos abaixo são **de referência**: revise, simule e teste em bancada, com carga falsa (seção 11), antes de ligar qualquer coisa ao carro.

A ordem das saídas é a do enum `kecu_saida_t`: **INJ1, INJ2, INJ3, INJ4, BOBINA1, BOBINA2** (pinos na seção 5). A válvula de marcha lenta (**IAC**) é à parte (seção 9.4).

### 9.1. Regras que valem para todas as saídas

1. **Resistor de 10 kΩ do gate ao GND**, bem perto do transistor. Com ele, um pino solto (modo sombra, reinício, placa ainda iniciando) mantém o estágio **desligado**. É por isso que o padrão do firmware é
   **nível ativo alto** (`KECU_SAIDA_ATIVA_ALTO=1`). O nível ativo baixo (`-DKECU_SAIDA_ATIVA_ALTO=0`) existe para drivers que ligam com o pino em nível baixo: nesse caso o resistor de repouso é um
   **pull-up** (que mantém o driver desligado), e o firmware já deixa o pino em nível alto antes de configurá-lo como saída. Essa opção vale para bicos e bobinas, **não** para o PWM da válvula de marcha lenta.
2. **Resistor de gate em série**, de uns 100 Ω, entre o pino e o gate: limita a corrente de carga do gate e amortece oscilações.
3. **MOSFET de nível lógico**: o RDS(on) tem de estar especificado para a tensão que o pino entrega (3,3 V no ESP32; 5 V no Mega). Um MOSFET comum, que só conduz bem com 10 V no gate, **não serve**.
4. **O pull-down tem de vencer qualquer pull-up interno.** O ESP32 tem pull-up e pull-down internos de 45 kΩ (valor nominal) [F11], e alguns pinos (os de *strapping*, por exemplo) saem do reset com o pull-up ligado [F11].
   No **pior caso**, o divisor de 10 kΩ (externo) contra 45 kΩ (interno) deixa o gate em 3,3 × 10 ÷ (10 + 45) = **0,6 V**. Escolha componentes cujo limiar de gate **mínimo**, na temperatura mais alta, fique bem
   acima disso. (O IGBT da seção 9.3, por exemplo, tem limiar mínimo de 0,75 V a 150 °C: margem curta.) Se quiser folga, use **4,7 kΩ**, que no mesmo cálculo dá 0,3 V.
5. **Toda carga indutiva** (bico, bobina, válvula) gera um pico de tensão ao desligar. Cada saída precisa do seu **grampo** (seções 9.2 a 9.4); sem ele, o transistor morre nos primeiros pulsos.
6. **Fusível em cada ramo**, e a alimentação dos bicos, da bobina e da válvula passando por um **relé principal** comandado pela chave de ignição: com a chave desligada, nada fica energizado.
7. **Terra de potência** separado do terra de sinal, unidos num ponto só (seção 6.3). Fios curtos e grossos entre a carga, o transistor e o terra.
8. **Primeiro teste com carga falsa e osciloscópio** (seção 11): lâmpada de 12 V no lugar do bico, lâmpada ou resistor no lugar da bobina, sem combustível por perto.

### 9.2. Bicos injetores

#### 9.2.1. Alta ou baixa impedância?

Meça a resistência de cada bico **frio e desligado**: de 8 a 16 Ω é **alta impedância**; de 0,5 a 5 Ω é **baixa impedância** [F8].

- **Alta impedância** (acionamento *saturado*): basta ligar a tensão da bateria no bico, e a resistência dele limita a corrente (cerca de 1 A com 12 V e 12 Ω). **É o único tipo que este projeto aciona direto.**
- **Baixa impedância**: pede controle de corrente (*peak-and-hold*): uma corrente alta, de alguns ampères, para abrir o bico depressa e, depois, uma corrente bem menor para segurá-lo aberto [F8].
  **O firmware não faz isso.** Ligar um bico de 1,2 Ω direto numa saída liga/desliga leva a corrente a mais de 10 A e queima o bico e o MOSFET. As saídas possíveis são:
  (a) trocar por bicos de alta impedância; (b) usar um **driver *peak-and-hold* externo**, comandado pelo sinal lógico da ECU; (c) pôr um **resistor de lastro** em série com cada bico
  (por exemplo, bico de 1,2 Ω + cerca de 5,6 Ω dá ≈ 2 A com 14,4 V, e o resistor dissipa **cerca de 25 W** enquanto o bico está ligado; exige resistor de potência com dissipador, e o bico abre mais
  devagar, o que muda o tempo morto).
- Em fóruns, o bico do **monoponto (TBI) da GM** aparece com valores de **baixa impedância**, em torno de 1 a 2 Ω; os relatos divergem [F9]. Se o seu for assim, **não** use o circuito da seção 9.2.2
  sem uma das três soluções acima.

#### 9.2.2. Circuito de referência (alta impedância)

```
 +12 V (depois do relé principal; fusível do ramo)
   │
 [ BICO ]   alta impedância (~12 Ω)
   │
   ├───────────┐                 D = dreno, G = gate, S = fonte
   │           │
   D        TVS ▼   cátodo no dreno, ânodo no GND
 G─┤ Q         │
   S           │
   ├───────────┘
   │
  GND de potência

 INJx ──[ 100 Ω ]──┬──► G
                   │
                [ 10 kΩ ]
                   │
                  GND
```

- **Q**: MOSFET de canal N, nível lógico, VDSS de 60 V ou mais, RDS(on) baixo na tensão do seu pino (seção 9.1). A dissipação em condução é desprezível (cerca de 1 A × RDS(on)).
- **TVS** entre o dreno e o GND: quando o bico desliga, a energia ½ × L × I² (da ordem de poucos mJ por pulso num bico de alta impedância; **meça** L com um medidor LCR) vai para o TVS, que limita a tensão.
  Exemplo: **SMBJ33A** (tensão de trabalho 33 V, ruptura entre 36,7 V e 40,6 V, grampeamento de até 53,3 V na corrente de pico da folha de dados) [F13]. O MOSFET precisa suportar a tensão de grampeamento
  com folga (por isso, 60 V ou mais), e o TVS não pode conduzir na tensão normal de bordo. Surtos da rede do carro acima da tensão de trabalho do TVS o fariam conduzir: a rede de potência também precisa de proteção (seção 6.1).
- Um **diodo comum** em paralelo com o bico também protege, mas **atrasa o fechamento** (o bico fica aberto um pouco mais e o tempo morto aumenta): prefira o TVS, ou um MOSFET automotivo com grampo e limitação de corrente integrados.
- **Um MOSFET por saída.** Não junte dois bicos no mesmo transistor sem recalcular a corrente, o fusível e o dissipador.

#### 9.2.3. O que o firmware faz com os bicos

- **INJ1 a INJ4 ligam todos juntos** (injeção simultânea), **uma vez por volta do virabrequim** (duas vezes por ciclo de 4 tempos). Cada abertura dura `inj_us = pulso ÷ 2 + tempo morto`
  (a fórmula do `pulso` está na seção 12.3).
- A injeção começa no **dente de referência** (o primeiro depois da falha da roda). Ela **não depende** do `PMS_OFFSET`.
- **Saída sem bico ligado não faz nada.** No **monoponto (TBI), ligue só a INJ1.** Com dois bicos, INJ1 e INJ2, e assim por diante. O `ReqFuel` (seção 12.3) é calculado com a soma da vazão dos bicos
  **efetivamente ligados**.
- O valor "PULSO" que o painel mostra (`pw_us` na telemetria) é `pulso + tempo morto`, isto é, com **um** tempo morto. O tempo que o bico fica aberto de verdade em cada ciclo é `pulso + 2 × tempo morto`
  (são duas aberturas). Para calcular o *duty* do bico use esta segunda conta (seção 12.3).
- Com o **limitador** acionado e `TIPO_CORTE` em 0 ou 2, as aberturas simplesmente deixam de ser agendadas. Na partida (abaixo de 400 rpm) o pulso é fixo: `ReqFuel × 0,4 × aquecimento`.

#### 9.2.4. Tempo morto

O bico demora um pouco para abrir depois que a corrente liga, e esse atraso aumenta quando a tensão da bateria cai. O firmware usa `TEMPO_MORTO_US` (padrão 900 µs, **a 14 V**) mais
`TEMPO_MORTO_COEF` (padrão 150 µs **por volt abaixo de 14 V**; acima de 14 V o tempo diminui): `tempo morto = TEMPO_MORTO_US + COEF × (14 − Vbat)`, limitado a 0 a 5000 µs.
Os padrões são **exemplos**: o valor certo vem da folha de dados do bico, se houver, e da calibração com a lambda (seção 12.4). Resistor de lastro e diodo de roda-livre mudam esse tempo.

### 9.3. Bobina de ignição

#### 9.3.1. O sinal que o firmware entrega

`BOBINA1` e `BOBINA2` ficam **ligadas durante o *dwell*** (a bobina carrega) e **desligam no instante da centelha**: a borda de **descida** é a centelha. Não é um pulso curto de gatilho: é a própria corrente do primário.

- `DWELL_US` (padrão 3000 µs; faixa de 500 a 8000 µs) é o tempo de carga **a 14 V**. O firmware multiplica por 14 ÷ Vbat (entre 0,8 e 1,6), limita o resultado a 500 a 8000 µs e, em rotação alta, a
  **85 % do intervalo entre duas centelhas** (180° do virabrequim).
- O ângulo da centelha é `PMS − avanço`, com o PMS dado por `PMS_OFFSET` (seção 12.2). Na partida (abaixo de 400 rpm) o avanço é fixo, de 10°.
- **Não existe saída EST/*bypass* para módulo HEI.** O firmware entrega só a corrente de carga da bobina.

#### 9.3.2. Duas rotas para o seu carro

Pelas pistas da seção 3.1, o seu carro provavelmente tem um **módulo HEI** no distribuidor. O módulo e a bobina podem ser integrados ou separados, conforme o modelo: confirme no carro (seção 3.2). Há duas rotas:

- **(a) Driver próprio.** Tirar o módulo HEI do circuito e comandar o primário da bobina com um driver de ignição (IGBT com grampo interno) ligado à ECU, como no circuito abaixo. **É o que este guia descreve.** O módulo HEI
  e o captador do distribuidor deixam de ser usados: o sinal de rotação vem da roda fônica (seção 7.1).
- **(b) Usar o módulo HEI como driver** (com o sinal EST). **Não está implementado nem verificado**: exigiria uma saída nova no firmware e o esquema exato do seu módulo.

#### 9.3.3. Circuito de referência (driver próprio)

```
 +12 V (depois do relé principal; fusível do ramo)
   │
 [ primário da bobina ]   meça a resistência (seção 9.3.3)
   │
   C                       C = coletor, G = gate, E = emissor
 G─┤ Q    IGBT de ignição com grampo interno (~360 V)
   E
   │
  GND de potência

 BOBINAx ──[ 100 Ω ]──┬──► G        (ESP32: veja a nota sobre a tensão de gate)
                      │
                   [ 10 kΩ ]
                      │
                     GND
```

- **IGBT de ignição com grampo interno, e não um MOSFET comum de 60 V**: a tensão no primário na hora da centelha chega a centenas de volts, e o IGBT "autograpeado" suporta isso sem se destruir. Exemplo: o
  **ISL9V5036** (Fairchild, hoje onsemi): grampo de 360 V típico (330 V a 390 V), energia de comutação indutiva autoprotegida (SCIS) de 500 mJ, corrente de coletor de 46 A a 25 °C (31 A a 110 °C), gate de nível lógico
  (limiar de 1,3 V a 2,2 V a 25 °C) e uso declarado como "driver de bobina em ignição automotiva" [F12]. É um **exemplo**: confira a folha de dados do componente que você comprar.
- **Tensão de gate.** A tensão máxima de gate do exemplo é de ±10 V (não acione o gate com 12 V). A queda de saturação (VCE(sat)) da folha de dados vale para uma tensão de gate específica; com os **3,3 V do ESP32**
  ela pode ser maior e o IGBT esquentar mais. Confira nas curvas e, se preciso, ponha um **buffer 74HCT** alimentado com 5 V entre o pino e o gate (a entrada TTL do 74HCT aceita 3,3 V), mantendo o resistor de 10 kΩ.
- **Corrente de pico e dwell.** A corrente do primário sobe como `i(t) = (V ÷ R) × (1 − e^(−t·R/L))`, com V a tensão da bateria, e R e L o primário da bobina. **Exemplo numérico** (valores inventados para ilustrar,
  **não** de uma bobina real): R = 1,5 Ω e L = 5 mH. A 14 V, 3 ms dão ≈ 5,5 A e 4 ms dão ≈ 6,5 A; a 6 V (partida com bateria fraca), 3 ms dão só ≈ 2,4 A. A energia guardada, `½ × L × i²`, é de ≈ 105 mJ a 6,5 A.
  **Meça** a resistência do primário da sua bobina com o multímetro e a indutância com um medidor LCR, pegue a corrente nominal com o fabricante e ajuste `DWELL_US` para chegar nela (seção 12.5). A correção
  14 ÷ Vbat do firmware é limitada a ×1,6, então, na partida com bateria fraca, a centelha tende a ficar mais fraca: é limite da física, não do firmware.
- **Limite de dwell no hardware.** O firmware limita o dwell (no máximo 8 ms pelo plano e 60 ms pelo agendador), mas, se o processador travar com o pino em nível alto, a bobina continua energizada até o cão de guarda
  reiniciar a placa (2 s no firmware): tempo de sobra para esquentar o primário e queimar o IGBT. Para uso no carro, inclua um limite **no hardware** (por exemplo, um monoestável que derruba o gate depois de uns 10 a 15 ms,
  ou um driver de ignição com limitação de corrente e corte por tempo). **Isso não faz parte deste projeto.**

#### 9.3.4. Distribuidor ou centelha perdida

- **Uma bobina + distribuidor** (padrão): `BOBINA1` comanda todas as centelhas e o distribuidor entrega cada uma ao cilindro certo pelo rotor. O distribuidor tem de ficar **fixo**, sem avanço mecânico nem a vácuo
  (o avanço agora é da ECU), e a **fase do rotor** em relação à centelha precisa ser conferida com a luz estroboscópica nos quatro cilindros (seção 12.2).
- **Duas bobinas, centelha perdida** (`-DKECU_BOBINAS=2`): `BOBINA1` atende o par de cilindros **1 e 4** e `BOBINA2` o par **2 e 3** (ordem de ignição 1-3-4-2; confira no manual do seu motor). Exige bobina dupla (um primário por par,
  cada um com dois terminais de vela) e a retirada do distribuidor. Se o motor não pegar ou falhar, confira se as duas bobinas não estão trocadas.

#### 9.3.5. Cuidados

- **Ruído**: cabos de vela resistivos, fiação do primário longe dos sensores e dos cabos de sinal (seção 6.3).
- **Corte de ignição no limitador.** `TIPO_CORTE` vale 0 (combustível), 1 (ignição) ou 2 (os dois; **padrão**). Cortar **só a ignição** (1) deixa o combustível passar para o escapamento: estouros no escape e dano ao
  catalisador, se o carro tiver um. Prefira 0 ou 2.

### 9.4. Marcha lenta (válvula IAC)

- O firmware tem **uma** saída de marcha lenta, em **PWM**: ESP32 no GPIO4 (LEDC, 10 bits, 200 Hz, ajustável com `-DKECU_IAC_FREQ_HZ`) e Mega no pino 9 (Timer2, ≈ 245 Hz, fixa). Quanto maior o *duty*, mais **aberta** a válvula;
  **não há opção para inverter** o sentido, então a válvula precisa abrir com mais duty.
- **O motor de passo do Multec não é suportado.** Segundo o texto de oficina da seção 3.1 [F3], a central do Multec tem terminais para um motor de passo na marcha lenta, ou seja, o carro provavelmente **não** tem
  válvula de PWM. As opções são: (a) **marcha lenta manual**, com o parafuso de batente ou o *bypass* da borboleta ajustado à mão; (b) trocar por uma **válvula de marcha lenta de PWM** (confira na folha de dados dela
  a frequência aceita: valores de poucas centenas de hertz são comuns, mas confirme); (c) escrever o código para motor de passo (**não existe neste projeto**).
- **Como a saída se comporta** (só com saídas habilitadas; em modo sombra o pino nem é configurado):
  - com a ECU **desarmada** ou com o motor parado, o *duty* é **0 %**;
  - com o motor girando, o *duty* é `ML_DUTY_BASE` (padrão 35 %), e o controle **PI** só atua quando `HAB_MARCHA_LENTA` está ligada, o TPS está abaixo de `ML_TPS_MAX` (padrão 4 %) **e** a rotação está abaixo de
    alvo + 500 rpm. Fora dessas condições, volta ao *duty* base;
  - o **alvo** vai de `ML_RPM_FRIO` (padrão 1300 rpm, com a água a 20 °C ou menos) a `ML_RPM_QUENTE` (padrão 850 rpm, a 80 °C ou mais), em reta;
  - `ML_KP` e `ML_KI` estão em centésimos de ponto percentual de *duty* por rpm de erro (padrão 5 e 2: 0,05 % por rpm e 0,02 % por rpm·s); a parte integral é limitada a ±30 %.
- **Sem válvula de PWM**: **não ligue nada ao pino IAC.** `HAB_MARCHA_LENTA = 0` só desliga o PI; o *duty* fixo `ML_DUTY_BASE` continua saindo no pino enquanto o motor gira (sem carga, não faz nada).
- **Circuito**: igual ao do bico (MOSFET de nível lógico, 100 Ω no gate, 10 kΩ para o GND), com um **diodo de roda-livre** em paralelo com a válvula (cátodo no +12 V), porque a frequência é baixa e a lentidão do
  diodo não atrapalha, ao contrário do bico. Dimensione o MOSFET e o fusível pela corrente da válvula (folha de dados).
- Se o motor falhar ao partir ou oscilar em marcha lenta, a causa mais provável é o **duty base** ou o ganho do PI: ajuste com o motor quente (seção 12.7).

### 9.5. Bomba de combustível, relé principal e o que o firmware não protege

**Este projeto não tem saída para a bomba de combustível, o relé principal, a ventoinha nem o ar-condicionado.** Isso é uma **lacuna de segurança**, não um detalhe: numa injeção original, a ECU mantém a bomba
ligada só com o motor girando (e por poucos segundos quando a ignição é ligada). Se você tirar a ECU original, o circuito da bomba do seu carro continua precisando existir e **continuar seguro**:

- a bomba não pode ficar ligada só porque a ignição está ligada (com o motor parado, um bico vazando ou uma mangueira solta continuariam despejando combustível);
- um **interruptor de inércia** (corta a bomba numa batida) é recomendável;
- o circuito exato (relé, interruptor de pressão de óleo, sinal da partida) varia por modelo: siga o diagrama do **seu** carro ou peça a um eletricista automotivo que revise o que você montar.

Uma saída de bomba no firmware (ligada por 2 a 3 s ao ligar a ignição e enquanto houver rotação) seria uma evolução natural; **hoje ela não existe**.

---

## 10. Compilar, gravar e configurar

### 10.1. Preparar o ambiente

- **PlatformIO**: a extensão do VS Code ou `pip install platformio` (precisa de Python 3 e de internet na primeira vez, para baixar as plataformas `espressif32` e `atmelavr` e o compilador de cada uma).
- **Driver USB-serial** da placa: CP210x ou CH340 nos DevKits do ESP32; no Mega, o da sua placa (ATmega16U2 ou CH340). No Linux, ponha o seu usuário no grupo `dialout` (`sudo usermod -aG dialout $USER`) e saia e
  entre de novo na sessão; no Windows, instale o driver do chip.
- O aplicativo Java precisa de **JDK 11 ou mais novo** (foi compilado com `--release 11` e testado com o JDK 21).

### 10.2. Ambientes de compilação

| Ambiente | Placa | Saídas | Use para |
|----------|-------|--------|----------|
| `esp32dev` (padrão) | ESP32 | **desabilitadas** (modo sombra) | bancada; leitura no carro com a injeção original comandando |
| `esp32dev_saidas` | ESP32 | habilitadas | só depois da seção 11 |
| `megaatmega2560` | Mega 2560 | **desabilitadas** (modo sombra) | bancada e aprendizado |
| `megaatmega2560_saidas` | Mega 2560 | habilitadas | só depois da seção 11 |

```
pip install platformio
cd firmware
pio run -e esp32dev                    # só compila
pio run -e esp32dev -t upload          # compila e grava na placa
pio run -e megaatmega2560 -t upload    # o mesmo, para o Mega
```

`pio run` sem `-e` compila só o ambiente padrão (`esp32dev`). O ambiente também define `KECU_PLACA`, que vai na resposta `HELLO` e aparece no aplicativo.

> **A primeira compilação de verdade pode apontar erros.** O código das placas (`hal_esp32.cpp`, `hal_avr.cpp`) foi verificado no PC contra *simuladores* das bibliotecas do Arduino-ESP32 e do ATmega2560, mas
> **nunca foi compilado com o xtensa-gcc nem com o avr-gcc**. Se aparecer um erro, a mensagem do compilador diz o arquivo e a linha; o mais provável é diferença de versão de biblioteca (o `platformio.ini` fixa
> `espressif32@^6.9.0`, com Arduino-ESP32 2.0.x; a HAL também trata o 3.x, mas só foi testada no simulador). Veja a seção 14.

### 10.3. Opções de compilação (*build flags*)

Elas vão na lista `build_flags` do ambiente, no `firmware/platformio.ini`, no formato `-DNOME=valor`.

| Opção | Padrão | O que faz |
|-------|--------|-----------|
| `KECU_ENABLE_OUTPUTS` | `0` (`1` nos ambientes `_saidas`) | `0` = **modo sombra**: nenhum pino de saída é configurado e o `ARMAR` recebe `NACK 6`. `1` = saídas compiladas (ainda dependem de `ARMAR`). |
| `KECU_AUTO_ARMAR` | `0` | `1` (só com saídas habilitadas) = a ECU liga **armada** e roda sem notebook. Só depois de validar tudo (seção 14), com extintor à mão. |
| `KECU_BOBINAS` | `1` | `1` = uma bobina + distribuidor; `2` = duas bobinas de centelha perdida (seção 9.3). |
| `KECU_SAIDA_ATIVA_ALTO` | `1` | nível do pino que liga bico e bobina (seção 9.1). Não vale para o PWM da válvula. |
| `KECU_CKP_BORDA_SUBIDA` | `1` | `1` = conta a borda de subida do sinal de rotação; `0` = a de descida (seção 7.1.1). |
| `KECU_RAZAO_5V` | `2.0f` no ESP32, `1.0f` no Mega | razão do divisor dos sensores de 5 V: MAP, TPS, óleo e lambda (seção 8.3). |
| `KECU_RAZAO_BATERIA` | `5.7f` no ESP32, `3.0f` no Mega | razão do divisor da bateria (seção 8.7). **No Mega, use 4.3f com 33 k + 10 k.** |
| `KECU_VREF_MV` | `3300` no ESP32, `5000` no Mega | tensão máxima que o ADC lê no pino, em mV. **No Mega, meça o 5 V** (seção 8.10). |
| `KECU_NTC_PULLUP_OHM` | `2490` | resistor de pull-up dos NTC, em Ω (seção 8.4). |
| `KECU_ADC_AMOSTRAS` | `4` no ESP32, `1` no Mega | leituras por canal que entram na média. |
| `KECU_DEBOUNCE_LEITURAS` | `4` | leituras iguais seguidas (uma a cada 5 ms) para aceitar a mudança das chaves *two-step* e *launch*. |
| `KECU_IAC_FREQ_HZ` | `200` (só ESP32) | frequência do PWM da válvula de marcha lenta (no Mega é ≈ 245 Hz, fixa). |
| `KECU_WDT_SEGUNDOS` | `2` (ESP32) | tempo do cão de guarda. |
| `KECU_AVR_WDT` | igual a `KECU_ENABLE_OUTPUTS` (Mega) | liga o cão de guarda de 2 s no Mega. |
| `KECU_SCHED_LARGURA_MAX_US` | `60000` | largura máxima, em µs, de qualquer pulso de bico ou bobina. |
| `KECU_NVM_ENDERECO` | `0` (Mega) | posição da EEPROM onde o bloco de 889 bytes começa. |

As **curvas dos sensores** (NTC, TPS, MAP, óleo e lambda) são constantes de `firmware/lib/kecu_core/kecu_config.h` e **o aplicativo não consegue mudá-las**: edite o arquivo, recompile e regrave. Só as constantes
com `#ifndef` (as de `KECU_VREF_MV`, `KECU_RAZAO_*` e `KECU_NTC_PULLUP_OHM`) podem ser trocadas por `-D`.

### 10.4. Gravar na placa

1. Ligue a placa ao notebook pelo cabo USB (confira se o cabo é de dados: alguns só carregam) e **feche tudo que use a porta serial** (monitor serial, outra janela do aplicativo): só um programa abre a porta por vez.
2. `pio run -e <ambiente> -t upload`. Alguns DevKits do ESP32 pedem que você segure o botão BOOT enquanto aparece "Connecting..." (a maioria faz isso sozinha).
3. A placa reinicia sozinha ao fim da gravação. A primeira vez que ela liga **sem calibração gravada**, usa os valores de fábrica (e **não** acende o erro de memória).
4. **A serial é binária.** Não use o monitor serial (`pio device monitor`): ele mostra lixo e ainda segura a porta. Quem fala com a placa é o aplicativo.

### 10.5. Rodar o aplicativo e conectar

```
./compilar.sh          (compilar.bat no Windows)   # compila o aplicativo Java
./executar.sh          (executar.bat no Windows)   # abre o painel completo
./executar.sh --simulador   # abre já ligado ao simulador em Java, sem placa nenhuma
./executar.sh --legado      # o analisador de binário original (janela + console)
./executar.sh --console     # só o analisador de binário, no console
```

1. Na barra de conexão, clique em **ATUALIZAR** para listar as portas e escolha uma:
   - `SIMULADOR (ECU virtual em Java)`: sem placa;
   - `FIRMWARE C (PC) - o mesmo código da placa`: só aparece se você compilou o firmware do PC (`make -C firmware/host`, precisa do gcc); roda o **mesmo código C da placa** no seu computador;
   - uma porta serial (`COM3`, `/dev/ttyUSB0`...) com a descrição que o sistema der: é a sua placa.
2. Deixe a velocidade em **115200** (é a do firmware) e clique em **CONECTAR**. O aplicativo manda `HELLO` até 6 vezes, a cada 500 ms, porque a placa pode estar reiniciando (seção 13.1).
3. No painel, a lâmpada **MODO SOMBRA** mostra que o firmware foi compilado sem saídas. A lâmpada **SAÍDAS ARMADAS** mostra se as saídas estão armadas. Lâmpadas de erro (ECT, IAT, MAP, TPS, BATERIA, ROTAÇÃO, MEMÓRIA,
   LAMBDA) acendem quando o firmware marca um sensor com fio solto ou em curto, uma perda de sincronia nos últimos 2 s, ou uma falha da memória.
4. **Comandos no topo**: `TWO-STEP` e `LAUNCH` (chaves virtuais, a ECU usa a chave física **ou** a do aplicativo), `ARMAR SAÍDAS` (pede confirmação; em modo sombra só explica por que não arma) e `BANCADA...` (só no
   simulador e no firmware do PC; com a placa de verdade ele fica desativado).
5. **Barra de calibração**: `LER DA ECU`, `ENVIAR TUDO` (manda parâmetros, eixos e tabelas para a **RAM** da ECU; **não grava**), `GRAVAR NA MEMÓRIA` (só com o motor **parado**), `PADRÕES DE FÁBRICA`, `ABRIR .KECU`
   e `SALVAR .KECU` (arquivo de texto com a calibração). Na aba `PARÂMETROS` há também `ENVIAR PARÂMETROS`.
6. **Abas**: `PAINEL` (instrumentos), `MAPAS` (tabelas 16×16 em 2D e 3D), `PARÂMETROS`, `BINÁRIO / HEX` (analisador de arquivos) e `REGISTRO` (o que o aplicativo e a ECU trocaram).

**Fluxo de trabalho recomendado para calibrar:**

1. `CONECTAR` e `LER DA ECU` (traz a calibração que está na placa).
2. `SALVAR .KECU` **antes** de mexer em qualquer coisa (é o seu backup).
3. Edite parâmetros e mapas e use `ENVIAR TUDO`: o efeito é imediato, mas **só na RAM**. As linhas de uma tabela chegam **uma a uma**, então, com o motor funcionando, o motor passa alguns instantes com uma
   mistura de linhas velhas e novas: faça mudanças **pequenas** de cada vez.
4. Os campos "só parado" (`CILINDROS`, `DENTES_RODA`, `DENTES_FALTANDO`, `MODO_SINAL` e `PMS_OFFSET_X10`) são recusados com o motor girando (o aplicativo mostra o erro).
5. Com o motor **parado**, `GRAVAR NA MEMÓRIA` e espere o fim (cerca de 3 s no Mega; no ESP32 é mais rápido). Depois, `SALVAR .KECU` de novo.
6. `PADRÕES DE FÁBRICA` carrega na RAM os valores de fábrica: eles são **exemplos genéricos** (seção 12.1), não uma calibração para o seu motor.

---

## 11. Teste de bancada, passo a passo

Cada etapa só vale se a anterior passou. **Não pule para o carro.** Material: fonte de bancada de 12 a 14 V **com limitação de corrente**, multímetro, **osciloscópio** (2 canais, de preferência),
gerador de função (ou um segundo microcontrolador) para imitar a roda fônica, potenciômetros de 10 kΩ, resistores, lâmpadas de 12 V (5 a 21 W), extintor, e **nenhum combustível por perto**.

### 11.1. Sem hardware: simulador e firmware do PC

1. `./executar.sh --simulador`: aprenda o painel, os mapas e os parâmetros com uma ECU virtual em Java. `BANCADA...` define à mão a rotação, o acelerador e os sensores.
2. (Opcional, precisa do gcc) `make -C firmware/host` e, no painel, escolha `FIRMWARE C (PC) - o mesmo código da placa`: roda o **mesmo código C** que vai para a placa, só que no seu computador. Serve para
   conferir o protocolo, os cálculos e a gravação sem placa. **Não** serve para validar tempo real: a temporização aqui é a do sistema operacional.

### 11.2. Placa em modo sombra, só com o USB: conexão e gravação

1. Grave um ambiente **sem saídas** (`esp32dev` ou `megaatmega2560`, seção 10) e conecte (seção 10.5).
2. **O que você deve ver**: na aba `REGISTRO`, "Conectado: ESP32 fw 1.0 (protocolo 1) [modo sombra: saídas desligadas]" (ou "Arduino (AVR)" no Mega); no `PAINEL`, a luz **CONECTADO** e a lâmpada **MODO SOMBRA** acesa.
3. Clique em `ARMAR SAÍDAS`: o aplicativo explica que o firmware está em modo sombra e **não** manda o comando.
4. `LER DA ECU`, depois `SALVAR .KECU`. Em seguida teste a **persistência**: mude `REV_LIMITE_RPM` (por exemplo, de 6500 para 6400), `ENVIAR PARÂMETROS`, `GRAVAR NA MEMÓRIA` (no Mega, espere cerca de 3 s; o painel pode
   mostrar `SEM SINAL` por instantes), **reinicie a placa** (botão RESET/EN ou tire e ponha a energia), `CONECTAR` de novo e `LER DA ECU`: o valor tem de ser 6400, e a lâmpada **MEMÓRIA** tem de continuar apagada.
5. **Se não conectar**: confira o cabo (de dados), o driver, a porta escolhida, se outro programa está com a porta aberta e a velocidade (115200). Depois de abrir a porta, a placa pode levar 2 s para subir (seção 13.1).

### 11.3. Entradas analógicas (sensores)

1. Troque cada sensor por um potenciômetro (ou por resistores fixos, no caso dos NTC) **pelo mesmo circuito** que o sensor real usaria (divisores da seção 8). Para os NTC, use a tabela de tensões da seção 8.4.
2. Em cada canal, confira **três pontos** (baixo, médio e alto): meça a tensão **no pino** e **no sensor** com o multímetro e compare com o que o `PAINEL` mostra. No ESP32, se o erro passar de 1 a 2 %, corrija a razão do divisor;
   no Mega, meça o 5 V e corrija `KECU_VREF_MV` (seção 8.10). Bateria: use a fonte de bancada em 10 V, 12 V e 14,4 V (seção 8.7).
3. **Teste de falha**: ponha o pino em curto com o GND, depois ligue-o ao 5 V (pelo divisor) e, por fim, solte o fio. A lâmpada do canal (**ECT, IAT, MAP, TPS, BATERIA** ou **LAMBDA**) tem de acender e o valor
   de emergência da seção 8.1 entra. A pressão de óleo **não** tem lâmpada.
4. **No Mega**, um sensor de 5 V ligado direto fica com o pino **flutuando** quando o fio solta, e a leitura é imprevisível. Se você quer que o fio solto seja detectado, ponha **100 kΩ do pino ao GND** em cada entrada
   (isso carrega um pouco o sensor, 1 a 2 % para um potenciômetro de TPS de 5 kΩ: refaça a medição da seção 8.5 **com** o resistor ligado). No ESP32, o resistor de baixo do divisor já faz esse papel.

### 11.4. Rotação (CKP), velocidade (VSS) e chaves

1. **Pulsos uniformes primeiro.** Ligue o gerador de função (onda quadrada de 0 a 3,3 V no ESP32, 0 a 5 V no Mega, ciclo de 50 %) ao pino CKP, com a placa em modo sombra e **sem sinal** (o campo só muda com o motor parado),
   e ponha `MODO_SINAL = 1` e `DENTES_RODA = 60`. Com isso, **a rotação mostrada em rpm é igual à frequência em Hz** (1000 Hz = 1000 rpm). Confira em 800, 3000 e 6000 Hz; as lâmpadas **MOTOR** e **SYNC** acendem.
   (Nesse modo o planejador **não** agenda nada: ele só mede a rotação.)
2. **Roda 60-2 simulada.** Volte para `MODO_SINAL = 0`, `DENTES_RODA = 60`, `DENTES_FALTANDO = 2`. O sinal tem de ter, por volta, **58 pulsos iguais e uma falha do tamanho de 2 dentes**: o período entre
   dentes é `1.000.000 ÷ rpm` µs (1000 rpm = 1000 µs por dente; 3000 rpm = 333 µs). Um segundo microcontrolador ou um gerador de formas de onda arbitrárias resolve.
   - **SYNC** acende depois de cerca de **2 voltas** (a ECU só confia na sincronia depois de ver a falha no lugar certo uma segunda vez).
   - Acrescente um pulso falso no meio da volta: **SYNC** apaga por instantes (até a ECU ver a falha no lugar certo de novo) e a lâmpada de erro **ROTAÇÃO** acende por 2 s; depois, tudo volta ao normal.
   - Pare o sinal: depois de 250 ms o painel mostra rotação zero e **MOTOR**/**SYNC** apagam.
3. **VSS.** Com `VSS_PULSOS_KM = 4000`, `km/h = frequência (Hz) × 3600 ÷ 4000`; 111 Hz dão cerca de 100 km/h. Sem pulso por 1,5 s, a velocidade volta a zero.
4. **Chaves.** Ligue a chave *two-step* (e a *launch*) entre o pino e o GND: o botão correspondente no topo do painel acompanha a chave. Com `HAB_TWO_STEP` ligada, a chave acionada e a rotação acima de `TWO_STEP_RPM`,
   a lâmpada **CORTE** acende. O *launch* só atua com `HAB_LAUNCH`, a chave, TPS acima de `LAUNCH_TPS_MIN` **e** velocidade abaixo de `LAUNCH_VEL_MAX`.

### 11.5. Cálculos de pulso e de avanço (ainda sem saídas)

Com o gerador num valor fixo (por exemplo, 3000 rpm), MAP e TPS nos potenciômetros e os NTC em resistores fixos, **confira à mão** o que o painel mostra (as fórmulas estão na seção 12.3): `VE` e `AVANÇO` vêm
da interpolação dos mapas nos eixos de rotação e de MAP, e o `PULSO` é `ReqFuel × VE × MAP × ...` mais o tempo morto. Passe da rotação do limitador (`REV_LIMITE_RPM`): **CORTE** acende; abaixo de
`limite − REV_HISTERESE_RPM`, apaga. Clique em `ARMAR SAÍDAS` só para ver o aviso: em modo sombra, **nada** é acionado.

### 11.6. Estágio de potência com cargas falsas (sem motor)

Monte o estágio de potência da seção 9 com a **fonte de bancada limitada a cerca de 2 A** (suba só se precisar), **lâmpadas de 12 V no lugar dos bicos**, uma lâmpada (ou a bobina de verdade, com um faísqueiro de bancada) no
lugar do primário, e o osciloscópio no gate de cada transistor. **A faísca da bobina é perigosa: não toque, e afaste tudo o que for inflamável.**

1. **Repouso (ainda em modo sombra):** ligue a placa, reinicie-a várias vezes e observe os gates: tem de ficar em **0 V** o tempo todo, **sem pico** na hora do boot (dispare o osciloscópio na borda de subida). As lâmpadas ficam apagadas.
2. Grave o ambiente com saídas (`esp32dev_saidas` ou `megaatmega2560_saidas`) e repita o passo 1: continua em 0 V, mesmo com a placa reiniciando.
3. Com a placa conectada e **desarmada**, gere a roda 60-2 a 1000 rpm: **nenhum pulso** nos gates.
4. `ARMAR SAÍDAS` (confirme o aviso; a lâmpada **SAÍDAS ARMADAS** acende). A 1000 rpm, uma volta dura 60 ms, e você tem de ver:
   - **Bicos**: as quatro saídas **sobem juntas**, **uma vez por volta** (a cada 60 ms), junto com a primeira borda de subida do sinal de rotação **depois da falha**. A largura é `inj_us = pulso ÷ 2 + tempo morto`
     (o painel mostra o `PULSO` como `pulso + tempo morto`, então a largura medida é **menor** que ele: compare com a conta da seção 9.2.3).
   - **Bobina**: **dois pulsos por volta** (um a cada 30 ms) em `BOBINA1` (ou, com `-DKECU_BOBINAS=2`, um pulso por volta em cada uma). A largura é o *dwell*: `DWELL_US × 14 ÷ Vbat`, com o fator entre 0,8 e 1,6. Em rotação
     alta o dwell é cortado em 85 % do intervalo entre centelhas.
   - **Ângulo da centelha**: a borda de **descida** da bobina acontece `PMS_OFFSET − avanço` graus **depois** da primeira borda de subida após a falha (e `+180°` para o segundo evento de cada volta). A 1000 rpm,
     1° vale cerca de 167 µs: com `PMS_OFFSET` de 90° e avanço de 15° (o do painel), a descida vem 75° depois, ou cerca de 12,5 ms. **Confira com dois canais do osciloscópio** (sinal de rotação e gate da bobina).
5. `ARMAR SAÍDAS` de novo (desarmar): os pulsos **param na hora**.
6. **Testes de falha** (armado):
   - pare o gerador (como um motor que apagou): em 250 ms o planejador para e **nenhum gate fica em nível alto**;
   - **desconecte o USB** com a placa alimentada: os pulsos **continuam** (desconectar o notebook não desarma; é por isso que o aplicativo avisa para não conectar e desconectar com o motor funcionando);
   - corte a energia e religue: a placa volta **desarmada**, a menos que você tenha compilado com `KECU_AUTO_ARMAR=1`;
   - baixe a fonte para 8 V e depois 6 V (a partida de um carro): **a placa não pode reiniciar**. Se reiniciar, o conversor ou o capacitor de reserva (seção 6.1) são insuficientes.
7. **Aquecimento**: deixe tudo funcionando por 10 a 15 minutos em rotação alta e confira, com a mão **longe** de partes energizadas ou com um termômetro infravermelho, a temperatura do MOSFET, do IGBT, do TVS e dos resistores.
   Meça no osciloscópio a **tensão de pico no dreno** (tem de ficar abaixo do VDSS do MOSFET) e, na bobina, a forma do pulso.

### 11.7. Primeiro teste no carro: só leitura

Corresponde à fase 3 da seção 3.4: placa em **modo sombra**, a injeção original comandando o motor, ligações **só de leitura** (seguidor de tensão ou resistor em série, sem dividir sensor com a ECU original). Confira a rotação
contra o conta-giros do painel do carro, a temperatura da água contra o ponteiro, a bateria contra o multímetro, e a **sincronia** (**SYNC**) na partida, em marcha lenta e em aceleração. Anote as **perdas de sincronia** e o ruído.

### 11.8. Lista final antes de armar no carro

Marque cada item com **sim** antes da fase 5 (seção 3.4):

- [ ] os quatro ambientes compilaram com as ferramentas reais e a placa escolhida rodou com a calibração salva (seção 14);
- [ ] todos os sensores foram conferidos em 3 pontos e os erros de fio solto aparecem;
- [ ] a sincronia na roda **real** é estável, inclusive na partida com bateria fraca;
- [ ] o `PMS_OFFSET` foi conferido com **luz estroboscópica** (seção 12.2) com a injeção original;
- [ ] o estágio de potência passou pela seção 11.6 (repouso a 0 V, pulsos corretos, aquecimento, tensão de pico, fusíveis e chave geral);
- [ ] existe um **limite de dwell no hardware** (seção 9.3.3) e o circuito da **bomba** é seguro (seção 9.5);
- [ ] um extintor e uma pessoa ao lado do carro; combustível sem vazamento; ambiente aberto e ventilado.

---

## 12. Roteiro de calibração (da primeira partida à estrada)

Faça **uma coisa de cada vez**, anote o que mudou e salve um `.KECU` novo a cada versão (`SALVAR .KECU`). A ordem importa: sincronia e ponto primeiro, depois combustível, depois o resto. Tudo isto assume que a seção 11 inteira
passou e que você sabe usar luz estroboscópica e um controlador de banda larga (seções 8.9 e 12.2). **Não há sensor de detonação**: um erro de calibração do avanço ou da mistura pode **destruir o motor**.

### 12.1. O que são os valores de fábrica

Os valores de `PADRÕES DE FÁBRICA` são **exemplos genéricos**, não uma calibração do seu motor:

- parâmetros: `REQ_FUEL_US` 8500 µs, tempo morto 900 µs (e 150 µs/V), `DWELL_US` 3000 µs, limitador em 6500 rpm, e assim por diante (`PARÂMETROS`);
- eixos: 16 pontos de rotação, de 500 a 7500 rpm, e 16 pontos de MAP, de 20 a 110 kPa;
- **mapa de VE**: um formato genérico (de 20 % a 110 %), **sem relação** com a eficiência volumétrica real do seu motor;
- **mapa de ignição**: de cerca de **6°** (carga alta, rotação baixa) a cerca de **35°** (carga baixa, rotação alta), 27° na linha de 110 kPa a 6500 rpm. Não foi feito para o seu motor;
- **alvo de lambda**: 1,00 até a carga média e descendo até 0,88 nas linhas de carga mais alta.

**Antes da primeira partida**, ponha o avanço **bem abaixo** do que o manual do seu motor indica (por exemplo, 5° a 10° a menos, principalmente em carga alta), e **suba aos poucos**. Se você não tem a curva original do
motor, procure-a antes de continuar.

### 12.2. `PMS_OFFSET`: acertar o ponto de ignição com a luz estroboscópica

**Definição.** `PMS_OFFSET_X10` (décimos de grau; de 0 a 360,0°) é o ângulo do virabrequim, **no sentido de rotação**, do primeiro dente **depois da falha** (a borda que o firmware conta: subida, por padrão) até o **PMS dos
cilindros 1 e 4**. O PMS dos cilindros 2 e 3 vem 180° depois. A centelha acontece em `PMS − avanço`.

**Estimativa inicial.** Com o PMS do cilindro 1 alinhado na marca, gire o virabrequim devagar, no sentido de rotação, e conte quantos dentes passam pelo sensor entre o primeiro dente depois da falha e o PMS. O ângulo é
`dentes × 6°` (roda 60-2) ou `dentes × 10°` (36-1). Se o PMS vier **antes** da falha, use `360° − (ângulo do PMS até o primeiro dente depois da falha)`. A incerteza é de meio dente (3° na 60-2); a luz estroboscópica corrige.
Verifique também se a marca da polia ou do volante é mesmo o PMS (não presuma).

**Ajuste** (motor **parado** para gravar este campo; o aplicativo recusa com o motor girando):

1. Coloque a estimativa em `PMS_OFFSET_X10` (o valor em décimos: 90° = 900) e `ENVIAR PARÂMETROS`.
2. Dê a partida. **Abaixo de 400 rpm o avanço é fixo, de 10°**; com o motor em marcha lenta, vale o avanço do mapa, que o painel mostra em `AVANÇO`. Aponte a luz estroboscópica para o cilindro 1 e leia o avanço **real** na marca.
3. Se a luz mostrar **menos** avanço que o comandado, **diminua** o `PMS_OFFSET`; se mostrar **mais**, aumente: `novo offset = offset − (avanço comandado − avanço lido)`. Pare o motor, mude o valor, repita.
4. Com o offset certo em marcha lenta, **confira em 2000 e 3000 rpm** com um mapa de ignição **plano** (todas as células com o mesmo valor, por exemplo 15°): a luz tem de mostrar o mesmo ângulo que o comandado. Um erro que
   **cresce com a rotação** indica atraso no condicionamento do sinal (filtro ou histerese na entrada), torção ou folga da roda, ou o atraso da interrupção (seção 13.2).
5. **Com distribuidor**: confira o avanço nos **quatro** cilindros (mude a pinça da luz). Uma diferença de vários graus entre cilindros indica a **fase do rotor** (a ponta do rotor não está bem centrada no terminal no momento da
   centelha): gire o corpo do distribuidor para centralizar, conforme o manual do seu carro. Como não há sensor de fase, a ECU dispara o par **1 e 4** (e o par **2 e 3**) a cada volta e **é o rotor quem escolhe** o cilindro certo.
6. Só depois que o ângulo estiver certo, restaure o seu mapa de ignição. **Não use o `PMS_OFFSET` para ajustar o avanço**: o avanço é do mapa.
7. Com o motor parado, `GRAVAR NA MEMÓRIA`.

### 12.3. `ReqFuel` e a conta do bico

**Como o firmware calcula o pulso** (a cada 5 ms):

```
pulso        = ReqFuel × (VE ÷ 100) × (MAP ÷ 100) × (293,15 ÷ (273,15 + IAT)) ÷ λ_alvo × aquecimento × (1 + AE ÷ 100)
aquecimento  = 1 + (80 − ECT) ÷ 80 × 0,4        (só abaixo de 80 °C: +0,5 % por °C, fixo no firmware)
tempo morto  = TEMPO_MORTO_US + COEF × (14 − Vbat)        (limitado a 0 a 5000 µs)
cada abertura:       inj_us = pulso ÷ 2 + tempo morto        (2 aberturas por ciclo, uma por volta)
aberto por ciclo:    pulso + 2 × tempo morto
partida (< 400 rpm): pulso = ReqFuel × 0,4 × aquecimento
```

Nas condições de **referência** (VE = 100 %, MAP = 100 kPa, ar a 20 °C, λ = 1), `pulso = ReqFuel`. Ou seja, o `ReqFuel` é o **tempo efetivo de abertura** (sem o tempo morto) que um bico precisa, por ciclo, para queimar toda a cilindrada,
nas condições de referência, na mistura estequiométrica:

```
ReqFuel = (ρ_ref × V_d ÷ AFR_est) ÷ (soma das vazões mássicas dos bicos ligados)
```

- `ρ_ref` = **1,188 g/L** (ar seco a 20 °C e 100 kPa); `V_d` = cilindrada **total**, em litros; `AFR_est` = relação ar/combustível **estequiométrica do seu combustível** (14,7 para gasolina pura; **menor** para
  gasolina com etanol, e **o valor depende da mistura**: calcule para o que você usa). O padrão `AFR_ESTEQ_X10 = 130` do aplicativo é só um exemplo e afeta **apenas o AFR mostrado** no painel, **não** o cálculo do pulso.
- A vazão do bico vem em cm³/min **numa pressão de combustível específica**; ela costuma variar com a raiz da pressão. Use a vazão **na pressão do seu sistema** (um monoponto trabalha numa pressão bem menor que um multiponto) e
  a densidade do combustível (cerca de 0,74 g/cm³ para gasolina pura).
- **Exemplo ilustrativo** (números inventados, **não** são uma recomendação): motor de 2,0 L, 4 bicos de 240 cm³/min. Cada bico dá 240 × 0,74 ÷ 60 = 2,96 g/s, e os quatro, 11,84 g/s. O ar por ciclo é 1,188 × 2,0 = 2,376 g.
  Com AFR 14,7: combustível = 0,1616 g e `ReqFuel` = 0,1616 ÷ 11,84 = **13,65 ms** (13650 µs). Com AFR 13,0: **15,44 ms**. Ambos cabem na faixa do parâmetro (500 a 30000 µs).
- **Confira o *duty* dos bicos**: `duty = (pulso + 2 × tempo morto) ÷ (120000 ÷ rpm)`, com os tempos em ms. A 6000 rpm, um ciclo dura 20 ms. No exemplo, com o `ReqFuel` de 15,44 ms (AFR 13,0), em carga plena (VE ≈ 90 %, λ-alvo 0,88, tempo morto 1 ms), o pulso fica perto de 15,8 ms
  e o *duty* perto de **89 %**: **alto demais** (mantenha **abaixo de uns 80 %**, para sobrar margem). Se passar disso, os bicos são pequenos para o motor.

**Ajuste fino do VE com a lambda** (motor quente, condição estável, nunca em transiente; espere a leitura assentar):
`VE novo = VE atual × (λ medida ÷ λ-alvo)`. Se a mistura está **pobre** (λ medida maior que o alvo), o VE sobe; se está **rica**, desce. Mude no máximo 5 a 10 % por rodada. **Nunca deixe a mistura pobre em carga alta**: comece rico
(λ-alvo de 0,85 a 0,90 em carga plena) e só empobreça se tiver como medir o resultado (dinamômetro e controle de temperatura de escape).

### 12.4. Tempo morto

Calibre o `ReqFuel` e o VE **primeiro**, em carga média e alta, onde o tempo morto é uma fração pequena do pulso. Depois ajuste o tempo morto em **marcha lenta e carga baixa**, onde o pulso é curto e o tempo morto pesa mais:

1. Se a lambda está certa nas cargas média e alta e **só** erra em marcha lenta, o problema é o tempo morto: **aumente-o** se estiver pobre em marcha lenta e **diminua** se estiver rico (ele entra **duas vezes** por ciclo).
2. Para o coeficiente de tensão (`TEMPO_MORTO_COEF`), com o motor em marcha lenta quente ligue cargas elétricas fortes (faróis, desembaçador) para a tensão cair uns 0,5 a 1 V e veja se a lambda se mantém: se ficar pobre ao cair a tensão,
   **aumente** o coeficiente; se ficar rica, diminua.

### 12.5. Ignição: *dwell* e avanço

- **`DWELL_US`**: parta da conta da seção 9.3.3 (corrente nominal da bobina) e confirme em bancada. Depois de alguns minutos em rotação alta, confira a **temperatura** da bobina e do IGBT e a qualidade da centelha. Falha em
  rotação alta com a bobina fria pode ser dwell curto, mas lembre que o firmware corta o dwell em 85 % do intervalo entre centelhas (a 6500 rpm, cerca de 3,9 ms): é limite físico.
- **Avanço**: o mapa de ignição é seu. Comece **conservador** (seção 12.1) e suba **1° de cada vez**, numa condição de carga e rotação fixas, medindo o torque (dinamômetro) ou o desempenho, e **pare** quando o ganho acabar,
  **voltando** 2° a 3° para ter margem. Sem sensor de detonação, **ouvir** não basta: a detonação destrói pistão em segundos. Combustível ruim, calor e carga alta pedem **menos** avanço.
- **Partida**: o avanço é **fixo em 10°** abaixo de 400 rpm (está no código, não é parâmetro).

### 12.6. Enriquecimentos: partida, aquecimento e aceleração

- **Aquecimento e partida são fixos no firmware** (`+0,5 % por °C abaixo de 80 °C`; na partida, `ReqFuel × 0,4 × aquecimento`). **Não há tabela nem parâmetro** para isso. Se a mistura a frio ficar longe do alvo, ou o motor
  não pegar a frio ou afogar a quente, a correção hoje exige **editar `kecu_control.c`** (as contas `aquecimento` e o `0,4` da partida) e regravar a placa.
- **Aceleração (AE)**, por parâmetros: `AE_LIMIAR` (taxa de abertura do TPS, em % por segundo; padrão 150), `AE_ENRIQUECIMENTO` (% extra no começo; padrão 30) e `AE_DURACAO_MS` (quanto tempo dura, caindo em reta até zero; padrão 400 ms).
  Com a lambda no painel, dê um golpe de acelerador a partir da marcha lenta: se a mistura **empobrece** (falha, hesitação), aumente o enriquecimento; se **enriquece** (fumaça preta), diminua. O limiar evita que movimentos lentos do pedal disparem o AE.

### 12.7. Marcha lenta

- **Sem válvula de PWM** (o provável no seu carro, seção 9.4): ajuste a rotação à mão e deixe `HAB_MARCHA_LENTA` em zero.
- **Com válvula de PWM**, motor **quente**: ajuste `ML_DUTY_BASE` até a rotação ficar perto de `ML_RPM_QUENTE` com o PI **quase centrado** (o *duty* mostrado em `IAC` perto do base). Depois ajuste `ML_KP` e `ML_KI`:
  se a rotação **oscila** (caça), diminua os dois; se demora a voltar depois de uma carga (ligar o ar-condicionado, a ventoinha), aumente `ML_KI`. Ajuste `ML_RPM_QUENTE` e `ML_RPM_FRIO` ao gosto do motor.
- Ela só controla com o TPS abaixo de `ML_TPS_MAX` (4 %) e a rotação abaixo do alvo + 500 rpm.

### 12.8. Limitador, *two-step* e *launch*

- **Nas primeiras partidas**, ponha o limitador **baixo** (por exemplo, 3500 rpm) até confirmar que a roda, a sincronia e a ignição estão certas, e suba aos poucos até o limite que o motor aguenta. `REV_HISTERESE_RPM` (padrão 150) é a
  rotação abaixo do limite em que o corte volta atrás. `TIPO_CORTE` em 0 ou 2 (seção 9.3.5).
- ***Two-step*** (`HAB_TWO_STEP`, `TWO_STEP_RPM`) e ***launch*** (`HAB_LAUNCH`, `LAUNCH_RPM`, `LAUNCH_TPS_MIN`, `LAUNCH_VEL_MAX`, `LAUNCH_RETARDO`) **só em pista fechada**, com o carro preparado e as pessoas longe. O
  *launch* só atua com o carro quase parado (`LAUNCH_VEL_MAX`, padrão 10 km/h).

### 12.9. Guardar o trabalho

- `GRAVAR NA MEMÓRIA` **só com o motor parado** (a ECU recusa com ele girando); leva cerca de 3 s no Mega.
- **Depois de reiniciar a placa**, `LER DA ECU` e compare com o seu `.KECU`: é isso que garante que a calibração boa voltou.
- Guarde um `.KECU` por versão (com data e uma nota do que mudou). **Nada** se perde se você tem o arquivo.
- Um `ENVIAR TUDO` sem `GRAVAR NA MEMÓRIA` vale **só até a placa reiniciar**: ao ligar, a ECU volta com o que foi gravado.

---

## 13. Armadilhas conhecidas

### 13.1. Abrir a porta serial pode reiniciar a placa

Esta é a armadilha mais importante para quem vai usar o notebook com o motor ligado.

- **Arduino Mega**: nas placas da família Mega, a linha DTR do conversor USB-serial é ligada ao RESET do ATmega2560 por um capacitor de 100 nF; quando o computador "abre" a porta, o DTR muda e a placa **reinicia** [F15]
  (a página que confirmei é a do Mega ADK, que usa o mesmo microcontrolador; **confira o esquema da sua placa**, porque clones variam).
- **ESP32**: os DevKits têm um circuito de auto-reset (DTR e RTS para o EN e o GPIO0) para gravar sem apertar botões [F4]. Em muitas placas, abrir a porta também reinicia o chip, e o ESP32 ainda leva alguns instantes para subir.
- **O aplicativo não consegue evitar isso.** Com a biblioteca `jSerialComm` [F10], o sistema operacional levanta o DTR na chamada `open()`; o mantenedor da biblioteca explica que `clearDTR()` só tem efeito **depois** de a porta estar aberta e que
  **não há configuração do sistema** que impeça isso, em nenhum sistema operacional; ele achou um contorno só para o Windows, numa versão de teste, e indicou o **hardware** como a solução robusta [F14].
  O aplicativo já se defende: manda `HELLO` até 6 vezes, a cada 500 ms, até a placa responder (`docs/PROTOCOLO.md`, seção 11).

**O que isso significa na prática:**

1. **Nunca conecte nem desconecte o aplicativo com o motor funcionando.** A placa reinicia e o motor para: os estágios de potência voltam ao pull-down (desligados) e, com `KECU_AUTO_ARMAR=0`, a ECU volta **desarmada** e o motor
   fica parado até alguém armar de novo (é uma falha segura, mas é uma falha). Com `KECU_AUTO_ARMAR=1` ela rearma sozinha ao subir, mas ainda precisa de cerca de 2 voltas para voltar à sincronia, então o motor engasga.
2. Conecte **antes** de dar a partida e desconecte **depois** de desligar o motor. Se o cabo sair com o motor funcionando e a placa alimentada pelo carro, o motor **continua** (a ECU não depende do notebook).
3. **Solução de hardware** (se você precisa conectar com o motor funcionando): desabilite o auto-reset da sua placa (algumas têm uma trilha ou ponte de solda para isso: a do Mega ADK se chama `RESET-EN` [F15]; em outras, usa-se
   um capacitor entre o RESET/EN e o GND). O método **depende da placa**, e sem o auto-reset você perde a gravação automática. **Teste em bancada** que abrir a porta não reinicia mais a placa antes de confiar nisso.
4. O computador que entra em suspensão, ou um cabo USB que solta, derruba a conexão (o aplicativo mostra `ERRO`), mas a ECU segue rodando.

### 13.2. A interrupção do dente atrasa a centelha

O dente e o alarme das bordas de saída **não se interrompem** (no ESP32, as duas rotinas usam a mesma trava; o AVR não aninha interrupções). Uma borda de saída agendada para o instante em que a interrupção do dente está rodando
**espera** o fim dela. A **centelha** é a borda de descida da bobina, então o atraso vira erro de ângulo: a 6000 rpm, **cada 10 µs valem 0,36°** de virabrequim (100 µs valem 3,6°). No AVR, onde o planejador leva dezenas de
microssegundos por dente (seção 4), esse erro pode passar de alguns graus em rotação alta; no ESP32, deve ser pequeno, mas **não foi medido**. O agendador trata o pulso que chega atrasado: o bico abre na hora e mantém a largura; a
bobina liga na hora e desliga no instante **original** (preserva o ângulo da centelha). Meça o ângulo real com a luz estroboscópica em várias rotações (seção 14).

### 13.3. Gravar na memória

- **Só com o motor parado.** A ECU recusa (`NACK 4`) com o motor girando.
- **Mega**: a EEPROM escreve em torno de 3,4 ms por byte que mudou, então um bloco totalmente novo (889 bytes) ocupa a placa por cerca de **3 s**, com o laço principal parado (a telemetria pausa e o painel mostra `SEM SINAL`
  por instantes). O aplicativo espera até 6 s, em 2 tentativas (`docs/PROTOCOLO.md`, seção 5.6). O cão de guarda é alimentado a cada byte gravado.
- **ESP32**: a escrita na flash (NVS) **bloqueia o laço principal** e desabilita o cache da flash por instantes. As rotinas que as interrupções do dente e do VSS chamam (decodificador, planejador e agendador) **não foram
  marcadas** para ficar na RAM de instruções (`IRAM_ATTR`), então uma interrupção que chegue **durante** a gravação **pode** reiniciar a placa. Com o motor parado não há dentes, mas **ruído no pino CKP** (que não tem filtro no software, seção 7.1.1)
  ou um carro rolando com o motor desligado (VSS) podem gerar essa interrupção. **Não foi verificado em placa.** Grave com o carro parado e longe de fontes de ruído, e depois confira com `LER DA ECU`.
- Uma gravação **interrompida** (queda de energia no meio) é detectada ao ligar: o bloco só é aceito se a assinatura, o tamanho, o CRC e todos os campos conferem; senão a ECU usa os **valores de fábrica** e acende o erro **MEMÓRIA**
  (`docs/PROTOCOLO.md`, seção 10). Por isso, guarde um `.KECU` de cada versão.

### 13.4. Cão de guarda (*watchdog*)

- No **ESP32** o cão de 2 s vale sempre. No **Mega**, só com as saídas habilitadas (`KECU_AVR_WDT`): no modo sombra o Mega roda **sem** cão de guarda.
- Bootloaders antigos do Mega podem deixar o cão armado depois de um reinício causado por ele mesmo, e a placa fica reiniciando sem parar. O projeto tem uma função na seção `.init3` (`kecu_wdt_desligar_no_boot`, em `hal_avr.cpp`)
  que o desliga antes do `main()`. **Não foi testada em placa.**

### 13.5. Outras armadilhas

| Armadilha | Sintoma | O que fazer |
|-----------|---------|-------------|
| Lâmpada **MODO SOMBRA** acesa depois de "gravar o firmware com saídas" | `ARMAR` só mostra o aviso; nenhum bico ou bobina é acionado | O firmware gravado é o **sem saídas**: grave o ambiente `_saidas` e confira que a lâmpada apagou |
| Dois programas na mesma porta (aplicativo, monitor serial, gravação do PlatformIO) | Erro ao abrir a porta ou ao gravar | Só um abre a porta por vez: feche os outros |
| `ENVIAR TUDO` com o motor funcionando | O motor passa instantes com linhas velhas e novas misturadas | Mude pouco de cada vez (seção 10.5) |
| Campos "só parado" (`CILINDROS`, `DENTES_RODA`, `DENTES_FALTANDO`, `MODO_SINAL`, `PMS_OFFSET_X10`) | O aplicativo mostra erro (`NACK 4`) | Pare o motor e envie de novo |
| Sinal de rotação com ruído | Perdas de sincronia, lâmpada **ROTAÇÃO** acende, o motor falha | Condicionamento melhor (Schmitt, blindagem, terra, seção 7.1); o firmware não filtra o pino |
| Tensão cai na partida | A placa reinicia durante a partida | Conversor de entrada larga e capacitor de reserva (seção 6.1); teste a 6 V (seção 11.6) |
| Saída do controlador de banda larga fora de 80 a 4920 mV durante o aquecimento (confira o manual dele) | Lâmpada **LAMBDA** acesa | A lambda só é mostrada (não entra no cálculo): espere o controlador ficar pronto antes de calibrar |
| Pinos errados | Placa não sobe, ou não lê o sinal | Pinos de *strapping*, GPIO16/17 do WROVER, pinos 11 a 13 do Mega (seção 5); ADC2 do ESP32 não convive com o Wi-Fi (não usado aqui) |
| Medidas do ADC | Leituras com erro de vários por cento | ESP32: ±6 % sem calibração (seção 8.10); Mega: meça o AVcc; divisor da bateria do Mega satura em 15 V com 20 k + 10 k (seção 8.7) |

---

## 14. O que ainda precisa ser validado

Esta é a lista honesta do que **não** foi feito. Ela serve de *checklist*: cada item só vira "validado" depois que você o fizer e anotar a data e o resultado. **Nada do que está aqui foi feito em placa, em sensor, em bico,
em bobina nem em motor.**

**O que já foi verificado, no PC** (veja `docs/EXPLICACAO.md` para os comandos e os números): o núcleo em C em várias variantes de compilação, o agendador de bordas, o código das duas placas (`hal_esp32.cpp`, `hal_avr.cpp`) contra
*simuladores* das bibliotecas de cada placa (incluindo testes de mutação), o protocolo, o aplicativo Java inteiro (modelo, comunicação, interface em tela virtual), a integração entre o aplicativo e o **mesmo código C** do firmware rodando no PC, e a
abertura de uma porta serial **de verdade** pelo `jSerialComm` (num pseudo-terminal do Linux).

| # | O que falta | Por que ainda não foi feito | Como validar |
|---|-------------|-----------------------------|--------------|
| 1 | **Compilar com as cadeias reais** os quatro ambientes (`esp32dev`, `esp32dev_saidas`, `megaatmega2560`, `megaatmega2560_saidas`) | Não havia acesso ao PlatformIO, ao xtensa-gcc nem ao avr-gcc | `pio run -e <ambiente>` sem erros; conferir o uso de RAM e de flash (o Mega tem só 8 KB de RAM) |
| 2 | **Rodar nas placas reais**: `HELLO`, telemetria, leitura e escrita de calibração, `GRAVAR`, por USB de verdade | Não havia placa | A seção 11.2 |
| 3 | **Precisão do ADC** de cada canal | Depende do chip, do divisor e do resistor | A seção 8.10 e a seção 11.3 |
| 4 | **Tempo de execução do planejador no AVR** | Só há uma estimativa (dezenas de µs por divisão de 32 bits) | Osciloscópio num pino ligado no começo e no fim da interrupção do dente (seção 4); tem de ficar bem abaixo do menor intervalo entre dentes na rotação máxima (seção 7.1.2) |
| 5 | **Atraso das interrupções e ângulo real da centelha** (ESP32 e Mega) | Depende do hardware real (seção 13.2) | Luz estroboscópica em várias rotações e em aceleração (seção 12.2); osciloscópio (seção 11.6) |
| 6 | **Memória permanente**: persistência, queda de energia no meio de um `GRAVAR`, **tempo real** da gravação no ESP32 | O tempo do NVS **não foi medido**; o do Mega é uma conta (3,4 ms por byte) | Gravar, tirar a energia (inclusive durante o `GRAVAR`), religar e conferir (seções 11.2 e 13.3) |
| 7 | **Cão de guarda** nas duas placas, e a função `.init3` no Mega | Nunca rodou em placa | Provocar um travamento (por exemplo, um laço infinito de teste) e ver o reinício e as saídas desligadas (seção 13.4) |
| 8 | **Reinício ao abrir a porta** (DTR) com as placas e os cabos que você tem | Depende do conversor USB-serial de cada placa (seção 13.1) | Abrir e fechar a conexão com a placa alimentada pela bancada e ver se ela reinicia; decidir se desabilita o auto-reset |
| 9 | **Sinais reais do motor**: sincronia na partida, em marcha lenta e em aceleração; ruído; condicionamento de Hall ou VR; partida com bateria fraca | Sem motor | Seções 7.1 e 11.7: registre as perdas de sincronia |
| 10 | **Estágio de potência real**: aquecimento, tensão de pico no dreno, EMI, o gate em 0 V durante o *boot*, o **limite de dwell em hardware** (que **não existe**) | Sem hardware montado | A seção 11.6 e a seção 9.3.3 |
| 11 | **ESP32: interrupções durante a gravação na flash** (`IRAM_ATTR` nas rotinas chamadas pelas interrupções) | Depende do compilador e da placa reais | Gerar sinal de rotação (ou ruído) durante `GRAVAR`; se a placa reiniciar, marcar as rotinas com `IRAM_ATTR` (seção 13.3) |
| 12 | **Aplicativo fora do Linux**: os `.bat` do Windows e a interface no Windows e no macOS; o `pom.xml` (Maven) | Os testes rodaram em Linux, com tela virtual; os arquivos `.bat` nunca rodaram no Windows; o Maven Central estava inacessível, então o `pom.xml` **nunca foi executado** | Rodar `compilar.bat`, `executar.bat` e `testar.bat`; `mvn package` |
| 13 | **Porta USB real no `jSerialComm`**: velocidade (baud), enquadramento, DTR e adaptadores USB de verdade | O teste automático usa um **pseudo-terminal** (cobre carregar a biblioteca nativa, abrir e fechar, ler, escrever e a queda do cabo), que **não** imita nada disso | Conectar de verdade a uma placa (seção 11.2) |
| 14 | **STM32 e outras placas** | Existe só o contrato `kecu_hal.h`: **não há HAL** de STM32 | Escrever a HAL (seção 4) e repetir esta lista |
| 15 | **O seu carro**: injeção (mono ou multiponto), impedância dos bicos, de onde vem o sinal de rotação, marcha lenta (motor de passo ou PWM), bobina e módulo HEI | A seção 3.1 é indireta | A seção 3.2 |
| 16 | **Circuito da bomba de combustível**, relé principal e interruptor de inércia | Fora do projeto (seção 9.5) | Diagrama do seu carro e revisão de um eletricista automotivo |
| 17 | **Curvas dos sensores** (NTC, TPS, MAP, óleo, lambda) para os **seus** sensores | Os valores de `kecu_config.h` são exemplos | Seção 8 |
| 18 | **Limites funcionais**: sem malha fechada de lambda, sem sensor de detonação, sem injeção sequencial nem sensor de fase, sem aquecimento ou partida ajustáveis, sem motor de passo, sem controle de bomba (seção 2.1) | São escolhas do escopo desta versão, não defeitos | Decida se a versão atende o seu uso |
| 19 | **Aspectos legais, de emissões, de garantia e de seguro** | Fora do escopo | Informe-se antes de modificar o carro |

---

## 15. Fontes consultadas

Os fatos sobre o **seu carro** (seção 3.1) vêm de fontes **indiretas** e valem como pista. Não encontrei o manual de fábrica nem o diagrama elétrico do Kadett GL 2.0 de 1996, e nenhuma norma automotiva (por exemplo, sobre transientes
da rede elétrica) foi consultada. Os componentes citados (SMBJ33A, ISL9V5036, MAX9926, 74LVC1G17, 74HCT) são **exemplos**: confira a folha de dados do que você comprar.

| Ref. | Fonte | O que sustenta |
|------|-------|----------------|
| [F1] | <https://oficinabrasil.com.br/noticia/do-fundo-do-bau/chevrolet-kadett-gsi-1993-a-vocacao-esportiva-com-a-modernidade-da-injecao-eletronica> | História do Kadett: o GSi 2.0 de 1993 com injeção multiponto (Bosch LE-Jetronic, ignição EZK); a injeção monoponto nas versões SL e SL/E; GL e GLS em 1994; o 2.0 do Sport com monoponto em 1996; injeção multiponto digital em 1997 |
| [F2] | <https://www.umec.com.br/bico-injetor-monoponto-gm-kadett-2-0-8v-1991-ate-1996--azul--84349-10618/p> | Existe um bico injetor **monoponto** para o "Kadett 2.0 8v, de 1991 até 1996" |
| [F3] | <https://carroetecnica.com.br/2018/08/02/kadet-1996-nao-entra-em-funcionamento/> | Kadett 1.8 EFI de 1996 com Multec 700: distribuidor com o módulo HEI e o sensor de rotação, sinal de rotação para a UCM, terminais de motor de passo para a marcha lenta |
| [F4] | <https://docs.espressif.com/projects/esptool/en/latest/esp32/advanced-topics/boot-mode-selection.html> | Pinos de *strapping* do ESP32 e o circuito de auto-reset por DTR e RTS |
| [F5] | <https://docs.espressif.com/projects/esp-faq/en/latest/software-framework/peripherals/adc.html> | Erro do ADC do ESP32 de ±6 % sem calibração; capacitor no pino para reduzir ruído |
| [F6] | <https://docs.espressif.com/projects/esp-faq/en/latest/hardware-related/hardware-design.html> | GPIO16 e GPIO17 ficam com a PSRAM nos módulos WROVER |
| [F7] | <https://www.analog.com/en/products/max9926.html> | Interface para sensores de relutância variável (família MAX9924 a MAX9927) |
| [F8] | <https://www.hpacademy.com/courses/efi-tuning-fundamentals/efi-components-high-and-low-impedence-injectors/> | Bicos de alta e de baixa impedância; o *peak-and-hold* |
| [F9] | <https://msefi.com/viewtopic.php?p=249424> | Relato de fórum (**anedótico**) sobre a resistência do bico de TBI; as opiniões divergem |
| [F10] | <https://github.com/Fazecast/jSerialComm> | A biblioteca de serial usada pelo aplicativo (versão 2.11.0; licenças Apache 2.0 e LGPL 3.0) |
| [F11] | <https://documentation.espressif.com/esp32_datasheet_en.pdf> | ESP32 Series Datasheet: pinos de *strapping* e seus pull-ups e pull-downs no reset (GPIO0 e GPIO5 com pull-up; GPIO2 e MTDI com pull-down; MTDO com pull-up); VDD_SDIO; tabela 5-3 de características DC. Os valores RPU = RPD = 45 kΩ, VIH mínimo de 0,75 × VDD e VIL máximo de 0,25 × VDD foram lidos num **espelho** da tabela (<https://deneyapkart.org/en/docs/elektriksel-ozellikler-esp32-2>): confira no PDF oficial |
| [F12] | <https://www.promelec.ru/UPLOAD/xml/pdfbase/ISL9V5036P3.pdf> (cópia de distribuidor da folha de dados Fairchild; produto atual: <https://www.onsemi.com/products/discrete-power-modules/igbts/isl9v5036s3st>) | IGBT de ignição ISL9V5036: grampo de 360 V (330 a 390 V), SCIS de 500 mJ, 46 A a 25 °C, gate de nível lógico, ±10 V de gate, uso em driver de bobina de ignição automotiva |
| [F13] | <https://www.lioncircuits.com/parts/SMBJ33A> | Valores do TVS SMBJ33A (listagem de distribuidor; confira a folha de dados do fabricante) |
| [F14] | <https://github.com/Fazecast/jSerialComm/issues/125> (e <https://forum.inductiveautomation.com/t/jserialcomm-toggles-dtr-when-opening-com-port/80508>) | O sistema operacional levanta o DTR em `open()`; a biblioteca não consegue evitar o reinício da placa; contorno só no Windows, em versão de teste; o hardware é a solução robusta |
| [F15] | <https://github.com/arduino/docs-content/pull/139/files> | Texto da documentação da Arduino (Mega ADK): o DTR é ligado ao reset do ATmega2560 por um capacitor de 100 nF; a trilha `RESET-EN` desabilita o auto-reset |
