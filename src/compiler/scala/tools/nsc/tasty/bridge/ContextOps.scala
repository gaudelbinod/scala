package scala.tools.nsc.tasty.bridge

import scala.annotation.tailrec

import scala.reflect.io.AbstractFile
import scala.tools.nsc.tasty.TastyUniverse
import scala.tools.nsc.tasty.TastyName, TastyName.TypeName
import scala.tools.nsc.tasty.TastyModes._

trait ContextOps { self: TastyUniverse =>
  import self.{symbolTable => u}, u.{internal => ui}
  import FlagSets._

  object defn {
    final val AnyRefTpe: Type = u.definitions.AnyRefTpe
    final val UnitTpe: Type = u.definitions.UnitTpe
    final val ObjectClass: ClassSymbol = u.definitions.ObjectClass
    final val AnyValClass: ClassSymbol = u.definitions.AnyValClass
    final val ScalaPackage: ModuleSymbol = u.definitions.ScalaPackage
    final val SeqClass: ClassSymbol = u.definitions.SeqClass
    @inline final def byNameType(arg: Type): Type = u.definitions.byNameType(arg)
    @inline final def scalaRepeatedType(arg: Type): Type = u.definitions.scalaRepeatedType(arg)
    @inline final def repeatedAnnotationClass(implicit ctx: Context): Option[Symbol] = ctx.classDependency("scala.annotation.internal.Repeated")
    @inline final def childAnnotationClass(implicit ctx: Context): Option[Symbol] = ctx.classDependency("scala.annotation.internal.Child")
    @inline final def arrayType(dims: Int, arg: Type): Type = (0 until dims).foldLeft(arg)((acc, _) => u.definitions.arrayType(acc))
  }

  private def describeOwner(owner: Symbol): String = {
    val kind =
      if (owner.is(Param)) {
        if (owner.isType) "type parameter"
        else "parameter"
      }
      else {
        owner.kindString
      }
    s"$kind ${owner.nameString}"
  }

  @inline final def unsupportedTermTreeError[T](noun: String)(implicit ctx: Context): T =
    unsupportedError(
      if (ctx.mode.is(ReadAnnotation)) s"$noun in an annotation of ${describeOwner(ctx.owner)}; note that complex trees are not yet supported for Annotations"
      else noun
    )

  @inline final def unsupportedError[T](noun: String)(implicit ctx: Context): T = {
    def location(owner: Symbol): String = {
      if (owner.isClass) s"${owner.kindString} ${owner.fullNameString}"
      else s"${describeOwner(owner)} in ${location(owner.owner)}"
    }
    typeError(s"Unsupported Scala 3 $noun; found in ${location(ctx.globallyVisibleOwner)}.")
  }

  @inline final def typeError[T](msg: String): T = throw new u.TypeError(msg)

  @inline final def assertError[T](msg: String): T =
    throw new AssertionError(s"assertion failed: ${u.supplementErrorMessage(msg)}")

  @inline final def assert(assertion: Boolean, msg: => Any): Unit =
    if (!assertion) assertError(String.valueOf(msg))

  @inline final def assert(assertion: Boolean): Unit =
    if (!assertion) assertError("")

  sealed abstract class Context {

    final def globallyVisibleOwner: Symbol = owner.logicallyEnclosingMember

    final def ignoreAnnotations: Boolean = u.settings.YtastyNoAnnotations

    final def adjustModuleClassCompleter(completer: TastyLazyType, name: TastyName): completer.type = {
      def findModule(name: TastyName, scope: Scope): Symbol = {
        val it = scope.lookupAll(encodeTermName(name)).filter(_.is(Module))
        if (it.hasNext) it.next()
        else noSymbol
      }
      completer.withSourceModule(ctx => findModule(name.toTermName, ctx.effectiveScope))
    }

    /** Either empty scope, or, if the current context owner is a class,
     *  the declarations of the current class.
     */
    final def effectiveScope: Scope =
      if (owner != null && owner.isClass) owner.rawInfo.decls
      else emptyScope

    final def requiredPackage(name: TastyName): Symbol = {
      val n = encodeTermName(name)
      if (n === u.nme.ROOT || n === u.nme.ROOTPKG) loadingMirror.RootPackage
      else if (n === u.nme.EMPTY_PACKAGE_NAME) loadingMirror.EmptyPackage
      loadingMirror.getPackage(name.toString)
    }

