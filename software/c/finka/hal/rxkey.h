#ifndef RXKEYCTRL_H_
#define RXKEYCTRL_H_

#include <stdint.h>
#include <stdio.h>
#include <limits.h>

static void rxkey_write(volatile uint32_t *p, int index, uint8_t *key)
{
	// 256 bits written at 32 bits at a time, 8 iterations
	// 32 bytes written at 4 bytes at a time, 8 iterations
	int base = index * (256/32);
	for (int word_num = 0; word_num < (256/32); word_num++) {
		uint32_t value = 0;
		value  = key[word_num * 4 + 0] << 0;
		value |= key[word_num * 4 + 1] << 8;
		value |= key[word_num * 4 + 2] << 16;
		value |= key[word_num * 4 + 3] << 24;
		*(p + base + word_num) = value;
	}
	/* read back */
	for (int word_num = 0; word_num < (256/32); word_num++) {
		uint32_t value = *(p + base + word_num);
		printf("index %d: 0x%08lx\n", word_num, value);
	}
}

#endif /* RXKEYCTRL_H_ */
