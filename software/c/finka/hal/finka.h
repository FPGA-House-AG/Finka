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
#define AXI_RXK     ((volatile uint32_t *)AXI_M1 + 0x8000/4)

//     corundumAxi4SharedBus           -> (0x00C00000L, 4 kB),
//     packetTxAxi4SharedBusWriter     -> (0x00C01000L, 4 kB),
//     packetRxAxi4SharedBusReader     -> (0x00C02000L, 4 kB),
//     lookupAxi4SharedBus             -> (0x00C03000L, 4 kB),
//     prefixAxi4SharedBus             -> (0x00C04000L, 4 kB),
//     packetRxAxi4SharedBusRxKey      -> (0x00C08000L, 4 kB),
//     apbBridge.io.axi                -> (0x00F00000L, 1 MB)//
#endif /* __MURAX_H__ */
