package rp

import Chisel._
import scala.util.Random
import scala.collection.immutable.Vector
import scala.collection.mutable.ArrayBuffer
import OLK.NORMA
import OLK.IOBundle_C
import OLK.IOBundle_R

import com.github.tototoshi.csv._


// Tests for the logical output. Single clock domain. Does not not include AsyncFifo's

class VSRPNorma (val n_features : Int, val n_components : Int, val n_dicts : Int, val bitWidth : Int, val fracWidth : Int, 
                    val seeds : ArrayBuffer[Int], val mem : ArrayBuffer[ArrayBuffer[Boolean]], val appType : Int, 
                      val inputFile : String, val outFile : String) extends Module {
  
  val io = new Bundle {

    val in = Fixed(INPUT, bitWidth, fracWidth)
    val out = Fixed(OUTPUT, bitWidth, fracWidth)
    val valid = Bool(OUTPUT)
    val added = Bool(OUTPUT)

    val gamma = Fixed(INPUT, bitWidth, fracWidth)
    val forget = Fixed(INPUT, bitWidth, fracWidth)
    val eta = Fixed(INPUT, bitWidth, fracWidth)
    val nu = Fixed(INPUT, bitWidth, fracWidth)

    val forceNA = Bool(INPUT)
    val yC = Bool(INPUT)
    val yReg = Fixed(INPUT, bitWidth, fracWidth)
  }


  val features = n_components //rand-projection goes from 8 to 6
  val dictSize = n_dicts
  
  val normaStages = ArrayBuffer( false, true, false, true, false, true, false, true, false, true, false, true, false, true, false, true, false, true, false, true, true, true ) 

  val vsrp = Module ( new VSRP ( n_features, n_components, bitWidth, fracWidth, seeds, mem) )
  val norma = Module( new NORMA( bitWidth, fracWidth, normaStages, 0, n_dicts, features, appType ) ) 
  
  
  var pCycles = norma.pCycles
  var rCycles = n_features + 1

  // Random Projection module inputs
  vsrp.io.dataIn.bits := io.in  
  vsrp.io.dataIn.valid := Bool( true )

 
  norma.io.example := vsrp.io.dataOut.bits
  norma.io.forceNA := ( !vsrp.io.dataOut.valid || io.forceNA )


  // Test 3:
  val yCreg = ShiftRegister(io.yC, rCycles) 
  val yRreg = ShiftRegister(io.yReg, rCycles)

  if (appType == 1){  
    val NORMAcIO = norma.io.asInstanceOf[IOBundle_C]
    NORMAcIO.yC := yCreg
  }
  if (appType == 3){
    val NORMArIO = norma.io.asInstanceOf[IOBundle_R]
    NORMArIO.yReg := yRreg
  }


  val outValid = ShiftRegister(vsrp.io.dataOut.valid, pCycles)

  norma.io.gamma := io.gamma
  norma.io.forget := io.forget
  norma.io.eta := io.eta
  norma.io.nu := io.nu

  io.out := norma.io.ft
  io.valid := outValid
  io.added := norma.io.addToDict

}

 



