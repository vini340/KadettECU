#!/usr/bin/env python3
"""
mutacao_placas.py - teste de mutação do código das placas (src/hal_esp32.cpp e src/hal_avr.cpp).

A ideia: um teste que passa não prova que ele serve para alguma coisa. Aqui o código da placa é QUEBRADO de propósito,
um defeito por vez (uma "mutação": inverter a polaridade de uma saída, esquecer de alimentar o cão de guarda, trocar o
prescaler do timer...), e o roteiro de teste de teste_placas/ roda de novo. Se algum teste falha, ele "matou" o mutante
(bom). Se tudo continua passando, o mutante SOBREVIVEU: o defeito passaria despercebido e falta um teste.

Uso (a partir de firmware/teste_placas):
    python3 mutacao_placas.py esp32            # todas as mutações do ESP32
    python3 mutacao_placas.py avr              # todas as mutações do Arduino Mega
    python3 mutacao_placas.py avr 3 7 12       # só as de índice 3, 7 e 12
    python3 mutacao_placas.py avr --lista      # só mostra a lista, sem rodar

Cada mutante roda numa cópia de src/ em um diretório temporário; os arquivos do projeto não são tocados. Precisa de
make, gcc e g++ (os mesmos do "make test"). Sai com código 0 se todos os mutantes foram mortos e 1 se algum sobreviveu.

Como ler a lista: (nome, variantes em que roda, [(trecho original, trecho mutado), ...]). O trecho original tem de
aparecer EXATAMENTE uma vez no arquivo, senão o script avisa (assim uma edição da HAL que renomeie algo não deixa um
mutante "morto" por engano). Uma mutação que não compila não vale como mutante e também é avisada.

NÃO entram na lista as mutações equivalentes (que não mudam o comportamento, como tirar um "if" que o chamador já
garante). Elas estão comentadas ao lado da lista de cada placa.
"""
import concurrent.futures
import os
import shutil
import subprocess
import sys
import tempfile

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.dirname(AQUI)  # firmware/

