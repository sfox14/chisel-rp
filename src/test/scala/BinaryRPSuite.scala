import org.scalatest.junit.JUnitSuite
import org.junit.Assert._
import org.junit.Test
import org.junit.Ignore
import scala.util.Random
import scala.collection.mutable.ArrayBuffer

import Chisel._
import rp._
import utils._
import math._

import com.github.tototoshi.csv._

/*
Binary RP TestSuite:
  - Chisel simulation only
*/

object toFixed
{
  def apply( myDouble : Double, fracWidth : Int, message : String = "Error: could not convert to fixed" ) : BigInt = {
    try{
        ( myDouble * BigDecimal( BigInt(1) << fracWidth ) ).toBigInt
      } catch{
        case x:Exception => throw new Exception(message)
      }
  }
}

class BinaryRPSuite extends TestSuite {

  /* This test verifies binary random projection using one PE. The input, output 
  and random seeds are dumped to csv's in ./test-outputs */
  @Test def _peTest{

    class PETest( c : BinaryPE, rand : Boolean = false ) extends Tester(c) {

      /* rng */
      val rng = new Random(23)

      /* get top-level parameters */
      val bitWidth = c.bitWidth
      val fracWidth = c.fracWidth
      val n_examples = 2
      val n_features = c.n_features
      val p_outputs = c.p_outputs
      val outWidth = c.outWidth
      val inWidth = c.inWidth
      val seeds = c.seeds

      /* print parameters */
      println(s"data type = Fixed(${bitWidth}, ${fracWidth})")
      println(s"number of input examples = ${n_examples}")
      println(s"number of features = ${n_features}")
      println(s"number of partial outputs = ${p_outputs}")
      println(s"streaming input width = ${inWidth}")
      println(s"streaming output width = ${outWidth}")
      println(s"random seeds = ${seeds}")

      /* csv writer */
      val path = "test-outputs/"
      val inWriter = CSVWriter.open(new java.io.File(s"${path}BinaryPE_input.csv"))
      val outWriter = CSVWriter.open(new java.io.File(s"${path}BinaryPE_output.csv"))
      val seedsWriter = CSVWriter.open(new java.io.File(s"${path}BinaryPE_seeds.csv"))

      /* make some random data */
      val outs = ArrayBuffer[Double]()
      //val xin = (0 until n_examples).map(x => (0 until n_features).grouped(k).toVector)
      val din = random_data(n_examples, n_features)
      val xin = din.map(x => x.grouped(inWidth).toVector)
      println(din)

      poke(c.io.din.valid, false)
      step(1)
      reset(20)
      poke(c.io.dout.ready, true)
      poke(c.io.din.valid, true)

      var vld = true
      var rdy = true    

      for( ix <- 0 until n_examples ){
        var iz = 0
        while( iz<(n_features/inWidth) ){
          for(iy <- 0 until inWidth){
            poke(c.io.din.bits(iy), toFixed(xin(ix)(iz)(iy), fracWidth)) // BigInt(xin(ix)(iz)(iy))
          }
          // randomise the in/out fifo
          vld = (rng.nextInt(3) == 0)
          rdy = (rng.nextInt(3) == 0)
          if( rand ){
            poke(c.io.din.valid, vld)
            poke(c.io.dout.ready, rdy)
          }

          if (peek(c.io.din.ready) == 1){
            iz += 1
          }

          if (peek(c.io.dout.valid) == 1){
            for(iw <- 0 until outWidth){
              outs += fromPeek.toDbl( peek(c.io.dout.bits(iw)), bitWidth, fracWidth )
            }
          }
          step(1)
        }
      }
      poke(c.io.din.valid, false)
      // get remaining outputs
      for(ix <- 0 until (n_examples*p_outputs - outs.length)+10 ){
        if (peek(c.io.dout.valid) == 1){
            for(iw <- 0 until outWidth){
              outs += fromPeek.toDbl( peek(c.io.dout.bits(iw)), bitWidth, fracWidth )
            }
        }
        step(1)
      }
      step(10)

      // save input, seeds and output
      val results = outs.grouped(p_outputs).toVector
      Predef.assert(din.length >= results.length, s"""Error: output length does 
        not equal input length""")
      for(ix <- 0 until din.length){
        inWriter.writeRow( (din(ix)) )
      }
      for(ix <- 0 until results.length){
        outWriter.writeRow( (results(ix)) )
      }
      seedsWriter.writeRow( (seeds) )

      inWriter.close()
      outWriter.close()
      seedsWriter.close()
    }

    val bitWidth = 18
    val fracWidth = 10
    val n_features = 16
    val p_outputs = 6
    val inWidth = 4
    val outWidth = 1
    
    /* make some random seeds */
    val rng = new Random(43)
    val seeds = (0 until inWidth).map(x => rng.nextInt( pow(2, 16).toInt ))

    println("Testing the Binary Random Projection ...")    
    chiselMainTest(Array("--genHarness", "--test", "--backend", "c", //"--wio", 
      "--compile", "--targetDir", dir.getPath.toString(), "--vcd"), // .emulator is a hidden directory
      () => Module(new BinaryPE( bitWidth, fracWidth, n_features, p_outputs, inWidth, 
        outWidth, seeds, true))) { f => new PETest(f, true) }
  }

