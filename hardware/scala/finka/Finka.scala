// Based on Briey, but with VGA and SDRAM controller removed

// Goal 1 is to expose a full (non-shared) AXI4 master on the top-level.
// see "extAxi4SharedBus" for the bus between crossbar and this master
// see "extAxi4Master" for the master interface for toplevel I/O
// This works, tested in hardware.

// Goal 2 is to expose a full (non-shared) AXI4 slave on the top-level.
// see "pcieAxi4SharedBus" for the bus between crossbar and this slave
// pcieAxi4SharedBus is bridged from pcieAxi4Bus
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
//import spinal.lib.soc.pinsec.{FinkaTimerCtrl, FinkaTimerCtrlExternal}
import spinal.lib.system.debugger.{JtagAxi4SharedDebugger, JtagBridge, SystemDebugger, SystemDebuggerConfig}

import spinal.lib.bus.misc.SizeMapping
//import spinal.lib.bus.regif.AccessType._
//import spinal.lib.bus.regif._
//import spinal.lib.bus.regif.Document.CHeaderGenerator
//import spinal.lib.bus.regif.Document.HtmlGenerator
//import spinal.lib.bus.regif.Document.JsonGenerator

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

// SpinalCorundum
import corundum._

case class FinkaConfig(axiFrequency : HertzNumber,
                       onChipRamSize : BigInt,
                       onChipRamHexFile : String,
                       cpuPlugins : ArrayBuffer[Plugin[VexRiscv]],
                       uartCtrlConfig : UartCtrlMemoryMappedConfig,
                       pcieAxi4Config : Axi4Config,
                       corundumDataWidth : Int)

object FinkaConfig{

  def default = {
    val config = FinkaConfig(
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
        new YamlPlugin("cpu0.yaml") // @TODO Maybe rename to Finka.yaml everywhere?
      )
    )
    config
  }
}

class Finka(val config: FinkaConfig) extends Component{

  import config._
  val debug = true
  val interruptCount = 4

  val io = new Bundle{
    // AXI4 Lite
    val clk     = in Bool()
    val rst     = in Bool()

    // Main components IO
    val jtag       = slave(Jtag())

    // AXI4 master towards an external AXI4 peripheral
    val corundumAxi4Master = master(Axi4(Axi4Config(32, 32, 2, useLock = false, useQos = false, useRegion = false)))

    // Peripherals IO
    val gpioA         = master(TriStateArray(32 bits))
    val uart          = master(Uart())
    val timerExternal = in(FinkaTimerCtrlExternal())
    val coreInterrupt = in Bool()

    /* register interface to IP address lookup update interface */
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

    // AXI4 slave from (external) PCIe bridge
    val pcieAxi4Slave = slave(Axi4(pcieAxi4Config))
    
    // in rx_clk clock domain, Ethernet/encrypted side, AXIS Corundum TDATA/TKEEP/TUSER
    val m_axis_tx = master Stream new Fragment(CorundumFrame(corundumDataWidth))
    val s_axis_rx = slave Stream new Fragment(CorundumFrame(corundumDataWidth))

    // in rx_clk clock domain, PCIe/plaintext side AXIS Corundum TDATA/TKEEP/TUSER
    //val frametxs = slave Stream new Fragment(CorundumFrame(corundumDataWidth))
    //val framerxm = master Stream new Fragment(CorundumFrame(corundumDataWidth))
  }

  val resetCtrlClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.rst,
    config = Config.syncConfig
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    val systemResetUnbuffered = False
    //    val coreResetUnbuffered = False

    //Implement a counter to keep the reset axiResetOrder high 64 cycles
    // Also this counter will automaticly do a reset when the system boot.
    val systemResetCounter = Reg(UInt(6 bits)) init(0)
    when(systemResetCounter =/= U(systemResetCounter.range -> true)){
      systemResetCounter := systemResetCounter + 1
      systemResetUnbuffered := True
    }
    //when(BufferCC(io.asyncReset)){
    //  systemResetCounter := 0
    //}

