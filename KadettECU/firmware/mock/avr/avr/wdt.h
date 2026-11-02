/* avr/wdt.h (SIMULADO): cão de guarda. WDTO_2S tem o mesmo valor da avr-libc (7). */
#ifndef MOCK_AVR_WDT_H
#define MOCK_AVR_WDT_H

#include <stdint.h>

#define WDTO_15MS 0
#define WDTO_1S 6
#define WDTO_2S 7
#define WDTO_4S 8

void mock_wdt_enable(uint8_t tempo);
void mock_wdt_reset(void);
void mock_wdt_disable(void);

#define wdt_enable(t) mock_wdt_enable((uint8_t)(t))
#define wdt_reset() mock_wdt_reset()
#define wdt_disable() mock_wdt_disable()

#endif