    final def log(str: => String): Unit = {
      if (u.settings.YdebugTasty)
        u.reporter.echo(
          pos = u.NoPosition,
          msg = str.linesIterator.map(line => s"#[$classRoot]: $line").mkString(System.lineSeparator)
        )
    }

    def owner: Symbol
    def source: AbstractFile
    def mode: TastyMode

    private final def loadingMirror: u.Mirror = u.mirrorThatLoaded(owner)

    final def classDependency(fullname: String): Option[Symbol] = loadingMirror.getClassIfDefined(fullname).toOption
    final def moduleDependency(fullname: String): Option[Symbol] = loadingMirror.getModuleIfDefined(fullname).toOption

    final lazy val classRoot: Symbol = initialContext.topLevelClass

    final def newLocalDummy: Symbol = owner.newLocalDummy(u.NoPosition)

    final def newWildcardSym(info: Type): Symbol =
      owner.newTypeParameter(u.nme.WILDCARD.toTypeName, u.NoPosition, emptyFlags).setInfo(info)

    final def isSameRoot(root: Symbol, name: TastyName): Boolean = {
      val selector = encodeTastyName(name)
      (root.owner `eq` this.owner) && selector === root.name
    }

    final def findOuterClassTypeParameter(name: TypeName): Symbol = {
      val selector: u.Name = encodeTypeName(name)
      owner.owner.typeParams.find(selector === _.name).getOrElse {
        throw new AssertionError(s"${owner.owner} has no type params.")
      }
    }

    final def newRefinementSymbol(parent: Type, owner: Symbol, name: TastyName, tpe: Type): Symbol = {
      val overridden = parent.member(encodeTastyName(name))
      val isOverride = isSymbol(overridden)
      var flags      = if (isOverride && overridden.isType) Override else emptyFlags
      val info = {
        if (name.isTermName) {
          flags |= Method | Deferred
          tpe match {
            case u.TypeRef(_, u.definitions.ByNameParamClass, arg :: Nil) => // nullary method
              ui.nullaryMethodType(arg)
            case u.PolyType(tparams, res) if res.paramss.isEmpty => ui.polyType(tparams, ui.nullaryMethodType(res))
            case _:u.MethodType | _:u.PolyType => tpe
            case _ => // val, which is not stable if structural. Dotty does not support vars
              if (isOverride && overridden.is(Stable)) flags |= Stable
              ui.nullaryMethodType(tpe)
          }
        }
        else {
          if (tpe.isInstanceOf[u.TypeBounds]) flags |= Deferred
          tpe
        }
      }
      newSymbol(owner, name, flags, info)
    }

    final def newSymbol(owner: Symbol, name: TastyName, flags: FlagSet, info: Type, privateWithin: Symbol = noSymbol): Symbol =
      adjustSymbol(
        symbol = {
          if (flags.is(Param)) {
            if (name.isTypeName) {
              owner.newTypeParameter(encodeTypeName(name.toTypeName), u.NoPosition, flags)
            }
            else {
              owner.newValueParameter(encodeTermName(name), u.NoPosition, flags)
            }
          }
          else if (name === TastyName.Constructor) {
            owner.newConstructor(u.NoPosition, flags & ~Stable)
          }
          else if (flags.is(Module)) {
            owner.newModule(encodeTermName(name), u.NoPosition, flags)
          }
          else if (name.isTypeName) {
            owner.newTypeSymbol(encodeTypeName(name.toTypeName), u.NoPosition, flags)
          }
          else {
            owner.newMethodSymbol(encodeTermName(name), u.NoPosition, flags)
          }
        },
        info = info,
        privateWithin = privateWithin
      )

    final def newClassSymbol(owner: Symbol, typeName: TypeName, flags: FlagSet, completer: TastyLazyType, privateWithin: Symbol): ClassSymbol = {
      adjustSymbol(
        symbol = owner.newClassSymbol(name = encodeTypeName(typeName), newFlags = flags.ensuring(Abstract, when = Trait)),
        info = completer,
        privateWithin = privateWithin
      )
    }

    final def adjustSymbol(symbol: Symbol, flags: FlagSet, info: Type, privateWithin: Symbol): symbol.type =
      adjustSymbol(symbol.setFlag(flags), info, privateWithin)

    final def adjustSymbol(symbol: Symbol, info: Type, privateWithin: Symbol): symbol.type = {
      symbol.privateWithin = privateWithin
      symbol.info = info
      symbol
    }

