// Wassa is based on Briey, but with VGA and SDRAM controller removed

package finka

import vexriscv.plugin._
import vexriscv._
import vexriscv.ip.{DataCacheConfig, InstructionCacheConfig}

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.amba4.axilite.AxiLite4Utils.Axi4Rich
import spinal.lib.bus.misc.SizeMapping

import spinal.lib.com.jtag.Jtag
import spinal.lib.com.jtag.sim.JtagTcp
import spinal.lib.com.uart.sim.{UartDecoder, UartEncoder}

import spinal.lib.com.uart.{Apb3UartCtrl, Uart, UartCtrlGenerics, UartCtrlMemoryMappedConfig}
import spinal.lib.io.TriStateArray
import spinal.lib.misc.HexTools
//import spinal.lib.soc.pinsec.{FinkaTimerCtrl, FinkaTimerCtrlExternal}
import spinal.lib.system.debugger.{JtagAxi4SharedDebugger, JtagBridge, SystemDebugger, SystemDebuggerConfig}

import spinal.core.sim.{SimPublic, TracingOff}

import scala.util.Random

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer
import scala.collection.Seq

case class WassaConfig(axiFrequency : HertzNumber,
                       onChipRamSize : BigInt,
                       onChipRamHexFile : String,
                       cpuPlugins : ArrayBuffer[Plugin[VexRiscv]],
                       uartCtrlConfig : UartCtrlMemoryMappedConfig,
                       pcieAxi4Config : Axi4Config,
                       corundumDataWidth : Int)

object WassaConfig{

  def default = {
    val config = WassaConfig(
      corundumDataWidth = 512,
      axiFrequency = 250 MHz,
      onChipRamSize = 256 kB,
      onChipRamHexFile = null, //"software/c/finka/hello_world/build/hello_world.hex",

      /* prot signals but no last signal - however SpinalHDL/Axi4 assumes Last for Axi4* classes */
      pcieAxi4Config = Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 0, useId = false, useRegion = false, 
        useBurst = false, useLock = false, useCache = false, useSize = false, useQos = false,
        useLen = false, useLast = true/*fails otherwise*/, useResp = true, useProt = true, useStrb = true),

      uartCtrlConfig = UartCtrlMemoryMappedConfig(
        uartCtrlConfig = UartCtrlGenerics(
          dataWidthMax      = 8,
          clockDividerWidth = 20,
          preSamplingSize   = 1,
          samplingSize      = 5,
          postSamplingSize  = 2
        ),
        txFifoDepth = 256,
        rxFifoDepth = 256
      ),
      cpuPlugins = ArrayBuffer(
        //new PcManagerSimplePlugin(0x00800000L, false),
        //          new IBusSimplePlugin(
        //            interfaceKeepData = false,
        //            catchAccessFault = true
        //          ),
        new IBusCachedPlugin(
          // processor first instruction fetch address after reset
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
          // 0x00C00000-0x00FFFFFF is uncached, system peripheral registers
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
            // trap and interrupt vector
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
        new YamlPlugin("wassa.yaml") // @TODO Maybe rename to Wassa.yaml everywhere?
      )
    )
    config
  }
}

class Wassa(val config: WassaConfig) extends Component{

  import config._
  val debug = true
  val interruptCount = 4

  val io = new Bundle{
    // main clock for CPU and AXI interconnect, 250 MHz
    val clk     = in Bool()
    val rst     = in Bool()

    // Main components IO
    val jtag       = slave(Jtag())

    // Peripherals IO
    val gpioA         = master(TriStateArray(32 bits))
    val uart          = master(Uart())
    val timerExternal = in(FinkaTimerCtrlExternal())
    val coreInterrupt = in Bool()
  }

