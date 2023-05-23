// Finka is the SoC for Blackwire
// It handles all incoming packets from Ethernet, except Wireguard Type 4.
// Finka handles Wireguard Type 1, 2 and 3 messages for handshaking.
// (The remaining Type 4 is handled in logic in the RX and TX RTL paths.)
//
// Finka is based on Briey, but with VGA and SDRAM controller removed

// Finka is based around a VexRiscv RV32IM CPU, which connects to a AXI4
// crossbar.
// Masters connected to this crossbar are the CPU instruction and data
// bus and a master coming from a PCIe BAR (MMIO).
// Slaves on the crossbar are:
//  - APH with timer, UART and GPIO peripherals.
//  - AXI4 bus going into Corundum app section.
//  - RX key lookup table (LUT), to update symmetric keys.
//  - TX key lookup table (LUT), to update symmetric keys.
//  - RX packet reader, to read non-Type 4 messages.
//  - TX packet writer, to write Ethernet packets.

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

// Blackwire
import blackwire._
// SpinalCorundum
import corundum._

// ScalablePipelinedLookup
import scalablePipelinedLookup._

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
    // main clock for CPU and AXI interconnect, 250 MHz
    val clk     = in Bool()
    val rst     = in Bool()

    // high speed clock specifically for crypto
    val turbo_clk     = in Bits(2 bits)
    val turbo_rst     = in Bits(2 bits)

    // Main components IO
    val jtag       = slave(Jtag())

    // AXI4 master towards an external AXI4 peripheral
    val corundumAxi4Master = master(Axi4(Axi4Config(32, 32, 2, useLock = false, useQos = false, useRegion = false)))

    // Peripherals IO
    val gpioA         = master(TriStateArray(32 bits))
    val uart          = master(Uart())
    val timerExternal = in(FinkaTimerCtrlExternal())
    val coreInterrupt = in Bool()

    // AXI4 slave from (external) PCIe bridge
    val pcieAxi4Slave = slave(Axi4(pcieAxi4Config))
    
    // AXIS Corundum TDATA/TKEEP/TUSER interfaces

    // encrypted to Ethernet CMAC
    val m_axis_tx = master Stream new Fragment(CorundumFrame(corundumDataWidth, 17))
    // encrypted from Ethernet CMAC
    val s_axis_rx = slave Stream new Fragment(CorundumFrame(corundumDataWidth))

    // plaintext from PCIe
    val s_axis_tx  = slave Stream new Fragment(CorundumFrame(corundumDataWidth, 17))
    // plaintext to PCIe
    val m_axis_rx = master Stream new Fragment(CorundumFrame(corundumDataWidth))

    // completions to PCIe
    val m_axis_tx_cpl = master(Stream(Bits(16 bits)))
    // completions from CMAC
    val s_axis_tx_cpl = slave(Stream(Bits(16 bits)))
  }
  //io.m_axis_tx.addAttribute("mark_debug")
  //io.s_axis_tx.addAttribute("mark_debug")

  //io.m_axis_tx_cpl.addAttribute("mark_debug")
  //io.s_axis_tx_cpl.addAttribute("mark_debug")

  val rxCryptoClockDomain = ClockDomain(
    clock = io.turbo_clk(0),
    reset = io.turbo_rst(0),
    config = Config.syncConfig
  )

  val txCryptoClockDomain = ClockDomain(
    clock = io.turbo_clk(1),
    reset = io.turbo_rst(1),
    config = Config.syncConfig
  )

  val resetCtrlClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.rst,
    config = Config.syncConfig
  )

  val resetCtrl = new ClockingArea(resetCtrlClockDomain) {
    // this reset call also be asserted by the DebugPlugin, if enabled
    val axiReset     = RegNext(io.rst) //.simPublic()
  }

  val axiClockDomain = ClockDomain(
    clock = io.clk,
    reset = resetCtrl.axiReset,
    frequency = FixedFrequency(axiFrequency),
    config = Config.syncConfig
  )

  // debug CD is reset by the external I/O reset sync to clk
  val debugClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.rst,
    frequency = FixedFrequency(axiFrequency)
  )

  // AXI4MM but most signals disabled: useId = false, useRegion = false, 
  // useBurst = false, useLock = false, useCache = false, useSize = false, useQos = false,
  // useLen = false, useLast = false, useResp = false, useProt = true, useStrb = false))
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

    // crossbar to slaves interconnects
    val corundumAxi4SharedBus = interconnect.copy()
    val packetTxAxi4SharedBusWriter = interconnect.copy()
    val packetRxAxi4SharedBusReader = interconnect.copy()
    val packetTxAxi4SharedBusPktHdr = interconnect.copy()
    val packetRxAxi4SharedBusRxKey = interconnect.copy()
    val packetTxAxi4SharedBusTxKey = interconnect.copy()
    val packetTxAxi4SharedBusP2S = interconnect.copy()
    val packetTxAxi4SharedBusP2EP = interconnect.copy()
    val packetTxAxi4SharedBusL2R = interconnect.copy()
    val packetTxAxi4SharedBusTxCounter = interconnect.copy()
    val packetRxTxAxi4SharedBusIP = interconnect.copy()
    val packetRxAxi4SharedBusSession = interconnect.copy()
    ////val accelAxi4SharedBusX25519 = interconnect.copy()

    val pcieAxi4Bus = Axi4(pcieAxi4Config)
    val pcieAxi4SharedBus = pcieAxi4Bus.toShared()

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

    // @NOTE keep finka.h in sync for software

      /* corundum slave */
      corundumAxi4SharedBus           -> (0x00C00000L, 4 kB),
      /* packet TX writer slave */
      packetTxAxi4SharedBusWriter     -> (0x00C01000L, 4 kB),

      /* packet RX reader slave */
      packetRxAxi4SharedBusReader     -> (0x00C02000L, 4 kB),
      /* packet TX packet header configuration slave */
      packetTxAxi4SharedBusPktHdr     -> (0x00C03000L, 4 kB),

      ////accelAxi4SharedBusX25519        -> (0x00C05000L, 4 kB),
      /* RX TX Allowed IP address lookup */
      /* X25519 accelerator */
      packetRxTxAxi4SharedBusIP       -> (0x00C06000L, 4 kB),
      /* packet TX nonce counter lookup table */
      packetTxAxi4SharedBusTxCounter  -> (0x00C10000L, 4 kB),
      // 1024 keys for 4 (curr, next, prev, unused) sessions/per * 256 peers
      // each key is 32 bytes (256 bits)
      // 32 kiB or 0x8000 bytes
      /* packet RX key lookup table */
      packetRxAxi4SharedBusRxKey      -> (0x00C20000L, 32 kB),
      /* packet TX key lookup table */
      packetTxAxi4SharedBusTxKey      -> (0x00C30000L, 32 kB),
      /* packet Peer to Session (P2S) lookup table */
      packetTxAxi4SharedBusP2S        -> (0x00C40000L, 32 kB),
      /* packet Peer to Endpoint (P2EP) lookup table */
      packetTxAxi4SharedBusP2EP       -> (0x00C50000L, 32 kB),
      /* packet Peer to Endpoint (L2R) lookup table */
      packetTxAxi4SharedBusL2R        -> (0x00C60000L, 32 kB),
      /* packet RX session lookup table */
      packetRxAxi4SharedBusSession    -> (0x00C70000L, 32 kB),

      /* AXI Peripheral Bus (APB) slave */
      apbBridge.io.axi                -> (0x00F00000L, 1 MB)
    )

    val peripheralSlaves = List(
      apbBridge.io.axi, corundumAxi4SharedBus, packetTxAxi4SharedBusWriter, packetRxAxi4SharedBusReader,
      packetTxAxi4SharedBusPktHdr, packetRxAxi4SharedBusSession, packetRxAxi4SharedBusRxKey, packetTxAxi4SharedBusTxKey,
      packetTxAxi4SharedBusP2S, packetTxAxi4SharedBusP2EP, packetTxAxi4SharedBusL2R,
      packetTxAxi4SharedBusTxCounter/*, accelAxi4SharedBusX25519*/, packetRxTxAxi4SharedBusIP)

    // sparse AXI4Shared crossbar
    // left side master, then for each master a List of accessible slaves on the right side
    axiCrossbar.addConnections(
      // CPU instruction bus (read-only master) can only access RAM slave
      core.iBus         -> List(ram.io.axi),
      // CPU data bus can access all slaves
      //@build should check for double entries
      core.dBus         -> (List(ram.io.axi) ++ peripheralSlaves),
      pcieAxi4SharedBus -> (List(ram.io.axi) ++ peripheralSlaves)
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

    // PCIe bus master
    axiCrossbar.addPipelining(pcieAxi4SharedBus)((pcie, crossbar) => {
      pcie.sharedCmd.halfPipe()   >>  crossbar.sharedCmd
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

    ////val x25519 = X25519Axi4(busconfig)
    ////x25519.io.ctrlbus << accelAxi4SharedBusX25519.toAxi4()
  }

  // packet rx area
  val packetRx = new ClockingArea(axiClockDomain) {
    val packetRxAxi4SharedBusReader  = Axi4Shared(busconfig)
    val packetRxAxi4SharedBusRxKey   = Axi4Shared(busconfig)
    val packetRxAxi4SharedBusSession = Axi4Shared(busconfig)

    // TDATA+TKEEP Ethernet frame from Corundum
    val sink = Stream(Fragment(CorundumFrame(corundumDataWidth)))
    val source = Stream(Fragment(CorundumFrame(corundumDataWidth)))

    val rx = BlackwireReceiveDual(busconfig, cryptoCD = rxCryptoClockDomain, has_busctrl = true, include_chacha = true)
    rx.io.sink << sink
    source << rx.io.source
    rx.io.ctrl_rxkey << packetRxAxi4SharedBusRxKey.toAxi4()
    rx.io.ctrl_session << packetRxAxi4SharedBusSession.toAxi4()

    val packetReader = CorundumFrameReaderAxi4(corundumDataWidth, busconfig)
    packetReader.io.input << rx.io.source_handshake

    // connect to bus
    packetReader.io.ctrlbus << packetRxAxi4SharedBusReader.toAxi4()
  }
  // connect AXIS RX from Corundum CMAC to Finka RX path
  io.s_axis_rx >> packetRx.sink
  // connect AXIS RX from Finka RX path to Corundum PCIe
  io.m_axis_rx << packetRx.source

  // packet tx area
  val packetTx = new ClockingArea(axiClockDomain) {
    // packet writer driven by RISC-V
    val packetTxAxi4SharedBusWriter = Axi4Shared(busconfig)
    val packetWriter = CorundumFrameWriterAxi4(corundumDataWidth, busconfig)
    packetWriter.io.ctrlbus << packetTxAxi4SharedBusWriter.toAxi4()

    val sink = Stream(Fragment(CorundumFrame(corundumDataWidth, userWidth = 17)))
    val source = Stream(Fragment(CorundumFrame(corundumDataWidth, userWidth = 17)))

    val cpl_sink = Stream(Bits(16 bits))
    val cpl_source = Stream(Bits(16 bits))

    val tx = BlackwireTransmit(busconfig, include_chacha = true, has_busctrl = true)
    // transmit packets
    tx.io.sink << sink
    // handshake packets from RISC-V
    tx.io.sink_handshake << packetWriter.io.output
    source << tx.io.source

    // transmit completions
    tx.io.cpl_sink << cpl_sink
    cpl_source << tx.io.cpl_source

    // TX packet header configuration driven by RISC-V
    val packetTxAxi4SharedBusPktHdr = Axi4Shared(busconfig)
    tx.io.ctrl_hdr << packetTxAxi4SharedBusPktHdr.toAxi4()

    // TX key LUT driven by RISC-V
    val packetTxAxi4SharedBusTxKey = Axi4Shared(busconfig)
    tx.io.ctrl_txkey << packetTxAxi4SharedBusTxKey.toAxi4()

    // Peer to Session LUT driven by RISC-V
    val packetTxAxi4SharedBusP2S = Axi4Shared(busconfig)
    tx.io.ctrl_p2s << packetTxAxi4SharedBusP2S.toAxi4()

    // Peer to Endpoint (P2EP) LUT driven by RISC-V
    val packetTxAxi4SharedBusP2EP = Axi4Shared(busconfig)
    tx.io.ctrl_p2ep << packetTxAxi4SharedBusP2EP.toAxi4()

    // Local to Remote Session (L2R) LUT driven by RISC-V
    val packetTxAxi4SharedBusL2R = Axi4Shared(busconfig)
    tx.io.ctrl_l2r << packetTxAxi4SharedBusL2R.toAxi4()

    // TX nonce counter LUT cleared by RISC-V
    val packetTxAxi4SharedBusTxCounter = Axi4Shared(busconfig)
    tx.io.ctrl_txc << packetTxAxi4SharedBusTxCounter.toAxi4()
  }

  // rx and tx
  val packetRxTx = new ClockingArea(axiClockDomain) {
    // IP lookup updated/written by RISC-V
    val ipl = LookupTop(config = LookupDataConfig(memInitTemplate = None))

    val rx_ipl = Flow(Bits(32 bits))
    val tx_ipl = Flow(Bits(32 bits))

    val rx_ipl_res = Flow(UInt(11 bits))
    val tx_ipl_res = Flow(UInt(11 bits))

    val packetRxTxAxi4SharedBusIP = Axi4Shared(busconfig)
    ipl.io.axi/*ctrl_ipl*/ << packetRxTxAxi4SharedBusIP.toAxi4.toLite()

    // RX and TX paths do IP address lookups
    ipl.io.lookup.apply(0) << rx_ipl
    ipl.io.lookup.apply(1) << tx_ipl
    // IP address lookup results returned to RX and TX paths
    rx_ipl_res.valid   := ipl.io.result.apply(0).valid
    rx_ipl_res.payload := ipl.io.result.apply(0).lookupResult.location
    tx_ipl_res.valid   := ipl.io.result.apply(1).valid
    tx_ipl_res.payload := ipl.io.result.apply(1).lookupResult.location
  }
  packetRxTx.packetRxTxAxi4SharedBusIP << axi.packetRxTxAxi4SharedBusIP
  packetRxTx.rx_ipl << packetRx.rx.io.source_ipl
  packetRxTx.tx_ipl << packetTx.tx.io.source_ipl
  packetRxTx.rx_ipl_res >> packetRx.rx.io.sink_ipl
  packetRxTx.tx_ipl_res >> packetTx.tx.io.sink_ipl

  // connect AXIS TX from Corundum PCIe to Finka TX path
  io.s_axis_tx >> packetTx.sink
  // connect AXIS TX from Finka TX path to Corundum CMAC
  io.m_axis_tx << packetTx.source

  // connect AXIS TX Corundum CMAC completions to Finka TX completion return path
  io.s_axis_tx_cpl >> packetTx.cpl_sink
  // connect AXIS TX completions from Finka TX path to Corundum PCIe
  io.m_axis_tx_cpl << packetTx.cpl_source

  // connect AXI4
  packetRx.packetRxAxi4SharedBusReader << axi.packetRxAxi4SharedBusReader
  packetTx.packetTxAxi4SharedBusWriter << axi.packetTxAxi4SharedBusWriter
  packetTx.packetTxAxi4SharedBusPktHdr << axi.packetTxAxi4SharedBusPktHdr
  packetRx.packetRxAxi4SharedBusRxKey << axi.packetRxAxi4SharedBusRxKey
  packetTx.packetTxAxi4SharedBusTxKey << axi.packetTxAxi4SharedBusTxKey
  packetTx.packetTxAxi4SharedBusP2S << axi.packetTxAxi4SharedBusP2S
  packetTx.packetTxAxi4SharedBusP2EP << axi.packetTxAxi4SharedBusP2EP
  packetTx.packetTxAxi4SharedBusL2R << axi.packetTxAxi4SharedBusL2R
  packetTx.packetTxAxi4SharedBusTxCounter << axi.packetTxAxi4SharedBusTxCounter
  packetRx.packetRxAxi4SharedBusSession << axi.packetRxAxi4SharedBusSession

  io.gpioA              <> axi.gpioACtrl.io.gpio
  io.timerExternal      <> axi.timerCtrl.io.external
  io.uart               <> axi.uartCtrl.io.uart
  io.pcieAxi4Slave      <> axi.pcieAxi4Bus
  io.corundumAxi4Master <> axi.corundumAxi4SharedBus.toAxi4()

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
      val socConfig = FinkaConfig.default.copy(
        //onChipRamHexFile = "software/c/finka/hello_world/build/hello_world.hex"
        onChipRamHexFile = "software/c/finka/pico-hello/build/pico-hello.hex"
      )
      val toplevel = new Finka(socConfig)
      // return this
      toplevel
    })
    //verilog.printPruned()
  }
}

