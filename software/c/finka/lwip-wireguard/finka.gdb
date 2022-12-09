target extended-remote :3333
set arch riscv:rv32
monitor reset halt
file build/hello_world.elf
load
break main
cont

