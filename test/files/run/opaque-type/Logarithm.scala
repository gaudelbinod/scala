import scala.language.implicitConversions

object opaquetypes {
  class A { def foo: Int = 1 }
  // The opaque type definition
  opaque type Logarithm = Double
  val l: Logarithm = 1.toDouble.asInstanceOf[Logarithm]
/*
  implicit def double2Log(d: Double): Logarithm = d.asInstanceOf[Logarithm]
  val l2: Logarithm = 1.toDouble*/

  // Logarithm$class, ideally all the methods would be static
  object Logarithm {
/*    implicit def log2Double(l: Logarithm): Double = l.asInstanceOf[Double]
    implicit def double2Log(d: Double): Logarithm = d.asInstanceOf[Logarithm]*/

    // This is the way to lift to the logarithm opaque type
/*    def apply(d: Double): Logarithm = d
    def safe(d: Double): Option[Logarithm] =
      if (d > 0.0) Some(d) else None*/

/*    // here you would define all the extension methods (0 or more)
    implicit class LogarithmOps(val `this`: Logarithm) extends AnyVal {
      // This is the way to unlift the logarithm opaque type
      def toDouble: Double = `this`
      def plus(that: Logarithm): Logarithm = `this` + that
      def times(that: Logarithm): Logarithm = `this` + that
    }*/
  }
}

object Test {
  def main(args: Array[String]): Unit = {
  }
}

object UseSite {
  import opaquetypes._
  // Should be removed
/*  import Logarithm._
  val fakeLogarithm: Logarithm = 1.0 // this fails
  val legitLogarithm = Logarithm(1.0) // this works
  val fakeDouble: Double = legitLogarithm // this fails
  val legitDouble: Double = legitLogarithm.toDouble // this works
  legitLogarithm.plus(Logarithm(2.0)) // this works
  legitLogarithm.times(Logarithm(3.0)) // this works*/
}
