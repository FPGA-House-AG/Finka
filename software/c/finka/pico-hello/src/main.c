#include <stdint.h>
#include <stdio.h>

#include "finka.h"

Uart_Config uart_cfg = {
  .dataLength = 8,
  .parity = 0,
  .stop = 1,
  .clockDivider = 250000000/8/115200-1
};

static int uart_putc(char c, FILE *file) {
	(void)file;
#if 1
	/* Translate newline into cr/lf */
	if (c == '\n')
		uart_putc('\r', file);
#endif
	/* Send character */
  uart_write(UART, c);
	/* Return character */
	return (int) (uint8_t) c;
}

static int uart_getc(FILE *file)
{
	uint8_t	c;
	(void)file;
	/* Read input */
	c = (uint8_t)uart_read(UART);

#if 1
	/* Translate return into newline */
	if (c == '\r')
		c = '\n';
#endif
	/* Echo */
	uart_putc(c, stdout);
	return (int)c;
}

/* Create a single FILE object for stdin and stdout */
static FILE __stdio = FDEV_SETUP_STREAM(uart_putc, uart_getc, NULL, _FDEV_SETUP_RW);

/*
 * Use the picolibc __strong_reference macro to share one variable for
 * stdin/stdout/stderr. This saves space, but prevents the application
 * from updating them independently.
 */

#ifdef __strong_reference
#define STDIO_ALIAS(x) __strong_reference(stdin, x);
#else
#define STDIO_ALIAS(x) FILE *const x = &__stdio;
#endif

FILE *const stdin = &__stdio;
STDIO_ALIAS(stdout);
STDIO_ALIAS(stderr);

void main() {
  uart_applyConfig(UART, &uart_cfg);
  int count = 0;

  *((volatile uint32_t *)AXI_M1 + 0x3000/4) = 0xDEADBEEFu;
  *((volatile uint32_t *)AXI_M1 + 0x3004/4) = 0xCAFEBABEu;

  putc('X', stdin);
#if 0
  for (;;) {
    static char buf[512];
		printf("What is your name? ");
		fgets(buf, sizeof(buf), stdin);
		printf("Good to meet you, %s", buf);
	}
#endif
  // 1 microsecond ticks to timers
  TIMER_PRESCALER->LIMIT = 250 - 1;
  timer_init(TIMER_A);
  while (count < 200) {
    //printf("hello world from pico-hello! %u\n", *((volatile uint32_t *)(&(TIMER_A->VALUE))));
    printf("hello world from pico-hello! %u\n", TIMER_A->VALUE);
    count += 2;
  }
}

void _cirqhandler(){
}