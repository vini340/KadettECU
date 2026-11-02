/* esp_task_wdt.h (SIMULADO) - cão de guarda de tarefas do ESP-IDF. Veja mock_esp32.h. */
#ifndef ESP_TASK_WDT_H_MOCK
#define ESP_TASK_WDT_H_MOCK

#include <stdbool.h>
#include <stdint.h>

typedef int esp_err_t;
#define ESP_OK 0

#ifdef MOCK_ESP_V3
typedef struct {
    uint32_t timeout_ms;
    uint32_t idle_core_mask;
    bool trigger_panic;
} esp_task_wdt_config_t;
esp_err_t esp_task_wdt_reconfigure(const esp_task_wdt_config_t *config);
#else
esp_err_t esp_task_wdt_init(uint32_t timeout_s, bool panic);
#endif
esp_err_t esp_task_wdt_add(void *tarefa);
esp_err_t esp_task_wdt_reset(void);

#endif
