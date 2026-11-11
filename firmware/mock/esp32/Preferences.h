/* Preferences.h (SIMULADO) - NVS do ESP32 como um mapa na RAM do PC. Veja mock_esp32.h. */
#ifndef PREFERENCES_H_MOCK
#define PREFERENCES_H_MOCK

#include <stddef.h>
#include <stdint.h>

class Preferences {
  public:
    bool begin(const char *nome, bool somenteLeitura = false);
    void end(void);
    size_t putBytes(const char *chave, const void *valor, size_t tamanho);
    size_t getBytes(const char *chave, void *buffer, size_t tamanhoMax);
    size_t getBytesLength(const char *chave);

  private:
    char nome_[16] = {0};
    bool aberto_ = false;
    bool somenteLeitura_ = false;
};

#endif
