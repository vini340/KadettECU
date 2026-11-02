/*
 * main.cpp - programa da placa KadettECU (ESP32 ou Arduino Mega 2560).
 *
 * Todo o trabalho está no núcleo em C (lib/kecu_core): leitura da roda fônica, mapas, limitador,
 * protocolo serial. Este arquivo só liga o núcleo à placa escolhida no platformio.ini.
 * Não use delay() em lugar nenhum: o laço precisa girar o mais rápido possível.
 */
#include <Arduino.h>

#include "hal_board.h"
#include "kecu.h"

void setup() {
    placa_iniciar();
    kecu_init();
    placa_ligar_interrupcoes();
}

void loop() {
    kecu_loop();
    placa_laco();
}
