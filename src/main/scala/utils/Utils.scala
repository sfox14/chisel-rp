package utils

import Chisel._
import scala.math._
import scala.util.Random
import sys.process._
import com.github.tototoshi.csv._
import scala.language.postfixOps


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


object fromPeek
{
  def apply( myNum : BigInt, bitWidth : Int ): BigInt = {
    if (myNum >= (BigInt(1) << (bitWidth - 1)))
      myNum - (BigInt(1) << bitWidth)
    else
      myNum
  }

  def toDbl( myNum : BigInt, bitWidth : Int, fracWidth : Int): Double = {

    val x = apply( myNum, bitWidth ).toDouble
    val ONE = (BigInt(1) << fracWidth).toDouble
    x/ONE
  } 
}

// list of [n_examples x n_features] between -1 and 1
object random_data
{
  def apply( n_examples : Int, n_features : Int, 
    rng : Random = new Random(43) ) : Seq[Seq[Double]] = {
    (0 until n_examples).map( x => (0 until n_features)
      .map( y => 2*rng.nextDouble -1).toList ).toList
  }
}
