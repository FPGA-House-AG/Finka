package finka

import spinal.core._
import spinal.lib._

import spinal.lib.bus.amba4.axi
import spinal.lib.bus.misc._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.bram._

import spinal.core._
import spinal.lib._

import scala.math.pow

// from tester/src/main/scala/spinal/tester/code/Play2.scala

case class LookupMem(memDataWidth : Int,
                wordCount : Int) extends Component {

  val bram_bus_config = BRAMConfig(memDataWidth, log2Up(wordCount))

  //val x =  Axi4SharedToBram(addressAxiWidth = 8, addressBRAMWidth = 8, dataWidth = 32, idWidth = 0)
  val addressWidth = log2Up(wordCount)

  val io = new Bundle {
    val portA = new Bundle {
      //val clk = in Bool()
     // val rst = in Bool()
      val en = in Bool()
      val wr = in Bool()
      val addr = in UInt (addressWidth bits)
      val wrData = in Bits (memDataWidth bits)
      val rdData = out Bits (memDataWidth bits)
    }
    val portB = new Bundle {
      val clk = in Bool()
      val rst = in Bool()
      //val portB = BRAM()
      val en = in Bool()
      val wr = in Bool()
      val addr = in UInt (addressWidth bits)
      val wrData = in Bits (memDataWidth bits)
      val rdData = out Bits (memDataWidth bits)
    }
  }

  val mem = Mem(Bits(memDataWidth bits), wordCount)

  val areaA = new Area { //ClockingArea(ClockDomain(io.portA.clk, io.portA.rst)) {
    io.portA.rdData := RegNext(mem.readWriteSync(
      enable  = io.portA.en,
      address = io.portA.addr,
      write   = io.portA.wr,
      data    = io.portA.wrData
    ))
  }
  val areaB = new ClockingArea(ClockDomain(io.portB.clk, io.portB.rst)) {
    io.portB.rdData := RegNext(mem.readWriteSync(
      enable  = io.portB.en,
      address = io.portB.addr,
      write   = io.portB.wr,
      data    = io.portB.wrData
    ))
  }

  // https://graphics.stanford.edu/~seander/bithacks.html#RoundUpPowerOf2
  def nextPowerofTwo(x: Int): Int = {
    var y = x
    y = y - 1
    y = y | (y >> 1)
    y = y | (y >> 2)
    y = y | (y >> 4)
    y = y | (y >> 8)
    y = y | (y >> 16)
    y = y + 1
    y
  }

  // address decoding assumes slave-local addresses
  def driveFrom(busCtrl : BusSlaveFactory) = new Area {
    assert(busCtrl.busDataWidth == 32)


    val bus_words_per_memory_word = (memDataWidth + busCtrl.busDataWidth - 1) / busCtrl.busDataWidth
    printf("bus_words_per_memory_word = %d (CPU writes needed to write one word into lookup table)\n", bus_words_per_memory_word)
    val stride_per_memory_word = nextPowerofTwo(bus_words_per_memory_word)
    printf("stride_per_memory_word    = %d (CPU words reserved per lookup table word)\n", stride_per_memory_word)

    printf("memory_words              = %d\n", wordCount)

    val memory_size = wordCount * stride_per_memory_word * busCtrl.busDataWidth / 8
    printf("memory map size = %d (0x%x) bytes\n", memory_size, memory_size)

    //assert(memory_size <= )

    printf("MaskMapping(0x%08x, 0x%08x)\n", bus_words_per_memory_word, stride_per_memory_word - 1)

    def isLastWritten(): Bool = {
      val mask_mapping = MaskMapping(bus_words_per_memory_word, stride_per_memory_word - 1)
      val ret = False
      busCtrl.onWritePrimitive(address = mask_mapping, false, ""){ ret := True }
      ret
    }

    def isFirstWritten(): Bool = {
      val mask_mapping = MaskMapping(0, stride_per_memory_word - 1)
      val ret = False
      busCtrl.onWritePrimitive(address = mask_mapping, false, ""){ ret := True }
      ret
    }

    busCtrl.read(B"32'hAABBCCDD", 0x000, documentation = null)


    //val addressed = isAddressed()

    //val reg_idx = busCtrl.writeAddress.resize(log2Up(dataWidth / 8)) / (busCtrl.busDataWidth / 8)


  }
}

// [Synth 8-3971] The signal "LookupMem/mem_reg" was recognized as a true dual port RAM template.
// [Synth 8-7030] Implemented Non-Cascaded Block Ram (cascade_height = 1) of width 32 for RAM "LookupMem/mem_reg"

// companion object
object LookupMemAxi4 {
  final val slaveAddressWidth = 10
}

// slave must be naturally aligned
case class LookupMemAxi4(wordWidth : Int, wordCount : Int, busCfg : Axi4Config) extends Component {

  // copy AXI4 properties from bus, but override address width for slave
  val slaveCfg = busCfg.copy(addressWidth = LookupMemAxi4.slaveAddressWidth)

  //val slave_address_space_size = pow(2, LookupMemAxi4.slaveAddressWidth).intValue
  val slave_address_space_size = 1 << LookupMemAxi4.slaveAddressWidth

  printf("slave_address_size = %d (0x%x) bytes\n", slave_address_space_size, slave_address_space_size)


  val io = new Bundle {
    val ctrlbus = slave(Axi4(slaveCfg))
  }
  val mem = LookupMem(wordWidth, wordCount)
  val ctrl = new Axi4SlaveFactory(io.ctrlbus)
  val bridge = mem.driveFrom(ctrl)
}

//Generate the CorundumFrameFilter's Verilog
object LookupMemAxi4Verilog {
  def main(args: Array[String]) {
    val config = SpinalConfig()
    config.generateVerilog({
      val toplevel = new LookupMemAxi4(65, 8, Axi4Config(LookupMemAxi4.slaveAddressWidth, 32, 2, useQos = false, useRegion = false))
      XilinxPatch(toplevel)
    })
  }
}

object LookupMemVerilog {
  def main(args: Array[String]) {
    val config = SpinalConfig()
    //config.addStandardMemBlackboxing(blackboxAll)

    val verilog = config.generateVerilog({
      val toplevel = new LookupMem(memDataWidth = 32,wordCount = 1024)
      // return this
      toplevel
      //XilinxPatch(toplevel)
    })
    //verilog.printPruned()
  }
}