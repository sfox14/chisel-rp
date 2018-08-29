import org.scalatest.junit.JUnitSuite
import org.junit.Assert._
import org.junit.Test
import org.junit.Ignore
import scala.util.Random
import scala.collection.mutable.ArrayBuffer

import Chisel._
import rp._

import com.github.tototoshi.csv._

/*
VSRP+NORMA TestSuite:
  - FPT 2016 paper
  - Chisel simulation only
*/

class VSRPSuite extends TestSuite {

  @Test def _VSRPTest{

    class VSRPTest( c : VSRP ) extends Tester(c) {

      def tryToFixed(myDouble : String, message : String) : BigInt = {
        try {
          (myDouble.trim.toDouble * BigDecimal( BigInt(1) << c.fracWidth )).toBigInt
        } catch {
          case x:Exception => throw new Exception(message)
        }
      }

      def fromPeek(myNum : BigInt) : BigInt = {
        if (myNum >= (BigInt(1) << (c.bitWidth - 1)))
          myNum - (BigInt(1) << c.bitWidth)
        else
          myNum
      }

      val ONE = (BigInt(1) << c.fracWidth).toDouble


      val examples = ArrayBuffer[ArrayBuffer[BigInt]]()
      val reader1 = CSVReader.open(new java.io.File("src/data/artificialNovMod.csv")) // artificialTwoClass.csv

      val iter1 = reader1.iterator // iterator
      var n_features = -1 // number of features
      var n = 0 //number of examples
      while ( iter1.hasNext ){
        val nxtLine = iter1.next
        n_features = nxtLine.length-3
        val temp = nxtLine.drop(3).map(x => {
              tryToFixed(x, "Could not convert a feature to fixed ")
              }).to[ArrayBuffer]

        examples.append(temp)
        n+=1
      }
      reader1.close()
      
      for (idx <- 0 until 2) {
        // read in one example at a time
        var valid = true
        var thres = 0.25 //used to vary valid_in

        val exIn = examples(idx) 
        for (idn <- 0 until n_features){
          // read in one feature at a time
          val dataIn = exIn(idn) //Scala Int
          poke(c.io.dataIn.bits, dataIn )
          valid = math.random < thres
          poke(c.io.dataIn.valid, valid)  
          
          while ( !valid ){

            peek(c.io.dataOut.bits)
            peek(c.io.dataOut.valid)
            peek(c.count)
            step(1)
            valid = math.random < thres
            poke(c.io.dataIn.bits, dataIn )
            poke(c.io.dataIn.valid, valid)
          }
          
          step(1)
          peek(c.io.dataOut.bits)
          peek(c.io.dataOut.valid)
          peek(c.count)
          println("----------------------------------------------------")
        } 
      }

      step(1)
      peek(c.io.dataOut.bits)
      peek(c.io.dataOut.valid)
      for (ix <- 0 until c.n_components){
        val outData = ( (fromPeek( peek(c.io.dataOut.bits(ix)) ).toDouble ) / ONE )
        println(outData)
      }
    
    }

    /* Instantiate the VSRPTest class */
    val rd1 = CSVReader.open(new java.io.File("src/resources/params.csv"))
    val params = rd1.all()
    val n_features = params(0)(0).toInt
    val n_components = params(0)(1).toInt
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
      seeds.append(res(ix).toInt)
    }
    rd.close()

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

    println("Testing the VSRP Random Projection ...")    
    chiselMainTest(Array("--genHarness", "--test", "--backend", "c", //"--wio", 
      "--compile", "--targetDir", dir.getPath.toString()), // .emulator is a hidden directory
      () => Module(new VSRP( n_features, n_components, bitWidth, 
        fracWidth, seeds, mem ))) { f => new VSRPTest(f) }
  
  }
}
