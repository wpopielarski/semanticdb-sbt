package scala.meta.internal.sbthost

import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.mutable
import scala.meta.internal.semanticdb.DatabaseOps
import scala.reflect.internal.util.OffsetPosition
import scala.reflect.internal.util.RangePosition
import scala.reflect.internal.util.SourceFile
import scala.tools.nsc.Phase
import scala.tools.nsc.io.VirtualFile
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.reporters.StoreReporter

import org.langmeta.internal.semanticdb.{ schema => s }
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Database

trait SbthostPipeline extends DatabaseOps { self: SbthostPlugin =>
  def workingDirectory = Paths.get(sys.props("user.dir")).normalize()

  def targetroot = {
    import java.io.File
    val default = Paths
      .get(
        g.settings.outputDirs.getSingleOutput
          .map(_.file.toURI)
          .getOrElse(new File(g.settings.d.value).getAbsoluteFile.toURI))
      .normalize()

    // Imitate what classesDirectory does for sbt 0.13.x for consistency
    if (default == workingDirectory) {
      // Sbthost is only meant to be used for 2.10, so this is safe
      workingDirectory.resolve(Paths.get("target", "scala-2.12", "classes"))
    } else default
  }
  var configSbt = SbthostConfig(
    sourceroot = workingDirectory.toAbsolutePath,
    targetroot = targetroot.toAbsolutePath
  )

  object SbthostComponent extends PluginComponent {
    private lazy val pathCount = mutable.Map.empty[Path, Int].withDefaultValue(0)
    val global: SbthostPipeline.this.global.type = SbthostPipeline.this.global
    // Select Sbt0137 dialect for scala sources extracted from sbt files
    private val isSbt = g.getClass.getName.contains("sbt.compiler.Eval")
    private val detectedDialect =
      if (isSbt) "Sbt1" else "Scala212"
    override val runsAfter = List("typer")
    override val runsRightAfter = Some("typer")
    val phaseName = "semanticdb-sbt"
    def getMessages(source: SourceFile): mutable.LinkedHashSet[s.Message] =
      g.reporter match {
        case reporter: StoreReporter =>
          reporter.infos.withFilter(_.pos.source == source).map { info =>
            val range = Option(info.pos).collect {
              case p: RangePosition  => s.Position(p.start, p.end)
              case p: OffsetPosition => s.Position(p.point, p.point)
            }
            val severity = info.severity.id match {
              case 0   => s.Message.Severity.INFO
              case 1   => s.Message.Severity.WARNING
              case 2   => s.Message.Severity.ERROR
              case els => s.Message.Severity.UNKNOWN
            }
            s.Message(range, severity, info.msg)
          }
        case els =>
          mutable.LinkedHashSet.empty
      }
    val isVisitedTree = mutable.Set.empty[g.Tree]
    override def newPhase(prev: Phase) = new StdPhase(prev) {
      isVisitedTree.clear()
      if (!isSbt) pathCount.clear()
      // sbt creates a new phase for each synthetic compilation unit,
      // even if they origin from the same source.
      else ()
      def apply(unit: g.CompilationUnit): Unit = {
        val attributes = unit.toDocument.copy(language = detectedDialect)
        def isValidSymbol(symbol: g.Symbol) =
          symbol.ne(null) && symbol != g.NoSymbol
        val sourcePath = unit.source.file match {
          case f: VirtualFile =>
            Paths.get(f.path)
          case els =>
            Paths.get(els.file.getAbsoluteFile.toURI)
        }
        val counter = {
          val n = pathCount(sourcePath)
          pathCount(sourcePath) = n + 1
          n
        }
        val filename = configSbt.relativePath(sourcePath)
        val semanticdbOutFile = configSbt.semanticdbPath(filename)
        semanticdbOutFile.toFile.getParentFile.mkdirs()
        // If this is not the first compilation unit for this .sbt file, append.
        val db = Database(Seq(attributes))
        if (counter > 0 && isSbt)
          db.append(AbsolutePath(semanticdbOutFile), AbsolutePath(filename))
        else
          db.save(AbsolutePath(semanticdbOutFile), AbsolutePath(filename))
      }
    }
  }
}
