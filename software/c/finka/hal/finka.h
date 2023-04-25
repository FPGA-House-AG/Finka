#ifndef __MURAX_H__
#define __MURAX_H__

#include "timer.h"
#include "prescaler.h"
#include "interrupt.h"
#include "gpio.h"
#include "uart.h"

// APH
#define GPIO_A    ((Gpio_Reg*)(0x00F00000))
#define TIMER_PRESCALER ((Prescaler_Reg*)0x00F20000)
#define TIMER_INTERRUPT ((InterruptCtrl_Reg*)0x00F20010)
#define TIMER_A ((Timer_Reg*)0x00F20040)
#define UART      ((Uart_Reg*)(0x00F10000))

// AXI
#define AXI_M1      ((volatile uint32_t *)(0x00C00000))
#define AXI_TX      ((volatile uint32_t *)AXI_M1 + 0x1000/4)
#define AXI_RX      ((volatile uint32_t *)AXI_M1 + 0x2000/4)
#define AXI_LUT     ((volatile uint32_t *)AXI_M1 + 0x3000/4)
#define AXI_PRF     ((volatile uint32_t *)AXI_M1 + 0x4000/4)
#define AXI_X25     ((volatile uint32_t *)AXI_M1 + 0x5000/4)
#define AXI_IPL     ((volatile uint32_t *)AXI_M1 + 0x6000/4)
#define AXI_TXC     ((volatile uint32_t *)AXI_M1 + 0x10000/4)
#define AXI_RXK     ((volatile uint32_t *)AXI_M1 + 0x20000/4)
#define AXI_TXK     ((volatile uint32_t *)AXI_M1 + 0x30000/4)
#define AXI_P2S     ((volatile uint32_t *)AXI_M1 + 0x40000/4)
#define AXI_P2EP    ((volatile uint32_t *)AXI_M1 + 0x50000/4)
#define AXI_L2R     ((volatile uint32_t *)AXI_M1 + 0x60000/4)

#endif /* __MURAX_H__ */
