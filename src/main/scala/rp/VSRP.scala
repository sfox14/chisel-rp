package rp

import Chisel._
import scala.util.Random
import scala.collection.immutable.Vector

import scala.collection.mutable.ArrayBuffer
import com.github.tototoshi.csv._



// Implements a Linear Feedback Shift Register

class LFSR [T <: Data]( seed : Int) extends Module{
  val io = new Bundle{
    val valid = Bool(INPUT)
    val off = Bool(INPUT)
    val out = UInt(OUTPUT, 1)
  }

  // Initialise the RNG
  val res = Reg(init = UInt(seed, 16)) 

  val nxt = res(0)^res(2)^res(3)^res(5) 
  val nxt_res = Cat(nxt, res(15,1))
  when( io.valid ){
    res := nxt_res
  }

  // Reset rng to its original state
  when ( io.off ){
    res := UInt(seed, width = 16) 
  }

  io.out := nxt

}


class VSRP (val n_features : Int, val n_components : Int, val bitWidth : Int, val fracWidth : Int, val seeds : ArrayBuffer[Int],
                        val mem : ArrayBuffer[ArrayBuffer[Boolean]], clk : Option[Clock] = None) extends Module( _clock = clk ) {
  val io = new Bundle {
    val dataIn = Valid( Fixed( INPUT, bitWidth, fracWidth ) ).flip
    val dataOut = Valid( Vec.fill( n_components ){ Fixed( OUTPUT, bitWidth, fracWidth ) } )
  }

  
  val bram = Vec( Range(0, n_features, 1).map( x => Vec ( Range(0, n_components, 1).map( y => Bool( mem(x)(y) ) ) ) ) )
  val lfsr = (0 until n_components).map( x => Module(new LFSR( seeds(x) )))
  for ( idx <- 0 until n_components ){
    lfsr(idx).io.valid := io.dataIn.valid
    lfsr(idx).io.off := Bool(false)
  }
  val count = Reg(init = UInt(0, log2Up(n_features)) )
  val add = UInt(1) & io.dataIn.valid
  count := count + add

  val rst = Reg(init = Bool(false))
  rst := Bool(false)
  when( count === UInt( n_features -1 ) && io.dataIn.valid ){
    count := UInt(0)
    rst := Bool(true)
    for ( idx <- 0 until n_components){
      lfsr(idx).io.off := Bool(true)
    }
  }.elsewhen( count === UInt(0) && !io.dataIn.valid ){ 
    rst := Bool(true)
  }

  val wr = (0 until n_components).map( x => Bool( bram(count)(x) ) )
  val state = (0 until n_components).map( x => Reg(init = Fixed(0, bitWidth, fracWidth)) ) 
  val dataOut = (0 until n_components).map( x => Reg( init = Fixed(0, bitWidth, fracWidth), next=state(x)))
  val vald = Reg( init = Bool(false), next = rst && io.dataIn.valid ) 
  
  for ( idx <- 0 until n_components){

    val funcA = state(idx) + io.dataIn.bits 
    val funcB = state(idx) - io.dataIn.bits  

    val funcAr = Fixed(0, bitWidth, fracWidth) + io.dataIn.bits
    val funcBr = Fixed(0, bitWidth, fracWidth) - io.dataIn.bits 

    // State machine
    when ( wr(idx) && (add === UInt(1)) ){ 
      
      state(idx) := funcBr
      when ( !rst ){
        state(idx) := funcB
      }        
      when (lfsr(idx).io.out === UInt(1)){
        state(idx) := funcAr  
        when ( !rst ){
          state(idx) := funcA
        }
      }
    }

    when ( rst && !wr(idx) && io.dataIn.valid ){ 
      state(idx) := Fixed(0, bitWidth, fracWidth)
    }

    io.dataOut.bits(idx) := dataOut(idx)  

  } 

  io.dataOut.valid := ( vald === UInt(1) )
  
}

// Generates VSRP verilog
object VSRPMain {

    val rd1 = CSVReader.open(new java.io.File("src/resources/params.csv"))
    val params = rd1.all()
    val n_features = params(0)(0).toInt
    val n_components = params(0)(1).toInt
    val bitWidth = params(0)(3).toInt
    val fracWidth = params(0)(4).toInt

    println(params(0))
    rd1.close()

      // 2. Read the precomputed non-zero indices
    //    - n_features x n_components (i.e. 8x4) 
    val mem = ArrayBuffer[ArrayBuffer[Boolean]]()
    val reader2 = CSVReader.open(new java.io.File("src/resources/mem.csv"))

    val iter2 = reader2.iterator
    var nc = -1
    var i=0
    while ( iter2.hasNext ){
      val nxtLine = iter2.next
      nc = nxtLine.length
      val temp = new ArrayBuffer[Boolean]()

      for (ix <-0 until nc){
        temp.append(nxtLine(ix).trim.toBoolean)
      }
      mem.append(temp)
      i+=1
    }
    reader2.close()


    // Get the seeds for the rng 
    val rd = CSVReader.open(new java.io.File("src/resources/seeds.csv"))
    val res = rd.all()(0)
    println(res)
    val seeds = new ArrayBuffer[Int]()
    for (ix <- 0 until n_components){
      seeds.append(res(ix).toInt)
    }

    def main(args: Array[String]): Unit = {
      println("Generating the Random Projection verilog")

      chiselMain(Array("--backend", "v", "--targetDir", "verilog"),
        () => Module(new VSRP(n_features, n_components, bitWidth, fracWidth, seeds, mem)))
  }

}