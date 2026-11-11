# Makefile - testes e ECU virtual do firmware KadettECU (rodam no PC, sem a placa).
#
#   make            compila o ecu_host (ECU virtual) em build/
#   make test       roda os testes em C (núcleo em 3 variantes + agendador em 2)
#   make sanitize   o mesmo, com AddressSanitizer + UndefinedBehaviorSanitizer
#   make clean
#
# Os testes das placas reais (HAL do ESP32 e do AVR contra mocks) ficam em ../teste_placas/Makefile.

CC ?= gcc
CORE := ../lib/kecu_core
SCHED := ../lib/kecu_sched
CORE_SRC := $(wildcard $(CORE)/*.c)
SCHED_SRC := $(wildcard $(SCHED)/*.c)
HDRS := $(wildcard $(CORE)/*.h) $(wildcard $(SCHED)/*.h) hal_host.h
WARN := -Wall -Wextra -Wshadow -Wpedantic -Wconversion -Wstrict-prototypes -Wmissing-prototypes
CFLAGS += -std=c99 -O2 -g $(WARN) -I$(CORE) -I.
LDLIBS := -lm
BUILD := build

TESTES := $(BUILD)/test_sombra $(BUILD)/test_saidas $(BUILD)/test_bobinas2 $(BUILD)/test_sched $(BUILD)/test_sched_b2

.PHONY: all test sanitize clean

all: $(BUILD)/ecu_host

$(BUILD)/ecu_host: ecu_host.c hal_host.c $(CORE_SRC) $(HDRS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) ecu_host.c hal_host.c $(CORE_SRC) -o $@ $(LDLIBS)

# saídas desabilitadas (modo sombra, o padrão do firmware)
$(BUILD)/test_sombra: test_core.c hal_host.c $(CORE_SRC) $(HDRS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -DKECU_ENABLE_OUTPUTS=0 test_core.c hal_host.c $(CORE_SRC) -o $@ $(LDLIBS)

# saídas habilitadas: testa o planejador de ignição/injeção capturando os pulsos pedidos à HAL
$(BUILD)/test_saidas: test_core.c hal_host.c $(CORE_SRC) $(HDRS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -DKECU_ENABLE_OUTPUTS=1 test_core.c hal_host.c $(CORE_SRC) -o $@ $(LDLIBS)

# saídas habilitadas e duas bobinas (centelha perdida)
$(BUILD)/test_bobinas2: test_core.c hal_host.c $(CORE_SRC) $(HDRS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -DKECU_ENABLE_OUTPUTS=1 -DKECU_BOBINAS=2 test_core.c hal_host.c $(CORE_SRC) -o $@ $(LDLIBS)

# agendador de bordas: o planejador do núcleo contra o agendador de verdade (kecu_sched_hal.c
# substitui as versões "fracas" de hal_agendar_pulso/hal_desligar_saidas de hal_host.c)
$(BUILD)/test_sched: test_sched.c hal_host.c $(CORE_SRC) $(SCHED_SRC) $(HDRS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(SCHED) -DKECU_ENABLE_OUTPUTS=1 test_sched.c hal_host.c $(CORE_SRC) $(SCHED_SRC) -o $@ $(LDLIBS)

$(BUILD)/test_sched_b2: test_sched.c hal_host.c $(CORE_SRC) $(SCHED_SRC) $(HDRS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(SCHED) -DKECU_ENABLE_OUTPUTS=1 -DKECU_BOBINAS=2 test_sched.c hal_host.c $(CORE_SRC) $(SCHED_SRC) -o $@ $(LDLIBS)

test: $(TESTES)
	@set -e; for t in $(TESTES); do echo "=== $$t"; $$t; done

sanitize:
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -fsanitize=address,undefined -fno-omit-frame-pointer -DKECU_ENABLE_OUTPUTS=0 test_core.c hal_host.c $(CORE_SRC) -o $(BUILD)/san_sombra $(LDLIBS)
	$(CC) $(CFLAGS) -fsanitize=address,undefined -fno-omit-frame-pointer -DKECU_ENABLE_OUTPUTS=1 -DKECU_BOBINAS=2 test_core.c hal_host.c $(CORE_SRC) -o $(BUILD)/san_saidas $(LDLIBS)
	$(CC) $(CFLAGS) -I$(SCHED) -fsanitize=address,undefined -fno-omit-frame-pointer -DKECU_ENABLE_OUTPUTS=1 test_sched.c hal_host.c $(CORE_SRC) $(SCHED_SRC) -o $(BUILD)/san_sched $(LDLIBS)
	$(BUILD)/san_sombra
	$(BUILD)/san_saidas
	$(BUILD)/san_sched

clean:
	rm -rf $(BUILD)
