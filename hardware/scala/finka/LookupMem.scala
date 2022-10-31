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



// true dual port ram with independent clocks, symmetric data widths
case class LookupMem(memDataWidth : Int,
                wordCount : Int) extends Component {

  //val bram_bus_config = BRAMConfig(memDataWidth, log2Up(wordCount))

  //val x =  Axi4SharedToBram(addressAxiWidth = 8, addressBRAMWidth = 8, dataWidth = 32, idWidth = 0)
  val memAddressWidth = log2Up(wordCount)

  val io = new Bundle {
    val portA = new Bundle {
      //val clk = in Bool()
     // val rst = in Bool()
      val en = in Bool()
      val wr = in Bool()
      val addr = in UInt (memAddressWidth bits)
      val wrData = in Bits (memDataWidth bits)
      val rdData = out Bits (memDataWidth bits)
    }
    val portB = new Bundle {
      val clk = in Bool()
      val rst = in Bool()
      //val portB = BRAM()
      val en = in Bool()
      val wr = in Bool()
      val addr = in UInt (memAddressWidth bits)
      val wrData = in Bits (memDataWidth bits)
      val rdData = out Bits (memDataWidth bits)
    }
  }

  val mem = Mem(Bits(memDataWidth bits), wordCount)

  // create read/write port A
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
    val bytes_per_cpu_word = busCtrl.busDataWidth / 8

    // for one memory word, calculate how many CPU words must be written
    val bus_words_per_memory_word = (memDataWidth + busCtrl.busDataWidth - 1) / busCtrl.busDataWidth
    printf("bus_words_per_memory_word    = %d (CPU writes needed to write one word into lookup table)\n", bus_words_per_memory_word)
    // for one memory word, calculate number of CPU words in the address space 
    // it is rounded up to the next power of two, so it will be 1, 2, 4, 8, 16 etc.
    val cpu_words_per_memory_word = nextPowerofTwo(bus_words_per_memory_word)
    val bytes_per_memory_word = cpu_words_per_memory_word * bytes_per_cpu_word
    val bytes_to_cpu_word_shift = log2Up(bytes_per_cpu_word)
    val bytes_to_memory_word_shift = log2Up(bytes_per_memory_word)
    printf("cpu_words_per_memory_word    = %d (CPU words reserved per lookup table word)\n", cpu_words_per_memory_word)
    printf("bytes_to_cpu_word_shift      = %d (bits to strip off)\n", bytes_to_cpu_word_shift)
    printf("bytes_to_memory_word_shift   = %d (bits to strip off)\n", bytes_to_memory_word_shift)

    printf("memory_words                 = %d\n", wordCount)


    // this is the address space exposed on the control bus
    val memory_size = wordCount * cpu_words_per_memory_word * bytes_per_cpu_word
    printf("memory space size = %d (0x%x) bytes\n", memory_size, memory_size)


    val bytes_to_memory_word_mask = bytes_per_memory_word - 1
    val cpu_word_to_memory_word_mask = cpu_words_per_memory_word - 1

    require(widthOf(busCtrl.writeAddress) == log2Up(memory_size), "LookupMemAxi4 slave address size must match memory size mapping")
    require(widthOf(busCtrl.readAddress) == log2Up(memory_size), "LookupMemAxi4 slave address size must match memory size mapping")

    printf("bytes_to_memory_word_mask      = 0x%08x\n", bytes_to_memory_word_mask)
    printf("cpu_word_to_memory_word_mask   = 0x%08x\n", cpu_word_to_memory_word_mask)


    //assert(memory_size <= )

    printf("isLastWritten = MaskMapping(0x%08x, 0x%08x)\n", (bus_words_per_memory_word - 1) * bytes_per_cpu_word, bytes_to_memory_word_mask)

    def isLastWritten(): Bool = {
      val mask_mapping_last = MaskMapping((bus_words_per_memory_word - 1) * bytes_per_cpu_word, bytes_to_memory_word_mask)
      val ret = False
      busCtrl.onWritePrimitive(address = mask_mapping_last, false, ""){ ret := True }
      ret
    }

    printf("isFirstWritten = MaskMapping(0x%08x, 0x%08x)\n", 0, bytes_to_memory_word_mask)


    def isFirstWritten(): Bool = {
      val mask_mapping_first = MaskMapping(0, bytes_to_memory_word_mask)
      val ret = False
      busCtrl.onWritePrimitive(address = mask_mapping_first, false, ""){ ret := True }
      ret
    }

    def isFirstRead(): Bool = {
      val mask_mapping_first = MaskMapping(0, bytes_to_memory_word_mask)
      val ret = False
      busCtrl.onReadPrimitive(address = mask_mapping_first, false, ""){ ret := True }
      ret
    }

    def isWritten(): Bool = {
      val size_mapping = SizeMapping(0, memory_size)
      val ret = False
      busCtrl.onWritePrimitive(address = size_mapping, false, ""){ ret := True }
      ret
    }


    val data = Bits(busCtrl.busDataWidth bits)
    busCtrl.nonStopWrite(data)

    val is_written = isWritten()
    val is_read_first = isFirstRead()
    val is_written_first = isFirstWritten()
    val is_written_last = isLastWritten()

    val expected = Reg(UInt(widthOf(busCtrl.writeAddress) bits))
    val write_data = Reg(Bits(memDataWidth bits))

    when (is_written_first) {
      expected := busCtrl.writeAddress + busCtrl.busDataWidth / 8
      write_data := data.resize(memDataWidth)
      //write_data := data.resize(memDataWidth) |<< ((cpu_words_per_memory_word - 1) * busCtrl.busDataWidth
    }

    when (is_written & (expected === busCtrl.writeAddress)) {
      expected := busCtrl.writeAddress + busCtrl.busDataWidth / 8
      write_data := (write_data |<< busCtrl.busDataWidth) | data.resize(memDataWidth)
    }

    val is_written_last_d1 = RegNext(is_written_last)
    val mem_wr_addr = RegNext((busCtrl.writeAddress >> bytes_to_memory_word_shift).resize(memAddressWidth) & ~U(bytes_to_memory_word_mask >> bytes_to_memory_word_shift, memAddressWidth bits))

    io.portA.en := True

    val mem_read_addr = UInt(memAddressWidth bits)
    mem_read_addr := (busCtrl.readAddress >> bytes_to_memory_word_shift).resize(memAddressWidth)

    val cpu_word_of_memory_word = UInt((bytes_to_memory_word_shift - bytes_to_cpu_word_shift) bits)
    cpu_word_of_memory_word := (busCtrl.readAddress >> bytes_to_cpu_word_shift).resize(bytes_to_memory_word_shift - bytes_to_cpu_word_shift) & U(cpu_word_to_memory_word_mask, (bytes_to_memory_word_shift - bytes_to_cpu_word_shift) bits)

    val bus_read_data = Bits(busCtrl.busDataWidth bits)
    bus_read_data := (io.portA.rdData >> (cpu_word_of_memory_word * busCtrl.busDataWidth)).resize(busCtrl.busDataWidth)
    busCtrl.readPrimitive(bus_read_data, SizeMapping(0, memory_size), 0, documentation = null)

    // drive read address on memory
    io.portA.addr := mem_read_addr //(busCtrl.readAddress >> bytes_to_memory_word_shift).resize(memAddressWidth) & ~U(bytes_to_memory_word_mask >> bytes_to_memory_word_shift, memAddressWidth bits)
    when (is_written_last_d1) {
      io.portA.addr := mem_wr_addr
    }

    io.portA.wrData := write_data
    io.portA.wr := RegNext(is_written_last)

    io.portB.en := True
    io.portB.wr := False
    io.portB.clk := False
    io.portB.rst := False
    io.portB.addr := 0
    io.portB.wrData := 0

    // SpinalHDL/lib/src/main/scala/spinal/lib/com/usb/udc/UsbDeviceCtrl.scala

    val readState = RegInit(U"00")
    //busCtrl.readPrimitive(io.portA.rdData.resize(cpu_words_per_memory_word * busCtrl.busDataWidth), SizeMapping(0, memory_size), 0, null)

    val cycle = Bool()
    val all = Bool()

    // if haltSentive is false, then the call back is made durring the whole access, while if that's true it is only done the last cycle of the access.

    all := False

    busCtrl.onReadPrimitive(SizeMapping(0, memory_size), haltSensitive = false, documentation = null) {
      all := True
      switch(readState){
        is (0) {
          busCtrl.readHalt()
          readState := 1
        }
        is (1) {
          busCtrl.readHalt()
          readState := 2
        }
      }
    }
    cycle := False
    busCtrl.onReadPrimitive(SizeMapping(0, memory_size), haltSensitive = true, documentation = null) {
      readState := 0
      cycle := True

      //busCtrl.readHalt()
    }

    //when (is_written_last) {
    //  io.portA.wr := True
    //}

    //onWritePrimitive(mapping, true, null) {
    //  val write_to := busCtrl.writeAddress
    //}
    //val write_word = Reg(Bits(memDataWidth bits))
    //val reg_idx = busCtrl.writeAddress.resize(log2Up(dataWidth / 8)) / (busCtrl.busDataWidth / 8)
  }
}

