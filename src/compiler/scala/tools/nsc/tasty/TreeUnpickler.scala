package scala.tools.nsc.tasty

import TastyRefs._
import TastyName.SignedName

import scala.annotation.switch
import scala.collection.mutable
import scala.reflect.io.AbstractFile
import scala.reflect.internal.Variance
import scala.util.chaining._
import scala.util.control.NonFatal

/** Unpickler for typed trees
 *  @param reader              the reader from which to unpickle
 *  @param splices
 */
class TreeUnpickler[Tasty <: TastyUniverse](
    reader: TastyReader,
    nameAtRef: NameRef => TastyName,
    splices: Seq[Any])(implicit
    val tasty: Tasty) { self =>
  import tasty._, FlagSets._
  import TastyFormat._
  import TreeUnpickler._
  import MaybeCycle._
  import TastyFlags._
  import TastyModes._
  import Signature._

  @inline
  final protected def assertTasty(cond: Boolean, msg: => String): Unit =
    if (!cond) unsupportedError(msg)

  /** A map from addresses of definition entries to the symbols they define */
  private val symAtAddr = new mutable.HashMap[Addr, Symbol]

  /** A temporary map from addresses of definition entries to the trees they define.
   *  Used to remember trees of symbols that are created by a completion. Emptied
   *  once the tree is inlined into a larger tree.
   */
  private val cycleAtAddr = new mutable.HashMap[Addr, MaybeCycle]

  /** A map from addresses of type entries to the types they define.
   *  Currently only populated for types that might be recursively referenced
   *  from within themselves (i.e. RecTypes, LambdaTypes).
   */
  private val typeAtAddr = new mutable.HashMap[Addr, Type]

  /** The root symbol denotation which are defined by the Tasty file associated with this
   *  TreeUnpickler. Set by `enterTopLevel`.
   */
  private[this] var roots: Set[Symbol] = _

  /** The root symbols that are defined in this Tasty file. This
   *  is a subset of `roots.map(_.symbol)`.
   */
  private[this] var seenRoots: Set[Symbol] = Set()

  /** The root owner tree. See `OwnerTree` class definition. Set by `enterTopLevel`. */
  private[this] var ownerTree: OwnerTree = _

  //---------------- unpickling trees ----------------------------------------------------------------------------------

  private def registerSym(addr: Addr, sym: Symbol)(implicit ctx: Context) = {
    ctx.log(s"registered ${showSym(sym)} in ${sym.owner} at $addr")
    symAtAddr(addr) = sym
  }

  /** Enter all toplevel classes and objects into their scopes
   */
  def enter(classRoot: Symbol, moduleRoot: Symbol)(implicit ctx: Context): Unit = {
    this.roots = Set(moduleRoot, classRoot)
    val rdr = new TreeReader(reader).fork
    ownerTree = new OwnerTree(NoAddr, 0, rdr.fork, reader.endAddr)
    if (rdr.isTopLevel)
      rdr.indexStats(reader.endAddr)
  }

  private def completeClassTpe1(implicit ctx: Context): ClassSymbol = {
    val cls = ctx.owner.asClass
    val assumedSelfType =
      if (cls.is(Module) && cls.owner.isClass) mkSingleType(cls.owner.thisType, cls.sourceModule)
      else noType
    cls.info = mkClassInfoType(cls.completer.parents, cls.completer.decls, assumedSelfType.typeSymbolDirect)
    cls
  }

  class Completer(reader: TastyReader, tastyFlagSet: TastyFlagSet)(implicit ctx: Context) extends TastyLazyType { self =>
    import reader._

    self.withTastyFlagSet(tastyFlagSet)

    override def complete(sym: Symbol): Unit = {
      // implicit assertion that the completion is done by the same mirror that loaded owner
      cycleAtAddr(currentAddr) = withPhaseNoLater(picklerPhase) {
        new TreeReader(reader).readIndexedMember()
      }
    }
  }

  /** A missing completer - from dotc */
  trait NoCompleter extends TastyLazyType {
    override def complete(sym: Symbol): Unit = throw new UnsupportedOperationException("complete")
  }

  class TreeReader(val reader: TastyReader) {
    import reader._

    def forkAt(start: Addr): TreeReader = new TreeReader(subReader(start, endAddr))
    def fork: TreeReader = forkAt(currentAddr)

    def skipTree(tag: Int): Unit =
      if (tag >= firstLengthTreeTag) goto(readEnd())
      else if (tag >= firstNatASTTreeTag) { readNat(); skipTree() }
      else if (tag >= firstASTTreeTag) skipTree()
      else if (tag >= firstNatTreeTag) readNat()

    def skipTree(): Unit = skipTree(readByte())

    def skipParams(): Unit =
      while ({
        val tag = nextByte
        tag == PARAM || tag == TYPEPARAM || tag == PARAMEND
      }) skipTree()

    def skipTypeParams(): Unit =
      while (nextByte === TYPEPARAM) skipTree()

    /** Record all directly nested definitions and templates in current tree
     *  as `OwnerTree`s in `buf`.
     *  A complication concerns member definitions. These are lexically nested in a
     *  Template node, but need to be listed separately in the OwnerTree of the enclosing class
     *  in order not to confuse owner chains.
     */
    def scanTree(buf: mutable.ListBuffer[OwnerTree], mode: MemberDefMode = AllDefs): Unit = {
      val start = currentAddr
      val tag = readByte()
      tag match {
        case VALDEF | DEFDEF | TYPEDEF | TYPEPARAM | PARAM | TEMPLATE =>
          val end = readEnd()
          for (i <- 0 until numRefs(tag)) readNat()
          if (tag === TEMPLATE) {
            // Read all member definitions now, whereas non-members are children of
            // template's owner tree.
            val nonMemberReader = fork
            scanTrees(buf, end, MemberDefsOnly)
            buf += new OwnerTree(start, tag, nonMemberReader, end)
          }
          else if (mode != NoMemberDefs)
            buf += new OwnerTree(start, tag, fork, end)
          goto(end)
        case tag =>
          if (mode === MemberDefsOnly) skipTree(tag)
          else if (tag >= firstLengthTreeTag) {
            val end = readEnd()
            val nrefs = numRefs(tag)
            if (nrefs < 0) {
              for (i <- nrefs until 0) scanTree(buf)
              goto(end)
            }
            else {
              for (_ <- 0 until nrefs) readNat()
              if (tag === BIND) {
                // a Bind is never the owner of anything, so we set `end = start`
                buf += new OwnerTree(start, tag, fork, end = start)
              }

              scanTrees(buf, end)
            }
          }
          else if (tag >= firstNatASTTreeTag) { readNat(); scanTree(buf) }
          else if (tag >= firstASTTreeTag) scanTree(buf)
          else if (tag >= firstNatTreeTag) readNat()
      }
    }

    /** Record all directly nested definitions and templates between current address and `end`
     *  as `OwnerTree`s in `buf`
     */
    def scanTrees(buf: mutable.ListBuffer[OwnerTree], end: Addr, mode: MemberDefMode = AllDefs): Unit = {
      while (currentAddr.index < end.index) scanTree(buf, mode)
      assert(currentAddr.index === end.index)
    }

    /** The next tag, following through SHARED tags */
    def nextUnsharedTag: Int = {
      val tag = nextByte
      if (tag === SHAREDtype || tag === SHAREDterm) {
        val lookAhead = fork
        lookAhead.reader.readByte()
        forkAt(lookAhead.reader.readAddr()).nextUnsharedTag
      }
      else tag
    }

    def readTastyName(): TastyName = nameAtRef(readNameRef())

// ------ Reading types -----------------------------------------------------

    /** Read names in an interleaved sequence of (parameter) names and types/bounds */
    def readParamNames(end: Addr): List[TastyName] =
      until(end) {
        val name = readTastyName()
        skipTree()
        name
      }

    /** Read types or bounds in an interleaved sequence of (parameter) names and types/bounds */
    def readParamTypes[T <: Type](end: Addr)(implicit ctx: Context): List[T] =
      until(end) { readNat(); readType().asInstanceOf[T] }

    /** Read reference to definition and return symbol created at that definition */
    def readSymRef()(implicit ctx: Context): Symbol = symbolAt(readAddr())

    /** The symbol at given address; create a new one if none exists yet */
    def symbolAt(addr: Addr)(implicit ctx: Context): Symbol = symAtAddr.get(addr) match {
      case Some(sym) =>
        sym
      case None =>
        ctx.log(s"No symbol at $addr")
        val sym = forkAt(addr).createSymbol()(ctx.withOwner(ownerTree.findOwner(addr)))
        ctx.log(s"forward reference to $sym")
        sym
    }

    /** The symbol defined by current definition */
    def symbolAtCurrent()(implicit ctx: Context): Symbol = symAtAddr.get(currentAddr) match {
      case Some(sym) =>
        assert(ctx.owner === sym.owner, s"owner discrepancy for ${showSym(sym)}, expected: ${showSym(ctx.owner)}, found: ${showSym(sym.owner)}")
        sym
      case None =>
        createSymbol()
    }

    def readConstant(tag: Int)(implicit ctx: Context): Constant = (tag: @switch) match {
      case UNITconst =>
        Constant(())
      case TRUEconst =>
        Constant(true)
      case FALSEconst =>
        Constant(false)
      case BYTEconst =>
        Constant(readInt().toByte)
      case SHORTconst =>
        Constant(readInt().toShort)
      case CHARconst =>
        Constant(readNat().toChar)
      case INTconst =>
        Constant(readInt())
      case LONGconst =>
        Constant(readLongInt())
      case FLOATconst =>
        Constant(java.lang.Float.intBitsToFloat(readInt()))
      case DOUBLEconst =>
        Constant(java.lang.Double.longBitsToDouble(readLongInt()))
      case STRINGconst =>
        Constant(readTastyName().asSimpleName.raw)
      case NULLconst =>
        Constant(null)
      case CLASSconst =>
        Constant(readType())
      case ENUMconst =>
        Constant(readTypeRef().termSymbol)
    }

    /** Read a type */
    def readType()(implicit ctx: Context): Type = {
      val start = currentAddr
      val tag = readByte()
      ctx.log(s"reading type ${astTagToString(tag)} at $start")

      def registeringTypeWith[T](tp: Type, op: => T): T = {
        typeAtAddr(start) = tp
        op
      }

      def readLengthType(): Type = {
        val end = readEnd()

        def readMethodic[N <: Name, PInfo <: Type, LT <: LambdaType]
            (companion: LambdaTypeCompanion[N, PInfo, LT], nameMap: TastyName => N)(implicit ctx: Context): Type = {
          val result = typeAtAddr.getOrElse(start, {
            val nameReader = fork
            nameReader.skipTree() // skip result
            val paramReader = nameReader.fork
            val paramNames = nameReader.readParamNames(end).map(nameMap)
            companion(paramNames)(
              pt => typeAtAddr(start) = pt,
              () => paramReader.readParamTypes[PInfo](end),
              () => readType()
            ).tap(typeAtAddr(start) = _)
          })
          goto(end)
          result
        }

        def readVariances(tp: Type): Type = tp match {
          case tp: LambdaPolyType if currentAddr != end =>
            val vs = until(end) {
              readByte() match {
                case STABLE => Variance.Invariant
                case COVARIANT => Variance.Covariant
                case CONTRAVARIANT => Variance.Contravariant
              }
            }
            tp.withVariances(vs)
          case _ => tp
        }

        val result =
          (tag: @switch) match {
            case TERMREFin =>
              val name   = readTastyName()
              val prefix = readType()
              val space  = readType()
              selectTerm(prefix, space, name)
            case TYPEREFin =>
              val name   = readTastyName()
              val prefix = readType()
              val space  = readType()
              selectType(prefix, space, name)
            case REFINEDtype =>
              val tname  = readTastyName()
              val parent = readType()
              val refinementClass = ctx.owner match {
                case enclosingRefinement: RefinementClassSymbol =>
                  enclosingRefinement.rawInfo match {
                    case EmptyRecTypeInfo => mkRefinedTypeWith(parent :: Nil, enclosingRefinement, mkScope)
                    case _                => ()
                  }
                  enclosingRefinement
                case _ => parent match {
                  case nestedRefinement: RefinedType => nestedRefinement.typeSymbol
                  case _                             => ctx.newRefinedClassSymbol(noPosition)
                }
              }
              val decl = {
                val isTerm     = nextUnsharedTag !== TYPEBOUNDS
                val name       = encodeTastyName(tname, isTerm)
                val tpe        = readType()
                val overridden = parent.member(name)
                val isOverride = isSymbol(overridden)
                var flags      = if (isOverride && overridden.isType) Override else emptyFlags
                val info = {
                  if (isTerm) {
                    flags |= Method | Deferred
                    tpe match {
                      case byNameType: TypeRef // nullary method
                      if byNameType.sym == defn.ByNameParamClass && byNameType.args.length == 1 =>
                        mkNullaryMethodType(byNameType.args.head)
                      case poly: PolyType if poly.resultType.paramss.isEmpty =>
                        mkPolyType(poly.typeParams, mkNullaryMethodType(poly.resultType))
                      case _:MethodType | _:PolyType => tpe
                      case _ => // val, which is not stable if structural. Dotty does not support vars
                        if (isOverride && overridden.is(Stable)) flags |= Stable
                        mkNullaryMethodType(tpe)
                    }
                  }
                  else {
                    if (tpe.isInstanceOf[TypeBounds]) flags |= Deferred
                    tpe
                  }
                }
                ctx.newSymbol(refinementClass, name, flags, info)
              }
              parent match {
                case nested: RefinedType =>
                  mkRefinedTypeWith(nested.parents, refinementClass, nested.decls.cloneScope.tap(_.enter(decl)))
                case _ =>
                  mkRefinedTypeWith(parent :: Nil, refinementClass, mkScope(decl))
              }
            case APPLIEDtype =>
              boundedAppliedType(readType(), until(end)(readType()))
            case TYPEBOUNDS =>
              val lo = readType()
              if (nothingButMods(end))
                typeRef(readVariances(lo))
              else TypeBounds.bounded(lo, readVariances(readType()))
            case ANNOTATEDtype =>
              mkAnnotatedType(readType(), mkAnnotation(readTerm()))
            case ANDtype =>
              mkIntersectionType(readType(), readType())
            case ORtype =>
              unionIsUnsupported
            case SUPERtype =>
              mkSuperType(readType(), readType())
            // case MATCHtype =>
            //   MatchType(readType(), readType(), until(end)(readType()))
            case POLYtype =>
              readMethodic(PolyType, encodeTastyNameAsType)
            case METHODtype =>
              readMethodic(MethodType, encodeTastyNameAsTerm)
            // case ERASEDMETHODtype =>
            //   readMethodic(ErasedMethodType, _.toTermName)
            // case ERASEDGIVENMETHODtype =>
            //   readMethodic(ErasedContextualMethodType, _.toTermName)
            case IMPLICITMETHODtype | GIVENMETHODtype =>
              readMethodic(ImplicitMethodType, encodeTastyNameAsTerm)
            case TYPELAMBDAtype =>
              readMethodic(HKTypeLambda, encodeTastyNameAsType)
            case PARAMtype => // reference to a type parameter within a LambdaType
              readTypeRef().typeParams(readNat()).ref
          }
        assert(currentAddr === end, s"$start $currentAddr $end ${astTagToString(tag)}")
        result
      }

      def readSimpleType(): Type = {
        (tag: @switch) match {
          case TYPEREFdirect =>
            readSymRef().termRef
          case TERMREFdirect =>
            readSymRef().singleRef
          case TYPEREFsymbol | TERMREFsymbol =>
            readSymNameRef()
          case TYPEREFpkg =>
            readPackageRef().moduleClass.ref
          case TERMREFpkg =>
            readPackageRef().termRef
          case TYPEREF =>
            val name   = readTastyName()
            val prefix = readType()
            selectType(prefix, prefix, name)
          case TERMREF =>
            val name   = readTastyName()
            val prefix = readType()
            selectTerm(prefix, prefix, name)
          case THIS =>
            val sym = readType() match {
              case tpe: TypeRef => tpe.sym
              case tpe: SingleType => tpe.sym
            }
            mkThisType(sym)
          case RECtype =>
            typeAtAddr.get(start) match {
              case Some(tp) =>
                skipTree(tag)
                tp
              case None =>
                mkRecType(rt =>
                  registeringTypeWith(rt, readType()(ctx.withOwner(rt.refinementClass)))
                ).tap(typeAtAddr(start) = _)
            }
          case RECthis =>
            readTypeRef().asInstanceOf[RecType].recThis
          case SHAREDtype =>
            val ref = readAddr()
            typeAtAddr.getOrElseUpdate(ref, forkAt(ref).readType())
          case BYNAMEtype =>
            val tpe = readType()
            defn.byNameType(tpe) // ExprType(readType())
          case _ =>
            mkConstantType(readConstant(tag))
        }
      }
      if (tag < firstLengthTreeTag) readSimpleType() else readLengthType()
    }

    private def readSymNameRef()(implicit ctx: Context): Type = {
      val sym    = readSymRef()
      val prefix = readType()
      // TODO [tasty]: restore this if github:lampepfl/dotty/tests/pos/extmethods.scala causes infinite loop
      // prefix match {
        // case prefix: ThisType if (prefix.sym `eq` sym.owner) && sym.isTypeParameter /*&& !sym.is(Opaque)*/ =>
        //   mkAppliedType(sym, Nil)
        //   // without this precaution we get an infinite cycle when unpickling pos/extmethods.scala
        //   // the problem arises when a self type of a trait is a type parameter of the same trait.
      // }
      prefixedRef(prefix, sym)
    }

    private def readPackageRef()(implicit ctx: Context): TermSymbol = {
      val name = encodeTastyNameAsTerm(readTastyName())
      if (name === nme.ROOT || name === nme.ROOTPKG) ctx.loadingMirror.RootPackage
      else if (name === nme.EMPTY_PACKAGE_NAME) ctx.loadingMirror.EmptyPackage
      else ctx.requiredPackage(name)
    }

    def readTypeRef(): Type = typeAtAddr(readAddr())

    def readTypeAsTypeRef()(implicit ctx: Context): TypeRef = readType().asInstanceOf[TypeRef]

// ------ Reading definitions -----------------------------------------------------

    private def nothingButMods(end: Addr): Boolean =
      currentAddr === end || isModifierTag(nextByte)

    private def normalizeFlags(tag: Int, owner: Symbol, givenFlags: FlagSet, tastyFlags: TastyFlagSet, name: Name, tname: TastyName, isAbsType: Boolean, isClass: Boolean, rhsIsEmpty: Boolean)(implicit ctx: Context): FlagSet = {
      val lacksDefinition =
        rhsIsEmpty &&
          name.isTermName && !isConstructorName(name) && !givenFlags.isOneOf(TermParamOrAccessor) ||
        isAbsType ||
        tastyFlags.is(Opaque) && !isClass
      var flags = givenFlags
      if (lacksDefinition && tag != PARAM) flags |= Deferred
      if (tag === DEFDEF) flags |= Method
      if (tag === VALDEF) {
        if (flags.not(Mutable)) flags |= Stable
        if (owner.flags.is(Trait)) flags |= Accessor
      }
      if (givenFlags.is(Module))
        flags = flags | (if (tag === VALDEF) ModuleCreationFlags else ModuleClassCreationFlags)
      if (ctx.owner.isClass) {
        if (tag === TYPEPARAM) flags |= Param
        else if (tag === PARAM) {
          flags |= ParamAccessor | Accessor | Stable
          if (!rhsIsEmpty) // param alias
            flags |= Method
        }
      }
      else if (isParamTag(tag)) flags |= Param
      if (tname.isDefaultName || flags.is(Param) && owner.isMethod && owner.is(DefaultParameterized)) {
        flags |= DefaultParameterized
      }
      flags
    }

    def isAbstractType(ttag: Int)(implicit ctx: Context): Boolean = nextUnsharedTag match {
      case LAMBDAtpt =>
        val rdr = fork
        rdr.reader.readByte()  // tag
        rdr.reader.readNat()   // length
        rdr.skipParams()       // tparams
        rdr.isAbstractType(rdr.nextUnsharedTag)
      case TYPEBOUNDS | TYPEBOUNDStpt => true
      case _ => false
    }

    /** Create symbol of definition node and enter in symAtAddr map
     *  @return  the created symbol
     */
    def createSymbol()(implicit ctx: Context): Symbol = nextByte match {
      case VALDEF | DEFDEF | TYPEDEF | TYPEPARAM | PARAM =>
        createMemberSymbol()
      case BIND =>
        createBindSymbol()
      case TEMPLATE =>
        val localDummy = ctx.newLocalDummy
        registerSym(currentAddr, localDummy)
        localDummy
      case tag =>
        throw new Error(s"illegal createSymbol at $currentAddr, tag = $tag")
    }

    private def createBindSymbol()(implicit ctx: Context): Symbol = {
      val start = currentAddr
      readByte() // tag
      readEnd()  // end
      val name = encodeTastyName(readTastyName(), nextUnsharedTag !== TYPEBOUNDS)
      val typeReader = fork
      val completer = new TastyLazyType {
        override def complete(sym: Symbol): Unit =
          sym.info = typeReader.readType()
      }
      val sym = ctx.newSymbol(ctx.owner, name, FlagSets.Case, completer)
      registerSym(start, sym)
      sym
    }

    /** Create symbol of member definition or parameter node and enter in symAtAddr map
     *  @return  the created symbol
     */
    def createMemberSymbol()(implicit ctx: Context): Symbol = {
      val start = currentAddr
      val tag = readByte()
      def isTypeTag = tag === TYPEDEF || tag === TYPEPARAM
      def isTerm = !isTypeTag
      val end = readEnd()
      val tname: TastyName = readTastyName()
      val name: Name = encodeTastyName(tname, isTerm)
      skipParams()
      val ttag = nextUnsharedTag
      val isAbsType = isAbstractType(ttag)
      val isClass = ttag === TEMPLATE
      val templateStart = currentAddr
      skipTree() // tpt
      val rhsIsEmpty = nothingButMods(end)
      if (!rhsIsEmpty) skipTree()
      val (givenFlags, tastyFlagSet, annotFns, privateWithin) =
        readModifiers(end, readTypedAnnot, readTypedWithin, noSymbol)
      val flags = normalizeFlags(tag, ctx.owner, givenFlags, tastyFlagSet, name, tname, isAbsType, isClass, rhsIsEmpty)
      def showFlags = {
        if (!tastyFlagSet)
          show(flags)
        else if (isEmpty(givenFlags))
          show(tastyFlagSet)
        else
          show(flags) + " | " + show(tastyFlagSet)
      }
      def isModuleClass   = flags.is(Module) && isClass
      def isTypeParameter = flags.is(Param) && isTypeTag
      def canEnterInClass = !isModuleClass && !isTypeParameter
      ctx.log {
        val msg = if (isSymbol(privateWithin)) s" private within $privateWithin" else ""
        s"""creating symbol ${name}${msg} at $start with flags $showFlags"""
      }
      def adjustIfModule(completer: TastyLazyType) =
        if (isModuleClass) ctx.adjustModuleClassCompleter(completer, name) else completer
      val sym = {
        if (isTypeTag && nme.CONSTRUCTOR === ctx.owner.name.toTermName && tag === TYPEPARAM) {
          ctx.owner.owner.typeParams.find(name === _.name).getOrElse {
            throw new AssertionError(s"${ctx.owner.owner} has no type params.")
          }
        }
        else {
          val completer = adjustIfModule(new Completer(subReader(start, end), tastyFlagSet))
          roots.find(root => (root.owner `eq` ctx.owner) && name === root.name) match {
            case Some(found) =>
              val rootd   = if (isModuleClass) found.linkedClassOfClass else found
              ctx.adjustSymbol(rootd, flags, completer, privateWithin) // dotty "removes one completion" here from the flags, which is not possible in nsc
              seenRoots += rootd
              ctx.log(s"replaced info of ${showSym(rootd)}")
              rootd
            case _ =>
              if (isModuleClass)
                ctx.adjustSymbol(completer.sourceModule.moduleClass, flags, completer, privateWithin)
              else if (isClass)
                ctx.newClassSymbol(ctx.owner, name.toTypeName, flags, completer, privateWithin)
              else
                ctx.newSymbol(ctx.owner, name, flags, completer, privateWithin)
          }
        }
      }
      sym.setAnnotations(annotFns.map(_(sym)))
      ctx.owner match {
        case cls: ClassSymbol if canEnterInClass =>
          val decls = cls.rawInfo.decls
          if (allowsOverload(sym)) decls.enter(sym)
          else decls.enterIfNew(sym)
        case _ =>
      }
      registerSym(start, sym)
      if (isClass) {
        sym.completer.withDecls(mkScope)
        val localCtx = ctx.withOwner(sym)
        forkAt(templateStart).indexTemplateParams()(localCtx)
      }
      goto(start)
      sym
    }

    private def allowsOverload(sym: Symbol) = ( // TODO [tasty]: taken from Namer. Added module symbols
      (sym.isSourceMethod || sym.isModule) && sym.owner.isClass && !sym.isTopLevel
    )

    /** Read modifier list into triplet of flags, annotations and a privateWithin
     *  boundary symbol.
     */
    def readModifiers[WithinType, AnnotType]
        (end: Addr, readAnnot: Context => Symbol => AnnotType, readWithin: Context => WithinType, defaultWithin: WithinType)
        (implicit ctx: Context): (FlagSet, TastyFlagSet, List[Symbol => AnnotType], WithinType) = {
      var tastyFlagSet = emptyTastyFlags
      var flags = emptyFlags
      var annotFns: List[Symbol => AnnotType] = Nil
      var privateWithin = defaultWithin
      while (currentAddr.index != end.index) {
        def addFlag(flag: FlagSet) = {
          flags |= flag
          readByte()
        }
        def addTastyFlag(flag: TastyFlagSet) = {
          tastyFlagSet |= flag
          readByte()
        }
        nextByte match {
          case PRIVATE => addFlag(Private)
          case INTERNAL => addTastyFlag(Internal)
          case PROTECTED => addFlag(Protected)
          case ABSTRACT =>
            readByte()
            nextByte match {
              case OVERRIDE => addFlag(AbsOverride)
              case _ => flags |= Abstract
            }
          case FINAL => addFlag(Final)
          case SEALED => addFlag(Sealed)
          case CASE => addFlag(Case)
          case IMPLICIT => addFlag(Implicit)
          case ERASED => addTastyFlag(Erased)
          case LAZY => addFlag(Lazy)
          case OVERRIDE => addFlag(Override)
          case INLINE => addTastyFlag(Inline)
          case INLINEPROXY => addTastyFlag(InlineProxy)
          case MACRO => addTastyFlag(TastyMacro)
          case OPAQUE => addTastyFlag(Opaque)
          case STATIC => addFlag(JavaStatic)
          case OBJECT => addFlag(Module)
          case TRAIT => addFlag(Trait)
          case ENUM => addTastyFlag(Enum)
          case LOCAL => addFlag(Local)
          case SYNTHETIC => addFlag(Synthetic)
          case ARTIFACT => addFlag(Artifact)
          case MUTABLE => addFlag(Mutable)
          case FIELDaccessor => addFlag(Accessor)
          case CASEaccessor => addFlag(CaseAccessor)
          case COVARIANT => addFlag(Covariant)
          case CONTRAVARIANT => addFlag(Contravariant)
          case SCALA2X => addTastyFlag(Scala2x)
          case DEFAULTparameterized => addFlag(DefaultParameterized)
          case STABLE => addFlag(Stable)
          case EXTENSION => addTastyFlag(Extension)
          case GIVEN => addFlag(Implicit)
          case PARAMsetter => addFlag(ParamAccessor)
          case PARAMalias => addTastyFlag(SuperParamAlias)
          case EXPORTED => addTastyFlag(Exported)
          case OPEN => addTastyFlag(Open)
          case PRIVATEqualified =>
            readByte()
            privateWithin = readWithin(ctx)
          case PROTECTEDqualified =>
            addFlag(Protected)
            privateWithin = readWithin(ctx)
          case ANNOTATION =>
            annotFns = readAnnot(ctx) :: annotFns
          case tag =>
            assert(assertion = false, s"illegal modifier tag ${astTagToString(tag)} at $currentAddr, end = $end")
        }
      }
      (flags, tastyFlagSet, if (ctx.ignoreAnnotations) Nil else annotFns.reverse, privateWithin)
    }

    private val readTypedWithin: Context => Symbol = implicit ctx => readType().typeSymbolDirect

    private val readTypedAnnot: Context => Symbol => Annotation = { implicit ctx =>
      readByte()
      val end = readEnd()
      val tp = readType()
      val lazyAnnotTree = readLaterWithOwner(end, rdr => ctx => rdr.readTerm()(ctx))
      owner => Annotation.deferredSymAndTree(owner)(tp.typeSymbolDirect)(lazyAnnotTree(owner))
    }

    /** Create symbols for the definitions in the statement sequence between
     *  current address and `end`.
     */
    def indexStats(end: Addr)(implicit ctx: Context): Unit = {
      while (currentAddr.index < end.index) {
        nextByte match {
          case tag @ (VALDEF | DEFDEF | TYPEDEF | TYPEPARAM | PARAM) =>
            symbolAtCurrent()
            skipTree()
          case IMPORT =>
            skipTree()
          case PACKAGE =>
            processPackage(end => implicit ctx => indexStats(end))
          case _ =>
            skipTree()
        }
      }
      assert(currentAddr.index === end.index)
    }

    /** Process package with given operation `op`. The operation takes as arguments
     *   - a `RefTree` representing the `pid` of the package,
     *   - an end address,
     *   - a context which has the processed package as owner
     */
    def processPackage[T](op: Addr => Context => T)(implicit ctx: Context): T = {
      readByte()
      val end = readEnd()
      val tpe = readTypeAsTypeRef()
      op(end)(ctx.withOwner(tpe.typeSymbolDirect.moduleClass))
    }

    /** Create symbols the longest consecutive sequence of parameters with given
     *  `tag` starting at current address.
     */
    def indexParams(tag: Int)(implicit ctx: Context): Unit =
      while (nextByte === tag) {
        symbolAtCurrent()
        skipTree()
      }

    /** Create symbols for all type and value parameters of template starting
     *  at current address.
     */
    def indexTemplateParams()(implicit ctx: Context): Unit = {
      assert(readByte() === TEMPLATE)
      readEnd()
      indexParams(TYPEPARAM)
      indexParams(PARAM)
    }

    def readIndexedMember()(implicit ctx: Context): NoCycle = cycleAtAddr.remove(currentAddr) match {
      case Some(maybeCycle) =>
        assert(maybeCycle ne Tombstone, s"Cyclic reference while unpickling definition at address ${currentAddr.index} in file ${ctx.source}")
        skipTree()
        maybeCycle.asInstanceOf[NoCycle]
      case _ =>
        val start = currentAddr
        cycleAtAddr(start) = Tombstone
        val noCycle = readNewMember()
        cycleAtAddr.remove(start)
        noCycle
    }

    private def readNewMember()(implicit ctx: Context): NoCycle = {
      val symAddr = currentAddr
      val sym     = symAtAddr(symAddr)
      val tag     = readByte()
      val end     = readEnd()
      val tname   = readTastyName()
      val name    = encodeTastyName(tname, sym.isTerm)

      ctx.log(s"completing member $name at $symAddr. ${showSym(sym)}")

      val completer = sym.completer

      def readParamss(implicit ctx: Context): List[List[NoCycle/*ValDef*/]] = nextByte match {
        case PARAM | PARAMEND =>
          readParams[NoCycle](PARAM) ::
            (if (nextByte == PARAMEND) { readByte(); readParamss } else Nil)

        case _ => Nil
      }

      try {
        val localCtx = ctx.withOwner(sym)
        tag match {
          case DEFDEF =>
            val supported = Extension | Inline | TastyMacro
            val unsupported = completer.tastyFlagSet &~ supported
            assertTasty(!unsupported, s"flags on $sym: ${show(unsupported)}")
            assertTasty(completer.tastyFlagSet.not(Inline), s"inline $sym")
            if (completer.tastyFlagSet.is(Extension)) ctx.log(s"$name is a Scala 3 extension method.")
            val typeParams = {
              if (nme.CONSTRUCTOR === sym.name.toTermName) {
                skipTypeParams()
                sym.owner.typeParams
              }
              else {
                readParams[NoCycle](TYPEPARAM)(localCtx).map(symFromNoCycle)
              }
            }
            val vparamss = readParamss(localCtx)
            val tpt = readTpt()(localCtx)
            val valueParamss = normalizeIfConstructor(vparamss.map(_.map(symFromNoCycle)), name === nme.CONSTRUCTOR)
            val resType = effectiveResultType(sym, typeParams, tpt.tpe)
            sym.info = mkDefDefType(if (name === nme.CONSTRUCTOR) Nil else typeParams, valueParamss, resType)
          case VALDEF => // valdef in TASTy is either a module value or a method forwarder to a local value.
            val isInline = completer.tastyFlagSet.is(Inline)
            val unsupported = completer.tastyFlagSet &~ (Inline | Enum | Extension)
            assertTasty(!unsupported, s"flags on $sym: ${show(unsupported)}")
            val tpe = readTpt()(localCtx).tpe
            if (isInline) assertTasty(isConstantType(tpe), s"inline val ${sym.nameString} with non-constant type $tpe")
            sym.info = {
              if (completer.tastyFlagSet.is(Enum)) mkConstantType(Constant((sym, tpe))).tap(_.typeSymbol.setFlag(Final))
              else if (sym.isMethod) mkNullaryMethodType(tpe)
              else tpe
            }
          case TYPEDEF | TYPEPARAM =>
            val unsupported = completer.tastyFlagSet &~ (Enum | Open | Opaque)
            assertTasty(!unsupported, s"flags on $sym: ${show(unsupported)}")
            if (sym.isClass) {
              sym.owner.ensureCompleted()
              readTemplate(symAddr)(localCtx)
            }
            else {
              sym.info = TypeBounds.empty // needed to avoid cyclic references when unpickling rhs, see https://github.com/lampepfl/dotty/blob/master/tests/pos/i3816.scala
              // sym.setFlag(Provisional) // TODO [tasty]: is there an equivalent in scala 2?
              val rhs = readTpt()(localCtx)
              sym.info = new NoCompleter {}
              // TODO [tasty]: if opaque type alias will be supported, unwrap `type bounds with alias` to bounds and then
              //               refine self type of the owner to be aware of the alias.
              sym.info = rhs.tpe match {
                case bounds @ TypeBounds(lo: PolyType, hi: PolyType) if !(mergeableParams(lo,hi)) =>
                  unsupportedError(s"diverging higher kinded bounds: $sym$bounds")
                case tpe: TypeBounds => normaliseBounds(tpe)
                case tpe             => tpe
              }
              if (sym.is(Param)) sym.resetFlag(Private | Protected)
              // if sym.isOpaqueAlias then sym.typeRef.recomputeDenot() // make sure we see the new bounds from now on
              // sym.resetFlag(Provisional)
            }
          case PARAM =>
            val unsupported = completer.tastyFlagSet &~ SuperParamAlias
            assertTasty(!unsupported, s"flags on parameter $sym: ${show(unsupported)}")
            val tpt = readTpt()(localCtx)
            if (nothingButMods(end) && sym.not(ParamAccessor)) {
              sym.info = tpt.tpe
            }
            else {
              sym.info = mkNullaryMethodType(tpt.tpe)
            }
        }
        ctx.log(s"typed ${showSym(sym)} : ${if (sym.isClass) sym.tpe else sym.info} in owner ${showSym(sym.owner)}")
        goto(end)
        NoCycle(at = symAddr)
      } catch {
        case err: UnsupportedError =>
          sym.info = errorType
          if (ctx.inGlobalScope) err.unsupportedInScopeError
          else throw err
        case NonFatal(err) =>
          sym.info = errorType
          throw err
      }
    }

    private def readTemplate(symAddr: Addr)(implicit ctx: Context): Unit = {
      val cls = completeClassTpe1
      val localDummy = symbolAtCurrent()
      assert(readByte() === TEMPLATE)
      val end = readEnd()

      // ** PARAMETERS **
      ctx.log(s"Template: reading parameters of $cls")
      val tparams = readIndexedParams[NoCycle](TYPEPARAM)
      if (tparams.nonEmpty) {
        cls.info = new PolyType(tparams.map(symFromNoCycle), cls.info)
      }
      readIndexedParams[NoCycle](PARAM) // skip value parameters

      // ** MEMBERS **
      ctx.log(s"Template: indexing members of $cls")
      val bodyIndexer = fork
      while (bodyIndexer.reader.nextByte != DEFDEF) bodyIndexer.skipTree() // skip until primary ctor
      bodyIndexer.indexStats(end)

      // ** PARENTS **
      ctx.log(s"Template: adding parents of $cls")
      val parents = {
        val parentCtx = ctx.withOwner(localDummy).addMode(InParents)
        collectWhile(nextByte != SELFDEF && nextByte != DEFDEF) {
          nextUnsharedTag match {
            case APPLY | TYPEAPPLY | BLOCK => readParentFromTerm()(parentCtx)
            case _ => readTpt()(parentCtx).tpe
          }
        }
      }
      val parentTypes = parents.map { tp =>
        val tpe = tp.dealias
        if (tpe.typeSymbolDirect === defn.ObjectClass) defn.AnyRefTpe
        else tpe
      }

      // ** CREATE EXTENSION METHODS **
      if (parentTypes.head.typeSymbolDirect === defn.AnyValClass) {
        // TODO [tasty]: please reconsider if there is some shared optimised logic that can be triggered instead.
        withPhaseNoLater(extmethodsPhase) {
          // duplicated from scala.tools.nsc.transform.ExtensionMethods
          cls.primaryConstructor.makeNotPrivate(noSymbol)
          for (decl <- cls.info.decls if decl.isMethod) {
            if (decl.isParamAccessor) decl.makeNotPrivate(cls)
            if (nme.CONSTRUCTOR !== decl.name.toTermName) {
              val extensionMeth = decl.newExtensionMethodSymbol(cls.companion, noPosition)
              extensionMeth setInfo extensionMethInfo(cls, extensionMeth, decl.info, cls)
            }
          }
        }
      }

      if (nextByte === SELFDEF) {
        ctx.log(s"Template: adding self-type of $cls")
        readByte()
        readLongNat()
        val selfTpe = readTpt().tpe
        ctx.log(s"Template: self-type is $selfTpe")
        cls.typeOfThis = selfTpe
      }
      cls.info = {
        val classInfo = new ClassInfoType(parentTypes, cls.rawInfo.decls, cls.asType)
        // TODO [tasty]: if support opaque types, refine the self type with any opaque members here
        if (tparams.isEmpty) classInfo
        else new PolyType(tparams.map(symFromNoCycle), classInfo)
      }
      ctx.log(s"Template: Updated info of $cls with parents $parentTypes.")
    }

    def isTopLevel: Boolean = nextByte === IMPORT || nextByte === PACKAGE

    def readIndexedStatAsSym(exprOwner: Symbol)(implicit ctx: Context): NoCycle = nextByte match {
      case TYPEDEF | VALDEF | DEFDEF =>
        readIndexedMember()
      case IMPORT =>
        unsupportedError("IMPORT in expression")
      case PACKAGE =>
        unsupportedError("PACKAGE in expression")
      case _ =>
        skipTree() // readTerm()(ctx.withOwner(exprOwner))
        NoCycle(at = NoAddr)
    }

    def readIndexedStatsAsSyms(exprOwner: Symbol, end: Addr)(implicit ctx: Context): List[NoCycle] =
      until(end)(readIndexedStatAsSym(exprOwner))

    def readStatsAsSyms(exprOwner: Symbol, end: Addr)(implicit ctx: Context): List[NoCycle] = {
      fork.indexStats(end)
      readIndexedStatsAsSyms(exprOwner, end)
    }

    def readIndexedParams[T <: MaybeCycle /*MemberDef*/](tag: Int)(implicit ctx: Context): List[T] =
      collectWhile(nextByte === tag) { readIndexedMember().asInstanceOf[T] }

    def readParams[T <: MaybeCycle /*MemberDef*/](tag: Int)(implicit ctx: Context): List[T] = {
      if (nextByte == tag) {
        fork.indexParams(tag)
        readIndexedParams(tag)
      }
      else {
        Nil
      }
    }

// ------ Reading trees -----------------------------------------------------

    def readTerm()(implicit ctx: Context): Tree = {  // TODO: rename to readTree
      val start = currentAddr
      val tag = readByte()
      ctx.log(s"reading term ${astTagToString(tag)} at $start")

      def readPathTerm(): Tree = {
        goto(start)
        readType() match {
          case path: TypeRef => TypeTree(path)
          case path: SingleType => TypeTree(path)
          case path: ThisType => new This(path.sym.name.toTypeName).setSymbol(path.sym).setType(path)
          case path: ConstantType => Literal(path.value).setType(path)
        }
      }

      def readQualId(): (Ident, TypeRef) = {
        val qual = readTerm().asInstanceOf[Ident]
        (Ident(qual.name), qual.tpe.asInstanceOf[TypeRef])
      }

      def completeSelect(name: TastyName, isTerm: Boolean)(implicit ctx: Context): Select = {
        val localCtx = ctx.selectionCtx(name)
        val qual     = readTerm()(localCtx)
        val qualType = qual.tpe
        Select(qual, encodeTastyName(name, isTerm)).setType(namedMemberOfPrefix(qualType, name, isTerm)(localCtx))
      }

      def readSimpleTerm(): Tree = tag match {
        case SHAREDterm =>
          forkAt(readAddr()).readTerm()
        case IDENT =>
          Ident(encodeTastyNameAsTerm(readTastyName())).setType(readType())
        case IDENTtpt =>
          Ident(encodeTastyNameAsType(readTastyName())).setType(readType())
        case SELECT =>
          completeSelect(readTastyName(), isTerm = true)
        case SELECTtpt =>
          completeSelect(readTastyName(), isTerm = false)
        case QUALTHIS =>
          val (qual, tref) = readQualId()
          new This(qual.name.toTypeName).setType(mkThisType(tref.sym))
        case NEW =>
          val tpt = readTpt()
          New(tpt).setType(tpt.tpe)
        case THROW =>
          Throw(readTerm()).setType(defn.NothingTpe)
        case SINGLETONtpt =>
          val tpt = readTerm()
          SingletonTypeTree(tpt).setType(tpt.tpe)
        case BYNAMEtpt =>
          val tpt = readTpt()
          mkFunctionTypeTree(Nil, tpt).setType(defn.byNameType(tpt.tpe))
        case NAMEDARG =>
          val name  = encodeTastyNameAsTerm(readTastyName())
          val value = readTerm()
          NamedArg(name, value).setType(value.tpe)
        case _ =>
          readPathTerm()
      }

      def readLengthTerm(): Tree = {
        val end = readEnd()
        val result =
          (tag: @switch) match {
            case SUPER =>
              val qual = readTerm()
              val (mixId, mixTpe) = ifBefore(end)(readQualId(), (untpd.EmptyTypeIdent, noType))
              val owntype = (
                if (!mixId.isEmpty) mixTpe
                // else if (context.inSuperInit) qual.tpe.firstParent // sourced from Typer
                else mkIntersectionType(qual.tpe.parents)
              )
              Super(qual, mixId.name.toTypeName).setType(mkSuperType(qual.tpe, owntype))
            case APPLY =>
              val fn = readTerm()
              val args = until(end)(readTerm())
              Apply(fn, args).setType(fn.tpe.dealiasWiden.finalResultType)
            case TYPEAPPLY =>
              val term = readTerm()
              val args = until(end)(readTpt())
              TypeApply(term, args).setType(term.tpe.resultType.substituteTypes(term.tpe.typeParams, args.map(_.tpe)))
            case TYPED =>
              val expr = readTerm()
              val tpt = readTpt()
              Typed(expr, tpt).setType(tpt.tpe)
            case ASSIGN =>
              Assign(readTerm(), readTerm()).setType(defn.UnitTpe)
            case BLOCK => // TODO [tasty]: when we support annotation trees, we need to restore readIndexedMember to create trees, and then put the stats in the block.
              val exprReader = fork
              skipTree()
              until(end)(skipTree()) //val stats = readStats(ctx.owner, end)
              val expr = exprReader.readTerm()
              expr//Block(stats, expr).setType(expr.tpe)
//            case INLINED =>
//              val exprReader = fork
//              skipTree()
//              def maybeCall = nextUnsharedTag match {
//                case VALDEF | DEFDEF => EmptyTree
//                case _ => readTerm()
//              }
//              val call = ifBefore(end)(maybeCall, EmptyTree)
//              val bindings = readStats(ctx.owner, end).asInstanceOf[List[ValOrDefDef]]
//              val expansion = exprReader.readTerm() // need bindings in scope, so needs to be read before
//              Inlined(call, bindings, expansion)
            case IF =>
              if (nextByte === INLINE) {
                unsupportedError("inline if")
                // readByte()
                // InlineIf(readTerm(), readTerm(), readTerm())
              }
              else {
                val cond = readTerm()
                val thenp = readTerm()
                val elsep = readTerm()
                If(cond, thenp, elsep).setType(lub(thenp.tpe, elsep.tpe))
              }
            case LAMBDA =>
              unsupportedError("LAMBDA")
              // val meth = readTerm()
              // val tpt = ifBefore(end)(readTpt(), emptyTree)
              // Closure(Nil, meth, tpt)
            case MATCH =>
              if (nextByte === IMPLICIT) {
                readByte()
                readCases(end) //InlineMatch(EmptyTree, readCases(end))
                unsupportedError("implicit match")
              }
              else if (nextByte === INLINE) {
                readByte()
                readTerm(); readCases(end) // InlineMatch(readTerm(), readCases(end))
                unsupportedError("inline match")
              }
              else {
                val sel = readTerm()
                val cases = readCases(end)
                Match(sel, cases).setType(lub(cases.map(_.tpe)))
              }
//            case RETURN =>
//              val from = readSymRef()
//              val expr = ifBefore(end)(readTerm(), EmptyTree)
//              Return(expr, Ident(from.termRef))
            case WHILE =>
              WhileDo(readTerm(), readTerm())
            case TRY =>
              val body = readTerm()
              val cases = readCases(end)
              val finalizer = ifBefore(end)(readTerm(), emptyTree)
              Try(body, cases, finalizer).setType(lub(cases.map(_.tpe)))
//            case SELECTouter =>
//              val levels = readNat()
//              readTerm().outerSelect(levels, SkolemType(readType()))
            case REPEATED =>
              val elemtpt = readTpt()
              SeqLiteral(until(end)(readTerm()), elemtpt).setType(elemtpt.tpe)
            case BIND =>
              val sym = symAtAddr.getOrElse(start, forkAt(start).createSymbol())
              readTastyName()
              readType()
              val body = readTerm()
              Bind(sym, body).setType(body.tpe)
            case ALTERNATIVE =>
              val alts = until(end)(readTerm())
              Alternative(alts).setType(lub(alts.map(_.tpe)))
            case UNAPPLY =>
              val fn = readTerm()
              val implicitArgs =
                collectWhile(nextByte === IMPLICITarg) {
                  readByte()
                  readTerm()
                }
              val patType = readType()
              val argPats = until(end)(readTerm())
              UnApply(fn, implicitArgs, argPats, patType)
            case REFINEDtpt =>
              val refineCls = symAtAddr.getOrElse(start, ctx.newRefinedClassSymbol(noPosition/*coordAt(start)*/))
              registerSym(start, refineCls)
              typeAtAddr(start) = refineCls.ref
              val refinement = mkRefinedType(readTpt().tpe :: Nil, refineCls)
              readStatsAsSyms(refineCls, end)(ctx.withOwner(refineCls))
              TypeTree(refinement)
            case APPLIEDtpt =>
              // If we do directly a tpd.AppliedType tree we might get a
              // wrong number of arguments in some scenarios reading F-bounded
              // types. This came up in #137 of collection strawman.
              val tycon   = readTpt()
              val args    = until(end)(readTpt())
              if (tycon.tpe === AndType) {
                val tpe = mkIntersectionType(args.map(_.tpe))
                CompoundTypeTree(args).setType(tpe)
              } else {
                AppliedTypeTree(tycon, args).setType(boundedAppliedType(tycon.tpe, args.map(_.tpe)))
              }
            case ANNOTATEDtpt =>
              val tpt = readTpt()
              val annot = readTerm()
              defn.repeatedAnnotationClass match {
                case Some(repeated)
                if annot.tpe.typeSymbol === repeated
                && tpt.tpe.typeSymbol.isSubClass(defn.SeqClass)
                && tpt.tpe.typeArgs.length == 1 =>
                  TypeTree(defn.scalaRepeatedType(tpt.tpe.typeArgs.head))
                case _ =>
                  Annotated(tpt, annot).setType(mkAnnotatedType(tpt.tpe, mkAnnotation(annot)))
              }
            case LAMBDAtpt =>
              val tparams = readParams[NoCycle](TYPEPARAM)
              val body    = readTpt()
              TypeTree(mkLambdaFromParams(tparams.map(symFromNoCycle), body.tpe)) //LambdaTypeTree(tparams, body)
//            case MATCHtpt =>
//              val fst = readTpt()
//              val (bound, scrut) =
//                if (nextUnsharedTag === CASEDEF) (EmptyTree, fst) else (fst, readTpt())
//              MatchTypeTree(bound, scrut, readCases(end))
            case TYPEBOUNDStpt =>
              val lo    = readTpt()
              val hi    = if (currentAddr == end) lo else readTpt()
              val alias = if (currentAddr == end) emptyTree else readTpt()
              if (alias != emptyTree) alias // only for opaque type alias
              else TypeBoundsTree(lo, hi).setType(TypeBounds.bounded(lo.tpe, hi.tpe))
//            case HOLE =>
//              readHole(end, isType = false)
//            case _ =>
//              readPathTerm()
          }
        assert(currentAddr === end, s"$start $currentAddr $end ${astTagToString(tag)}")
        result
      }

      if (tag < firstLengthTreeTag) readSimpleTerm() else readLengthTerm() // dotty sets span of tree to start
    }

    def readTpt()(implicit ctx: Context): Tree = {
      val tpt: Tree = nextByte match {
        case SHAREDterm =>
          readByte()
          forkAt(readAddr()).readTpt()
//        case BLOCK =>
//          readByte()
//          val end = readEnd()
//          val typeReader = fork
//          skipTree()
//          val aliases = readStats(ctx.owner, end)
//          val tpt = typeReader.readTpt()
//          Block(aliases, tpt)
//        case HOLE =>
//          readByte()
//          val end = readEnd()
//          readHole(end, isType = true)
        case tag =>
          if (isTypeTreeTag(tag)) readTerm()
          else {
            val tp = readType()
            if (!(isNoType(tp) || isError(tp))) TypeTree(tp) else emptyTree
          }
      }
      tpt
    }

    /** TODO [tasty]: SPECIAL OPTIMAL CASE FOR TEMPLATES */
    def readParentFromTerm()(implicit ctx: Context): Type = {
      val start = currentAddr
      val tag = readByte()
      ctx.log(s"reading parent-term ${astTagToString(tag)} at $start")

      def completeSelectionParent(name: TastyName)(implicit ctx: Context): Type = name match {
        case SignedName(TastyName.Constructor, _: MethodSignature[_]) =>
          constructorOfPrefix(readParentFromTerm())(ctx.selectionCtx(name))
        case _ =>
          typeError(s"Parent of ${ctx.owner} is not a constructor.")
      }

      def readSimpleTermAsType(): Type = tag match {
        case SELECT => completeSelectionParent(readTastyName())
        case NEW    => readTpt().tpe
      }

      def readLengthTermAsType(): Type = {
        val end = readEnd()
        val result: Type =
          (tag: @switch) match {
            case APPLY =>
              val fn = readParentFromTerm()
              until(end)(skipTree())
              fn.dealiasWiden.finalResultType
            case TYPEAPPLY =>
              val fn = readParentFromTerm()
              val args = until(end)(readTpt())
              fn.resultType.substituteTypes(fn.typeParams, args.map(_.tpe))
            case BLOCK =>
              val exprReader = fork
              skipTree()
              until(end)(skipTree()) //val stats = readStats(ctx.owner, end)
              exprReader.readParentFromTerm()
          }
        assert(currentAddr === end, s"$start $currentAddr $end ${astTagToString(tag)}")
        result
      }
      if (tag < firstLengthTreeTag) readSimpleTermAsType() else readLengthTermAsType()
    }

    def readCases(end: Addr)(implicit ctx: Context): List[CaseDef] =
      collectWhile((nextUnsharedTag === CASEDEF) && currentAddr != end) {
        if (nextByte === SHAREDterm) {
          readByte()
          forkAt(readAddr()).readCase()(ctx.withNewScope)
        }
        else readCase()(ctx.withNewScope)
      }

    def readCase()(implicit ctx: Context): CaseDef = {
      assert(readByte() === CASEDEF)
      val end = readEnd()
      val pat = readTerm()
      val rhs = readTerm()
      val guard = ifBefore(end)(readTerm(), emptyTree)
      CaseDef(pat, guard, rhs).setType(rhs.tpe) //setSpan(start, CaseDef(pat, guard, rhs))
    }

    def readLaterWithOwner[T <: AnyRef](end: Addr, op: TreeReader => Context => T)(implicit ctx: Context): Symbol => T = {
      val localReader = fork
      goto(end)
      owner => readWith(localReader, owner, ctx.mode, ctx.source, op)
    }

  }

  def readWith[T <: AnyRef](
    reader: TreeReader,
    owner: Symbol, mode: TastyMode,
    source: AbstractFile,
    op: TreeReader => Context => T)(
    implicit ctx: Context
  ): T = withPhaseNoLater(picklerPhase) {
    ctx.log(s"starting to read at ${reader.reader.currentAddr} with owner $owner")
    op(reader)(ctx
      .withOwner(owner)
      .withMode(mode)
      .withSource(source)
    )
  }

  /** A lazy datastructure that records how definitions are nested in TASTY data.
   *  The structure is lazy because it needs to be computed only for forward references
   *  to symbols that happen before the referenced symbol is created (see `symbolAt`).
   *  Such forward references are rare.
   *
   *  @param   addr    The address of tree representing an owning definition, NoAddr for root tree
   *  @param   tag     The tag at `addr`. Used to determine which subtrees to scan for children
   *                   (i.e. if `tag` is template, don't scan member defs, as these belong already
   *                    to enclosing class).
   *  @param   reader  The reader to be used for scanning for children
   *  @param   end     The end of the owning definition
   */
  class OwnerTree(val addr: Addr, tag: Int, reader: TreeReader, val end: Addr) {

    private var myChildren: List[OwnerTree] = _

    /** All definitions that have the definition at `addr` as closest enclosing definition */
    def children: List[OwnerTree] = {
      if (myChildren === null) myChildren = {
        val buf = new mutable.ListBuffer[OwnerTree]
        reader.scanTrees(buf, end, if (tag === TEMPLATE) NoMemberDefs else AllDefs)
        buf.toList
      }
      myChildren
    }

    /** Find the owner of definition at `addr` */
    def findOwner(addr: Addr)(implicit ctx: Context): Symbol = {
      def search(cs: List[OwnerTree], current: Symbol): Symbol =
        try cs match {
        case ot :: cs1 =>
          if (ot.addr.index === addr.index) {
            assert(current.exists, s"no symbol at $addr")
            current
          }
          else if (ot.addr.index < addr.index && addr.index < ot.end.index)
            search(ot.children, reader.symbolAt(ot.addr))
          else
            search(cs1, current)
        case Nil =>
          throw new TreeWithoutOwner
      }
      catch {
        case ex: TreeWithoutOwner =>
          ctx.log(s"no owner for $addr among $cs%, %") // pickling.println
          throw ex
      }
      try search(children, noSymbol)
      catch {
        case ex: TreeWithoutOwner =>
          ctx.log(s"ownerTree = $ownerTree") // pickling.println
          throw ex
      }
    }

    override def toString: String =
      s"OwnerTree(${addr.index}, ${end.index}, ${if (myChildren === null) "?" else myChildren.mkString(" ")})"
  }

  def symFromNoCycle(noCycle: NoCycle): Symbol = symAtAddr(noCycle.at)
}

object TreeUnpickler {

  sealed trait MaybeCycle
  object MaybeCycle {
    case class  NoCycle(at: Addr) extends MaybeCycle
    case object Tombstone         extends MaybeCycle
  }

  /** An enumeration indicating which subtrees should be added to an OwnerTree. */
  type MemberDefMode = Int
  final val MemberDefsOnly = 0   // add only member defs; skip other statements
  final val NoMemberDefs = 1     // add only statements that are not member defs
  final val AllDefs = 2          // add everything

  class TreeWithoutOwner extends Exception
}
