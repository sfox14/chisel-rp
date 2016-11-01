
package exanic

import Chisel._
import chiselutils.interfaces.exanicx4.TimeSeriesInterface
import chiselutils.utils.Serializer
import chiselutils.xilinx.AsyncFifoXilinx
import scala.collection.mutable.ArrayBuffer
import OLK.NORMA



class rpnMod (val n_features : Int, val n_components : Int, val n_dicts : Int, val bitWidth : Int, val fracWidth : Int,
                val seeds : ArrayBuffer[Int], val mem : ArrayBuffer[ArrayBuffer[Boolean]] ) 
                    extends TimeSeriesInterface( Fixed( width = bitWidth, fracWidth = fracWidth ), 15 ) {
  
  
  val usrClk = Clock( src = clock, period = 2 )
  usrClk.setName("usrClk")
  
  val features = n_components //rand-projection goes from 8 to 6
  val dictSize = n_dicts
  val normaStages = ArrayBuffer( false, true, false, true, false, true, false, true, false, true, false, true, false, true, false, true, true, true ) // stages for 12

  val randomProjection = Module ( new RandomProjection ( n_features, n_components, bitWidth, fracWidth, seeds, mem ) )  
  val norma = Module( new NORMA( bitWidth, fracWidth, normaStages, 0, n_dicts, features, 2, clk = usrClk ) )
  
  
  // Use the serializer to deal with conversions
  val vecBitsIn = Vec.fill( 64 ) { UInt( width = 1 ) }
  for ( idx <- 0 until 64 )
    vecBitsIn(idx) := io.dataIn.bits( math.floor( idx/8.0 ).toInt )( idx % 8 )
  
  val vecBitsOut = Fixed(width = bitWidth, fracWidth = fracWidth )
  vecBitsOut := Fixed(0, width = bitWidth, fracWidth = fracWidth )
  
  val serializer = Module( new Serializer( UInt( width = 1 ), 64, 1*bitWidth ) )
  serializer.io.dataIn.bits := vecBitsIn
  serializer.io.dataIn.valid := io.dataIn.valid
  io.dataIn.ready := serializer.io.dataIn.ready
  serializer.io.flush := Bool(false)
  val myFixedType = Fixed( width = bitWidth, fracWidth = fracWidth )
  vecBitsOut := myFixedType.fromBits( ( 0 until bitWidth ).reverse.map( x => {
       serializer.io.dataOut.bits( x ) }).reduce(_##_) )

  randomProjection.io.dataIn.bits := RegNext( vecBitsOut )  
  randomProjection.io.dataIn.valid := RegNext( serializer.io.dataOut.valid ) 

  val pCycles = norma.pCycles
  val rCycles = n_features + 1


  // Convert to usrClk domain
  val asyncFifo = Module( new AsyncFifoXilinx( Vec.fill( features ) { Fixed( width = bitWidth, fracWidth = fracWidth ) }, 32, clock, usrClk ) )
                                               
  asyncFifo.io.enq.bits := RegNext( randomProjection.io.dataOut.bits ) 
  asyncFifo.io.enq.valid := RegNext( randomProjection.io.dataOut.valid )
  asyncFifo.io.deq.ready := Bool(true)
  
  val regInFws = (0 until 4).map( idx => myFixedType.fromBits(
    if ( bitWidth > 32 )
      UInt( 0, bitWidth - 32 ) ## io.regIn(idx)( 31, 0 )
    else
      io.regIn(idx)( bitWidth - 1, 0 )
  ))

  norma.io.example := Reg( next = asyncFifo.io.deq.bits, clock = usrClk )
  norma.io.forceNA := Reg( next = (!asyncFifo.io.deq.valid || io.regIn(4)(0) ), clock = usrClk ) 
  
  // usrClk should 1/2 the speed and sync to clock so can just connect
  norma.io.gamma  := Reg( next = regInFws(0), clock = usrClk )
  norma.io.forget := Reg( next = regInFws(1), clock = usrClk )
  norma.io.eta    := Reg( next = regInFws(2), clock = usrClk )
  norma.io.nu     := Reg( next = regInFws(3), clock = usrClk )

  //shiftregister for the valid in usrClk domain - pCycles
  val normaValidOutSR = Vec.fill( pCycles + 1) { Reg( init = Bool(false), clock = usrClk ) } 
  normaValidOutSR(0) := asyncFifo.io.deq.valid 
  val normaValidOut = normaValidOutSR( pCycles ) //the last valid in shift register
  // need a shiftRegister on usrClk
  for ( idx <- 0 until pCycles )
    normaValidOutSR(idx + 1) := normaValidOutSR(idx)

  // convert back to the primary clock domain
  val outAsync = Module( new AsyncFifoXilinx( Fixed( width = bitWidth, fracWidth = fracWidth ), 32, usrClk, clock ) )
  outAsync.io.enq.bits := norma.io.ft 
  outAsync.io.enq.valid := normaValidOut 

  for ( idx <- 0 until 15 ) {
    io.regOut(idx) := io.regIn(idx)
  }
  io.memAddr := UInt( 0 )
  io.regOutEn := Bool(true)

  io.dataOut.valid := outAsync.io.deq.valid
  io.dataOut.bits :=  outAsync.io.deq.bits
  outAsync.io.deq.ready := io.dataOut.ready

  val readyReg = Reg( init = UInt(0, 32 ) )
  val asyncFifoCount = Reg( init = UInt(0, 32 ) )
  val outAsyncCount = Reg( init = UInt(0, 32 ) )
  when( io.error ) {
    readyReg := io.dataOut.ready ## asyncFifo.io.enq.ready ## outAsync.io.enq.ready
    asyncFifoCount := asyncFifo.io.count
    outAsyncCount := outAsync.io.count
  }

  io.regOut(0) := readyReg
  io.regOut(1) := asyncFifoCount
  io.regOut(2) := outAsyncCount
  io.regOut(3) := RegNext( asyncFifo.io.werr.toArray.reduce(_##_) )
  io.regOut(4) := RegNext( asyncFifo.io.rerr.toArray.reduce(_##_) )
  io.regOut(5) := RegNext( outAsync.io.werr.toArray.reduce(_##_) )
  io.regOut(6) := RegNext( outAsync.io.rerr.toArray.reduce(_##_) )

  io.error := RegNext(!io.dataOut.ready || !asyncFifo.io.enq.ready || !outAsync.io.enq.ready)

}


