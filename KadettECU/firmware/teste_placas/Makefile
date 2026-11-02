# Makefile - testa o código das placas (src/hal_esp32.cpp, src/hal_avr.cpp) no PC, contra simuladores das
# bibliotecas de cada placa (mock/esp32, mock/avr). O núcleo em C e o agendador compilam como C99, a HAL e
# o main.cpp como C++11, com os MESMOS -D que o platformio.ini usa na placa.
#
#   make test       compila e roda todas as variantes
#   make sanitize   o mesmo, com AddressSanitizer + UndefinedBehaviorSanitizer
#   make clean
#
# ATENÇÃO: isto NÃO compila com a cadeia de ferramentas de verdade (xtensa-gcc, avr-gcc) nem roda em placa.
# Veja docs/HARDWARE.md, "O que ainda precisa ser validado".

CC ?= gcc
CXX ?= g++
CORE := ../lib/kecu_core
SCHED := ../lib/kecu_sched
SRC := ../src
MOCK := ../mock
BUILD := build

CSRC := $(wildcard $(CORE)/*.c) $(wildcard $(SCHED)/*.c)
HDRS := $(wildcard $(CORE)/*.h) $(wildcard $(SCHED)/*.h) $(wildcard $(SRC)/*.h) $(wildcard $(MOCK)/*/*.h) $(wildcard *.h)

FLAGS_C := -std=c99 -O2 -g -Wall -Wextra -Wshadow -Wpedantic -Wconversion -Wstrict-prototypes -Wmissing-prototypes
FLAGS_PROD := -std=c++11 -O2 -g -Wall -Wextra -Wshadow -Wpedantic -Wconversion
FLAGS_TESTE := -std=c++11 -O2 -g -Wall -Wextra
SAN :=
ifdef SANITIZE
SAN := -fsanitize=address,undefined -fno-omit-frame-pointer
endif

# ---------------------------------------------------------------- ESP32
ESP_INC := -I$(CORE) -I$(SCHED) -I$(SRC) -I$(MOCK)/esp32
ESP_DEFS := -DARDUINO_ARCH_ESP32 -DKECU_PLACA=1 -DKECU_RAZAO_5V=2.0f -DKECU_RAZAO_BATERIA=5.7f

# variante: nome -> definições extras
ESP_VARIANTES := sombra saidas saidas_b2 saidas_baixo auto sombra_v3 saidas_v3
D_sombra := -DKECU_ENABLE_OUTPUTS=0
D_saidas := -DKECU_ENABLE_OUTPUTS=1
D_saidas_b2 := -DKECU_ENABLE_OUTPUTS=1 -DKECU_BOBINAS=2
D_saidas_baixo := -DKECU_ENABLE_OUTPUTS=1 -DKECU_SAIDA_ATIVA_ALTO=0
D_auto := -DKECU_ENABLE_OUTPUTS=1 -DKECU_AUTO_ARMAR=1
D_sombra_v3 := -DMOCK_ESP_V3 -DKECU_ENABLE_OUTPUTS=0
D_saidas_v3 := -DMOCK_ESP_V3 -DKECU_ENABLE_OUTPUTS=1

ESP_DEPS := test_hal_esp32.cpp $(SRC)/hal_esp32.cpp $(SRC)/main.cpp $(MOCK)/esp32/mock_esp32.cpp $(CSRC) $(HDRS)