# ============================================================================================ ESP32
ESP32 = [
    ("polaridade invertida", ["saidas"],
     [("const bool alto = (ligada != 0) == (KECU_SAIDA_ATIVA_ALTO != 0);", "const bool alto = (ligada != 0) != (KECU_SAIDA_ATIVA_ALTO != 0);")]),
    ("divisor do timer 40", ["saidas"], [("timerBegin(0, 80, true)", "timerBegin(0, 40, true)")]),
    ("relógio começa em zero", ["saidas"], [("timerWrite(s_timer, 1000);", "timerWrite(s_timer, 0);")]),
    ("atenuação 6 dB", ["saidas"],
     [("analogSetPinAttenuation(k_pino_adc[i], ADC_11db);", "analogSetPinAttenuation(k_pino_adc[i], ADC_6db);")]),
    ("sem buffer de recepção maior", ["saidas"], [("Serial.setRxBufferSize(1024);", "")]),
    ("setRxBufferSize depois do begin", ["saidas"],
     [("    Serial.setRxBufferSize(1024);\n    Serial.begin(115200);", "    Serial.begin(115200);\n    Serial.setRxBufferSize(1024);")]),
    ("cão de guarda não alimentado", ["saidas"], [("    esp_task_wdt_reset();\n    kecu_sched_verificar();", "    kecu_sched_verificar();")]),
    ("vigia do laço removido", ["saidas"], [("    esp_task_wdt_reset();\n    kecu_sched_verificar();", "    esp_task_wdt_reset();")]),
    ("cão de guarda sem inscrever o laço", ["saidas"], [("    esp_task_wdt_add(NULL);", "")]),
    ("cão de guarda sem reinício", ["saidas"],
     [("esp_task_wdt_init(KECU_WDT_SEGUNDOS, true);", "esp_task_wdt_init(KECU_WDT_SEGUNDOS, false);")]),
    ("alarme sem margem mínima", ["saidas"], [("if (d < KECU_ALARME_MIN_US) {", "if (d < 0) {")]),
    ("válvula ignora o armado", ["saidas"], [("float p = g_kecu.armado ? percentual : 0.0f;", "float p = percentual;")]),
    ("válvula com 8 bits", ["saidas"], [("#define IAC_BITS 10", "#define IAC_BITS 8")]),
    ("válvula a 20 kHz", ["saidas"], [("#define KECU_IAC_FREQ_HZ 200", "#define KECU_IAC_FREQ_HZ 20000")]),
    ("pino vira saída antes do nível", ["saidas"],
     [("        digitalWrite(s_pino_saida[i], KECU_SAIDA_ATIVA_ALTO ? LOW : HIGH);\n        pinMode(s_pino_saida[i], OUTPUT);",
       "        pinMode(s_pino_saida[i], OUTPUT);\n        digitalWrite(s_pino_saida[i], KECU_SAIDA_ATIVA_ALTO ? LOW : HIGH);")]),
    ("antirruído de 1 leitura", ["saidas"], [("++s_in_conta[canal] >= KECU_DEBOUNCE_LEITURAS", "++s_in_conta[canal] >= 1")]),
    ("chaves sem pull-up", ["saidas"], [("pinMode(PIN_TWO_STEP, INPUT_PULLUP);", "pinMode(PIN_TWO_STEP, INPUT);")]),
    ("serial ignora o espaço livre", ["saidas"],
     [("    if (n > livre) {\n        n = livre;\n    }\n    return (int)Serial.write", "    return (int)Serial.write")]),
    ("serial lê mais do que chegou", ["saidas"],
     [("    if (n > maximo) {\n        n = maximo;\n    }\n    return (int)Serial.readBytes(destino, (size_t)n);",
       "    return (int)Serial.readBytes(destino, (size_t)maximo);")]),
    ("hal_millis vem do relógio de microssegundos", ["saidas"],
     [("return (uint32_t)millis(); /* dá a volta só a cada ~49 dias */", "return hal_micros() / 1000u;")]),
    ("ISR do timer sem seção crítica", ["saidas"],
     [("    portENTER_CRITICAL_ISR(&s_mux);\n    kecu_sched_no_timer();\n    portEXIT_CRITICAL_ISR(&s_mux);", "    kecu_sched_no_timer();")]),
    ("ISR do dente sem seção crítica", ["saidas"],
     [("    portENTER_CRITICAL_ISR(&s_mux);\n    kecu_sched_ancorar(t);\n    kecu_engine_on_tooth(t);\n    portEXIT_CRITICAL_ISR(&s_mux);",
       "    kecu_sched_ancorar(t);\n    kecu_engine_on_tooth(t);")]),
    ("seção da ISR do dente sem sair", ["saidas"],
     [("    kecu_engine_on_tooth(t);\n    portEXIT_CRITICAL_ISR(&s_mux);", "    kecu_engine_on_tooth(t);")]),
    ("ISR do dente sem âncora", ["saidas"], [("    kecu_sched_ancorar(t);\n    kecu_engine_on_tooth(t);", "    kecu_engine_on_tooth(t);")]),
    ("hal_secao_entrar vazio", ["saidas"], [("    portENTER_CRITICAL(&s_mux); /* aceita aninhamento no mesmo núcleo */", "")]),
    ("hal_secao_sair vazio", ["saidas"], [("    portEXIT_CRITICAL(&s_mux);\n}", "}")]),
    ("interrupção do dente sempre na descida", ["saidas"], [("KECU_CKP_BORDA_SUBIDA ? RISING : FALLING", "FALLING")]),
    ("NVS só de leitura", ["saidas"], [("s_nvm_ok = s_prefs.begin(NVM_NAMESPACE, false);", "s_nvm_ok = s_prefs.begin(NVM_NAMESPACE, true);")]),
    ("gravação NVS com resultado invertido", ["saidas"],
     [("return s_prefs.putBytes(NVM_CHAVE, origem, (size_t)n) == (size_t)n ? 0 : 1;",
       "return s_prefs.putBytes(NVM_CHAVE, origem, (size_t)n) == (size_t)n ? 1 : 0;")]),
    ("leitura NVS sem apagar o buffer", ["saidas"],
     [("    memset(destino, 0xFF, (size_t)n); /* memória \"apagada\": o núcleo não acha a assinatura e usa os padrões */\n", "")]),
    ("ADC sem média", ["saidas"], [("return (uint16_t)(soma / KECU_ADC_AMOSTRAS);", "return (uint16_t)(soma / 1);")]),
    ("chave com polaridade invertida", ["saidas"], [("digitalRead(pino) == LOW ? 1 : 0", "digitalRead(pino) == HIGH ? 1 : 0")]),
    ("chave: leituras não precisam ser seguidas", ["saidas"], [("        s_in_conta[canal] = 0;\n    } else if", "    } else if")]),
    ("VSS ligado na ISR do dente", ["saidas"],
     [("attachInterrupt(digitalPinToInterrupt(PIN_VSS), isr_vss, RISING);", "attachInterrupt(digitalPinToInterrupt(PIN_VSS), isr_dente, RISING);")]),
    ("pinos de saída rotacionados", ["saidas"],
     [("digitalWrite(s_pino_saida[saida], alto ? HIGH : LOW);", "digitalWrite(s_pino_saida[(saida + 1) % KECU_SAIDAS], alto ? HIGH : LOW);")]),
    ("alarme sem a base de 64 bits", ["saidas"],
     [("const uint64_t alvo = agora + (uint64_t)(uint32_t)d;", "const uint64_t alvo = (uint64_t)instante_us;")]),
    ("hal_timer_parar não para", ["saidas"], [("    timerAlarmDisable(s_timer);\n#endif\n}", "    (void)s_timer;\n#endif\n}")]),
    ("modo sombra configura os pinos", ["sombra"],
     [("#if KECU_ENABLE_OUTPUTS\nstatic uint8_t s_pino_saida", "#if 1\nstatic uint8_t s_pino_saida"),
      ("#if KECU_ENABLE_OUTPUTS\n    for (int i = 0; i < KECU_SAIDAS; i++) {\n        digitalWrite",
       "#if 1\n    for (int i = 0; i < KECU_SAIDAS; i++) {\n        digitalWrite")]),
    ("modo sombra escreve nos pinos", ["sombra"],
     [("#if KECU_ENABLE_OUTPUTS\nstatic uint8_t s_pino_saida", "#if 1\nstatic uint8_t s_pino_saida"),
      ("hal_saida_escrever(kecu_saida_t saida, uint8_t ligada) {\n#if KECU_ENABLE_OUTPUTS", "hal_saida_escrever(kecu_saida_t saida, uint8_t ligada) {\n#if 1")]),
    ("válvula tocada no modo sombra", ["sombra"],
     [("#if KECU_ENABLE_OUTPUTS\nstatic void iac_iniciar", "#if 1\nstatic void iac_iniciar"),
      ("extern \"C\" void hal_iac_pwm(float percentual) {\n#if KECU_ENABLE_OUTPUTS", "extern \"C\" void hal_iac_pwm(float percentual) {\n#if 1")]),
    ("V3: alarme não estaciona", ["saidas_v3"],
     [("    timerAlarm(s_timer, timerRead(s_timer) + 3000000000ull, false, 0);", "    (void)s_timer;")]),
    ("V3: timer a 80 MHz", ["saidas_v3"], [("timerBegin(1000000)", "timerBegin(80000000)")]),
]