// [Synth 8-3971] The signal "LookupMem/mem_reg" was recognized as a true dual port RAM template.
// [Synth 8-7030] Implemented Non-Cascaded Block Ram (cascade_height = 1) of width 32 for RAM "LookupMem/mem_reg"

// companion object
object LookupMemAxi4 {
  //final val slaveAddressWidth = 10
}

// slave must be naturally aligned
case class LookupMemAxi4(wordWidth : Int, wordCount : Int, busCfg : Axi4Config) extends Component {

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

  /* calculate the bus slave address width needed to address the lookup table */
  val bytes_per_cpu_word = busCfg.dataWidth / 8
  val bus_words_per_memory_word = (wordWidth + busCfg.dataWidth - 1) / busCfg.dataWidth
  val cpu_words_per_memory_word = nextPowerofTwo(bus_words_per_memory_word)
  val bytes_per_memory_word = cpu_words_per_memory_word * bytes_per_cpu_word
  val memory_space = wordCount * bytes_per_memory_word
  val memory_space_address_bits = log2Up(memory_space)

  // the driving bus must have all address bits
  require(busCfg.addressWidth >= memory_space_address_bits)

  // copy AXI4 properties from bus, but override address width for slave
  val slaveCfg = busCfg.copy(addressWidth = memory_space_address_bits)

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
      val toplevel = new LookupMemAxi4(65, 1024, Axi4Config(32, 32, 2, useQos = false, useRegion = false))
      XilinxPatch(toplevel)
    })
  }
}

object LookupMemVerilog {
  def main(args: Array[String]) {
    val config = SpinalConfig()
    //config.addStandardMemBlackboxing(blackboxAll)

    val verilog = config.generateVerilog({
      val toplevel = new LookupMem(memDataWidth = 32, wordCount = 1024)
      // return this
      toplevel
      //XilinxPatch(toplevel)
    })
    //verilog.printPruned()
  }
}