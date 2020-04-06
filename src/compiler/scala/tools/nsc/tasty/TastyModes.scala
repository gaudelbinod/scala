package scala.tools.nsc.tasty

import scala.collection.mutable

/**Flags To Control traversal of Tasty
 */
object TastyModes {

  final val EmptyTastyMode: TastyMode = TastyMode(0)
  final val ReadParents: TastyMode    = TastyMode(1 << 0)
  final val ReadAnnotation: TastyMode = TastyMode(1 << 1)

  case class TastyMode(val toInt: Int) extends AnyVal { mode =>

    def |(other: TastyMode): TastyMode = TastyMode(toInt | other.toInt)
    def &(mask: TastyMode): TastyMode  = TastyMode(toInt & mask.toInt)
    def is(mask: TastyMode): Boolean   = (this & mask) == mask

    def debug: String = {
      if (mode == EmptyTastyMode) "EmptyTastyMode"
      else {
        val sb = mutable.ArrayBuffer.empty[String]
        if (mode.is(ReadParents)) sb += "ReadParents"
        if (mode.is(ReadAnnotation)) sb += "ReadAnnotation"
        sb.mkString("|")
      }
    }

  }

}