# ============================================================================================ Arduino Mega
# Mutações equivalentes que ficam de fora (o comportamento não muda, então nenhum teste PODE distingui-las):
#   - tirar "if (livre <= 0) return 0;" de hal_serial_escrever: com livre == 0 o "n = livre" já dá n == 0;
#   - tirar o "if (c < 0) break;" de hal_serial_ler: com "available() > 0" o read() nunca devolve -1;
#   - trocar micros_sem_cli() por hal_micros() em isr_vss: dentro da interrupção o bit I já vem desligado e o SREG restaurado
#     também o deixa desligado;
#   - tirar "TIFR1 = _BV(OCF1A)" de hal_timer_parar: o próximo hal_timer_armar apaga o aviso de qualquer jeito;
#   - tirar "TIFR1 = 0xFF" ou "s_voltas = 0" da inicialização do Timer1: o chip e as variáveis estáticas já começam zerados;
#   - 1023 -> 1024 no divisor do ADC: erro de 0,1 % (5 mV), menor que a precisão do próprio ADC.
AVR = [
    # ---------------------------------------------------------------- saídas na PORTA
    ("saída: polaridade invertida", ["saidas", "saidas_baixo"],
     [("    if ((ligada != 0) == (KECU_SAIDA_ATIVA_ALTO != 0)) {\n        PORTA |= bit;", "    if ((ligada != 0) != (KECU_SAIDA_ATIVA_ALTO != 0)) {\n        PORTA |= bit;")]),
    ("saída: bit deslocado de um", ["saidas"], [("(uint8_t)(1u << (unsigned)saida);", "(uint8_t)(1u << ((unsigned)saida + 1u));")]),
    ("saída: PORTA = bit (apaga as outras)", ["saidas"], [("        PORTA |= bit;\n    } else {", "        PORTA = bit;\n    } else {")]),
    ("saída: desligar uma desliga todas", ["saidas"], [("        PORTA &= (uint8_t)~bit;\n    }\n#else", "        PORTA &= 0;\n    }\n#else")]),
    # ---------------------------------------------------------------- Timer1 e relógio
    ("Timer1: prescaler 64 em vez de 8", ["saidas"], [("TCCR1B = (uint8_t)_BV(CS11);", "TCCR1B = (uint8_t)(_BV(CS11) | _BV(CS10));")]),
    ("Timer1: continua em PWM de 8 bits", ["saidas"], [("        TCCR1A = 0;\n", "")]),
    ("Timer1: relógio começa em zero", ["saidas"], [("TCNT1 = 2000; /* 1000 us: o núcleo não gosta de começar do zero */", "TCNT1 = 0;")]),
    ("Timer1: sem interrupção de estouro", ["saidas"], [("TIMSK1 = (uint8_t)_BV(TOIE1);", "TIMSK1 = 0;")]),
    ("Timer1: estouro não conta voltas", ["saidas"], [("ISR(TIMER1_OVF_vect) {\n    s_voltas++;\n}", "ISR(TIMER1_OVF_vect) {\n}")]),
    ("relógio: voltas << 16", ["saidas"], [("return (voltas << 15) | (uint32_t)(ticks >> 1);", "return (voltas << 16) | (uint32_t)(ticks >> 1);")]),
    ("relógio: ticks >> 2", ["saidas"], [("return (voltas << 15) | (uint32_t)(ticks >> 1);", "return (voltas << 15) | (uint32_t)(ticks >> 2);")]),
    ("relógio: estouro pendente ignorado", ["saidas"],
     [("(estamos com as interrupções desligadas) */\n    if ((TIFR1 & _BV(TOV1)) != 0 && ticks < 0x8000u) {\n        voltas++;\n    }\n",
       "(estamos com as interrupções desligadas) */\n")]),
    ("relógio: sem a guarda ticks < 0x8000", ["saidas"],
     [("(estamos com as interrupções desligadas) */\n    if ((TIFR1 & _BV(TOV1)) != 0 && ticks < 0x8000u) {",
       "(estamos com as interrupções desligadas) */\n    if ((TIFR1 & _BV(TOV1)) != 0) {")]),
    ("alarme: estouro pendente ignorado", ["saidas"],
     [("    if ((TIFR1 & _BV(TOV1)) != 0 && ticks < 0x8000u) {\n        voltas++;\n    }\n    const uint32_t agora_us", "    const uint32_t agora_us")]),
    ("alarme: sem a guarda ticks < 0x8000", ["saidas"],
     [("    if ((TIFR1 & _BV(TOV1)) != 0 && ticks < 0x8000u) {\n        voltas++;\n    }\n    const uint32_t agora_us",
       "    if ((TIFR1 & _BV(TOV1)) != 0) {\n        voltas++;\n    }\n    const uint32_t agora_us")]),
    ("hal_micros sem desligar as interrupções", ["saidas"],
     [("    const uint8_t sreg = SREG;\n    cli();\n    const uint32_t t = micros_sem_cli();", "    const uint8_t sreg = SREG;\n    const uint32_t t = micros_sem_cli();")]),
    ("hal_micros religa à força", ["saidas"], [("    SREG = sreg;\n    return t;", "    sei();\n    return t;")]),
    ("hal_millis vem do relógio de microssegundos", ["saidas"], [("return (uint32_t)millis(); /* Timer0 do Arduino; dá a volta só a cada ~49 dias */", "return hal_micros() / 1000u;")]),
    # ---------------------------------------------------------------- comparador A (o alarme das bordas)
    ("alarme: sem leitura fresca do contador", ["saidas"],
     [("    /* a conta acima gastou alguns ticks: confere contra uma leitura fresca para nunca escrever no passado */\n"
       "    const uint16_t fresco = TCNT1;\n"
       "    if ((int16_t)(alvo - fresco) < (int16_t)KECU_ALARME_MIN_TICKS) {\n"
       "        alvo = (uint16_t)(fresco + (uint16_t)KECU_ALARME_MIN_TICKS);\n"
       "    }\n", "")]),
    ("alarme: margem mínima de 1 tick", ["saidas"], [("#define KECU_ALARME_MIN_TICKS 8", "#define KECU_ALARME_MIN_TICKS 1")]),
    ("alarme: passo máximo de 40 ms", ["saidas"], [("#define PASSO_MAX_US 16000", "#define PASSO_MAX_US 40000")]),
    ("alarme: sem limite de passo", ["saidas"],
     [("    if (d_us > (int32_t)PASSO_MAX_US) {\n        d_us = (int32_t)PASSO_MAX_US; /* espera longa: acorda no meio do caminho e reprograma */\n    }\n", "")]),
    ("alarme: sem tratar borda vencida", ["saidas"],
     [("    if (d_ticks < (int32_t)KECU_ALARME_MIN_TICKS) {\n        d_ticks = (int32_t)KECU_ALARME_MIN_TICKS; /* já venceu ou está colado: o mais cedo possível */\n    }\n", "")]),
    ("alarme: aviso antigo não apagado", ["saidas"],
     [("    OCR1A = alvo;\n    TIFR1 = _BV(OCF1A); /* apaga um aviso antigo (escrever 1 apaga) */\n", "    OCR1A = alvo;\n")]),
    ("alarme: escreve 0 em TIFR1", ["saidas"], [("TIFR1 = _BV(OCF1A); /* apaga um aviso antigo (escrever 1 apaga) */", "TIFR1 = 0;")]),
    ("alarme: nunca habilita a interrupção", ["saidas"], [("    TIMSK1 |= _BV(OCIE1A);\n    SREG = sreg;\n}", "    SREG = sreg;\n}")]),
    ("alarme: TIMSK1 = (derruba o estouro)", ["saidas"], [("    TIMSK1 |= _BV(OCIE1A);\n    SREG = sreg;\n}", "    TIMSK1 = _BV(OCIE1A);\n    SREG = sreg;\n}")]),
    ("alarme: religa com sei()", ["saidas"], [("    TIMSK1 |= _BV(OCIE1A);\n    SREG = sreg;\n}", "    TIMSK1 |= _BV(OCIE1A);\n    sei();\n}")]),
    ("alarme: sem desligar as interrupções", ["saidas"],
     [("    const uint8_t sreg = SREG;\n    cli();\n    const uint16_t ticks = TCNT1;", "    const uint8_t sreg = SREG;\n    const uint16_t ticks = TCNT1;")]),
    ("parar: não desliga a interrupção", ["saidas"],
     [("    TIMSK1 &= (uint8_t)~_BV(OCIE1A);\n    TIFR1 = _BV(OCF1A);\n    SREG = sreg;", "    TIFR1 = _BV(OCF1A);\n    SREG = sreg;")]),
    ("parar: TIMSK1 = 0 (derruba o estouro)", ["saidas"],
     [("    TIMSK1 &= (uint8_t)~_BV(OCIE1A);\n    TIFR1 = _BV(OCF1A);", "    TIMSK1 = 0;\n    TIFR1 = _BV(OCF1A);")]),
    ("parar: sem desligar as interrupções", ["saidas"],
     [("extern \"C\" void hal_timer_parar(void) {\n    const uint8_t sreg = SREG;\n    cli();", "extern \"C\" void hal_timer_parar(void) {\n    const uint8_t sreg = SREG;")]),
    ("interrupção do alarme não chama o agendador", ["saidas"], [("ISR(TIMER1_COMPA_vect) {\n    kecu_sched_no_timer();\n}", "ISR(TIMER1_COMPA_vect) {\n}")]),
    ("vigia do laço removido", ["saidas"], [("    kecu_sched_verificar();\n}", "}")]),
    # ---------------------------------------------------------------- interrupções dos sensores de rotação
    ("dente: sem âncora", ["saidas"], [("    kecu_sched_ancorar(t);\n    kecu_engine_on_tooth(t);", "    kecu_engine_on_tooth(t);")]),
    ("dente: âncora depois do núcleo", ["saidas"],
     [("    kecu_sched_ancorar(t);\n    kecu_engine_on_tooth(t);", "    kecu_engine_on_tooth(t);\n    kecu_sched_ancorar(t);")]),
    ("VSS ligado na interrupção do dente", ["saidas"],
     [("attachInterrupt(digitalPinToInterrupt(PIN_VSS), isr_vss, RISING);", "attachInterrupt(digitalPinToInterrupt(PIN_VSS), isr_dente, RISING);")]),
    ("VSS na borda de descida", ["saidas"], [("isr_vss, RISING);", "isr_vss, FALLING);")]),
    ("dente sempre na borda de descida", ["saidas"], [("KECU_CKP_BORDA_SUBIDA ? RISING : FALLING", "FALLING")]),
    # ---------------------------------------------------------------- seção crítica
    ("seção: sem aninhamento", ["saidas"],
     [("    if (s_secao_nivel == 0) {\n        s_secao_sreg = sreg; /* dentro de uma interrupção o bit I já vem limpo: a saída então não reabilita nada */\n    }\n",
       "    s_secao_sreg = sreg;\n")]),
    ("seção: sair sempre religa", ["saidas"], [("        SREG = s_secao_sreg;\n    }\n}", "        sei();\n    }\n}")]),
    ("seção: sair a mais desequilibra", ["saidas"], [("if (s_secao_nivel > 0 && --s_secao_nivel == 0) {", "if (--s_secao_nivel == 0) {")]),
    ("seção: entrar sem desligar as interrupções", ["saidas"],
     [("    const uint8_t sreg = SREG;\n    cli();\n    if (s_secao_nivel == 0) {", "    const uint8_t sreg = SREG;\n    if (s_secao_nivel == 0) {")]),
    # ---------------------------------------------------------------- sensores e chaves
    ("ADC: não descarta a 1ª conversão", ["saidas"], [("    (void)analogRead(pino); /* descartada: ainda carrega o canal anterior */\n", "")]),
    ("ADC: referência de 1100 mV", ["saidas"], [("(soma * (uint32_t)KECU_VREF_MV + divisor / 2u)", "(soma * 1100u + divisor / 2u)")]),
    ("ADC: canal errado", ["saidas"], [("const uint8_t pino = k_pino_adc[canal];", "const uint8_t pino = k_pino_adc[(canal + 1) % KECU_ADC_CANAIS];")]),
    ("chave: polaridade invertida", ["saidas"], [("digitalRead(pino) == LOW ? 1 : 0", "digitalRead(pino) == HIGH ? 1 : 0")]),
    ("chave: antirruído de 1 leitura", ["saidas"], [("++s_in_conta[canal] >= KECU_DEBOUNCE_LEITURAS", "++s_in_conta[canal] >= 1")]),
    ("chave: leituras não precisam ser seguidas", ["saidas"], [("        s_in_conta[canal] = 0;\n    } else if", "    } else if")]),
    ("chave: estado nunca muda", ["saidas"], [("        s_in_estavel[canal] = leitura;\n", "")]),
    ("chave two-step sem pull-up", ["saidas"], [("pinMode(PIN_TWO_STEP, INPUT_PULLUP);", "pinMode(PIN_TWO_STEP, INPUT);")]),
    ("chave launch sem pull-up", ["saidas"], [("pinMode(PIN_LAUNCH, INPUT_PULLUP);", "pinMode(PIN_LAUNCH, INPUT);")]),
    # ---------------------------------------------------------------- serial
    ("serial: escreve sem olhar o espaço livre", ["saidas"], [("    if (n > livre) {\n        n = livre; /* Serial.write espera quando o buffer enche: só escreve o que cabe */\n    }\n", "")]),
    ("serial: lê além do máximo", ["saidas"], [("    while (n < maximo && Serial.available() > 0) {", "    while (Serial.available() > 0) {")]),
    ("serial a 57600 baud", ["saidas"], [("Serial.begin(115200);", "Serial.begin(57600);")]),
    # ---------------------------------------------------------------- EEPROM
    ("EEPROM: sem wdt_reset na gravação", ["saidas"], [("        wdt_reset(); /* a gravação passa de 2 s: alimenta o cão de guarda a cada byte */\n", "")]),
    ("EEPROM: leitura deslocada de um byte", ["saidas"], [("destino[i] = EEPROM.read(KECU_NVM_ENDERECO + i);", "destino[i] = EEPROM.read(KECU_NVM_ENDERECO + i + 1);")]),
    ("EEPROM: gravação deslocada de um byte", ["saidas"], [("EEPROM.update(KECU_NVM_ENDERECO + i, origem[i]);", "EEPROM.update(KECU_NVM_ENDERECO + i + 1, origem[i]);")]),
    ("EEPROM: leitura sem limite de tamanho", ["saidas"],
     [("extern \"C\" int hal_nvm_ler(uint8_t *destino, int n) {\n    if (n < 0 || n > (int)EEPROM.length() - KECU_NVM_ENDERECO) {",
       "extern \"C\" int hal_nvm_ler(uint8_t *destino, int n) {\n    if (n < 0 || n > 1000000) {")]),
    ("EEPROM: gravação sem limite de tamanho", ["saidas"],
     [("extern \"C\" int hal_nvm_gravar(const uint8_t *origem, int n) {\n    if (n < 0 || n > (int)EEPROM.length() - KECU_NVM_ENDERECO) {",
       "extern \"C\" int hal_nvm_gravar(const uint8_t *origem, int n) {\n    if (n < 0 || n > 1000000) {")]),
    ("EEPROM: aceita tamanho negativo", ["saidas"],
     [("extern \"C\" int hal_nvm_gravar(const uint8_t *origem, int n) {\n    if (n < 0 || n > (int)EEPROM.length() - KECU_NVM_ENDERECO) {",
       "extern \"C\" int hal_nvm_gravar(const uint8_t *origem, int n) {\n    if (n > (int)EEPROM.length() - KECU_NVM_ENDERECO) {")]),
    # ---------------------------------------------------------------- válvula de marcha lenta
    ("válvula: Timer2 no prescaler padrão", ["saidas"],
     [("    TCCR2B = (uint8_t)((TCCR2B & (uint8_t)~(_BV(CS22) | _BV(CS21) | _BV(CS20))) | _BV(CS22) | _BV(CS20));\n", "")]),
    ("válvula: Timer2 com prescaler 64", ["saidas"], [("| _BV(CS22) | _BV(CS20));", "| _BV(CS22));")]),
    ("válvula: pino não vira saída", ["saidas"], [("    pinMode(PIN_IAC, OUTPUT);\n", "")]),
    ("válvula ignora o armado", ["saidas"], [("float p = g_kecu.armado ? percentual : 0.0f;", "float p = percentual;")]),
    ("válvula sem limite superior", ["saidas"], [("    if (p > 100.0f) {\n        p = 100.0f;\n    }\n", "")]),
    ("válvula sem limite inferior", ["saidas"], [("    if (p < 0.0f) {\n        p = 0.0f;\n    }\n", "")]),
    ("válvula: escala 100/255", ["saidas"], [("(int)(p * 255.0f / 100.0f + 0.5f)", "(int)(p * 100.0f / 255.0f + 0.5f)")]),
    ("válvula: pino errado", ["saidas"], [("analogWrite(PIN_IAC, (int)(p", "analogWrite(PIN_IAC + 1, (int)(p")]),
    # ---------------------------------------------------------------- arranque e cão de guarda
    ("arranque: DDRA antes do nível da PORTA", ["saidas_baixo"],
     [("    if (KECU_SAIDA_ATIVA_ALTO) {\n        PORTA &= (uint8_t)~MASCARA_SAIDAS; /* porta ainda é entrada: só garante que o pull-up interno está desligado */\n    } else {\n        PORTA |= MASCARA_SAIDAS;\n    }\n    DDRA |= MASCARA_SAIDAS;",
       "    DDRA |= MASCARA_SAIDAS;\n    if (KECU_SAIDA_ATIVA_ALTO) {\n        PORTA &= (uint8_t)~MASCARA_SAIDAS;\n    } else {\n        PORTA |= MASCARA_SAIDAS;\n    }")]),
    ("arranque: nível LIGADO na PORTA antes da direção", ["saidas", "saidas_baixo"],
     [("    if (KECU_SAIDA_ATIVA_ALTO) {\n        PORTA &= (uint8_t)~MASCARA_SAIDAS; /* porta ainda é entrada", "    if (!KECU_SAIDA_ATIVA_ALTO) {\n        PORTA &= (uint8_t)~MASCARA_SAIDAS; /* porta ainda é entrada")]),
    ("arranque: DDRA = 0xFF", ["saidas"], [("    DDRA |= MASCARA_SAIDAS;\n#endif\n\n    /* 2) entradas */", "    DDRA = 0xFF;\n#endif\n\n    /* 2) entradas */")]),
    ("cão de guarda não ligado", ["saidas"], [("    wdt_enable(WDTO_2S);\n", "")]),
    ("cão de guarda de 1 s", ["saidas"], [("wdt_enable(WDTO_2S);", "wdt_enable(WDTO_1S);")]),
    ("cão de guarda ligado também no modo sombra", ["sombra"], [("#define KECU_AVR_WDT KECU_ENABLE_OUTPUTS", "#define KECU_AVR_WDT 1")]),
    ("cão de guarda não alimentado no laço", ["saidas"], [("#if KECU_AVR_WDT\n    wdt_reset();\n#endif\n    kecu_sched_verificar();", "    kecu_sched_verificar();")]),
    # ---------------------------------------------------------------- modo sombra
    ("modo sombra configura os pinos", ["sombra"], [("#if KECU_ENABLE_OUTPUTS\n    if (KECU_SAIDA_ATIVA_ALTO) {", "#if 1\n    if (KECU_SAIDA_ATIVA_ALTO) {")]),
    ("modo sombra escreve nos pinos", ["sombra"],
     [("hal_saida_escrever(kecu_saida_t saida, uint8_t ligada) {\n#if KECU_ENABLE_OUTPUTS", "hal_saida_escrever(kecu_saida_t saida, uint8_t ligada) {\n#if 1")]),
    ("válvula tocada no modo sombra", ["sombra"],
     [("#if KECU_ENABLE_OUTPUTS\nstatic void iac_iniciar", "#if 1\nstatic void iac_iniciar"),
      ("extern \"C\" void hal_iac_pwm(float percentual) {\n#if KECU_ENABLE_OUTPUTS", "extern \"C\" void hal_iac_pwm(float percentual) {\n#if 1")]),
]

