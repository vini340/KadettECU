/* EEPROM.h (SIMULADO): a biblioteca EEPROM do Arduino AVR (read, update, length). O Mega tem 4096 bytes. */
#ifndef MOCK_EEPROM_H
#define MOCK_EEPROM_H

#include <stdint.h>

class EEPROMClass {
  public:
    uint8_t read(int endereco);
    void update(int endereco, uint8_t valor); /* só grava se o byte mudou; leva ~3,3 ms por byte gravado */
    uint16_t length(void);
};
extern EEPROMClass EEPROM;

#endif
