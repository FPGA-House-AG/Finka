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

/* send packet to the downstream transmit FIFO */
void packet_send(uint32_t *p, int len)
{
	if (len == 0) return 0;

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

void packet_available()
{
	return *((volatile uint32_t *)AXI_M1 + 0x2040/4);
}

/* receive packets from the downstream receive FIFO */
/* downstream means PHY facing */
int packet_recv(uint32_t *p, int len)
{
	if (len == 0) return 0;

	uint32_t corundumDataWidth = *((volatile uint32_t *)AXI_M1 + 0x2020/4);

	int received = 0;
	int word_num = 0;
	int done = 0;
	int avail = 0;
	int overflow = 0;
	int packet_len = 0;

	// 31: valid
	// 30: last
	// 15-0: empty, number of empty bytes in current word
	uint32_t valid_last_empty = 0;

	int last = 0;
	while (!last) {
		// poll until stream words become available in the receive FIFO
		while (avail == 0) {
			avail = *((volatile uint32_t *)AXI_M1 + 0x2040/4);
		}

		int valid = 0;
		// wait until the stream has a valid word available
		// { avail > 0 }, so we can assert that valid must be true
		while (!valid) {
			valid_last_empty = *((volatile uint32_t *)AXI_M1 + 0x2080/4);
			valid = (valid_last_empty & (1 << 31)) >> 31;
		}
		// { valid word available, check last flag }
		last = (valid_last_empty & (1 << 30)) >> 30;

		// { valid word available }, determine number of empty bytes in stream word
		int empty_bytes = valid_last_empty & 0xFFFFu;
		// assume all bytes are available
		int avail_bytes = corundumDataWidth / 8;
		if (last) {
			avail_bytes -= empty_bytes;
		}
		int avail_words = (avail_bytes + 3) / 4;
		//assert(avail_words <= (corundumDataWidth / 32));

		int addr = 0;
		// iterate over all non-empty 32-bit words in the current stream word
		while (addr < avail_words) {
			if (word_num < (len / 4)) {
				p[word_num] = *((volatile uint32_t *)AXI_M1 + 0x2100/4 + addr);
			} else {
				overflow = 1;
			}
			word_num += 1;
			addr += 1;
		}
		packet_len += avail_bytes;
	}
	return overflow? 0: packet_len;
}

void main() {
	uart_applyConfig(UART, &uart_cfg);
    //println("Hello world! I am Finka.");

    uint32_t identifier = *((volatile uint32_t *)AXI_M1 + 0x1000/4);
    uint32_t version = *((volatile uint32_t *)AXI_M1 + 0x1000/4);

	static uint32_t packet[512];
	uint8_t *ptr = (uint8_t *)packet;
	for (int i = 0; i < 512 * 4; i++) {
		ptr[i] = i;
	}

	while (1) {
		int packet_len = packet_recv(packet, 512);
		print("L:");
		print_int(packet_len);
		print("\n");
		if (packet_len > 0) {
			packet_send(packet, packet_len);
		}
	}

#if 0 // packet transmit
	//int packet_len = (corundumDataWidth/8)*2+1;
	for (int packet_len = 1; packet_len <= (corundumDataWidth/8)*2+1; packet_len++) {
	  packet_send(packet, packet_len);
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
