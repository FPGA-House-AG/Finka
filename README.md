Finka is the soft-core SoC in the Blackwire PoC.

The SoC is based around the VexRiscv RISC-V CPU. This is written in
SpinalHDL. The SoC is designed in SpinalHDL as well (after it was
proven much more productive than doing this any other way).

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


### TESTING VERILATED

Generate the Verilog source, then compile and run the Verilated executable

make rtl
make -C hardware/test/finka run

