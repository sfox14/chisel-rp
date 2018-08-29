/** This file provides blocks for convienient vector manipulations
  */

package utils

import scala.collection.mutable.ArrayBuffer
import Chisel._

/** This block provides a conversion from a vector of one width to another
  * The interface has a bits/valid designed to pull input from a fifo
  * The output is bits/valid
  */
class Serializer[T <: Data]( genType : T, widthIn : Int, widthOut : Int) extends Module {

  if ( widthIn < 1 || widthOut < 1 ) {
    ChiselError.error("Widths of input and output vectors must be greater than 0")
  }

  private def gcd(a: Int, b: Int): Int = if (b == 0) a.abs else gcd(b, a % b)

  val io = new Bundle {
    val dataIn = Decoupled(Vec.fill(widthIn) { genType.cloneType }).flip
    val flush = Bool(INPUT)
    val dataOut = Valid(Vec.fill(widthOut) { genType.cloneType })
    val flushed = Bool(OUTPUT)
  }
  val genWidth = genType.getWidth

  // compute GCD then attach to vec of combined
  val widthGcd = { if ( widthIn > widthOut ) gcd( widthIn, widthOut ) else gcd( widthOut, widthIn ) }
  val gcdType = UInt( width = widthGcd*genWidth )
  val gcdWidthIn = widthIn/widthGcd
  val gcdWidthOut = widthOut/widthGcd
  val vecInComb = Vec.fill( gcdWidthIn ) { gcdType.cloneType }
  val vecOutComb = Vec.fill( gcdWidthOut ) { gcdType.cloneType }
  vecInComb.toArray.zipWithIndex.foreach( x => {
    val ary = new ArrayBuffer[T]()
    for ( idx <- 0 until widthGcd )
      ary += io.dataIn.bits((x._2 + 1)*widthGcd - idx - 1)
    x._1 := ary.reduceLeft( _ ## _ )
  })
  vecOutComb.toArray.zipWithIndex.foreach( x => {
    for ( idx <- 0 until widthGcd )
      io.dataOut.bits(x._2*widthGcd + idx) := x._1((idx + 1)*genWidth - 1, idx*genWidth)
  })

  val inBW = log2Up(gcdWidthIn)
  val outBW = log2Up(gcdWidthOut)

  // first trivial case is gcdWidthIn == gcdWidthOut
  if ( gcdWidthIn == gcdWidthOut ) {
    io.dataOut.valid := io.dataIn.valid
    io.dataOut.bits := io.dataIn.bits
    io.dataIn.ready := Bool(true)
    io.flushed := io.flush
  }

  // second case is if gcdWidthIn < gcdWidthOut
  if ( gcdWidthIn < gcdWidthOut ) {
    // In the general case, we need to be able to wire each value to anywhere in the output
    // There will also be left over Input to be wrapped around
    val outPos = RegInit(UInt(0, outBW)) // the position we are upto in the output reg
    val outPosNext = outPos + UInt(gcdWidthIn, outBW + 1) // the next position after this clock cycle
    val excess = outPos - UInt( gcdWidthOut - gcdWidthIn, outBW ) // When the last of the output is filled in, how many of the input are left
    val remaining = UInt( gcdWidthOut, outBW + 1) - outPos // the number of spots remaining
    val filled = ( UInt( gcdWidthOut - gcdWidthIn, outBW ) <= outPos ) && io.dataIn.valid // Indicates if the output was just filled
    val fillFlush = ( UInt( gcdWidthOut - gcdWidthIn, outBW ) < outPos ) && io.dataIn.valid
    val flushReg = RegNext( fillFlush && io.flush )
    io.flushed := ( !fillFlush && io.flush ) || flushReg
    when ( io.flushed ) {
      flushReg := Bool(false)
    }

    // at least 1 value can come directly from the input
    val tmpSig = Vec.fill( gcdWidthOut - 1 ) { gcdType.cloneType }
    val tmpReg = Reg( Vec.fill( gcdWidthOut - 1 ) { gcdType.cloneType } )
    tmpReg := tmpSig
    tmpSig := tmpReg
    // a vec representing the mix from the input and register
    val tmpOut = Vec.fill( gcdWidthIn ) { gcdType.cloneType }

    // assign tmpOut from tmpReg and input
    for ( i <- 0 until gcdWidthIn ) {
      when ( excess > UInt(i, inBW) ) {
        tmpOut(i) := tmpSig( UInt( gcdWidthOut - gcdWidthIn, outBW ) + UInt( i, outBW ) )
        when ( filled ) {
          tmpOut(i) := tmpReg( UInt( gcdWidthOut - gcdWidthIn, outBW ) + UInt( i, outBW ) )
        }
      } .otherwise {
        tmpOut(i) := vecInComb( UInt(i, inBW) - excess )
      }
    }

    // assign the output
    for ( i <- 0 until ( gcdWidthOut - gcdWidthIn ) ) {
      vecOutComb(i) := tmpSig(i)
      when ( filled ) {
        vecOutComb(i) := tmpReg(i)
      }
    }
    for ( i <- 0 until gcdWidthIn ) {
      vecOutComb( i + gcdWidthOut - gcdWidthIn ) := tmpOut(i)
    }
    io.dataOut.valid := filled || ( io.flushed &&  (io.dataIn.valid ||  outPos =/= UInt(0, outBW) ) )
    io.dataIn.ready := !( fillFlush && io.flush ) || io.flushed

    // update the output position
    when ( io.dataIn.valid ) {
      outPos := outPosNext
      when ( filled ) {
        outPos := excess
      }
    }
    when ( io.flushed ) {
      outPos := UInt(0, outBW)
    }

    val hiOut = UInt( width = outBW )
    val loOut = UInt( width = outBW )
    val hiIn = UInt( width = inBW )
    val loIn = UInt( width = inBW )

    when ( io.dataIn.valid && !( filled && ( excess === UInt( 0, outBW ) ) )) {
      DynamicVecAssign( tmpSig, hiOut, loOut, vecInComb, hiIn, loIn )
    }

    hiOut := outPosNext - UInt(1, outBW)
    loOut := outPos
    hiIn := UInt( gcdWidthIn - 1, inBW )
    loIn := UInt( 0, inBW )

    when ( filled ) {
      // excess > 0 for vec assign to be used
      hiOut := excess - UInt(1, outBW)
      loOut := UInt(0, outBW)
      loIn := remaining
    }
  }

  // final case if gcdWidthIn > gcdWidthOut
  if ( gcdWidthIn > gcdWidthOut ) {

    // Store the last gcdWidthOut - 1 values of width In
    val tmpRegWidth = { if ( gcdWidthOut == 1 ) 1 else gcdWidthOut - 1 }
    val tmpReg = Reg( Vec.fill( tmpRegWidth ) { gcdType.cloneType } )
    val inPos = RegInit( UInt( 0, inBW ) )
    val remaining = UInt( gcdWidthOut, inBW + 1 ) - inPos
    val inPosNext = inPos + UInt( gcdWidthOut, inBW + 1 )
    val leftOver = RegInit( Bool(false) )
    val used = { ( !leftOver && ( UInt( gcdWidthIn - gcdWidthOut, inBW + 1) < inPosNext ) && io.dataIn.valid ) ||
      leftOver && ( UInt( gcdWidthIn - gcdWidthOut, inBW + 1 ) < inPos ) && io.dataIn.valid }
    val flushReg = RegInit(Bool(false))
    val flush = io.flush || flushReg
    flushReg := ( io.flush && io.dataIn.valid && ( inPosNext =/= UInt(gcdWidthIn, inBW + 1) ) ) || flushReg
    io.flushed := { ( io.dataIn.valid && flush && used && (inPosNext === UInt(gcdWidthIn, inBW + 1)) ) ||
                    ( io.dataIn.valid && flushReg && leftOver ) ||
                    ( !io.dataIn.valid && io.flush ) }

    when ( io.dataIn.valid ) {
      for ( i <- 0 until (gcdWidthOut - 1) ) {
        tmpReg(i) := vecInComb( gcdWidthIn - gcdWidthOut + 1 + i )
      }
      inPos := inPosNext
    }
    val tmpOut = Vec.fill( gcdWidthOut ) { gcdType.cloneType }
    for ( i <- 0 until gcdWidthOut ) {
      val tmpRegIdx = (inPos - UInt(1, inBW)) + UInt(i, outBW)
      when ( UInt(i, outBW ) < remaining ) {
        tmpOut(i) := tmpReg( tmpRegIdx( outBW - 1, 0 ) )
      } .otherwise {
        tmpOut(i) := vecInComb( UInt( i, inBW ) - remaining )
      }
    }
    when ( leftOver && io.dataIn.valid) {
      leftOver := Bool(false)
      inPos := inPos
    }
    when ( used ) {
      when( inPosNext =/= UInt( gcdWidthIn, inBW + 1 ) ) {
        leftOver := Bool(true)
        inPos := inPosNext - UInt( gcdWidthIn - gcdWidthOut, inBW + 1 )
        when ( leftOver ) {
          inPos := inPosNext - UInt( gcdWidthIn, inBW + 1 )
        }
      } .otherwise {
        inPos := UInt( 0, inBW )
      }
    }

    when ( io.flushed ) {
      inPos := UInt( 0, inBW )
      leftOver := Bool(false)
      flushReg := Bool(false)
    }

    io.dataIn.ready := ( used && !flush ) || io.flushed
    io.dataOut.valid := io.dataIn.valid || ( io.flushed && leftOver )
    // dynVec with default value
    val dynVecOut = Vec.fill( gcdWidthOut ) { gcdType.cloneType }
    for ( i <- 0 until gcdWidthOut ) {
      dynVecOut( i ) := tmpOut( i )
    }
    DynamicVecAssign( dynVecOut, UInt( gcdWidthOut - 1, outBW ), UInt( 0, outBW ), vecInComb, inPosNext - UInt( 1, inBW ), inPos )
    vecOutComb := dynVecOut
    when ( leftOver ) {
      vecOutComb := tmpOut
    }
  }
}
