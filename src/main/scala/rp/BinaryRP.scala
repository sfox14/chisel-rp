package rp

import Chisel._
import scala.util.Random
import scala.collection.immutable.Vector

import scala.collection.mutable.ArrayBuffer
import com.github.tototoshi.csv._

import utils._
import math._


class SerialToParallel(val bitWidth : Int, val fracWidth : Int, outWidth : Int) extends Module{
  val io = new Bundle{
    val din = Valid( Fixed(INPUT, bitWidth, fracWidth) ).flip()
    val dout = Valid( Vec.fill( outWidth ){ Fixed(OUTPUT, bitWidth, fracWidth) } )
  }
  // outWidth between 1 and 8

  val parReg = (0 until outWidth).map(x => 
    RegEnable(Fixed(width=bitWidth, fracWidth=fracWidth), enable=io.din.valid)
    )

  parReg(0) := io.din.bits
  for(ix <- 1 until outWidth){
    parReg(ix) := parReg(ix-1)
  }

  val vecBitsOut = Vec.fill( outWidth ){Fixed(width=bitWidth, fracWidth=fracWidth)}
  for( ix <-0 until outWidth ){
    vecBitsOut(ix) := parReg(ix)
  }

  val counter = RegInit(UInt(0, width=4))
  val addA = UInt(1) & io.din.valid
  val validOut = RegInit(Bool(false))

  // default connections
  counter := counter + addA
  validOut := Bool(false)

  // trigger
  when( counter === UInt(outWidth-1) && io.din.valid ){
    validOut := Bool(true)
    counter := UInt(0)
  }

  io.dout.bits := vecBitsOut
  io.dout.valid := validOut

}


