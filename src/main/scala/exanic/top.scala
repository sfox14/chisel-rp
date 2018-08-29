package exanic 

import Chisel._
import com.github.tototoshi.csv._
import scala.collection.mutable.ArrayBuffer

// Generate verilog for VSRP+NORMA targeting ExanicX4 (same as FPT16 paper)

object VSRPNormaMain {

  val rd1 = CSVReader.open(new java.io.File("src/resources/params.csv"))
  val params = rd1.all()
  val n_features = params(0)(0).toInt
  val n_components = params(0)(1).toInt
  val n_dicts = params(0)(2).toInt
  val bitWidth = params(0)(3).toInt
  val fracWidth = params(0)(4).toInt
  println(params(0))
  rd1.close()
  
  // Get the seeds for the rng 
  val rd = CSVReader.open(new java.io.File("src/resources/seeds.csv"))
  val res = rd.all()(0)
  println(res)
  val seeds = new ArrayBuffer[Int]()
  for (ix <- 0 until n_components){
    seeds.append(res(ix).toInt) // might need to more here, i.e. convert to fixed point
  }

  // 2. Read the precomputed non-zero indices
  //    - n_features x n_components (i.e. 8x4) 
  val mem = ArrayBuffer[ArrayBuffer[Boolean]]()
  val reader2 = CSVReader.open( new java.io.File("src/resources/mem.csv") )

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

  def modFunc() : VSRPNormaMod = {
    new VSRPNormaMod( n_features, n_components, n_dicts, bitWidth, fracWidth, seeds, mem )
  }

  def main(args: Array[String]): Unit = {
    println("Generating the VSRP Random Projection + NORMA verilog")
    val chiselArgs = args.drop(1)
    /** create UserApplication verilog
      * args = userMod output type
      *        fifoDepth
      *        memSize
      *        userModule
      *        noBytes to return
      */
    //96 bytes = 32 examples * 8 features * 24bits
    chiselMain(chiselArgs, () => 
      Module( new VSRPNormaX4( Fixed( width=bitWidth, fracWidth=fracWidth ), 800, 128, modFunc, 16 ) )) 

  }
}

