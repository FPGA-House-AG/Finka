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

run: compile tap
	cp -a ../../../build/rtl/wireguard/Finka.v*ram*.bin .
	# depends on a root capability
	# RUN echo "ALL ALL = NOPASSWD:/usr/sbin/setcap cap_net_admin=+pe" >>/etc/sudoers.d/cap_net
	#sudo setcap cap_net_admin=+pe ./obj_dir/VFinka
	 ./obj_dir/VFinka
ifeq ($(DEBUG),yes)
	gdb --args ./obj_dir/VFinka -ex run
else
	obj_dir/VFinka
endif

verilate: ../../../build/rtl/Finka.v
	rm -f Finka.v*.bin
	#verilator -I../../.. -cc $^ -CFLAGS -std=c++11  ${ADDCFLAGS} --gdbbt ${VERILATOR_ARGS} -Wno-WIDTH -Wno-UNOPTFLAT -Wno-MULTIDRIVEN --x-assign unique --exe main.cpp
	verilator -cc $^ -CFLAGS -std=c++11  ${ADDCFLAGS} --gdbbt ${VERILATOR_ARGS} -Wno-WIDTH -Wno-UNOPTFLAT -Wno-MULTIDRIVEN --x-assign unique --exe main.cpp
 	
compile: verilate
	make -j16  -C obj_dir/ -f VFinka.mk VFinka
 	
clean:
	rm -rf obj_dir
	rm -f Finka.v*.bin
	rm -f Finka.fst*
	rm -f Finka.logTrace

.PHONY: tap
tap:
	# Depends on:
	# RUN echo "ALL ALL = NOPASSWD:/usr/sbin/ip" >>/etc/sudoers.d/ip
	sudo ip link delete tap0
	sudo ip tuntap add tap0 mode tap user $(shell id -un) group $(shell id -gn)
	sudo ip link set address aa:bb:cc:11:11:11 dev tap0
	sudo ip link set tap0 up
	sudo ip addr add 192.168.255.1/24 dev tap0

# POINTOPOINT does not perform any ARP
archived:
	#echo USER=$(shell id -un)
	#sudo ip addr add 192.168.255.1 peer 192.168.255.2 dev tap0
	#sudo ip addr add local 192.168.255.1 remote 192.168.255.2 dev tap0
	#sudo arp -s 192.168.255.2 aa:bb:cc:22:22:22 
 	