  val resetCtrlClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.rst,
    config = Config.syncConfig
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    // this reset call also be asserted by the DebugPlugin, if enabled
    val axiReset        = RegNext(io.rst) //.simPublic()
    val axiResetDelayed = RegNext(axiReset)
  }

  val axiClockDomain = ClockDomain(
    clock = io.clk,
    reset = resetCtrl.axiResetDelayed,
    frequency = FixedFrequency(axiFrequency),
    config = Config.syncConfig
  )

  // debug CD is reset by the external I/O reset sync to clk
  val debugClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.rst,
    frequency = FixedFrequency(axiFrequency)
  )

  val axi = new ClockingArea(axiClockDomain) {

    // instruction and data memory
    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = onChipRamSize,
      idWidth = 4
    )

    if (config.onChipRamHexFile != null) {
      println("Initializing Axi4SharedOnChipRam with " + config.onChipRamHexFile)
      HexTools.initRam(ram.ram, config.onChipRamHexFile, 0x00800000L)
    } else {
      println("[WARNING] Axi4SharedOnChipRam is NOT initialized.")
    }

    val apbBridge = Axi4SharedToApb3Bridge(
      addressWidth = 20,
      dataWidth    = 32,
      idWidth      = 4
    )

    val gpioACtrl = Apb3Gpio(
      gpioWidth = 32,
      withReadSync = true
    )
    val timerCtrl = FinkaTimerCtrl()

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
          plugin.externalInterrupt := io.coreInterrupt
          plugin.timerInterrupt := timerCtrl.io.interrupt
        }
        case plugin : DebugPlugin      => plugin.debugClockDomain{
          resetCtrl.axiReset setWhen(RegNext(plugin.io.resetOut))
          io.jtag <> plugin.io.bus.fromJtag()
        }
        case _ =>
      }
    }

    val axiCrossbar = Axi4CrossbarFactory()

    axiCrossbar.addSlaves(
      ram.io.axi                      -> (0x00800000L, onChipRamSize),
      /* AXI Peripheral Bus (APB) slave */
      apbBridge.io.axi                -> (0x00F00000L, 1 MB)
    )

    val peripheralSlaves = List(apbBridge.io.axi)

    // sparse AXI4Shared crossbar
    // left side master, then for each master a List of accessible slaves on the right side
    axiCrossbar.addConnections(
      // CPU instruction bus (read-only master) can only access RAM slave
      core.iBus         -> List(ram.io.axi),
      // CPU data bus can access all slaves
      core.dBus         -> (List(ram.io.axi) ++ peripheralSlaves)
    )

    for (slave <- peripheralSlaves) {
      axiCrossbar.addPipelining(slave)((crossbar, ctrl) => {
        crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
        crossbar.writeData            >/-> ctrl.writeData
        crossbar.writeRsp              <-/<  ctrl.writeRsp
        crossbar.readRsp               <-/<  ctrl.readRsp
      })
    }

    /* CPU instruction and data slave */
    axiCrossbar.addPipelining(ram.io.axi)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>   ctrl.sharedCmd
      crossbar.writeData             >/-> ctrl.writeData
      crossbar.writeRsp              <-/< ctrl.writeRsp
      crossbar.readRsp               <-/< ctrl.readRsp
      // mnemonic: / cuts the ready path, - stages valid and data
    })

    // CPU data bus master
    axiCrossbar.addPipelining(core.dBus)((cpu, crossbar) => {
      cpu.sharedCmd             >>  crossbar.sharedCmd
      cpu.writeData             >>  crossbar.writeData
      cpu.writeRsp              <<  crossbar.writeRsp
      //Data cache directly use read responses without buffering, so pipeline it for FMax
      cpu.readRsp               <-< crossbar.readRsp
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

  val packetRx = new ClockingArea(axiClockDomain) {
    val sf = StreamFifo(Bits(8 bits), 8)
  }

  io.gpioA              <> axi.gpioACtrl.io.gpio
  io.timerExternal      <> axi.timerCtrl.io.external
  io.uart               <> axi.uartCtrl.io.uart

  addPrePopTask(() => renameWassaIO)

  // Do more renaming
  private def renameWassaIO(): Unit = {
    io.flatten.foreach(bt => {
      if(bt.getName().contains("_aw_tvalid")) bt.setName(bt.getName().replace("_aw_tvalid", "_awvalid"))
      if(bt.getName().contains("_ar_tvalid")) bt.setName(bt.getName().replace("_ar_tvalid", "_arvalid"))
      if(bt.getName().contains("_w_tvalid")) bt.setName(bt.getName().replace("_w_tvalid", "_wvalid"))
      if(bt.getName().contains("_b_tvalid")) bt.setName(bt.getName().replace("_b_tvalid", "_bvalid"))
      if(bt.getName().contains("_r_tvalid")) bt.setName(bt.getName().replace("_r_tvalid", "_rvalid"))
      if(bt.getName().contains("_aw_tready")) bt.setName(bt.getName().replace("_aw_tready", "_awready"))
      if(bt.getName().contains("_ar_tready")) bt.setName(bt.getName().replace("_ar_tready", "_arready"))
      if(bt.getName().contains("_w_tready")) bt.setName(bt.getName().replace("_w_tready", "_wready"))
      if(bt.getName().contains("_b_tready")) bt.setName(bt.getName().replace("_b_tready", "_bready"))
      if(bt.getName().contains("_r_tready")) bt.setName(bt.getName().replace("_r_tready", "_rready"))
      if(bt.getName().contains("_aw_tlast")) bt.setName(bt.getName().replace("_aw_tlast", "_awlast"))
      if(bt.getName().contains("_ar_tlast")) bt.setName(bt.getName().replace("_ar_tlast", "_arlast"))
      if(bt.getName().contains("_w_tlast")) bt.setName(bt.getName().replace("_w_tlast", "_wlast"))
      if(bt.getName().contains("_b_tlast")) bt.setName(bt.getName().replace("_b_tlast", "_blast"))
      if(bt.getName().contains("_r_tlast")) bt.setName(bt.getName().replace("_r_tlast", "_rlast"))
      if(bt.getName().contains("_aw_")) bt.setName(bt.getName().replace("_aw_", "_aw"))
      if(bt.getName().contains("_ar_")) bt.setName(bt.getName().replace("_ar_", "_ar"))
      if(bt.getName().contains("_w_")) bt.setName(bt.getName().replace("_w_", "_w"))
      if(bt.getName().contains("_b_")) bt.setName(bt.getName().replace("_b_", "_b"))
      if(bt.getName().contains("_r_")) bt.setName(bt.getName().replace("_r_", "_r"))
      if(bt.getName().contains("pcieAxi4Slave_")) bt.setName(bt.getName().replace("pcieAxi4Slave_", "pcie_axi_"))
      if(bt.getName().contains("corundumAxi4Master_")) bt.setName(bt.getName().replace("corundumAxi4Master_", "xbar_axi_"))
    })
  }

  // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => renameWassaIO())
}

object Wassa {
  def main(args: Array[String]) {
    val verilog = Config.spinal.generateVerilog({
      val toplevel = new Wassa(WassaConfig.default)
      toplevel
    })
    verilog.printPruned()

    val vhdl = Config.spinal.generateVhdl({
      val toplevel = new Wassa(WassaConfig.default)
      toplevel
    })
    //vhdl.printPruned()
  }
}

object WassaWithMemoryInit{
  def main(args: Array[String]) {
    val config = Config.spinal
    val verilog = config.generateVerilog({
      val socConfig = WassaConfig.default.copy(
        onChipRamHexFile = "software/c/finka/pico-hello/build/pico-hello.hex"
      )
      val toplevel = new Wassa(socConfig)
      // return this
      toplevel
    })
    //verilog.printPruned()
  }
}

import scala.util.Random
import spinal.core.sim._
import scala.collection.mutable.ListBuffer

object WassaSim {

  def main(args: Array[String]): Unit = {
    val simSlowDown = false

    val simConfig = SimConfig
    // synchronous resets, see Config.scala
    //.withConfig(Config.spinal)
    //.allOptimisation
    .withGhdl.addRunFlag("--unbuffered").addRunFlag("--ieee-asserts=disable").addRunFlag("--assert-level=none").addRunFlag("--backtrace-severity=warning")
    //.withWave

    // !! set to true to generate a wavefrom dump for GTKWave -f 
    val waveform = false
    if (waveform) simConfig.withFstWave//.withWaveDepth(10) // does not work with Verilator, use SimTimeout()
//
    simConfig.compile{

      val socConfig = WassaConfig.default.copy(
        onChipRamHexFile = "software/c/finka/pico-hello/build/pico-hello.hex"
      )

      val dut = new Wassa(socConfig)

      dut.resetCtrl.axiReset.simPublic()
      dut.resetCtrl.axiResetDelayed.simPublic()

      /* return dut */
      dut
    }
    //.doSimUntilVoid{dut =>
    //.doSim("test", 0/*fixed seed, to replicate*/){dut =>
    .doSim{dut =>
      val clkPeriod =   (1e12 / dut.config.axiFrequency.toDouble).toLong
      val jtagClkPeriod =    clkPeriod * 4/* this must be 4 (maybe more, not less) */
      val uartBaudRate =     115200
      val uartBaudPeriod =  (1e12 / uartBaudRate).toLong

      // de-assert interrupt input to CPU
      dut.io.coreInterrupt #= false

      dut.io.timerExternal.clear #= true
      dut.io.timerExternal.tick #= true

      dut.axiClockDomain.forkStimulus(clkPeriod)
      dut.clockDomain.forkStimulus(clkPeriod)

      //val axiClockDomain = ClockDomain(dut.io.clk, dut.io.rst)
      //axiClockDomain.forkStimulus(clkPeriod)

      // stop after 1M clocks to prevent disk wearout
      //if (waveform) SimTimeout(100000 * clkPeriod)

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

      var commits_seen = 0
      // run 0.1 second after done
      var cycles_post = 100000

      dut.axiClockDomain.waitRisingEdge(40)

      dut.io.timerExternal.clear #= false

      //simSuccess()
    }
  }
}
