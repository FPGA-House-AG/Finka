// Based on Briey, but with VGA and SDRAM controller removed

// Goal 1 is to expose a full (non-shared) AXI4 master on the top-level.
// see "extAxiSharedBus" for the bus between crossbar and this master
// see "extAxi4Master" for the master interface for toplevel I/O
// This works, tested in hardware.

// Goal 2 is to expose a full (non-shared) AXI4 slave on the top-level.
// see "pcieAxiSharedBus" for the bus between crossbar and this slave
// pcieAxiSharedBus is bridged from pcieAxi4Bus
// see "pcieAxi4Slave" for the slave interface for toplevel I/O
// This compiles.

package finka

import vexriscv.plugin._
import vexriscv._
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba4.axi._
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.jtag.sim.JtagTcp
import spinal.lib.com.uart.sim.{UartDecoder, UartEncoder}
import spinal.core.sim.{SimPublic, TracingOff}

import spinal.lib.com.uart.{Apb3UartCtrl, Uart, UartCtrlGenerics, UartCtrlMemoryMappedConfig}
import spinal.lib.io.TriStateArray
import spinal.lib.misc.HexTools
import spinal.lib.soc.pinsec.{PinsecTimerCtrl, PinsecTimerCtrlExternal}
import spinal.lib.system.debugger.{JtagAxi4SharedDebugger, JtagBridge, SystemDebugger, SystemDebuggerConfig}

import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType._
import spinal.lib.bus.regif._
import spinal.lib.bus.regif.Document.CHeaderGenerator
import spinal.lib.bus.regif.Document.HtmlGenerator
import spinal.lib.bus.regif.Document.JsonGenerator

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

import corundum._

case class FinkaConfig(axiFrequency : HertzNumber,
                       onChipRamSize : BigInt,
                       onChipRamHexFile : String,
                       cpuPlugins : ArrayBuffer[Plugin[VexRiscv]],
                       uartCtrlConfig : UartCtrlMemoryMappedConfig,
                       pcieAxi4Config : Axi4Config)

object FinkaConfig{

