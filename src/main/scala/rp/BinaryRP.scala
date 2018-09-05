package rp

import Chisel._
import scala.util.Random
import scala.collection.immutable.Vector

import scala.collection.mutable.ArrayBuffer
import com.github.tototoshi.csv._

import utils._
import math._


// BinaryRP Dot Product
class BinaryDP(bitWidth : Int, fracWidth : Int, k : Int, 
  seeds : Seq[Int]) extends Module {
  val io = new Bundle {
    val din = Valid( Vec.fill( k ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip
    val dout = Valid( Fixed(OUTPUT, bitWidth, fracWidth) )
    val rst = Bool(INPUT)
  }


  def accumulate( ops : Seq[Fixed] ) : (Fixed, Int) = {
    var tmp = ops
    var stages = 0
    while( tmp.size>1 ){
      tmp = tmp.grouped(2).map(x => RegNext( x.reduce(_ + _))).toSeq
      stages +=1
    }
    (tmp(0), stages)
  }

  val lfsrs = (0 until k).map( x => Module(new LFSR( seeds(x) )) )
  for (idx <-0 until k){
    lfsrs(idx).io.valid := io.din.valid
    lfsrs(idx).io.off := io.rst
  }

  // stage 1 // lfsrs(x).io.out
  val operands = (0 until k).map(x => Mux( Bool(true), 
    RegNext( io.din.bits(x) ), RegNext( -io.din.bits(x) ) ) ).toVector
  
  // accumulate
  val (acc_out, a_stages) = accumulate( operands )

  // total stages
  val stages = a_stages + 1

  io.dout.bits := acc_out
  io.dout.valid := ShiftRegister(io.din.valid, stages)

}


// BinaryRP Processing Element
class BinaryPE(val bitWidth : Int, val fracWidth : Int, val k : Int, val n_features : Int, 
  val n_outputs : Int, val seeds : Seq[Int], forSim : Boolean=true) extends Module {
  val io = new Bundle {
    val din = Decoupled( Vec.fill( k ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip
    val dout = Decoupled( Fixed(OUTPUT, bitWidth, fracWidth) )
  }

  val ZERO = Fixed(0, bitWidth, fracWidth)
  val dataType = Fixed(width=bitWidth, fracWidth=fracWidth)


  /* Binary dot product module */
  val dotprod = Module( new BinaryDP(bitWidth, fracWidth, k, seeds) )

  /* local mem for partial outputs */
  val id = 321
  val mem = Module( new PipelinedDualPortLutRAM(dataType, log2Up(n_outputs), 1, 1, id,
        (0 until n_outputs).map(x => BigInt(0)), forSim ))


  /* control */
  val counterA = RegInit( UInt(0, width=7) ) // limit=n_outputs, incr=1
  val counterB = RegInit( UInt(0, width=7) ) // limit=n_features/k, incr=1
  val rowDone = Bool() // trigger
  val colDone = Bool()
  val ctrlvalid = Bool()
  val addA = UInt(1) & ctrlvalid 
  val addB = UInt(1) & rowDone
  val stall = Bool()

  /* default */
  rowDone := Bool(false)
  colDone := Bool(false)
  counterA := counterA + addA
  counterB := counterB + addB
  stall := !io.dout.ready
  ctrlvalid := io.din.valid & !stall

  /* transition when outputs accumulated for k features */
  when( counterA === UInt(n_outputs-1) && ctrlvalid){
    rowDone := Bool(true)
    counterA := UInt(0)
  }
  when( counterB === UInt(n_features/k -1) ){
    colDone := Bool(true)
    when( rowDone ){
      counterB := UInt(0)
    }
  }

  /* Add pipeline registers */
  var rdAddr = counterA
  var wrAddr = ShiftRegister(counterA, mem.stages + 1)
  var rst = colDone
  var din = io.din.bits
  var valid = ctrlvalid

  if( dotprod.stages > mem.stages ){
    println("dotprod.stages > mem.stages")
    rdAddr = ShiftRegister(counterA, (dotprod.stages-mem.stages) )
    wrAddr = ShiftRegister(counterA, (dotprod.stages + 1) )
  }else if( dotprod.stages < mem.stages ){
    println("dotprod.stages < mem.stages")
    var td = (mem.stages - dotprod.stages)
    rst = ShiftRegister(colDone, td)
    din = ShiftRegister(io.din.bits, td)
    valid = ShiftRegister(ctrlvalid, td)
  }else{
    println("dotprod.stages == mem.stages")
  }

  /* connect dp module */  
  dotprod.io.din.bits := din
  dotprod.io.din.valid := valid
  dotprod.io.rst := rst
  var opA = dotprod.io.dout.bits

  /* connect mem module */
  // port 0 - read
  mem.io.ports(0).req.addr := rdAddr
  val opB = mem.io.ports(0).rsp.readData
  // port 1 - write
  val wdata = dataType
  val wen = RegNext(dotprod.io.dout.valid)
  mem.io.ports(1).req.addr := wrAddr
  mem.io.ports(1).req.writeData := wdata 
  mem.io.ports(1).req.writeEn := wen

  /* Adder */
  val result = RegNext( opA + opB )

  /* Output control */
  val done = Bool()
  done := ShiftRegister(rst, dotprod.stages+1)
  val outValid = wen & done

  /* write back to mem */
  wdata := Mux(outValid, ZERO, result)

  /* output */
  io.dout.bits := result
  io.dout.valid := outValid
  io.din.ready := rowDone

  // assertions
  Predef.assert( n_outputs >= mem.stages+1, s"""Error: Not enough partial outputs 
    to fill the pipeline. Requires n_outputs >= ${mem.stages+1}""" )
  Predef.assert( (n_features%k)==0, s"""Error: Number of features must be divisible
    by the streaming width""" )


}

class BinaryRP(val bitWidth : Int, val fracWidth : Int, val k : Int, 
  val outWidth : Int, val p : Int, seeds : Seq[Seq[Int]]) extends Module {
  
  val io = new Bundle {
    val din = Decoupled( Vec.fill( k ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip
    val dout = Decoupled( Vec.fill( k ){ Fixed(OUTPUT, bitWidth, fracWidth) } )
  }

  // k: stream_width
  // p: number of PEs
  // n_features
  // n_outputs

  /*
  Assume a (n_features x n_outputs) random matrix. Computed row-wise, storing the 
  partial outputs in embedded memory. This reduces DRAM reads for the input since
  each feature, i.e. input dimension, is fully reused.  
  */

  /* Ingress network*/

  //val pes = (0 until p).map( x => Module(new ))

  /* Egress network */

  
}

// Generates VSRP verilog

object BinaryRPMain {

    val bitWidth = 18
    val fracWidth = 10
    val k = 8
    val outWidth = 1
    val p = 1
    val seeds = (0 until p).map(x => (0 until k).map(y => 0))

    def main(args: Array[String]): Unit = {
      println("Generating the Binary Random Projection verilog")

      chiselMain( Array("--backend", "v", "--targetDir", "verilog"),
        () => Module(new BinaryRP(bitWidth, fracWidth, k, outWidth, p, seeds)) )
  }

}