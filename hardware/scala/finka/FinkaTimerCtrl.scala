package finka

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3SlaveFactory, Apb3Config, Apb3}
import spinal.lib.misc.{InterruptCtrl, Timer, Prescaler}

/*
 * Based on Pinsec TimerCtrl in SpinalHDL library
 * - changed prescaler to 20 bits
 * - removed BufferCC on tick input
 * - removed Timers B, C and D
 */
object FinkaTimerCtrl{
  def getApb3Config() = new Apb3Config(
    addressWidth = 8,
    dataWidth = 32
  )
}

case class FinkaTimerCtrlExternal() extends Bundle{
  val clear = Bool()
  val tick = Bool()
}

case class FinkaTimerCtrl() extends Component {
  val io = new Bundle{
    val apb = slave(Apb3(FinkaTimerCtrl.getApb3Config()))
    val external = in(FinkaTimerCtrlExternal())
    val interrupt = out Bool()
  }
  val prescaler = Prescaler(20)
  val timerA = Timer(32)

  val busCtrl = Apb3SlaveFactory(io.apb)
  val prescalerBridge = prescaler.driveFrom(busCtrl, 0x00)

  val timerABridge = timerA.driveFrom(busCtrl, 0x40)(
    /* bit 0 indicates tick at every clock, bit 1 enables tick at prescaler overflow */
    ticks  = List(True, prescaler.io.overflow),
    clears = List(timerA.io.full)
    /* This initialization will run the timer from the prescaler input:
     *   CLRsTCKs 
     * 0x00010002 */
  )

  val interruptCtrl = InterruptCtrl(1)
  val interruptCtrlBridge = interruptCtrl.driveFrom(busCtrl, 0x10)
  interruptCtrl.io.inputs(0) := timerA.io.full
  io.interrupt := interruptCtrl.io.pendings.orR
}
