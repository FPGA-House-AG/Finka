package finka

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3SlaveFactory, Apb3Config, Apb3}
import spinal.lib.misc.{InterruptCtrl, Timer, Prescaler}

/*
 * Based on FinkaTimerCtrl in SpinalHDL, but with BufferCC and Timers B,C,D removed
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
  val prescaler = Prescaler(16)
  val timerA = Timer(32)

  val busCtrl = Apb3SlaveFactory(io.apb)
  val prescalerBridge = prescaler.driveFrom(busCtrl, 0x00)

  val timerABridge = timerA.driveFrom(busCtrl, 0x40)(
    ticks  = List(True, prescaler.io.overflow),
    clears = List(timerA.io.full)
  )

  val interruptCtrl = InterruptCtrl(1)
  val interruptCtrlBridge = interruptCtrl.driveFrom(busCtrl, 0x10)
  interruptCtrl.io.inputs(0) := timerA.io.full
  io.interrupt := interruptCtrl.io.pendings.orR
}