  @Test def _rpTest{

    class RPTest( c : BinaryRP ) extends Tester(c) {

      /* rng */
      val rng = new Random(23)

      /* get top-level parameters */
      val bitWidth = c.bitWidth
      val fracWidth = c.fracWidth
      val n_examples = 2
      val n_features = c.n_features
      val n_outputs = c.n_outputs
      val p = c.p
      val inWidth = c.inWidth
      val outWidth = c.outWidth
      val seeds = c.seeds

      /* print parameters */
      println(s"data type = Fixed(${bitWidth}, ${fracWidth})")
      println(s"number of input examples = ${n_examples}")
      println(s"number of features = ${n_features}")
      println(s"number of outputs = ${n_outputs}")
      println(s"number of PEs = ${p}")
      println(s"streaming input width = ${inWidth}")
      println(s"streaming output width = ${outWidth}")
      println(s"random seeds = ${seeds}")

      /* csv writer */
      val path = "test-outputs/"
      val inWriter = CSVWriter.open(new java.io.File(s"${path}BinaryRP_input.csv"))
      val outWriter = CSVWriter.open(new java.io.File(s"${path}BinaryRP_output.csv"))
      val seedsWriter = CSVWriter.open(new java.io.File(s"${path}BinaryRP_seeds.csv"))

      /* make some random data */
      val outs = ArrayBuffer[Double]()
      //val din = (0 until n_examples).map(x => (0 until n_features)).toVector //.grouped(inWidth).toVector)
      val din = random_data(n_examples, n_features)
      val xin = din.map(x => x.grouped(inWidth).toVector)
      println(din)

      poke(c.io.din.valid, false)
      step(1)
      reset(20)
      poke(c.io.dout.ready, true)
      poke(c.io.din.valid, true)

      var vld = true
      var rdy = true
      var nouts = 0    

      for( ix <- 0 until n_examples ){
        var iz = 0
        while( iz<(n_features/inWidth) ){
          for(iy <- 0 until inWidth){
            poke(c.io.din.bits(iy), toFixed(xin(ix)(iz)(iy), fracWidth)) // BigInt(xin(ix)(iz)(iy))
            //poke(c.io.din.bits(iy), BigInt(xin(ix)(iz)(iy)) )
          }

          if (peek(c.io.din.ready) == 1){
            iz += 1
          }

          if (peek(c.io.dout.valid) == 1){
            nouts += 1
            for(iw <- 0 until outWidth){
              outs += fromPeek.toDbl( peek(c.io.dout.bits(iw)), bitWidth, fracWidth )
            }
          }
          step(1)
        }
      }
      poke(c.io.din.valid, false)
      // get remaining outputs
      
      while( nouts<(n_examples*(n_outputs/outWidth)) ){
        if (peek(c.io.dout.valid) == 1){
          nouts += 1
          for(iw <- 0 until outWidth){
            outs += fromPeek.toDbl( peek(c.io.dout.bits(iw)), bitWidth, fracWidth )
          }
        }
        step(1)
      }
      step(10)

      // save input, seeds and output
      val results = outs.grouped(n_outputs).toVector
      Predef.assert(din.length >= results.length, s"""Error: output length does 
        not equal input length""")
      for(ix <- 0 until din.length){
        inWriter.writeRow( (din(ix)) )
      }
      for(ix <- 0 until results.length){
        outWriter.writeRow( (results(ix)) )
      }
      for(ix <- 0 until seeds.length){
        seedsWriter.writeRow( (seeds(ix)) )
      }
      inWriter.close()
      outWriter.close()
      seedsWriter.close()
    }

    val bitWidth = 18
    val fracWidth = 10
    val n_features = 16
    val n_outputs = 24
    val inWidth = 4
    val outWidth = 2
    val p = 4
    
    /* make some random seeds */
    val rng = new Random(43)
    val seeds = (0 until p).map(x => (0 until inWidth).map(y => rng.nextInt( pow(2, 16).toInt )))

    println("Testing the Binary Random Projection ...")    
    chiselMainTest(Array("--genHarness", "--test", "--backend", "c", //"--wio", 
      "--compile", "--targetDir", dir.getPath.toString(), "--vcd"), // .emulator is a hidden directory
      () => Module(new BinaryRP( bitWidth, fracWidth, n_features, n_outputs, p, inWidth, outWidth,
        seeds, true))) { f => new RPTest(f) }
  }






}