# $(1) = nome da variante, $(2) = definições dessa variante
define ESP_REGRA
$(BUILD)/esp32_$(1)/teste: $(ESP_DEPS)
	@mkdir -p $(BUILD)/esp32_$(1)
	@set -e; for f in $(CSRC); do \
	    $(CC) $(FLAGS_C) $(SAN) $(ESP_DEFS) $(2) $(ESP_INC) -c $$$$f -o $(BUILD)/esp32_$(1)/c_$$$$(basename $$$$f .c).o; \
	done
	$(CXX) $(FLAGS_PROD) $(SAN) $(ESP_DEFS) $(2) $(ESP_INC) -c $(SRC)/hal_esp32.cpp -o $(BUILD)/esp32_$(1)/hal_esp32.o
	$(CXX) $(FLAGS_PROD) $(SAN) $(ESP_DEFS) $(2) $(ESP_INC) -c $(SRC)/main.cpp -o $(BUILD)/esp32_$(1)/main.o
	$(CXX) $(FLAGS_TESTE) $(SAN) $(ESP_DEFS) $(2) $(ESP_INC) -c $(MOCK)/esp32/mock_esp32.cpp -o $(BUILD)/esp32_$(1)/mock_esp32.o
	$(CXX) $(FLAGS_TESTE) $(SAN) $(ESP_DEFS) $(2) $(ESP_INC) -c test_hal_esp32.cpp -o $(BUILD)/esp32_$(1)/test_hal_esp32.o
	$(CXX) $(SAN) $(BUILD)/esp32_$(1)/*.o -o $$@ -lm
endef
$(foreach v,$(ESP_VARIANTES),$(eval $(call ESP_REGRA,$(v),$(D_$(v)))))

ESP_TESTES := $(foreach v,$(ESP_VARIANTES),$(BUILD)/esp32_$(v)/teste)

# ---------------------------------------------------------------- Arduino Mega 2560 (ATmega2560, 16 MHz, 5 V)
# Os -D são os mesmos do ambiente megaatmega2560 do platformio.ini. Os divisores e a referência do ADC mudam
# em relação ao ESP32: o ADC do Mega lê até 5 V (AVcc), então os sensores de 5 V entram direto (razão 1,0) e a
# bateria passa por um divisor de 20 k / 10 k (razão 3,0).
AVR_INC := -I$(CORE) -I$(SCHED) -I$(SRC) -I$(MOCK)/avr
AVR_DEFS := -DARDUINO_ARCH_AVR -DKECU_PLACA=3 -DKECU_VREF_MV=5000 -DKECU_RAZAO_5V=1.0f -DKECU_RAZAO_BATERIA=3.0f \
            -DSERIAL_RX_BUFFER_SIZE=256 -DSERIAL_TX_BUFFER_SIZE=128

AVR_VARIANTES := sombra saidas saidas_b2 saidas_baixo auto
A_sombra := -DKECU_ENABLE_OUTPUTS=0
A_saidas := -DKECU_ENABLE_OUTPUTS=1
A_saidas_b2 := -DKECU_ENABLE_OUTPUTS=1 -DKECU_BOBINAS=2
A_saidas_baixo := -DKECU_ENABLE_OUTPUTS=1 -DKECU_SAIDA_ATIVA_ALTO=0
A_auto := -DKECU_ENABLE_OUTPUTS=1 -DKECU_AUTO_ARMAR=1

AVR_DEPS := test_hal_avr.cpp $(SRC)/hal_avr.cpp $(SRC)/main.cpp $(MOCK)/avr/mock_avr.cpp $(CSRC) $(HDRS)

# $(1) = nome da variante, $(2) = definições dessa variante
define AVR_REGRA
$(BUILD)/avr_$(1)/teste: $(AVR_DEPS)
	@mkdir -p $(BUILD)/avr_$(1)
	@set -e; for f in $(CSRC); do \
	    $(CC) $(FLAGS_C) $(SAN) $(AVR_DEFS) $(2) $(AVR_INC) -c $$$$f -o $(BUILD)/avr_$(1)/c_$$$$(basename $$$$f .c).o; \
	done
	$(CXX) $(FLAGS_PROD) $(SAN) $(AVR_DEFS) $(2) $(AVR_INC) -c $(SRC)/hal_avr.cpp -o $(BUILD)/avr_$(1)/hal_avr.o
	$(CXX) $(FLAGS_PROD) $(SAN) $(AVR_DEFS) $(2) $(AVR_INC) -c $(SRC)/main.cpp -o $(BUILD)/avr_$(1)/main.o
	$(CXX) $(FLAGS_TESTE) $(SAN) $(AVR_DEFS) $(2) $(AVR_INC) -c $(MOCK)/avr/mock_avr.cpp -o $(BUILD)/avr_$(1)/mock_avr.o
	$(CXX) $(FLAGS_TESTE) $(SAN) $(AVR_DEFS) $(2) $(AVR_INC) -c test_hal_avr.cpp -o $(BUILD)/avr_$(1)/test_hal_avr.o
	$(CXX) $(SAN) $(BUILD)/avr_$(1)/*.o -o $$@ -lm
endef
$(foreach v,$(AVR_VARIANTES),$(eval $(call AVR_REGRA,$(v),$(A_$(v)))))

AVR_TESTES := $(foreach v,$(AVR_VARIANTES),$(BUILD)/avr_$(v)/teste)

.PHONY: all test sanitize clean

all: $(ESP_TESTES) $(AVR_TESTES)

test: $(ESP_TESTES) $(AVR_TESTES)
	@set -e; for v in $(ESP_VARIANTES); do echo "=== esp32_$$v"; $(BUILD)/esp32_$$v/teste; done
	@set -e; for v in $(AVR_VARIANTES); do echo "=== avr_$$v"; $(BUILD)/avr_$$v/teste; done

sanitize:
	$(MAKE) clean
	$(MAKE) SANITIZE=1 test

clean:
	rm -rf $(BUILD)