  def default = {
    val config = FinkaConfig(
      axiFrequency = 250 MHz,
      onChipRamSize = 64 kB,
      onChipRamHexFile = null, //"software/c/finka/hello_world/build/hello_world.hex",
      uartCtrlConfig = UartCtrlMemoryMappedConfig(
        uartCtrlConfig = UartCtrlGenerics(
          dataWidthMax      = 8,
          clockDividerWidth = 20,
          preSamplingSize   = 1,
          samplingSize      = 5,
          postSamplingSize  = 2
        ),
        txFifoDepth = 16,
        rxFifoDepth = 16
      ),
      /* prot signals but no last signal - however SpinalHDL/Axi4 assumes Last for Axi4* classes */
      pcieAxi4Config = Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 0, useId = false, useRegion = false, 
        useBurst = false, useLock = false, useCache = false, useSize = false, useQos = false,
        useLen = false, useLast = true/*fails otherwise*/, useResp = true, useProt = true, useStrb = true),
      cpuPlugins = ArrayBuffer(
        //new PcManagerSimplePlugin(0x00800000L, false),
        //          new IBusSimplePlugin(
        //            interfaceKeepData = false,
        //            catchAccessFault = true
        //          ),
        new IBusCachedPlugin(
          resetVector = 0x00800000L,
          prediction = STATIC,
          config = InstructionCacheConfig(
            cacheSize = 4096,
            bytePerLine =32,
            wayCount = 1,
            addressWidth = 32,
            cpuDataWidth = 32,
            memDataWidth = 32,
            catchIllegalAccess = true,
            catchAccessFault = true,
            asyncTagMemory = false,
            twoCycleRam = true,
            twoCycleCache = true
          )
          //            askMemoryTranslation = true,
          //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //              portTlbSize = 4
          //            )
        ),
        //                    new DBusSimplePlugin(
        //                      catchAddressMisaligned = true,
        //                      catchAccessFault = true
        //                    ),
        new DBusCachedPlugin(
          config = new DataCacheConfig(
            cacheSize         = 4096,
            bytePerLine       = 32,
            wayCount          = 1,
            addressWidth      = 32,
            cpuDataWidth      = 32,
            memDataWidth      = 32,
            catchAccessError  = true,
            catchIllegal      = true,
            catchUnaligned    = true
          ),
          memoryTranslatorPortConfig = null
          //            memoryTranslatorPortConfig = MemoryTranslatorPortConfig(
          //              portTlbSize = 6
          //            )
        ),
        new StaticMemoryTranslatorPlugin(
          // 0x00C00000-0x00FFFFFF is uncached
          ioRange      = _(23 downto 22) === 0x3
        ),
        new DecoderSimplePlugin(
          catchIllegalInstruction = true
        ),
        new RegFilePlugin(
          regFileReadyKind = plugin.SYNC,
          zeroBoot = false
        ),
        new IntAluPlugin,
        new SrcPlugin(
          separatedAddSub = false,
          executeInsertion = true
        ),
        new FullBarrelShifterPlugin,
        new MulPlugin,
        new DivPlugin,
        new HazardSimplePlugin(
          bypassExecute           = true,
          bypassMemory            = true,
          bypassWriteBack         = true,
          bypassWriteBackBuffer   = true,
          pessimisticUseSrc       = false,
          pessimisticWriteRegFile = false,
          pessimisticAddressMatch = false
        ),
        new BranchPlugin(
          earlyBranch = false,
          catchAddressMisaligned = true
        ),
        new CsrPlugin(
          config = CsrPluginConfig(
            catchIllegalAccess = false,
            mvendorid      = null,
            marchid        = null,
            mimpid         = null,
            mhartid        = null,
            misaExtensionsInit = 66,
            misaAccess     = CsrAccess.NONE,
            mtvecAccess    = CsrAccess.NONE,
            mtvecInit      = 0x00800020l,
            mepcAccess     = CsrAccess.READ_WRITE,
            mscratchGen    = false,
            mcauseAccess   = CsrAccess.READ_ONLY,
            mbadaddrAccess = CsrAccess.READ_ONLY,
            mcycleAccess   = CsrAccess.NONE,
            minstretAccess = CsrAccess.NONE,
            ecallGen       = false,
            wfiGenAsWait   = false,
            ucycleAccess   = CsrAccess.NONE,
            uinstretAccess = CsrAccess.NONE
          )
        ),
        new YamlPlugin("cpu0.yaml")
      )
    )
    config
  }
}

class Finka(val config: FinkaConfig) extends Component{

  //Legacy constructor
  //def this(axiFrequency: HertzNumber) {
  //  this(FinkaConfig.default.copy(axiFrequency = axiFrequency))
  //}

  import config._
  val debug = true
  val interruptCount = 4

  val io = new Bundle{
    // Clocks / reset
    val asyncReset = in Bool()

    val axiClk     = in Bool()

    val packetClk   = in Bool()
    //val packetRst   = in Bool()

    // Main components IO
    val jtag       = slave(Jtag())

    // AXI4 master towards an external AXI4 peripheral
    //val extAxi4Master = master(Axi4(Axi4Config(32, 32, 2, useQos = false, useRegion = false)))

    // AXI4 slave from (external) PCIe bridge
    val pcieAxi4Slave = slave(Axi4(pcieAxi4Config))

    // Peripherals IO
    val gpioA         = master(TriStateArray(32 bits))
    val uart          = master(Uart())
    val timerExternal = in(PinsecTimerCtrlExternal())
    val coreInterrupt = in Bool()

    val update0 = out UInt(32 bits)
    val update1 = out UInt(32 bits)
    val update2 = out UInt(32 bits)
    val update3 = out UInt(32 bits)
    val update4 = out UInt(32 bits)
    val update5 = out UInt(32 bits)
    val update6 = out UInt(32 bits)
    val do_update = out Bool()

    val update = out UInt(64 bits)
    val commit = out Bool()
  }

  val resetCtrlClockDomain = ClockDomain(
    clock = io.axiClk,
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    val systemResetUnbuffered  = False
    //    val coreResetUnbuffered = False

    //Implement an counter to keep the reset axiResetOrder high 64 cycles
    // Also this counter will automaticly do a reset when the system boot.
    val systemResetCounter = Reg(UInt(6 bits)) init(0)
    when(systemResetCounter =/= U(systemResetCounter.range -> true)){
      systemResetCounter := systemResetCounter + 1
      systemResetUnbuffered := True
    }
    when(BufferCC(io.asyncReset)){
      systemResetCounter := 0
    }

    //Create all reset used later in the design
    val systemReset  = RegNext(systemResetUnbuffered)
    val axiReset     = RegNext(systemResetUnbuffered)
    val packetReset  = RegNext(systemResetUnbuffered)
  }

