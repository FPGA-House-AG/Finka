Finka is the soft-core SoC of Blackwire.

The SoC is based around the VexRiscv RISC-V CPU

This SoC also defines the top-level Ethernet AXI Streaming
interfaces, as it muxes of the non-WireGuard-Type4 messages
into externally implemented (HDL/RTL) data paths, and the
other ARP, WireGuard Type-1-2-3 traffic to/from the CPU.

## TESTING

- SpinalSim using a Verilator, GHDL (or other?) simulator.
- CocoTB testbench against generated VHDL.
- Verilated (stand-alone) executable.

### TESTING WITH SPINALSIM

sbt "runMain finka.FinkaSim"

### COCOTB

Slow simulation. Will take a few minutes before you see UART characters

make rtl
make -C cocotb/finka


### TESTING WITH VERILATOR, FULLY VERILATED

Generate the Verilog source, then compile and run the Verilated executable

make -C software/c/finka/pico-hello
make rtl
make -C hardware/test/finka run

This will run the initial program in RAM; this is loaded by the "make run"
step. So to run a different program only the RAM binaries have to be updated,
the RTL remains identical. However, the RAM binaries are generated during the
RTL step, as Finka uses separate BRAMs per byte lane, handled by SpinalHDL.

When the executable is run, it will also expose a JTAG/TCP interface, so:

make debug

will allow upload via JTAG and debug via GDB.