object FinkaWireguard {
  def main(args: Array[String]) {
    val config = Config.spinal.copy(targetDirectory = "build/rtl/wireguard")
    val vhdlReport = config.generateVhdl({
      val socConfig = FinkaConfig.default.copy(onChipRamHexFile = "../lwip-wireguard/build-riscv/echop.hex")
      val toplevel = new Finka(socConfig)
      // return this
      toplevel
    })
    val verilogReport = config.generateVerilog({
      val socConfig = FinkaConfig.default.copy(onChipRamHexFile = "../lwip-wireguard/build-riscv/echop.hex")
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

    val simConfig = SimConfig
    // synchronous resets, see Config.scala
    .withConfig(Config.spinal)
    .allOptimisation
    .withGhdl.addRunFlag("--unbuffered").addRunFlag("--ieee-asserts=disable").addRunFlag("--assert-level=none").addRunFlag("--backtrace-severity=warning")
    //.withWave

    //.withVerilator.addSimulatorFlag("-Wno-MULTIDRIVEN") // to simulate, even with true dual port RAM
    //.withXSim.withXilinxDevice("xcvu35p-fsvh2104-2-e")
    // LD_LIBRARY_PATH=/opt/Xilinx//Vivado/2021.2/lib/lnx64.o stdbuf -oL -eL sbt "runMain finka.FinkaSim"
    
    simConfig
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/bus_pkg1.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/AEAD_encryption_wrapper_kar.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/AEAD_encryptor_kar.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/ChaCha20_128.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/ChaCha_int.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/col_round.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/diag_round.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/half_round.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/mod_red_1305.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/mul_136_kar.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/mul136_mod_red.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/mul_36.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/mul_68_kar.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/mul_gen_0.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/mul_red_pipeline.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/Poly_1305_pipe_kar.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/Poly_1305_pipe_top_kar.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/q_round.vhd")
    .addRtl(s"../ChaCha20Poly1305/src_dsp_opt/r_pow_n_kar.vhd")

    simConfig
    .addRtl(s"../x25519/src_ecdh_FSM/add_255_mod_red.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/clamp_k_u.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/kP_FSM.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/kP_round_fsm.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mod_inv_FSM.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mod_red_25519.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_136_kar.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_255_kar.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_255_mod_red.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_36.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_4_255_mod_red.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_68_kar.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_A_255_mod_red.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/mul_gen_0.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/sub_255_mod_red.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/X25519_FSM_AXI_ST.vhd")
    .addRtl(s"../x25519/src_ecdh_FSM/X25519_FSM.vhd")

    // !! set to true to generate a wavefrom dump for GTKWave -f 
    val waveform = false
    if (waveform) simConfig.withFstWave//.withWaveDepth(10) // does not work with Verilator, use SimTimeout()
//
    simConfig.compile{

      val socConfig = FinkaConfig.default.copy(
        corundumDataWidth = 512,
        //onChipRamHexFile = "software/c/finka/hello_world/build/hello_world.hex"
        onChipRamHexFile = "software/c/finka/pico-hello/build/pico-hello.hex"
        //onChipRamHexFile = "../lwip-wireguard/build-riscv/echop.hex"
      )

      val dut = new Finka(socConfig)

      // expose internal signals
      //dut.resetCtrl.systemReset.simPublic()
      dut.resetCtrl.axiReset.simPublic()
      dut.packetRx.packetReader.io.ctrlbus.r.valid.simPublic()
      // to wait on writes/reads to/from rxkey
      dut.packetRx.rx.io.ctrl_rxkey.w.valid.simPublic()
      dut.packetRx.rx.io.ctrl_rxkey.r.valid.simPublic()

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

      // de-assert interrupt input to CPU
      dut.io.coreInterrupt #= false

      // transmit path (downstream) to Ethernet port does not accept data yet
      dut.io.m_axis_tx.ready #= false

      // receive path (upstream) from Ethernet port does not have valid data yet
      dut.io.s_axis_rx.valid #= false
      dut.io.s_axis_rx.payload.tuser.assignBigInt(0)

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

      var commits_seen = 0
      // run 0.1 second after done
      var cycles_post = 100000

      axiClockDomain.waitRisingEdge(40)

      dut.io.timerExternal.clear #= false


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

      //dut.io.m_axis_tx.ready #= true

      //val monitorResetsThread = fork {
      //  if (dut.resetCtrl.axiReset.toBoolean == true) {
      //    printf("\nAXI RESET\n");
      //    dut.axiClockDomain.waitRisingEdge()
      //  }
      //}

      // packet generator
      val sendThread = fork {

        val payload =
        // <-------- Ethernet header --------------> <-IPv4 header IHL=5 protocol=0x11->                         <--5555,5555,len0x172-> <----Wireguard Type 4 ------------------------> < encrypted payload
          "01 02 03 04 05 06 01 02 03 04 05 06 08 00 45 11 22 33 44 55 66 77 88 11 00 00 00 00 00 00 00 00 00 00 15 b3 15 b3 01 72 00 00 04 00 00 00 00 00 00 01 40 41 42 43 44 45 46 47 a4 79 cb 54 62 89 " +
          "46 d6 f4 04 2a 8e 38 4e f4 bd 2f bc 73 30 b8 be 55 eb 2d 8d c1 8a aa 51 d6 6a 8e c1 f8 d3 61 9a 25 8d b0 ac 56 95 60 15 b7 b4 93 7e 9b 8e 6a a9 57 b3 dc 02 14 d8 03 d7 76 60 aa bc 91 30 92 97 " +
          "1d a8 f2 07 17 1c e7 84 36 08 16 2e 2e 75 9d 8e fc 25 d8 d0 93 69 90 af 63 c8 20 ba 87 e8 a9 55 b5 c8 27 4e f7 d1 0f 6f af d0 46 47 1b 14 57 76 ac a2 f7 cf 6a 61 d2 16 64 25 2f b1 f5 ba d2 ee " +
          "98 e9 64 8b b1 7f 43 2d cc e4 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 "

        /* create a List of Arrays */
        val hexstring = payload.filterNot(_.isWhitespace)
        val packet_length = hexstring.size / 2/*nibbles per byte*/ - 4;
        val words = hexstring.grouped(2/*nibbles per byte*/).grouped(dut.config.corundumDataWidth/8)
        // create a list of BigInts, for each word
        var strs  = new ListBuffer[BigInt]()
        words.zipWithIndex.foreach {
          case (word, count) => {
            printf("%s\n", word/*.reverse.*/.mkString(""))
            //printf("%s\n", word.reverse.mkString(""))
            strs += BigInt(word.reverse.mkString(""), 16)
          }
        }
        val plaintext = strs.toList
        printf("packet_length = %d, number of words = %d\n", packet_length, plaintext.size)

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

        // wait for rxkeys to have been written first
        //dut.axiClockDomain.waitSamplingWhere(dut.packetRx.rx.io.ctrl_rxkey.w.valid.toBoolean)
        //printf("RX Keys have been written.\n")
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //dut.axiClockDomain.waitRisingEdgeWhere(dut.packetRx.rx.io.ctrl_rxkey.r.valid.toBoolean)
        //printf("RX Keys have been read.\n")

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

      // packet receiver
      val receiveThread = fork {
        dut.axiClockDomain.waitSamplingWhere(dut.io.m_axis_tx.ready.toBoolean & dut.io.m_axis_tx.valid.toBoolean)
        printf("Saw packet from DUT\n");
      }

        //if (dut.packet.packetWriter.bridge.commit2.toBoolean) {
        //  println("COMMIT2PACKET")
        //}

      while (true) {
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