    /** Determines the owner of a refinement in the current context by the following steps:
     *  1) if the owner if this context is a refinement symbol, we are in a recursive RefinedType. Ensure that the
     *     context owner is initialised with the parent and reuse it.
     *  2) if the parent is also a RefinedType, then we will flatten the nested structure by reusing its owner
     *  3) the parent is not a RefinedType, and we are not in an enclosing RefinedType, so create a new RefinementClassSymbol.
     *  The Parent alongside the RefinedType owner are passed to the given operation
     */
    final def withRefinementOwner[T](parent: Type)(op: (Type, Symbol) => T): T = {
      val clazz = owner match {
        case enclosing: u.RefinementClassSymbol =>
          enclosing.rawInfo match {
            case EmptyRecTypeInfo => mkRefinedTypeWith(parent :: Nil, enclosing, mkScope)
            case _                => ()
          }
          enclosing
        case _ => parent match {
          case nested: u.RefinedType => nested.typeSymbol
          case _                     => newRefinementClassSymbol
        }
      }
      op(parent, clazz)
    }

    final def newRefinementClassSymbol: Symbol = owner.newRefinementClass(u.NoPosition)

    final def newExtensionMethodSymbol(owner: Symbol, companionModule: Symbol) =
      owner.newExtensionMethodSymbol(companionModule, u.NoPosition)

    final def setTypeDefInfo(sym: Symbol, info: Type): Unit = sym.info = normaliseIfBounds(info, sym)(this)

    @tailrec
    final def initialContext: InitialContext = this match {
      case ctx: InitialContext => ctx
      case ctx: FreshContext   => ctx.outer.initialContext
    }

    final def withOwner(owner: Symbol): Context =
      if (owner `ne` this.owner) fresh(owner) else this

    final def withNewScope: Context =
      fresh(newLocalDummy)

    final def selectionCtx(name: TastyName): Context = this // if (name.isConstructorName) this.addMode(Mode.InSuperCall) else this
    final def fresh(owner: Symbol): FreshContext = new FreshContext(owner, this, this.mode)

    private def sibling(mode: TastyMode): FreshContext = new FreshContext(this.owner, outerOrThis, mode)
    private def sibling: FreshContext = sibling(mode)

    private def outerOrThis: Context = this match {
      case ctx: FreshContext => ctx.outer
      case ctx               => ctx
    }

    final def addMode(mode: TastyMode): Context =
      if (!this.mode.is(mode)) sibling(this.mode | mode)
      else this

    final def retractMode(mode: TastyMode): Context =
      if (this.mode.isOneOf(mode)) sibling(this.mode &~ mode)
      else this

    final def withMode(mode: TastyMode): Context =
      if (mode != this.mode) sibling(mode)
      else this

    final def withSource(source: AbstractFile): Context =
      if (source `ne` this.source) sibling.atSource(source)
      else this

    final def withPhaseNoLater[T](phase: String)(op: Context => T): T =
      u.enteringPhaseNotLaterThan[T](u.findPhaseWithName(phase))(op(this))

    /** Enter a phase and apply an error handler if the current phase is after the one specified
      */
    final def withSafePhaseNoLater[E, T](phase: String)(pf: PartialFunction[Throwable, E])(op: Context => T): Either[E, T] = {
      val phase0 = u.findPhaseWithName(phase)
      if (u.isAtPhaseAfter(phase0)) {
        try {
          u.enteringPhaseNotLaterThan(phase0)(Right(op(this)))
        } catch pf andThen (Left(_))
      } else {
        Right(op(this))
      }
    }

    @inline final def mkScope(syms: Symbol*): Scope = u.newScopeWith(syms:_*)
    def mkScope: Scope = u.newScope
    def emptyScope: Scope = u.EmptyScope
  }

  final class InitialContext(val topLevelClass: Symbol, val source: AbstractFile) extends Context {
    def mode: TastyMode = EmptyTastyMode
    def owner: Symbol = topLevelClass.owner
  }

  final class FreshContext(val owner: Symbol, val outer: Context, val mode: TastyMode) extends Context {
    private[this] var mySource: AbstractFile = null
    def atSource(source: AbstractFile): this.type = { mySource = source ; this }
    def source: AbstractFile = if (mySource == null) outer.source else mySource
  }
}
