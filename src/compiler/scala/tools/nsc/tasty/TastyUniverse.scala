package scala.tools.nsc.tasty

import bridge._

abstract class TastyUniverse extends TastyCore
  with FlagOps
  with TypeOps
  with AnnotationOps
  with ContextOps
  with SymbolOps
  with NameOps
  with TreeOps
