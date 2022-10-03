#ifndef __MURAX_H__
#define __MURAX_H__

#include "timer.h"
#include "prescaler.h"
#include "interrupt.h"
#include "gpio.h"
#include "uart.h"

#define GPIO_A    ((Gpio_Reg*)(0x00F00000))
#define TIMER_PRESCALER ((Prescaler_Reg*)0x00F20000)
#define TIMER_INTERRUPT ((InterruptCtrl_Reg*)0x00F20010)
#define TIMER_A ((Timer_Reg*)0x00F20040)
#define TIMER_B ((Timer_Reg*)0x00F20050)
#define UART      ((Uart_Reg*)(0x00F10000))
#define AXI_M1      ((volatile uint32_t *)(0x00C00000))

#endif /* __MURAX_H__ */
