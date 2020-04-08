/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc.tasty.bridge

import scala.tools.nsc.tasty.{TastyFlags, TastyUniverse}, TastyFlags._
import scala.reflect.internal.{Flags, ModifierFlags}

trait FlagOps { self: TastyUniverse =>
  import self.{symbolTable => u}

  object FlagSets {
    import u.Flag

    val Private: FlagSet = Flag.PRIVATE
    val Protected: FlagSet = Flag.PROTECTED
    val AbsOverride: FlagSet = Flag.ABSOVERRIDE
    val Abstract: FlagSet = Flag.ABSTRACT
    val Final: FlagSet = Flag.FINAL

    val Interface: FlagSet = Flag.INTERFACE
    val Sealed: FlagSet = Flag.SEALED
    val Case: FlagSet = Flag.CASE
    val Implicit: FlagSet = ModifierFlags.IMPLICIT
    val Lazy: FlagSet = Flag.LAZY
    val Override: FlagSet = Flag.OVERRIDE
    val Macro: FlagSet = Flag.MACRO
    val JavaStatic: FlagSet = ModifierFlags.STATIC
    val JavaEnum: FlagSet = ModifierFlags.JAVA_ENUM
    val JavaDefined: FlagSet = ModifierFlags.JAVA
    val Module: FlagSet = Flags.MODULE
    val Trait: FlagSet = Flag.TRAIT
    val Local: FlagSet = Flag.LOCAL
    val Synthetic: FlagSet = Flag.SYNTHETIC
    val Artifact: FlagSet = Flag.ARTIFACT
    val Mutable: FlagSet = Flag.MUTABLE
    val Accessor: FlagSet = Flags.ACCESSOR
    val CaseAccessor: FlagSet = Flag.CASEACCESSOR
    val Covariant: FlagSet = Flag.COVARIANT
    val Contravariant: FlagSet = Flag.CONTRAVARIANT
    val DefaultParameterized: FlagSet = Flag.DEFAULTPARAM
    val Stable: FlagSet = Flag.STABLE
    val ParamAccessor: FlagSet = Flag.PARAMACCESSOR
    val Param: FlagSet = Flag.PARAM
    val Deferred: FlagSet = Flag.DEFERRED
    val Method: FlagSet = Flags.METHOD

    val TermParamOrAccessor: FlagSet = Param | ParamAccessor
    val ModuleCreationFlags: FlagSet = Module | Lazy | Final | Stable
    val ModuleClassCreationFlags: FlagSet = Module | Final
  }

  implicit class FlagSetOps(private val flagSet: FlagSet) {
    private def flags: FlagSet = {
      val fs = flagSet & u.phase.flagMask
      (fs | ((fs & Flags.LateFlags) >>> Flags.LateShift)) & ~((fs & Flags.AntiFlags) >>> Flags.AntiShift)
    }
    private def getFlag(mask: FlagSet): FlagSet = {
      mask & (if ((mask & Flags.PhaseIndependentFlags) == mask) flagSet else flags)
    }
    def not(mask: FlagSet): Boolean = !isOneOf(mask)
    def is(mask: FlagSet): Boolean = getFlag(mask) == mask
    def ensuring(is: FlagSet, when: FlagSet): FlagSet = if (flagSet.is(when)) (flagSet | is) else flagSet
    def is(mask: FlagSet, butNot: FlagSet): Boolean = is(mask) && not(butNot)
    def isOneOf(mask: FlagSet): Boolean = getFlag(mask) != 0
  }

  def isEmpty(flags: FlagSet): Boolean = flags == u.NoFlags
  def emptyFlags: FlagSet = u.NoFlags
  def emptyTastyFlags: TastyFlagSet = EmptyTastyFlags

  def show(flags: FlagSet): String = u.show(flags)
  def show(flags: TastyFlagSet): String =
    if (!flags) "EmptyTastyFlags"
    else flags.toSingletonSets.map { f =>
      (f: @unchecked) match {
        case Erased          => "erased"
        case Internal        => "<internal>"
        case Inline          => "inline"
        case InlineProxy     => "<inlineproxy>"
        case Opaque          => "opaque"
        case Scala2x         => "<scala2x>"
        case Extension       => "<extension>"
        case Given           => "given"
        case Exported        => "<exported>"
        case NoInits         => "<noinits>"
        case TastyMacro      => "<tastymacro>"
        case Enum            => "enum"
        case Open            => "open"
        case SuperParamAlias => "<superparamalias>"
      }
    } mkString(" | ")
}
