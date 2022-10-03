#include <stdint.h>

#include "finka.h"

void print(const char*str){
	while(*str){
		uart_write(UART,*str);
		str++;
	}
}

print_int(int x) {
	if (x >= 100) uart_write(UART, '0' + x/100);
	x %= 100;
	if (x >= 10) uart_write(UART, '0' + x/10);
	x %= 10;
	uart_write(UART, '0' + x);
	uart_write(UART,'\n');
}

void println(const char*str){
	print(str);
	uart_write(UART,'\n');
}

void delay(uint32_t loops){
	for(int i=0;i<loops;i++){
		int tmp = GPIO_A->OUTPUT;
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
    println("Hello world! I am Finka.");
#if 0
	*((volatile uint32_t *)AXI_M1) = 0xaabbccddU;
	*((volatile uint8_t *)AXI_M1 + 0) = (uint8_t)0x11U;
	*((volatile uint8_t *)AXI_M1 + 1) = (uint8_t)0x22U;
	*((volatile uint8_t *)AXI_M1 + 2) = (uint8_t)0x33U;
	*((volatile uint8_t *)AXI_M1 + 3) = (uint8_t)0x44U;
	*((volatile uint32_t *)AXI_M1) = 0xdeadbeefU;
	*((volatile uint32_t *)AXI_M1 + 1) = 0xbabecafeU;
#elif 0 // prefix
	for (int i = 0; i < 7; i++) {
		print_int(i);
		*((volatile uint32_t *)AXI_M1 + 0x00/4 + i) = i * 0x11;
	}
#endif
#if 1 // packet
	int len = (512/32) + 8;
	for (int w = 0; w < len; w++) {
		int addr = w % (512/32);
		*((volatile uint32_t *)AXI_M1 + 0x1000/4 + addr) = w;
		/* last 32-bit word, assert VALID and TLAST to write to TX FIFO */
		if (w == (len - 1)) {
			// valid=1, tlast=1
			*((volatile uint32_t *)AXI_M1 + 0x1044/4) = 1;
		/* last 32-bit word of a 512-bit word, assert VALID to write to TX FIFO */
		} else if (addr == ((512/32) - 1)) {
			// valid=1, tlast=0
			*((volatile uint32_t *)AXI_M1 + 0x1040/4) = 1;
		}
	}
#endif
    GPIO_A->OUTPUT_ENABLE = 0x0000000F;
	GPIO_A->OUTPUT = 0x00000001;

    const int nleds = 8;
    const int nloops = 20000;
    //timer_init(TIMER_A);
    while(1){
    	for(unsigned int i=0;i<nleds-1;i++){
    		GPIO_A->OUTPUT = 1<<i;
    		delay(nloops);

			print(".");
    	}
    	for(unsigned int i=0;i<nleds-1;i++){
			GPIO_A->OUTPUT = (1<<(nleds-1))>>i;
			delay(nloops);
						print(".");

		}
        println("Hello world! I am Finka again.");
    }
	//	(void)*AXI_M1;
}

void irqCallback(){
	int x = 42;
	int y = x * 4;
	(void)x;
	(void)y;
	//uart_write(UART,'.');
}
