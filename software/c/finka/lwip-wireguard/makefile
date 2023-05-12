
OPENOCD = /opt/openocd-vexriscv/bin/openocd
CROSS_COMPILE = riscv32-unknown-elf-

RISCV_GDB=$(CROSS_COMPILE)gdb

OBJDIR = ../../../../../lwip-wireguard/build-riscv
PROJ_NAME=echop

# Debug the program using GDB, which starts OpenOCD(-VexRiscv) with GDB pipe
# This requires the VexRiscv to run in the SpinalSim waiting for TCP JTAG.
debug: $(OBJDIR)/$(PROJ_NAME)
	$(RISCV_GDB) \
	-ex "set remotetimeout 30" \
	-ex "target extended-remote | $(OPENOCD) -c \"gdb_port pipe; log_output openocd.log\" -c \"set MURAX_CPU0_YAML ../../../../cpu0.yaml\" -f \"interface/jtag_tcp.cfg\" -f \"./finka.cfg\""  \
	-ex "set arch riscv:rv32" \
	-ex "monitor reset halt" \
	-ex "set pagination off" \
	-ex "layout split" \
	-ex "load" \
	-ex "echo \\n" \
	-ex "echo \\n" \
	-ex "break main" \
	-ex "x/4i 0x800000" \
	-ex "cont" \
	$(OBJDIR)/$(PROJ_NAME)

openocd:
	$(OPENOCD) -c "set MURAX_CPU0_YAML ../../../../cpu0.yaml" -f interface/jtag_tcp.cfg -f ./finka.cfg
