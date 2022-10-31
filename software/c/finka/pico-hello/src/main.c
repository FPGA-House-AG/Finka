#include <stdint.h>

#include "finka.h"

void print(const char *str){
  while (*str) {
    uart_write(UART, *str);
    str++;
  }
}

Uart_Config uart_cfg = {
  .dataLength = 8,
  .parity = 0,
  .stop = 1,
  .clockDivider = 250000000/8/115200-1
};

void main() {
  uart_applyConfig(UART, &uart_cfg);
  int count = 0;

  *((volatile uint32_t *)AXI_M1 + 0x3000/4) = 0xDEADBEEFu;
  *((volatile uint32_t *)AXI_M1 + 0x3004/4) = 0xCAFEBABEu;

  while (count < 200) {
    print("hello world from pico-hello! %d\n");
    count += 2;
  }
}