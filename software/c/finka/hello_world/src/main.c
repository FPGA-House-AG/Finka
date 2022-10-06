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

void packet_send(uint32_t *p, int len)
{
	if (len == 0) return;

	uint32_t corundumDataWidth = *((volatile uint32_t *)AXI_M1 + 0x1020/4);

	int remaining = len;
	int word_num = 0;

	uint32_t space = 0;

	// iterate over all 32-bit data words of the packet, except the last word
	while (remaining > sizeof(uint32_t)) {
		while (space == 0) {
			space = *((volatile uint32_t *)AXI_M1 + 0x1040/4);
		}
		int addr = word_num % (corundumDataWidth / 32);
		*((volatile uint32_t *)AXI_M1 + 0x1100/4 + addr) = p[word_num];
		word_num += 1;
		remaining -= 4;
		space--;
	}
	// { remaining is in range [0-4] }
	if (remaining > 0)
	/* last 32-bit word */
	{
		while (space == 0) {
			space = *((volatile uint32_t *)AXI_M1 + 0x1040/4);
		}
		int addr = word_num % (corundumDataWidth / 32);
		/* last 32-bit word, assert TLAST and indicate empty bytes */
		*((volatile uint32_t *)AXI_M1 + 0x1080/4) = sizeof(uint32_t) - remaining;
		*((volatile uint32_t *)AXI_M1 + 0x1100/4 + addr) = p[word_num];
	}
}

void main() {
	uart_applyConfig(UART, &uart_cfg);
    //println("Hello world! I am Finka.");

    uint32_t identifier = *((volatile uint32_t *)AXI_M1 + 0x1000/4);
    uint32_t version = *((volatile uint32_t *)AXI_M1 + 0x1000/4);

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
#if 1 // packet generation
	// note that the packets are always a multiple of 32-bit or 4-bytes
	// this is a limitation in the packet generator IP. For Wireguard,
	// this is no limitation
	// todo read from hardware

	static uint32_t packet[512];
	uint8_t *ptr = (uint8_t *)packet;
	for (int i = 0; i < 512 * 4; i++) {
		ptr[i] = i;
	}

	uint32_t corundumDataWidth = *((volatile uint32_t *)AXI_M1 + 0x1020/4);

	//int packet_len = (corundumDataWidth/8)*2+1;
	for (int packet_len = 1; packet_len <= (corundumDataWidth/8)*2+1; packet_len++) {
	  packet_send(packet, packet_len);
	}
	//*((volatile uint32_t *)AXI_M1 + 0x18/4) = 0xbabecafeU;
	//*((volatile uint32_t *)AXI_M1 + 0x18/4) = 0xbabecafeU;
	//*((volatile uint32_t *)AXI_M1 + 0x18/4) = 0xbabecafeU;
	//*((volatile uint32_t *)AXI_M1 + 0x18/4) = 0xbabecafeU;

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
