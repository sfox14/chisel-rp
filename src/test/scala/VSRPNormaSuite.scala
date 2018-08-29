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

class VSRPNormaSuite extends TestSuite {

  @Test def _VSRPNnormaTest{
    class VSRPNormaTest( c : VSRPNorma ) extends Tester(c) {
      
      def tryToBool(myBool : String, message : String) : Boolean = {
        try {
          myBool.trim.toBoolean
        } catch {
          case x:Exception => throw new Exception(message)
        }
      }

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

      //for converting from peek to output_full.csv
      val ONE = (BigInt(1) << c.fracWidth).toDouble 

      // 1. Read test input examples
      //    - Create an ArrayBuffer to hold examples. 
      //    - Then read them into Rand one by one. 
      val examples = ArrayBuffer[ArrayBuffer[BigInt]]()
      val forceNA = ArrayBuffer[Boolean]()
      val yC = ArrayBuffer[Boolean]()
      val yReg = ArrayBuffer[BigInt]()
      val expectedyReg = ArrayBuffer[BigInt]()
      val reader1 = CSVReader.open(new java.io.File(c.inputFile)) 

      val iter1 = reader1.iterator // iterator
      var n_features = -1 // number of features
      var n = 0 //number of examples
      while ( iter1.hasNext ){
        val nxtLine = iter1.next

        val yR = tryToFixed(nxtLine(2), "Could not convert y to fixed")
        yReg.append(yR)
        expectedyReg.append(yR)
        yC.append( (yR == ONE) )

        val fna = tryToBool(nxtLine(1), "Could not convert fna to bool")
        forceNA.append(fna)

        n_features = nxtLine.length-3
        
        val temp = nxtLine.drop(3).map(x => {
              tryToFixed(x, "Could not convert a feature to fixed ")
              }).to[ArrayBuffer]

        examples.append(temp)
        n+=1
      }
      reader1.close()
      
      // 2. Read the precomputed non-zero indices
      //    - n_features x n_components (i.e. 8x4) 
      val memNonZero = ArrayBuffer[ArrayBuffer[Boolean]]()
      val reader2 = CSVReader.open(new java.io.File("src/resources/mem.csv"))

      val iter2 = reader2.iterator
      var n_components = -1
      var i=0
      while ( iter2.hasNext ){
        val nxtLine = iter2.next
        n_components = nxtLine.length
        val temp = new ArrayBuffer[Boolean]()

        for (ix <-0 until n_components){
          temp.append(nxtLine(ix).trim.toBoolean)
        }
        memNonZero.append(temp)
        i+=1
      }
      reader2.close()

      
      printf("Number of examples: %d\n", n)
      printf("Number of features: %d\n", n_features)
      printf("Number of components: %d\n", n_components)


      Predef.assert( n_features == i, "Error: n_features not consistent") //dataset problem
      Predef.assert( n_components == c.n_components, "Error: n_components not consistent") //change n_components


      val rd3 = CSVReader.open(new java.io.File("src/resources/params.csv"))
      val norma_params = rd3.all()
      val gamma = tryToFixed(norma_params(2)(0), "Could not cast gamma")
      val forget = tryToFixed(norma_params(2)(1), "Could not cast forget") 
      val eta = tryToFixed(norma_params(2)(2), "Could not cast eta")
      val nu = tryToFixed(norma_params(2)(3), "Could not cast nu")  
      rd3.close()

      println(gamma)
      println(forget)
      println(eta)
      println(nu)
      
        // 6. Output writer "src/resources/output_full.csv"
      val writer  = CSVWriter.open(new java.io.File(c.outFile)) 

      // 7. Begin tests
      poke(c.io.gamma, gamma)
      poke(c.io.forget, forget)
      poke(c.io.eta, eta)
      poke(c.io.nu, nu)

      var cycles = 0
      var index = 0
      for (idx <- 0 until n){
        // read in one example at a time
        poke(c.io.yC, yC(idx))
        poke(c.io.yReg, yReg(idx))
        poke(c.io.forceNA, forceNA(idx))
        val exIn = examples(idx)
        for (idn <- 0 until n_features){
          // read in one feature at a time
          val dataIn = exIn(idn) 
          poke(c.io.in, dataIn)

          step(1)
          cycles+=1

          peek(c.vsrp.io.dataOut.valid)
          index = (cycles - (c.norma.pCycles + c.rCycles))/n_features
          peek(c.norma.io.forceNA)

          
          if ( (peek(c.io.valid).toInt) == 1 ){
            val outData = ( (fromPeek( peek(c.io.out) ).toDouble ) / ONE )
            println(index) 
            writer.writeRow(List(peek(c.io.added)==1, (expectedyReg(index).toDouble/ONE), outData))
          }
          
          if ( (peek(c.io.out).toInt) != 0){
            println("Output received")
          }
          if ( (peek(c.io.added).toInt) == 1){
            println("Dictionary appended")
          }
          
        }

      }
      // This is to catch the last output.
      // The logic takes n+1 clock cycles. Hence we still need to write the last output.
      
      for (kk <- 0 until c.pCycles+1){
        step(1)
        cycles+=1
        index = (cycles - (c.norma.pCycles + c.rCycles))/n_features
        if ( (peek(c.io.valid).toInt) == 1 ){
            val outData = ( (fromPeek( peek(c.io.out) ).toDouble ) / ONE )
            println(index) 
            writer.writeRow(List(peek(c.io.added)==1, (expectedyReg(index).toDouble/ONE), outData))
          }
        n+=1
      }
      println("Output written to "+c.outFile)
      writer.close()
    }

    /*
    top-level simulation params
    */
    val rd1 = CSVReader.open(new java.io.File("src/resources/params.csv"))
    val params = rd1.all()
    val n_features = params(0)(0).toInt
    val n_components = params(0)(1).toInt
    val n_dicts = params(0)(2).toInt
    val bitWidth = params(0)(3).toInt
    val fracWidth = params(0)(4).toInt
    val appType = params(0)(5).toInt
    val inputFile = params(1)(0)
    val outFile = dir.getPath.toString()+"/VSRPNorma.csv" 

    println(params(0))
    println(params(1))
    println(params(2))
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


    chiselMainTest(Array("--genHarness", "--compile", "--test", "--backend", "c", "--targetDir", 
    	dir.getPath.toString()), () => { Module( new VSRPNorma( n_features, n_components, n_dicts, 
        bitWidth, fracWidth, seeds, mem, appType, inputFile, outFile ) ) }) { c => new VSRPNormaTest( c ) }
  
  }

}
