import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Macros {
  def impl(c: Context) = c.universe.Literal(c.universe.Constant(()))
  def foo: Unit = macro impl
}