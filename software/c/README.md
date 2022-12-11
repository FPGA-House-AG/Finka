=== hello_world

This is the reference software that comes with VexRiscv's Murax SoC.
It is not using any C library

=== pico-hello

This is our design using a GCC cross toolchain that has newlib's cousin
picolibc enabled. This allows us to use a few C library functions with
a small footprint.

=== lwip-wireguard

This builds lwip-wireguard against picolibc.

=== hal

Hardware abstraction layer.
Common headers that need to be kept in sync with Finka.scala