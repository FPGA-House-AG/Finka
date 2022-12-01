DEBUG?=no
TRACE?=no
PRINT_PERF?=no
TRACE_START=0
ADDCFLAGS += -CFLAGS -pthread -LDFLAGS -pthread


ifeq ($(TRACE),yes)
	VERILATOR_ARGS += --trace
	ADDCFLAGS += -CFLAGS -DTRACE --trace-fst
endif
ifeq ($(DEBUG),yes)
	ADDCFLAGS += -CFLAGS "-g3 -O0"
endif
ifneq ($(DEBUG),yes)
	ADDCFLAGS += -CFLAGS "-O3"
endif
ifeq ($(PRINT_PERF),yes)
	ADDCFLAGS += -CFLAGS -DPRINT_PERF
endif

ADDCFLAGS += -CFLAGS -DTRACE_START=${TRACE_START}

# terminal one:
# /opt/openocd-vexriscv/bin/openocd -c "log_output openocd.log" -c "set MURAX_CPU0_YAML ../../../../cpu0.yaml" -f "interface/jtag_tcp.cfg" -f "finka.cfg"

all: clean compile

run: compile
	#gdb --args ./obj_dir/VFinka -ex run
	obj_dir/VFinka

verilate: ../../../build/rtl/Finka.v
	rm -f Finka.v*.bin
	cp ../../../build/rtl/Finka.v*ram*.bin . | true
	#verilator -I../../.. -cc $^ -CFLAGS -std=c++11  ${ADDCFLAGS} --gdbbt ${VERILATOR_ARGS} -Wno-WIDTH -Wno-UNOPTFLAT --x-assign unique --exe main.cpp
	verilator -cc $^ -CFLAGS -std=c++11  ${ADDCFLAGS} --gdbbt ${VERILATOR_ARGS} -Wno-WIDTH -Wno-UNOPTFLAT --x-assign unique --exe main.cpp
 	
compile: verilate
	make  -j  -C obj_dir/ -f VFinka.mk VFinka
 	
clean:
	rm -rf obj_dir
	rm -f Finka.v*.bin
 	
