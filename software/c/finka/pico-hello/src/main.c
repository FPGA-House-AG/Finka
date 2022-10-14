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
  print("hello world from pico-hello!\n");
}