PLACAS = {
    "esp32": {"arquivo": "hal_esp32.cpp", "prefixo": "esp32", "mutacoes": ESP32},
    "avr": {"arquivo": "hal_avr.cpp", "prefixo": "avr", "mutacoes": AVR},
}


def rodar(args):
    base_tmp, placa, i, (nome, variantes, edicoes) = args
    cfg = PLACAS[placa]
    base = os.path.join(base_tmp, "m%03d" % i)
    shutil.rmtree(base, ignore_errors=True)
    os.makedirs(base)
    src = os.path.join(base, "src")
    shutil.copytree(os.path.join(RAIZ, "src"), src)
    caminho = os.path.join(src, cfg["arquivo"])
    texto = open(caminho, encoding="utf-8").read()
    for velho, novo in edicoes:
        n = texto.count(velho)
        if n != 1:
            return (i, nome, "ERRO: o trecho aparece %d vezes (devia ser 1): %r" % (n, velho[:60]), "")
        texto = texto.replace(velho, novo)
    open(caminho, "w", encoding="utf-8").write(texto)
    build = os.path.join(base, "build")
    motivo = ""
    for variante in variantes:
        alvo = "%s/%s_%s/teste" % (build, cfg["prefixo"], variante)
        c = subprocess.run(["make", "-C", AQUI, "SRC=" + src, "BUILD=" + build, alvo], capture_output=True, text=True)
        if c.returncode != 0:
            erros = [l for l in c.stderr.splitlines() if "error" in l]
            return (i, nome, "NÃO COMPILOU (não vale como mutante)", erros[0] if erros else c.stderr[-200:])
        try:
            r = subprocess.run([alvo], capture_output=True, text=True, timeout=600)
        except subprocess.TimeoutExpired:
            return (i, nome, "MORTO (travou, variante %s)" % variante, "")
        falhas = [l.strip() for l in r.stdout.splitlines() if "FALHOU" in l]
        if r.returncode != 0 or falhas:
            return (i, nome, "MORTO em %s (%d verificações falharam)" % (variante, len(falhas)),
                    falhas[0] if falhas else "o programa terminou com código %d" % r.returncode)
        resumo = [l for l in r.stdout.splitlines() if "verificações" in l]
        motivo = resumo[0] if resumo else ""
    shutil.rmtree(base, ignore_errors=True)
    return (i, nome, "SOBREVIVEU  <<<<<<<<<<", motivo)