  val axiClockDomain = ClockDomain(
    clock = io.axiClk,
    reset = resetCtrl.axiReset,
    frequency = FixedFrequency(axiFrequency) //The frequency information is used by the SDRAM controller
  )

  val debugClockDomain = ClockDomain(
    clock = io.axiClk,
    reset = resetCtrl.systemReset,
    frequency = FixedFrequency(axiFrequency)
  )

  val packetClockDomain = ClockDomain(
    clock = io.packetClk,
    reset = resetCtrl.packetReset /*BufferCC(resetCtrl.systemResetUnbuffered)*/
  )

  val axi = new ClockingArea(axiClockDomain) {

    // instruction and data memory
    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = onChipRamSize,
      idWidth = 4
    )

    if (config.onChipRamHexFile != null) {
      println("Initializing Axi4SharedOnChipRam with ", config.onChipRamHexFile)
      HexTools.initRam(ram.ram, config.onChipRamHexFile, 0x00800000L)
    } else {
      println("[WARNING] Axi4SharedOnChipRam is NOT initialized.")
    }

    val prefixAxiSharedBus = Axi4Shared(Axi4Config(32, 32, 2, useQos = false, useRegion = false))
    val packetAxiSharedBus = Axi4Shared(Axi4Config(32, 32, 2, useQos = false, useRegion = false))

    val pcieAxi4Bus = Axi4(pcieAxi4Config)
    val pcieAxiSharedBus = pcieAxi4Bus.toShared()

    //, useId = false, useRegion = false, 
    // useBurst = false, useLock = false, useCache = false, useSize = false, useQos = false,
    // useLen = false, useLast = false, useResp = false, useProt = true, useStrb = false))

    val apbBridge = Axi4SharedToApb3Bridge(
      addressWidth = 20,
      dataWidth    = 32,
      idWidth      = 4
    )

    val gpioACtrl = Apb3Gpio(
      gpioWidth = 32,
      withReadSync = true
    )
    val timerCtrl = PinsecTimerCtrl()

    val uartCtrl = Apb3UartCtrl(uartCtrlConfig)
    uartCtrl.io.apb.addAttribute(Verilator.public)

    val core = new Area{
      val config = VexRiscvConfig(
        plugins = cpuPlugins += new DebugPlugin(debugClockDomain, 3/*breakpoints*/)
      )

      val cpu = new VexRiscv(config)
      var iBus : Axi4ReadOnly = null
      var dBus : Axi4Shared = null
      for(plugin <- config.plugins) plugin match{
        case plugin : IBusSimplePlugin => iBus = plugin.iBus.toAxi4ReadOnly()
        case plugin : IBusCachedPlugin => iBus = plugin.iBus.toAxi4ReadOnly()
        case plugin : DBusSimplePlugin => dBus = plugin.dBus.toAxi4Shared()
        case plugin : DBusCachedPlugin => dBus = plugin.dBus.toAxi4Shared(true/*stageCmd required (?)*/)
        case plugin : CsrPlugin        => {
          plugin.externalInterrupt := BufferCC(io.coreInterrupt)
          plugin.timerInterrupt := timerCtrl.io.interrupt
        }
        case plugin : DebugPlugin      => debugClockDomain{
          resetCtrl.axiReset setWhen(RegNext(plugin.io.resetOut))
          io.jtag <> plugin.io.bus.fromJtag()
        }
        case _ =>
      }
    }

    val axiCrossbar = Axi4CrossbarFactory()

    axiCrossbar.addSlaves(
      ram.io.axi          -> (0x00800000L, onChipRamSize),
      prefixAxiSharedBus  -> (0x00C00000L, 4 kB),
      packetAxiSharedBus  -> (0x00C01000L, 4 kB),
      apbBridge.io.axi    -> (0x00F00000L, 1 MB)
    )

