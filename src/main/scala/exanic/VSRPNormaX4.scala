package exanic

import Chisel._
import utils.Serializer

// This interface has optimisations for reducing single-prediction (batch-1) latency

class VSRPNormaX4[ T <: Bits ]( genType : T, fifoDepth : Int, memSize : Int,
  getUserMod : () => TimeSeriesInterface[T], bytesOut : Int ) extends ExanicX4Interface {

  // create a counter
  val timer = RegInit( UInt(0, width = 32) )
  val start = RegInit( Bool(false) )
  val on = RegInit( Bool(false) )
  val finished = RegInit( Bool(false) )

  // create the user module
  val userMod = Module( getUserMod() )
  val noReg = userMod.getNoReg()

  // connect memory interface, read only
  val userMem = Mem( memSize, Vec.fill(16) { UInt( width = 8 ) } )
  val memWData = Vec.fill( 16 ) { UInt( width = 8 ) }
  (0 until 16).foreach( idx => { memWData(idx) := io.memWData(8*(idx + 1) - 1, 8*idx) } )
  userMem.write( io.memWAddr, memWData, io.memWEn )

  // The register interface
  val regIntR = RegInit( Vec.fill( noReg + 1 ) { UInt( width = 32 ) } )
  val regIntW = RegInit( Vec.fill( noReg + 1 ) { UInt( width = 32 ) } ) /////////////////////////////////////
  val controlReg = regIntR( noReg ) // bit0 => reset error, bit1 => userMod reset, bit2 => drain
  val errRst = controlReg(0)
  userMod.reset := controlReg(1) || reset
  val fifoDrain = controlReg(2)
  val statusVal = regIntW(noReg)
  statusVal := UInt( 0, 32 )
  when ( io.regWEn ) {
    regIntR( io.regWAddr ) := io.regWData
  }
  io.regRData := regIntW( io.regRAddr )

  // error checking
  val rx1Err = RegInit( Bool(false) )
  val pktDrop = RegInit( Bool(false) )
  val crcFail = RegInit( Bool(false) )
  val fifoFull = RegInit( Bool(false) )
  val txFifoFull = RegInit( Bool(false) )
  val dirOutFull = RegInit( Bool(false) )
  val userErr = RegInit( Bool(false) )
  val error = UInt( width = 7 )
  error := rx1Err ## pktDrop ## crcFail ## fifoFull ## txFifoFull ## dirOutFull ## userErr

  // Strip the CRC off data and pass into combiner
  val stripper = Module(new StripCrc )
  stripper.io.in <> io.rx1Usr.rxComm.hndshk   //rx1Usr <> rx1Host (rx1Host receives data at port 1, on loopback from port 0)

  // combine data into continuous stream
  val combiner = Module(new DataCombiner )
  for ( idx <- 0 until 8 ) {
    combiner.io.dataIn(idx) := stripper.io.out.data( 8*(idx + 1) - 1, 8*idx )
  }
  combiner.io.vld := stripper.io.out.vld
  combiner.io.len := stripper.io.out.len

  // data fifo broken into bytes
  val fifo = Module(new Queue( Vec.fill( 8 ) { UInt( width = 8 ) } , fifoDepth ) )
  fifo.io.enq.bits := combiner.io.dataOut
  fifo.io.enq.valid := combiner.io.vldOut

  val inTimer = RegInit( UInt(0, width=32) )
  val firstOne = RegInit( Bool(false) )

  when( stripper.io.in.vld ){
    start := Bool(true)
    when( !firstOne ){
      inTimer := io.hwTime
      firstOne := Bool(true)
    }
  }

  when( start && !finished ){
    timer := timer + UInt( 1, width = 32 )
  }

  // pass to time series interface
  userMod.io.dataIn <> fifo.io.deq
  val memData = userMem.read( userMod.io.memAddr )
  userMod.io.memData := UInt(0, 128)
  for ( idx <- 0 until 16 )
    userMod.io.memData(8*(idx + 1) - 1, 8*idx) := memData(idx)

  for ( idx <- 0 until noReg ) {
    userMod.io.regIn(idx) := regIntR(idx)
    when ( userMod.io.regOutEn ) {
      regIntW(idx) := userMod.io.regOut(idx) /////////////////////////////
    }
  }






  // combine time series output
  val noOutputs = math.ceil( bytesOut*8.0/userMod.bwOfGenType ).toInt - 1
  //val vecDataOut = Vec.fill( userMod.bwOfGenType ) { UInt( width = 1 ) }
  //for( x <- 0 until userMod.bwOfGenType )
  //  vecDataOut(x) := userMod.io.dataOut.bits(x)


   // padding = 64 bits total
  val dOut = Vec.fill( 64 ){ UInt( width = 1) }
  (0 until 64).foreach( idx => dOut(idx) := UInt(0, 1) )
  (0 until 24).foreach( idx => dOut(idx) := userMod.io.dataOut.bits(idx) )

  
  /*/ pad zeros when one cycle after userMod valid too
  val delayValid = Reg( init = Bool(false), next = userMod.io.dataOut.valid )
  when( delayValid ){
    (0 until 24).foreach( idx => dOut(idx) := UInt(0, 1) )
  }*/
  

  val outToBuffer = Module( new Serializer( UInt( width = 1 ), 64, 64 ) )
  outToBuffer.io.dataIn.bits :=  dOut //vecDataOut
  outToBuffer.io.dataIn.valid := userMod.io.dataOut.valid //|| delayValid
  userMod.io.dataOut.ready := outToBuffer.io.dataIn.ready





  outToBuffer.io.flush := fifoDrain
  val buffer = RegNext( outToBuffer.io.dataOut.bits )
  val bufferVld = RegNext( outToBuffer.io.dataOut.valid )





  // Join bits together into bytes
  val bufferByte = Vec.fill( 8 ) { UInt( width = 8 ) }
  ( 0 until 8).foreach( idx => bufferByte(idx) := UInt(0, 8) )
  ( 0 until 64).foreach( idx => { bufferByte( idx/8 )( idx % 8 ) := buffer( idx ) })


  when( userMod.io.dataOut.valid ){
    ( 0 until 8).foreach( idx => bufferByte(idx) := UInt(0, 8) )
  }

  /*
  val directOutputFifo = Module( new Queue( Vec.fill( 8 ) { UInt( width = 8 ) }, fifoDepth ) )
  directOutputFifo.io.enq.bits := bufferByte
  directOutputFifo.io.enq.valid := bufferVld
  */

  /*
  // Pad data to fit with required number of bytes
  val dataSep = Module( new DataSeparator( bytesOut ) )
  dataSep.io.enq <> directOutputFifo.io.deq
  val dRst = dataSep.reset
  dRst := fifoDrain || reset
  */

  // output fifo
  val fifoTxOut = Module(new Queue( Vec.fill( 8 ) { UInt( width = 8 ) } , fifoDepth ) )
  //fifoTxOut.io.enq <> directOutputFifo.io.deq // dataSep.io.deq // 
  fifoTxOut.io.enq.bits := bufferByte
  fifoTxOut.io.enq.valid := bufferVld || userMod.io.dataOut.valid



  // reset errors after put in fifo
  rx1Err := rx1Err || io.rx1Usr.rxComm.err
  pktDrop := pktDrop || io.rx1Usr.rxComm.pktDrop
  crcFail := crcFail || io.rx1Usr.rxComm.crcFail
  fifoFull := fifoFull || !fifo.io.enq.ready
  dirOutFull := dirOutFull //|| !directOutputFifo.io.enq.ready
  userErr := userErr || userMod.io.error
  txFifoFull := txFifoFull || !fifoTxOut.io.enq.ready
  when ( errRst ) {
    rx1Err := Bool(false)
    pktDrop := Bool(false)
    crcFail := Bool(false)
    fifoFull := Bool(false)
    dirOutFull := Bool(false)
    txFifoFull := Bool(false)
    userErr := Bool(false)
  }

  val noSegment = 1 //math.floor( (bytesOut - 1)/8.0 ).toInt //11
  val segmentCounter = RegInit( UInt( 0, log2Up(noSegment + 1) ) ) //4bits
  val tx1Output = UInt( width = 64 )
  val sof = ( segmentCounter === UInt( 0, log2Up(noSegment + 1) ) ) //4bits
  val eof = ( segmentCounter >= UInt( 1, log2Up(noSegment + 1) ) )
  
  val len = UInt( width = 3 )
  len := UInt( 0, 3 )
  tx1Output := UInt(0, 64)
  for ( x <- 0 until 8 ) {
    tx1Output(8*(x + 1) - 1, 8*x) := fifoTxOut.io.deq.bits(x)
  }  
  when ( eof ) {
    len := UInt( 0, 3 ) //bytesOut % 8
  }

  when ( fifoTxOut.io.deq.valid && fifoTxOut.io.deq.ready ) { //( fifoTxOut.io.deq.valid && fifoTxOut.io.deq.ready )
    segmentCounter := segmentCounter + UInt( 1, log2Up(noSegment + 1) )
    when( eof ) {
      segmentCounter := UInt( 0, log2Up(noSegment + 1) )
    }
  }
  when ( fifoDrain ) {
    segmentCounter := UInt( 0, log2Up(noSegment + 1) )
  }

  statusVal := fifoTxOut.io.count ## segmentCounter ## error  //directOutputFifo.io.count ##
  
  
  val sending = RegInit( Bool(false) )
  sending := sending || ( fifoTxOut.io.count > UInt( noSegment, log2Up(fifoDepth) ) ) 
  when ( ( eof && io.tx1Usr.hndshk.vld && io.tx1Usr.ack ) || fifoDrain ) {
    sending := Bool(false)
  }

  // timing stuff
  when( fifoTxOut.io.deq.valid ){
    finished := Bool(true)
  }

  val outTimer = RegInit( UInt( 0, width = 32 ) )
  when( fifoTxOut.io.deq.valid ){
    outTimer := io.hwTime
  }

  val hndshkTimer = RegInit( UInt( 0, width = 32 ) )
  when( eof && io.tx1Usr.hndshk.vld && io.tx1Usr.ack ){
    hndshkTimer := io.hwTime
  }

  regIntW(7) := RegNext( io.rx0Usr.rxComm.timeStamp )
  regIntW(8) := RegNext( io.rx1Usr.rxComm.timeStamp )
  regIntW(9) := RegNext( inTimer )
  regIntW(10) := RegNext( outTimer )
  regIntW(11) := RegNext( hndshkTimer )
  regIntW(12) := RegNext( timer )

  io.tx1Usr.hndshk.data := tx1Output
  io.tx1Usr.hndshk.vld := fifoTxOut.io.deq.valid && sending
  io.tx1Usr.hndshk.sof := sof && io.tx1Usr.hndshk.vld
  io.tx1Usr.hndshk.eof := eof && io.tx1Usr.hndshk.vld
  io.tx1Usr.hndshk.len := len
  fifoTxOut.io.deq.ready := ( io.tx1Usr.ack && sending ) || fifoDrain  //&& sending
}



// 7 - rx0
// 8 - rx1
// 9 - inTimer @ the first stripper.io.in.vld
// 10 - outTimer @ fifoTxOut.io.deq.valid
// 11 - hndshkTimer @ eof && io.tx1Usr.hndshk.vld && io.tx1Usr.ack
// 12 - cycle accurate timer. stripper.io.in.vld => fifoTxOut.io.deq.valid