def main():
    args = [a for a in sys.argv[1:]]
    if not args or args[0] not in PLACAS:
        print(__doc__)
        return 2
    placa = args.pop(0)
    so_lista = "--lista" in args
    escolha = [a for a in args if not a.startswith("--")]
    mutacoes = PLACAS[placa]["mutacoes"]
    itens = list(enumerate(mutacoes))
    if escolha:
        itens = [t for t in itens if str(t[0]) in escolha]
    if so_lista:
        for i, (nome, variantes, _) in itens:
            print("%3d  %-50s %s" % (i, nome, ",".join(variantes)))
        print("\n%d mutações" % len(itens))
        return 0
    base_tmp = tempfile.mkdtemp(prefix="mutacao_%s_" % placa)
    try:
        trabalhos = [(base_tmp, placa, i, m) for i, m in itens]
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(2, min(4, os.cpu_count() or 2))) as ex:
            resultados = sorted(ex.map(rodar, trabalhos))
    finally:
        shutil.rmtree(base_tmp, ignore_errors=True)
    ruins = 0
    for i, nome, estado, detalhe in resultados:
        print("%3d %-50s %s" % (i, nome, estado))
        if detalhe and not estado.startswith("MORTO"):
            print("      " + (detalhe.strip().splitlines()[0][:150] if detalhe.strip() else ""))
        if not estado.startswith("MORTO"):
            ruins += 1
    mortos = len(resultados) - ruins
    print("\n%d mutações: %d mortas, %d sobreviveram ou são inválidas" % (len(resultados), mortos, ruins))
    return 1 if ruins else 0


if __name__ == "__main__":
    sys.exit(main())