    // sparse AXI4Shared crossbar
    // left side master, then for each master a List of accessible slaves on the right side
    axiCrossbar.addConnections(
      // CPU instruction bus (read-only master) can only access RAM slave
      core.iBus        -> List(ram.io.axi),
      // CPU data bus (read-only master) can access all slaves
      core.dBus        -> List(ram.io.axi, apbBridge.io.axi, prefixAxiSharedBus, packetAxiSharedBus),
      pcieAxiSharedBus -> List(ram.io.axi, apbBridge.io.axi, prefixAxiSharedBus, packetAxiSharedBus)
    )

    /* AXI Peripheral Bus */
    axiCrossbar.addPipelining(apbBridge.io.axi)((crossbar,bridge) => {
      crossbar.sharedCmd.halfPipe() >> bridge.sharedCmd
      crossbar.writeData.halfPipe() >> bridge.writeData
      crossbar.writeRsp             << bridge.writeRsp
      crossbar.readRsp              << bridge.readRsp
    })

    /* prefix update slave */
    axiCrossbar.addPipelining(prefixAxiSharedBus)((crossbar,ctrl) => {
      crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    /* packet generator slave */
    axiCrossbar.addPipelining(packetAxiSharedBus)((crossbar,ctrl) => {
      crossbar.sharedCmd.halfPipe() >> ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    axiCrossbar.addPipelining(ram.io.axi)((crossbar,ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    // CPU data bus master
    axiCrossbar.addPipelining(core.dBus)((cpu,crossbar) => {
      cpu.sharedCmd             >>  crossbar.sharedCmd
      cpu.writeData             >>  crossbar.writeData
      cpu.writeRsp              <<  crossbar.writeRsp
      cpu.readRsp               <-< crossbar.readRsp //Data cache directly use read responses without buffering, so pipeline it for FMax
    })

    // PCIe bus master
    axiCrossbar.addPipelining(pcieAxiSharedBus)((pcie,crossbar) => {
      pcie.sharedCmd             >>  crossbar.sharedCmd
      pcie.writeData             >>  crossbar.writeData
      pcie.writeRsp              <<  crossbar.writeRsp
      pcie.readRsp               <<  crossbar.readRsp
    })

    axiCrossbar.build()

    val apbDecoder = Apb3Decoder(
      master = apbBridge.io.apb,
      slaves = List(
        gpioACtrl.io.apb -> (0x00000, 4 kB),
        uartCtrl.io.apb  -> (0x10000, 4 kB),
        timerCtrl.io.apb -> (0x20000, 4 kB)
      )
    )
  }

  val prefix = new ClockingArea(packetClockDomain) {
    val prefixAxi4Bus = Axi4(Axi4Config(32, 32, 2, useQos = false, useRegion = false/*, useStrb = false*/))
  
    val ctrl = new Axi4SlaveFactory(prefixAxi4Bus)
    val reg_idx = ((ctrl.writeAddress & 0xFFF) / 4)
  
    val regs = Vec.tabulate(7)(i => ctrl.createWriteOnly(UInt(32 bits), address = 0x00C00000L + i * 4, bitOffset = 0))
    val update = UInt(64 bits)
    update := regs(0) @@ regs(1)
    // match a range of addresses using OR of single addresses
    val commit = 
      /*ctrl.isWriting(address = 0x00C00000L) |
      ctrl.isWriting(address = 0x00C00004L) |
      ctrl.isWriting(address = 0x00C00008L) |
      ctrl.isWriting(address = 0x00C0000cL) |
      ctrl.isWriting(address = 0x00C00010L) |
      ctrl.isWriting(address = 0x00C00014L) |*/
      ctrl.isWriting(address = 0x00C00018L)
    val do_update = RegNext(commit) init (False)
  }
  io.update0 := prefix.regs(0)
  io.update1 := prefix.regs(1)
  io.update2 := prefix.regs(2)
  io.update3 := prefix.regs(3)
  io.update4 := prefix.regs(4)
  io.update5 := prefix.regs(5)
  io.update6 := prefix.regs(6)
  io.do_update := prefix.do_update

  val packet = new ClockingArea(packetClockDomain) {
    val packetAxi4Bus = Axi4(Axi4Config(32, 32, 2, useQos = false, useRegion = false/*, useStrb = false*/))

    val ctrl = new Axi4SlaveFactory(packetAxi4Bus)

    val stream_word = Reg(Bits(512 bits))
    ctrl.writeMultiWord(stream_word, 0xC01000, documentation = null)

    // match a range of addresses using mask
    import spinal.lib.bus.misc.MaskMapping

    // writing packet word content?
    def isAddressed(): Bool = {
      val mask_mapping = MaskMapping(0xFFFFC0L/*64 addresses, 16 32-bit regs*/, 0xC01000L)
      val ret = False
      ctrl.onWritePrimitive(address = mask_mapping, false, ""){ ret := True }
      ret
    }
    val commit2 = RegNext(isAddressed())

    val reg_idx = ((ctrl.writeAddress & 0xFFF) / 4)

    // set (per-byte) tkeep bits for all 32-bit registers being written
    val tkeep = Reg(Bits(512 / 8 bits)) init (0)
    when (isAddressed()) {
      val reg_idx = (ctrl.writeAddress - 0x00) >> 2
      tkeep := tkeep
      //tkeep(reg_idx * 4, 4 bits) := tkeep(reg_idx * 4, 4 bits) | ctrl.writeByteEnable
      tkeep(reg_idx * 4, 4 bits) := B"1111"
    }
    val valid = Bool
    valid := False
    // 0x40 VALID=1, TLAST=0
    ctrl.onWrite(0xC01040L, null){
      valid := True
      tkeep := 0
      stream_word := 0
    }
    val tlast = Bool
    tlast := False
    // 0x44 VALID=1, TLAST=1
    ctrl.onWrite(0xC01044L, null){
      valid := True
      tlast := True
      tkeep := 0
      stream_word := 0
    }
    val strb = RegNext(ctrl.writeByteEnable);

    val mux = CorundumFrameMuxPrio(8);
  }

  val axi2packetCDC = Axi4SharedCC(Axi4Config(32, 32, 2, useQos = false, useRegion = false), axiClockDomain, packetClockDomain, 2, 2, 2, 2)
  axi2packetCDC.io.input << axi.packetAxiSharedBus
  packet.packetAxi4Bus << axi2packetCDC.io.output.toAxi4()

  val axi2prefixCDC = Axi4SharedCC(Axi4Config(32, 32, 2, useQos = false, useRegion = false), axiClockDomain, packetClockDomain, 2, 2, 2, 2)
  axi2prefixCDC.io.input << axi.prefixAxiSharedBus
  prefix.prefixAxi4Bus << axi2prefixCDC.io.output.toAxi4()

  io.gpioA          <> axi.gpioACtrl.io.gpio
  io.timerExternal  <> axi.timerCtrl.io.external
  io.uart           <> axi.uartCtrl.io.uart
  io.pcieAxi4Slave  <> axi.pcieAxi4Bus

  io.commit := prefix.commit
  io.update := prefix.update

  //noIoPrefix()
}

// https://gitter.im/SpinalHDL/SpinalHDL?at=5c2297c28d31aa78b1f8c969
object XilinxPatch {
  def apply[T <: Component](c : T) : T = {
    //Get the io bundle via java reflection
    val m = c.getClass.getMethod("io")
    val io = m.invoke(c).asInstanceOf[Bundle]

    //Patch things
    io.elements.map(_._2).foreach{
      //case axi : AxiLite4 => AxiLite4SpecRenamer(axi)
      case axi : Axi4 => Axi4SpecRenamer(axi)
      case _ =>
    }

    //Builder pattern return the input argument
    c 
  }
}

object Finka{
  def main(args: Array[String]) {
    val config = SpinalConfig()
    val verilog = config.generateVerilog({
      val toplevel = new Finka(FinkaConfig.default)
      XilinxPatch(toplevel)
    })
    //verilog.printPruned()
  }
}

object FinkaWithMemoryInit{
  def main(args: Array[String]) {
    val config = SpinalConfig()
    val verilog = config.generateVerilog({
      val socConfig = FinkaConfig.default.copy(onChipRamHexFile = "software/c/finka/hello_world/build/hello_world.hex", onChipRamSize = 64 kB)
      val toplevel = new Finka(socConfig)
      XilinxPatch(toplevel)
    })
    //verilog.printPruned()
  }
}

import spinal.core.sim._
object FinkaSim {
  def main(args: Array[String]): Unit = {
    val simSlowDown = false
    val socConfig = FinkaConfig.default.copy(
      onChipRamSize = 64 kB,
      onChipRamHexFile = "software/c/finka/hello_world/build/hello_world.hex"
    )

    val simConfig = SimConfig.allOptimisation/*.withWave*/

    simConfig.compile{
      val dut = new Finka(socConfig)

      //dut.prefix.reg_idx.simPublic()
      //dut.prefix.regs.simPublic()
      //dut.prefix.committed.simPublic()

      dut.packet.strb.simPublic()
      dut.packet.reg_idx.simPublic()
      dut.packet.tkeep.simPublic()
      dut.packet.stream_word.simPublic()
      dut.packet.commit2.simPublic()
      dut.packet.valid.simPublic()
      dut.packet.tlast.simPublic()
      //dut.packet.ctrl.writeByteEnable.simPublic()
      dut
    }.doSimUntilVoid{dut =>
      // SimConfig.allOptimisation.withWave.compile
      val mainClkPeriod = (1e12/dut.config.axiFrequency.toDouble).toLong
      val jtagClkPeriod = mainClkPeriod * 4/* this must be 4 (maybe more, not less) */
      val uartBaudRate = 115200
      val uartBaudPeriod = (1e12/uartBaudRate).toLong

      val axiClockDomain = ClockDomain(dut.io.axiClk, dut.io.asyncReset)
      axiClockDomain.forkStimulus(mainClkPeriod)

      val packetClockDomain = ClockDomain(dut.io.packetClk)
      packetClockDomain.forkStimulus((1e12/322e6).toLong)

      val tcpJtag = JtagTcp(
        jtag = dut.io.jtag,
        jtagClkPeriod = jtagClkPeriod
      )

      val uartTx = UartDecoder(
        uartPin = dut.io.uart.txd,
        baudPeriod = uartBaudPeriod
      )

      val uartRx = UartEncoder(
        uartPin = dut.io.uart.rxd,
        baudPeriod = uartBaudPeriod
      )

      dut.io.coreInterrupt #= false

      var commits_seen = 0
      // run 0.1 second after done
      var cycles_post = 100000

      packetClockDomain.waitSampling(1)

      while (true) {
        if (dut.io.commit.toBoolean) {
          println("COMMIT #", commits_seen)
          //printf("STRB : %04d\n", dut.prefix.ctrl.writeByteEnable.toLong.toBinaryString.toInt)
          //printf("REG# : %X\n", dut.prefix.reg_idx.toLong)
          //printf("UPDATE : %X\n", dut.io.update.toBigInt)
          commits_seen += 1
        }
        //if (dut.prefix.committed.toBoolean) {
        if (dut.io.do_update.toBoolean) {
          printf("REG0 : %X\n", dut.io.update0.toLong)
          printf("REG1 : %X\n", dut.io.update1.toLong)
          printf("REG2 : %X\n", dut.io.update2.toLong)
          printf("REG3 : %X\n", dut.io.update3.toLong)
          printf("REG4 : %X\n", dut.io.update4.toLong)
          printf("REG5 : %X\n", dut.io.update5.toLong)
          printf("REG6 : %X\n", dut.io.update6.toLong)
        }
        //printf("commit2 : %X\n", dut.packet.commit2.toBoolean.toInt)

        if (dut.packet.commit2.toBoolean) {
          printf("REG# : %X\n", dut.packet.reg_idx.toLong)
          printf("STREAM : %08X\n", dut.packet.stream_word.toBigInt)
          printf("TKEEP : %016X\n", dut.packet.tkeep.toBigInt)
        }
        if (dut.packet.valid.toBoolean) {
          printf("*VALID == %d\n", dut.packet.valid.toBoolean.toInt);
          printf("*TLAST == %d\n", dut.packet.tlast.toBoolean.toInt);
          printf("*REG# : %X\n", dut.packet.reg_idx.toLong)
          printf("*STREAM : %08X\n", dut.packet.stream_word.toBigInt)
          printf("*TKEEP : %016X\n", dut.packet.tkeep.toBigInt)
        }
        packetClockDomain.waitRisingEdge()
        if (commits_seen > 4) cycles_post -= 1
        if (cycles_post == 0) simSuccess()
      }
      simSuccess()
    }
  }
}