    //Create all reset used later in the design
    //val systemReset  = RegNext(io.rst) //simPublic()
    val axiReset     = RegNext(io.rst).simPublic()
  }

  val axiClockDomain = ClockDomain(
    clock = io.clk,
    reset = resetCtrl.axiReset,
    frequency = FixedFrequency(axiFrequency), //The frequency information is used by the SDRAM controller
    config = Config.syncConfig
  )

  // debug CD is reset by the external I/O reset sync to clk
  val debugClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.rst,
    frequency = FixedFrequency(axiFrequency)
  )

  val busconfig = Axi4Config(32, 32, 2, useLock = false, useQos = false, useRegion = false)
  // interconnect is an AXI4 Shared AW/AR bus (SpinalHDL specific)
  val interconnect = Axi4Shared(busconfig)

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

    val corundumAxi4SharedBus = interconnect.copy()
    val prefixAxi4SharedBus = interconnect.copy()
    val packetTxAxi4SharedBus = interconnect.copy()
    val packetRxAxi4SharedBus = interconnect.copy()
    val lookupAxi4SharedBus = interconnect.copy()

    val pcieAxi4Bus = Axi4(pcieAxi4Config)
    val pcieAxi4SharedBus = pcieAxi4Bus.toShared()

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
          plugin.externalInterrupt := io.coreInterrupt //BufferCC(io.coreInterrupt)
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
      ram.io.axi            -> (0x00800000L, onChipRamSize),
      // @NOTE keep finka.h in sync for software
      corundumAxi4SharedBus -> (0x00C00000L, 4 kB),
      packetTxAxi4SharedBus -> (0x00C01000L, 4 kB),
      packetRxAxi4SharedBus -> (0x00C02000L, 4 kB),
      lookupAxi4SharedBus   -> (0x00C03000L, 4 kB),
      prefixAxi4SharedBus   -> (0x00C04000L, 4 kB),
      apbBridge.io.axi      -> (0x00F00000L, 1 MB)
    )

    // sparse AXI4Shared crossbar
    // left side master, then for each master a List of accessible slaves on the right side
    axiCrossbar.addConnections(
      // CPU instruction bus (read-only master) can only access RAM slave
      core.iBus         -> List(ram.io.axi),
      // CPU data bus can access all slaves
      core.dBus         -> List(ram.io.axi, apbBridge.io.axi, corundumAxi4SharedBus, prefixAxi4SharedBus, packetTxAxi4SharedBus, packetRxAxi4SharedBus, lookupAxi4SharedBus),
      pcieAxi4SharedBus -> List(ram.io.axi, apbBridge.io.axi, corundumAxi4SharedBus, prefixAxi4SharedBus, packetTxAxi4SharedBus, packetRxAxi4SharedBus, lookupAxi4SharedBus)
    )

    /* AXI Peripheral Bus (APB) slave */
    axiCrossbar.addPipelining(apbBridge.io.axi)((crossbar, bridge) => {
      crossbar.sharedCmd.halfPipe() >> bridge.sharedCmd
      crossbar.writeData.halfPipe() >> bridge.writeData
      crossbar.writeRsp             << bridge.writeRsp
      crossbar.readRsp              << bridge.readRsp
    })

    /* corundum slave */
    axiCrossbar.addPipelining(corundumAxi4SharedBus)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    /* prefix update slave */
    axiCrossbar.addPipelining(prefixAxi4SharedBus)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    /* packet TX writer slave */
    axiCrossbar.addPipelining(packetTxAxi4SharedBus)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    /* packet RX reader slave */
    axiCrossbar.addPipelining(packetRxAxi4SharedBus)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    /* lookup table slave */
    axiCrossbar.addPipelining(lookupAxi4SharedBus)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
    })

    /* instruction and data RAM slave */
    axiCrossbar.addPipelining(ram.io.axi)((crossbar, ctrl) => {
      crossbar.sharedCmd.halfPipe()  >>  ctrl.sharedCmd
      crossbar.writeData            >/-> ctrl.writeData
      crossbar.writeRsp              <<  ctrl.writeRsp
      crossbar.readRsp               <<  ctrl.readRsp
      // mnemonic: / cuts the ready path, - stages valid and data
    })

    // CPU data bus master
    axiCrossbar.addPipelining(core.dBus)((cpu, crossbar) => {
      cpu.sharedCmd             >>  crossbar.sharedCmd
      cpu.writeData             >>  crossbar.writeData
      cpu.writeRsp              <<  crossbar.writeRsp
      cpu.readRsp               <-< crossbar.readRsp //Data cache directly use read responses without buffering, so pipeline it for FMax
    })

    // PCIe bus master
    axiCrossbar.addPipelining(pcieAxi4SharedBus)((pcie, crossbar) => {
      pcie.sharedCmd             >>  crossbar.sharedCmd
      pcie.writeData             >/->  crossbar.writeData
      pcie.writeRsp              <-/<  crossbar.writeRsp
      pcie.readRsp               <-/<  crossbar.readRsp
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

    val lookupTable = LookupMemAxi4(33, 128, busconfig, axiClockDomain)
    lookupTable.io.ctrlbus << lookupAxi4SharedBus.toAxi4()
    //val x =  Axi4SharedToBram(addressAxiWidth = 8, addressBRAMWidth = 8, dataWidth = 32, idWidth = 0)
  }

  val prefix = new ClockingArea(axiClockDomain) {
    val prefixAxi4Bus = Axi4(Axi4Config(32, 32, 2, useLock = false, useQos = false, useRegion = false/*, useStrb = false*/))
  
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

  val lookupRxSessionID = new ClockingArea(axiClockDomain) {

    val counter = Reg(U(0, 6 bits))

    // pretend to lookup at index 0x20
    //axi.lookupTable.io.clk := rxClockDomain.readClockWire
    //axi.lookupTable.io.rst := rxClockDomain.readResetWire
    axi.lookupTable.io.en := True
    axi.lookupTable.io.wr := False
    axi.lookupTable.io.wrData := 0
    axi.lookupTable.io.addr := counter.resized
    counter := counter + 1
  }

  // packet rx area
  val packetRx = new ClockingArea(axiClockDomain) {
    val packetRxAxi4SharedBus = Axi4Shared(busconfig)

    val sink = Stream(Fragment(CorundumFrame(corundumDataWidth)))

    // received on Ethernet port, going into SoC
    val stash = CorundumFrameFlowStash(corundumDataWidth, 32, 24)
    // drop when stash does not have room for a full packet
    val drop = !stash.io.sink.ready
    stash.io.sink << sink.throwWhen(drop)
    val packetReader = CorundumFrameReaderAxi4(corundumDataWidth, busconfig)
    packetReader.io.input << stash.io.source

    // connect to bus
    packetReader.io.ctrlbus << packetRxAxi4SharedBus.toAxi4()
  }
  // connect AXIS RX from Corundum to Finka
  io.s_axis_rx >> packetRx.sink

  // packet tx area
  val packetTx = new ClockingArea(axiClockDomain) {
    val packetTxAxi4SharedBus = Axi4Shared(busconfig)
    val packetWriter = CorundumFrameWriterAxi4(corundumDataWidth, busconfig)
    packetWriter.io.ctrlbus << packetTxAxi4SharedBus.toAxi4()
  }
  // connect AXIS RX from Finka to Corundum
  io.m_axis_tx << packetTx.packetWriter.io.output

  packetTx.packetTxAxi4SharedBus << axi.packetTxAxi4SharedBus
  packetRx.packetRxAxi4SharedBus << axi.packetRxAxi4SharedBus
  prefix.prefixAxi4Bus << axi.prefixAxi4SharedBus.toAxi4()

  io.gpioA              <> axi.gpioACtrl.io.gpio
  io.timerExternal      <> axi.timerCtrl.io.external
  io.uart               <> axi.uartCtrl.io.uart
  io.pcieAxi4Slave      <> axi.pcieAxi4Bus
  io.corundumAxi4Master <> axi.corundumAxi4SharedBus.toAxi4()

  io.commit := prefix.commit
  io.update := prefix.update

  // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => CorundumFrame.renameAxiIO(io))

  // Do more renaming
  private def renameFinkaIO(): Unit = {
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
  //Axi4SpecRenamer(io.corundumAxi4Master)
  //Axi4SpecRenamer(io.pcieAxi4Slave)

  // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => renameFinkaIO())
  addPrePopTask(() => CorundumFrame.renameAxiIO(io))
}

object Finka {
  def main(args: Array[String]) {
    val verilog = Config.spinal.generateVerilog({
      val toplevel = new Finka(FinkaConfig.default)
      toplevel
    })
    verilog.printPruned()

    val vhdl = Config.spinal.generateVhdl({
      val toplevel = new Finka(FinkaConfig.default)
      toplevel
    })
    //vhdl.printPruned()
  }
}

object FinkaWithMemoryInit{
  def main(args: Array[String]) {
    val config = Config.spinal
    val verilog = config.generateVerilog({
      val socConfig = FinkaConfig.default
      //.copy(onChipRamHexFile = "software/c/finka/hello_world/build/hello_world.hex")
      .copy(onChipRamHexFile = "software/c/finka/pico-hello/build/pico-hello.hex")
      val toplevel = new Finka(socConfig)
      // return this
      toplevel
    })
    //verilog.printPruned()

    // BEGIN: TRY TO EXTRACT XDC FOR CROSS_CLOCKING - LEON
    verilog.toplevel.walkComponents{ c =>
      c.dslBody.walkDeclarations{
        case signal : Bits => {

 //crossClockGrayTag(val pushClock: ClockDomain, val popClock: ClockDomain) extends SpinalTag
  //.addTag(new crossClockGrayTag(ClockDomain.current.readClockWire,

          signal.getTag(classOf[crossClockGrayTag]) match {
            case Some(tag) =>  println(s"$signal from ${tag.pushClock.readClockWire} to ${tag.popClock.readClockWire} !!!!")
            case None =>
          }
        }
        case _ =>
      }
    }

    //    case bt: BaseType => {
    //      if (bt.isReg && (bt.hasTag(crossClockDomain) || bt.hasTag(crossClockBuffer))) {
    //        bt.addAttribute("async_reg", "true")
    //      }
    //    }
    // END: TRY TO EXTRACT XDC FOR CROSS_CLOCKING - LEON





  }
}

object FinkaWireguard{
  def main(args: Array[String]) {
    val config = Config.spinal.copy(targetDirectory = "build/rtl/wireguard")
    val vhdlReport = config.generateVhdl({
      val socConfig = FinkaConfig.default.copy(onChipRamHexFile = "../wg_lwip/build-riscv/echop.hex")
      val toplevel = new Finka(socConfig)
      // return this
      toplevel
    })
    val verilogReport = config.generateVerilog({
      val socConfig = FinkaConfig.default.copy(onChipRamHexFile = "../wg_lwip/build-riscv/echop.hex")
      val toplevel = new Finka(socConfig)
      // return this
      toplevel
    })
    //verilog.printPruned()
  }
}

import scala.util.Random
import spinal.core.sim._
import scala.collection.mutable.ListBuffer

object FinkaSim {

  def main(args: Array[String]): Unit = {
    val simSlowDown = false
    val socConfig = FinkaConfig.default.copy(
      corundumDataWidth = 128,
      //onChipRamHexFile = "software/c/finka/hello_world/build/hello_world.hex"
      //onChipRamHexFile = "software/c/finka/pico-hello/build/pico-hello.hex"
      onChipRamHexFile = "../wg_lwip/build-riscv/echop.hex"
    )

    val simConfig = SimConfig
    // synchronous resets, see Config.scala
    .withConfig(Config.spinal)
    .allOptimisation
    //.withGhdl.addRunFlag("--unbuffered").addRunFlag("--ieee-asserts=disable").addRunFlag("--assert-level=none").addRunFlag("--backtrace-severity=warning")
    .withVerilator.addSimulatorFlag("-Wno-MULTIDRIVEN") // to simulate, even with true dual port RAM
    //.withXSim.withXilinxDevice("xcvu35p-fsvh2104-2-e")
    // LD_LIBRARY_PATH=/opt/Xilinx//Vivado/2021.2/lib/lnx64.o stdbuf -oL -eL sbt "runMain finka.FinkaSim"

    // !! set to true to generate a wavefrom dump for GTKWave -f 
    val waveform = false
    if (waveform) simConfig.withFstWave//.withWaveDepth(10) // does not work with Verilator, use SimTimeout()

    simConfig.compile{
      val dut = new Finka(socConfig)

      // expose internal signals
      //dut.resetCtrl.systemReset.simPublic()
      dut.resetCtrl.axiReset.simPublic()

      /* return dut */
      dut
    }
    //.doSimUntilVoid{dut =>
    //.doSim("test", 0/*fixed seed, to replicate*/){dut =>
    .doSim{dut =>
      val clkPeriod =   (1e12 / dut.config.axiFrequency.toDouble).toLong
      val packetClkPeriod = (1e12 / 322e6).toLong
      val jtagClkPeriod =    clkPeriod * 4/* this must be 4 (maybe more, not less) */
      val uartBaudRate =     115200
      val uartBaudPeriod =  (1e12 / uartBaudRate).toLong

      dut.io.s_axis_rx.valid #= false

      dut.io.timerExternal.clear #= true
      dut.io.timerExternal.tick #= true

      val axiClockDomain = ClockDomain(dut.io.clk, dut.io.rst)
      axiClockDomain.forkStimulus(clkPeriod)

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

      dut.io.coreInterrupt #= false

      dut.io.m_axis_tx.ready #= false
      dut.io.s_axis_rx.valid #= false
      dut.io.s_axis_rx.payload.tuser.assignBigInt(0)

      var commits_seen = 0
      // run 0.1 second after done
      var cycles_post = 100000


      axiClockDomain.waitRisingEdge(40)

if (false) {
      // push one word in stream
      dut.io.s_axis_rx.payload.tdata.assignBigInt(0x0011223344556677L)
      dut.io.s_axis_rx.payload.tkeep.assignBigInt(0x00FF)
      dut.io.s_axis_rx.payload.tuser.assignBigInt(0)
      dut.io.s_axis_rx.payload.last #= false
      dut.io.s_axis_rx.valid #= true
      dut.axiClockDomain.waitSamplingWhere(dut.io.s_axis_rx.ready.toBoolean)
      dut.io.s_axis_rx.payload.last #= false
      dut.io.s_axis_rx.valid #= true
      dut.axiClockDomain.waitSamplingWhere(dut.io.s_axis_rx.ready.toBoolean)
      dut.io.s_axis_rx.payload.last #= true
      dut.io.s_axis_rx.valid #= true
      dut.axiClockDomain.waitSamplingWhere(dut.io.s_axis_rx.ready.toBoolean)
      dut.io.s_axis_rx.valid #= false

      // push one word in stream
      dut.io.s_axis_rx.payload.tdata.assignBigInt(0x0011223344556677L)
      dut.io.s_axis_rx.payload.tkeep.assignBigInt(0x00FF)
      dut.io.s_axis_rx.payload.tuser.assignBigInt(0)
      dut.io.s_axis_rx.payload.last #= true
      dut.io.s_axis_rx.valid #= true
      dut.axiClockDomain.waitSamplingWhere(dut.io.s_axis_rx.ready.toBoolean)
      dut.io.s_axis_rx.valid #= false
}

dut.io.m_axis_tx.ready #= true

      //val monitorResetsThread = fork {
      //  if (dut.resetCtrl.axiReset.toBoolean == true) {
      //    printf("\nAXI RESET\n");
      //    dut.axiClockDomain.waitRisingEdge()
      //  }
      //}

      // packet generator
      val sendThread = fork {

        //ffffffffffffaabbcc11111108060001080006040001aabbcc111111c0a8ff01000000000000c0a8ff02

        val payload_wg4 =
        // <-------- Ethernet header --------------> <-IPv4 header IHL=5 protocol=0x11->                         <--5555,5555,len0x172-> <----Wireguard Type 4 ------------------------> < L a  d  i  e  s
          "01 02 03 04 05 06 01 02 03 04 05 06 08 00 45 11 22 33 44 55 66 77 88 11 00 00 00 00 00 00 00 00 00 00 15 b3 15 b3 01 72 00 00 04 00 00 00 11 22 33 44 c1 c2 c3 c4 c5 c6 c7 c8 4c 61 64 69 65 73 " +
        //  a  n  d     G  e  n  t  l  e  m  e  n     o  f     t  h  e     c  l  a  s  s     o  f     '  9  9  :     I  f     I     c  o  u  l  d     o  f  f  e  r     y  o  u     o  n  l  y     o  n
          "20 61 6e 64 20 47 65 6e 74 6c 65 6d 65 6e 20 6f 66 20 74 68 65 20 63 6c 61 73 73 20 6f 66 20 27 39 39 3a 20 49 66 20 49 20 63 6f 75 6c 64 20 6f 66 66 65 72 20 79 6f 75 20 6f 6e 6c 79 20 6f 6e " +
        //  e     t  i  p     f  o  r     t  h  e     f  u  t  u  r  e  ,     s  u  n  s  c  r  e  e  n     w  o  u  l  d     b  e     i  t  . <---------- Poly 1305 Tag (16 bytes) --------->
          "65 20 74 69 70 20 66 6f 72 20 74 68 65 20 66 75 74 75 72 65 2c 20 73 75 6e 73 63 72 65 65 6e 20 77 6f 75 6c 64 20 62 65 20 69 74 2e 13 05 13 05 13 05 13 05 13 05 13 05 13 05 13 05 00 00 00 00 "

        val payload = "ffffffffffffaabbcc11111108060001080006040001aabbcc111111c0a8ff01000000000000c0a8ff02"

        /* create a List of Arrays */
        val hexstring = payload.filterNot(_.isWhitespace)
        val packet_length = hexstring.size / 2/*nibbles per byte*/;
        val words = hexstring.grouped(2/*nibbles per byte*/).grouped(dut.config.corundumDataWidth/8)
        var strs  = new ListBuffer[BigInt]()
        words.zipWithIndex.foreach {
          case (word, count) => {
            printf("%s\n", word/*.reverse.*/.mkString(""))
            //printf("%s\n", word.reverse.mkString(""))
            strs += BigInt(word.reverse.mkString(""), 16)
          }
        }
        val plaintext = strs.toList
        printf("packet_length = %d, number of words = %d", packet_length, plaintext.size)

        // 64 - 6 = 58 bytes for all headers
        // 3 * 64 bytes - 4 = 188 bytes for full Ethernet packet (as above)
        // 188 - 58 = 130 bytes for encrypted/decrypted (16 bytes ceiling padded) payload and the Poly1305 tag
        // 130 - 16 = 114 bytes for encrypted/decrypted (16 bytes ceiling padded) )payload
        // 114 bytes fits in 8 128-bit words

        val dataWidth = dut.config.corundumDataWidth
        val maxDataValue = scala.math.pow(2, dataWidth).intValue - 1
        val keepWidth = dataWidth/8
        var data0 = 0

        var last0 = false
        var valid0 = false
        var tkeep0 = BigInt(0)
        var pause = false

        dut.axiClockDomain.waitSamplingWhere(dut.io.m_axis_tx.ready.toBoolean & dut.io.m_axis_tx.valid.toBoolean)
        printf("Saw packet from DUT\n");

        var sent = 0
        while (sent < 1) {
          //var packet_length = 3 * 64 - 4 // = 188 bytes
          var remaining = packet_length

          var word_index = 0
          // iterate over frame content
          while (remaining > 0) {
            printf("remaining = %d\n", remaining)
            val tkeep_len = if (remaining >= keepWidth) keepWidth else remaining;
            printf("tkeep_len = %d\n", tkeep_len)
            valid0 = (Random.nextInt(8) > 2)
            valid0 &= !pause
            if (pause) pause ^= (Random.nextInt(16) >= 15)
            if (!pause) pause ^= (Random.nextInt(128) >= 127)

            assert(tkeep_len <= keepWidth)
            tkeep0 = 0
            data0 = 0
            if (valid0) {
              last0 = (remaining <= keepWidth)
              for (i <- 0 until tkeep_len) {
                tkeep0 = (tkeep0 << 1) | 1
              }
            }

            dut.io.s_axis_rx.valid #= valid0
            dut.io.s_axis_rx.payload.tdata #= plaintext(word_index)
            dut.io.s_axis_rx.last #= last0
            dut.io.s_axis_rx.payload.tkeep #= tkeep0

            //dut.io.source.ready #= (Random.nextInt(8) > 1)

            // Wait a rising edge on the clock
            dut.axiClockDomain.waitRisingEdge()

            dut.io.s_axis_rx.valid #= false


            if (dut.io.s_axis_rx.ready.toBoolean & dut.io.s_axis_rx.valid.toBoolean) {
              remaining -= tkeep_len
              word_index += 1
            }
          }
          sent += 1
        }
      } //fork

        //if (dut.packet.packetWriter.bridge.commit2.toBoolean) {
        //  println("COMMIT2PACKET")
        //}

      while (true) {
        if (dut.io.commit.toBoolean) {
          println("COMMIT #", commits_seen)
          //printf("STRB : %04d\n", dut.prefix.ctrl.writeByteEnable.toLong.toBinaryString.toInt)
          //printf("REG# : %X\n", dut.prefix.reg_idx.toLong)
          //printf("UPDATE : %X\n", dut.io.update.toBigInt)
          commits_seen += 1
        }
        //if (dut.prefix.committed.toBoolean) {
        if (dut.io.do_update.toBoolean && false) {
          printf("REG0 : %X\n", dut.io.update0.toLong)
          printf("REG1 : %X\n", dut.io.update1.toLong)
          printf("REG2 : %X\n", dut.io.update2.toLong)
          printf("REG3 : %X\n", dut.io.update3.toLong)
          printf("REG4 : %X\n", dut.io.update4.toLong)
          printf("REG5 : %X\n", dut.io.update5.toLong)
          printf("REG6 : %X\n", dut.io.update6.toLong)
        }

        if (dut.io.s_axis_rx.valid.toBoolean & dut.io.s_axis_rx.ready.toBoolean) {
          printf("S_AXIS_RX VALID == %X\n", dut.io.s_axis_rx.valid.toBoolean.toInt)
          printf("S_AXIS_RX TLAST == %X\n", dut.io.s_axis_rx.last.toBoolean.toInt)
          // 4 bits per printf hex nibble
          val dw = dut.config.corundumDataWidth / 4
          // one keep bit per byte, 4 bits per printf hex nibble
          val kw = dut.config.corundumDataWidth / 8 / 4
          printf(s"S_AXIS_RX TDATA == 0x%0${dw}X\n", dut.io.s_axis_rx.payload.tdata.toBigInt)
          printf(s"S_AXIS_RX TKEEP == 0x%0${kw}X\n", dut.io.s_axis_rx.payload.tkeep.toBigInt)
        }

        if (dut.io.m_axis_tx.valid.toBoolean & dut.io.m_axis_tx.ready.toBoolean) {
          printf("M_AXIS_TX VALID == %X\n", dut.io.m_axis_tx.valid.toBoolean.toInt)
          printf("M_AXIS_TX TLAST == %X\n", dut.io.m_axis_tx.last.toBoolean.toInt)
          // 4 bits per printf hex nibble
          val dw = dut.config.corundumDataWidth / 4
          // one keep bit per byte, 4 bits per printf hex nibble
          val kw = dut.config.corundumDataWidth / 8 / 4
          printf(s"M_AXIS_TX TDATA == 0x%0${dw}X\n", dut.io.m_axis_tx.payload.tdata.toBigInt)
          printf(s"M_AXIS_TX TKEEP == 0x%0${kw}X\n", dut.io.m_axis_tx.payload.tkeep.toBigInt)
        }

        axiClockDomain.waitRisingEdge()

        if (commits_seen > 4) cycles_post -= 1
        if (cycles_post == 0) simSuccess()
        if (commits_seen > 3) simSuccess()

      }
      //simSuccess()
    }
  }
}