// BinaryRP Dot Product
class BinaryDP(bitWidth : Int, fracWidth : Int, inWidth : Int, 
  seeds : Seq[Int]) extends Module {
  val io = new Bundle {
    val din = Valid( Vec.fill( inWidth ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip
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

  val lfsrs = (0 until inWidth).map( x => Module(new LFSR( seeds(x) )) )
  for (idx <-0 until inWidth){
    lfsrs(idx).io.valid := io.din.valid
    lfsrs(idx).io.off := io.rst
  }

  // stage 1 // lfsrs(x).io.out
  val operands = (0 until inWidth).map(x => Mux( Bool(true), 
    RegNext( io.din.bits(x) ), RegNext( -io.din.bits(x) ) ) ).toVector
  
  // accumulate
  val (acc_out, a_stages) = accumulate( operands )

  // total stages
  val stages = a_stages + 1

  io.dout.bits := acc_out
  io.dout.valid := ShiftRegister(io.din.valid, stages)

}


// BinaryRP Processing Element
class BinaryPE(val bitWidth : Int, val fracWidth : Int, val n_features : Int, 
  val p_outputs : Int, val inWidth : Int, val outWidth : Int, val seeds : Seq[Int], 
  forSim : Boolean=true) extends Module {
  
  val io = new Bundle {
    val din = Decoupled( Vec.fill( inWidth ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip
    val dout = Decoupled( Vec.fill( outWidth ){ Fixed(OUTPUT, bitWidth, fracWidth) } )
    val dinOut = Valid( Vec.fill( inWidth ){ Fixed(OUTPUT, bitWidth, fracWidth) } )
  }

  val ZERO = Fixed(0, bitWidth, fracWidth)
  val dataType = Fixed(width=bitWidth, fracWidth=fracWidth)


  /* Binary dot product module */
  val dotprod = Module( new BinaryDP(bitWidth, fracWidth, inWidth, seeds) )

  /* local mem for partial outputs */
  val id = 321
  val mem = Module( new PipelinedDualPortLutRAM(dataType, log2Up(p_outputs), 1, 1, id,
        (0 until p_outputs).map(x => BigInt(0)), forSim ))


  /* control */
  val counterA = RegInit( UInt(0, width=7) ) // limit=p_outputs, incr=1
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
  when( counterA === UInt(p_outputs-1) && ctrlvalid){
    rowDone := Bool(true)
    counterA := UInt(0)
  }
  when( counterB === UInt(n_features/inWidth -1) ){
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
  io.din.ready := rowDone

  /* broadcast tree */
  io.dinOut.bits := RegNext(io.din.bits)
  io.dinOut.valid := RegNext(ctrlvalid) 

  /* serial to parallel converter */
  val stp = Module( new SerialToParallel(bitWidth, fracWidth, outWidth) )
  stp.io.din.bits := result
  stp.io.din.valid := outValid

  io.dout.bits := stp.io.dout.bits
  io.dout.valid := stp.io.dout.valid

  val stages = dotprod.stages + 1

  // assertions
  Predef.assert( p_outputs >= mem.stages+1, s"""Error: Not enough partial outputs 
    to fill the pipeline. Requires p_outputs >= ${mem.stages+1}""" )
  Predef.assert( (n_features%inWidth)==0, s"""Error: Number of features must be divisible
    by the input streaming width""" )
  Predef.assert( (p_outputs%outWidth)==0, s"""Error: Number of outputs must be divisible
    by the output streaming width""" )

}

class BinaryRP(val bitWidth : Int, val fracWidth : Int, val n_features : Int, 
	val n_outputs : Int, val p : Int, val inWidth : Int, val outWidth : Int,  
  val seeds : Seq[Seq[Int]], forSim : Boolean=true) extends Module {
  
  val io = new Bundle {
    val din = Decoupled( Vec.fill( inWidth ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip
    val dout = Decoupled( Vec.fill( outWidth ){ Fixed(OUTPUT, bitWidth, fracWidth) } )
  }

  // inWidth == k: stream_width
  // outWidth: egress width
  // p: number of PEs
  // n_features: input dimensionality 
  // n_outputs: output dimensionality

  /*
  Assume a (n_features x n_outputs) random matrix. Computed row-wise, storing the 
  partial outputs in embedded memory. This reduces DRAM reads for the input since
  each feature, i.e. input dimension, is fully reused.  
  */

  Predef.assert( (n_outputs%p)==0, s"""Error: Total number of outputs must be divisible
    by the number of PEs""" )
  val p_outputs = (n_outputs/p)
  val dataType = Fixed(width=bitWidth, fracWidth=fracWidth)

    /* Ingress network*/
  val inFifo = Module( new Queue(Vec.fill( inWidth ){dataType}, 10) )
  inFifo.io.enq.bits := io.din.bits
  inFifo.io.enq.valid := io.din.valid

  io.din.ready := (inFifo.io.count < UInt(10))

  val array = (0 until p).map(x => Module( new BinaryPE(bitWidth, fracWidth, n_features, 
  	p_outputs, inWidth, outWidth, seeds(x), forSim) ))

  inFifo.io.deq.ready := array(0).io.din.ready // will stall
  array(0).io.din.bits := inFifo.io.deq.bits
  array(0).io.din.valid := inFifo.io.deq.valid
  for( ix <- 1 until p){
    array(ix).io.din.bits := array(ix-1).io.dinOut.bits
    array(ix).io.din.valid := array(ix-1).io.dinOut.valid
    array(ix).io.dout.ready := Bool(true)
  }

  array(0).io.dout.ready := Bool(true) //never stall

  /* Egress network */
  val egress = Module(new EgressNetwork(bitWidth, fracWidth, n_outputs, p, outWidth) )

  for (ix <- 0 until p){
  	egress.io.din(ix).bits := array(ix).io.dout.bits
  	egress.io.din(ix).valid := array(ix).io.dout.valid
  }

  io.dout.bits := egress.io.dout.bits
  io.dout.valid := egress.io.dout.valid
  
}


class OutBuffer(val bitWidth : Int, val fracWidth : Int, p_outputs : Int, 
	outWidth : Int) extends Module{

  val io = new Bundle{
    val din = Valid( Vec.fill( outWidth ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip()
    val nextData = Valid( Vec.fill( outWidth ){ Fixed(INPUT, bitWidth, fracWidth) } ).flip()
    val enNext = Bool(INPUT)
    val readyIn = Bool(INPUT)
    val readyOut = Bool(OUTPUT)
    val dout = Valid( Vec.fill( outWidth ){ Fixed(OUTPUT, bitWidth, fracWidth) } )
  }

  val inData = Mux( io.enNext, io.nextData, io.din )
  val buffer = Module( new Queue(Vec.fill( outWidth ){Fixed(width=bitWidth, 
    fracWidth=fracWidth)}, (p_outputs/outWidth) ) )

  buffer.io.enq.bits := inData.bits
  buffer.io.enq.valid := inData.valid
  buffer.io.deq.ready := io.readyIn

  io.readyOut := RegNext(io.readyIn)
  io.dout.bits := buffer.io.deq.bits
  io.dout.valid := buffer.io.deq.valid

}


class EgressNetwork(val bitWidth : Int, val fracWidth : Int, n_outputs : Int, 
	p : Int, outWidth : Int ) extends Module{
  
  val io = new Bundle{
    val din = Vec.fill( p ){ Valid( Vec.fill( outWidth ){ Fixed(INPUT, bitWidth, fracWidth) }).flip() }
    val dout = Valid( Vec.fill( outWidth ){ Fixed(OUTPUT, bitWidth, fracWidth) } )
  }

  Predef.assert( (n_outputs%p)==0, s"""Error: Total number of outputs must be divisible
    by the number of PEs""" )  
  val p_outputs = (n_outputs/p)

  // define control
  val counterA = RegInit(UInt(0, width=8))
  val counterB = RegInit(UInt(0, width=log2Up(n_outputs*p)+1 ))
  val enDeq = RegInit(Bool(false))


  // buffer network
  val buffer0 = Module( new Queue(Vec.fill( outWidth ){Fixed(width=bitWidth, 
    fracWidth=fracWidth)}, (p_outputs/outWidth) ) )
  val bufferTree = (0 until p-1).map(x => Module( new OutBuffer(bitWidth, fracWidth, 
    p_outputs, outWidth) ))

  // connect buffer0 inputs
  val sel0 = !enDeq
  val inData = Valid( Vec.fill( outWidth ){ Fixed(width=bitWidth, fracWidth=fracWidth) } )
  if (p>1){
  	inData := Mux( sel0, io.din(0), bufferTree(0).io.dout )
  } else {
  	inData := io.din(0)
  }
  buffer0.io.enq.bits := inData.bits
  buffer0.io.enq.valid := inData.valid
  buffer0.io.deq.ready := Bool(true)  // assume that egress keeps up with compute

  // default control connections
  val addA = UInt(1) & io.din(0).valid
  val addB = UInt(1) & inData.valid
  counterA := counterA + addA
  counterB := counterB + addB

  //trigger
  when( counterA === UInt((p_outputs/outWidth)-1) && addA === UInt(1) ){
  	enDeq := Bool(true)
    counterA := UInt(0)
  }
  when( counterB === UInt((n_outputs/outWidth)-1) && addB === UInt(1) ){
  	enDeq := Bool(false)
    counterB := UInt(0)
  }

  // connect outBuffer tree
  if (p>1){
  	for (ix <- 1 until p){
    	bufferTree(ix-1).io.din := io.din(ix)
  	}
  	bufferTree(0).io.enNext := RegNext(enDeq)
  	bufferTree(0).io.readyIn := RegNext(enDeq)
  	for (ix <- 1 until p-1){
  		bufferTree(ix).io.enNext := bufferTree(ix-1).io.readyOut
    	bufferTree(ix).io.readyIn := bufferTree(ix-1).io.readyOut
    	bufferTree(ix-1).io.nextData := bufferTree(ix).io.dout
  	}
  }
  
  // outputs
  io.dout.bits := buffer0.io.deq.bits
  io.dout.valid := buffer0.io.deq.valid

}

// Generates VSRP verilog
object BinaryRPMain {

    val bitWidth = 18
    val fracWidth = 10
    val n_features = 16
    val n_outputs = 6
    val inWidth = 4
    val outWidth = 2
    val p = 1

    val seeds = (0 until p).map(x => (0 until inWidth).map(y => 0))

    def main(args: Array[String]): Unit = {
      println("Generating the Binary Random Projection verilog")

      chiselMain( Array("--backend", "v", "--targetDir", "verilog"),
        () => Module(new BinaryRP(bitWidth, fracWidth, n_features, n_outputs, 
        	p, inWidth, outWidth, seeds, false)) )
  }

}
