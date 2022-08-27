package scala.scalanative
package codegen

import java.io.File
import java.nio.file.{Path, Paths}
import scala.collection.mutable
import scala.scalanative.build.{Config, IncCompilationContext}
import scala.scalanative.build.core.ScalaNative.dumpDefns
import scala.scalanative.io.VirtualDirectory
import scala.scalanative.nir._
import scala.scalanative.util.{Scope, partitionBy, procs}
import scala.scalanative.compat.CompatParColls.Converters._

object CodeGen {

  /** Lower and generate code for given assembly. */
  def apply(config: build.Config, linked: linker.Result)(implicit
      incCompilationContext: IncCompilationContext = new IncCompilationContext
  ): Seq[Path] = {
    val defns = linked.defns
    val proxies = GenerateReflectiveProxies(linked.dynimpls, defns)

    implicit val meta: Metadata =
      new Metadata(linked, proxies, config.compilerConfig.is32BitPlatform)

    val generated = Generate(Global.Top(config.mainClass), defns ++ proxies)
    val embedded = ResourceEmbedder(config)
    val lowered = lower(generated ++ embedded)
    dumpDefns(config, "lowered", lowered)
    emit(config, lowered)
  }

  private def lower(defns: Seq[Defn])(implicit meta: Metadata): Seq[Defn] = {
    val buf = mutable.UnrolledBuffer.empty[Defn]

    partitionBy(defns)(_.name).par
      .map {
        case (_, defns) =>
          Lower(defns)
      }
      .seq
      .foreach { defns => buf ++= defns }

    buf.toSeq
  }

  /** Generate code for given assembly. */
  private def emit(config: build.Config, assembly: Seq[Defn])(implicit
      meta: Metadata,
      incCompilationContext: IncCompilationContext = new IncCompilationContext
  ): Seq[Path] =
    Scope { implicit in =>
      val env = assembly.map(defn => defn.name -> defn).toMap
      val workdir = VirtualDirectory.real(config.workdir)

      // Partition into multiple LLVM IR files proportional to number
      // of available processesors. This prevents LLVM from optimizing
      // across IR module boundary unless LTO is turned on.
      def separate(): Seq[Path] =
        partitionBy(assembly, procs)(_.name.top.mangle).par
          .map {
            case (id, defns) =>
              val sorted = defns.sortBy(_.name.show)
              Impl(config, env, sorted).gen(id.toString, workdir)
          }
          .toSeq
          .seq

      // Incremental compilation code generation
      def separateInc(): Seq[Path] =
        assembly
          .groupBy { defn =>
            val packageName =
              defn.name.top.id.split('.').init.mkString(".")
            packageName
          }
          .par
          .map {
            case (pack, defns) =>
              incCompilationContext.collectFromCurr(pack, defns)
              if (incCompilationContext.isChanged(pack)) {
                val sorted = defns.sortBy(_.name.show)
                val packagePrefix = config.workdir.resolve(
                  pack.split('.').init.mkString(File.separator)
                )
                if (!packagePrefix.toFile.exists()) {
                  packagePrefix.toFile.mkdirs()
                }
                val packagePath = pack.replace(".", File.separator)
                Impl(config, env, sorted).gen(packagePath, workdir)
              } else {
                val packagePrefix = config.workdir
                  .resolve(pack.replace(".", File.separator))
                  .resolve("..")
                  .normalize
                assert(packagePrefix.toFile.exists())
                val packagePath = pack.replace(".", File.separator)
                config.workdir.resolve(s"$packagePath.ll")
              }
          }
          .seq
          .toSeq

      // Generate a single LLVM IR file for the whole application.
      // This is an adhoc form of LTO. We use it in release mode if
      // Clang's LTO is not available.
      def single(): Seq[Path] = {
        val sorted = assembly.sortBy(_.name.show)
        Impl(config, env, sorted).gen(id = "out", workdir) :: Nil
      }

      // For some reason in the CI matching for `case _: build.Mode.Release` throws compile time erros
      import build.Mode._
      (
        config.mode,
        config.LTO,
        config.compilerConfig.incrementalCompilation
      ) match {
        case (_, _, true)                                   => separateInc()
        case (Debug, _, _)                                  => separate()
        case (ReleaseFast | ReleaseFull, build.LTO.None, _) => single()
        case (ReleaseFast | ReleaseFull, _, _)              => separate()
      }
    }

  object Impl {
    import scala.scalanative.codegen.AbstractCodeGen
    import scala.scalanative.codegen.compat.os._

    def apply(config: Config, env: Map[Global, Defn], defns: Seq[Defn])(implicit
        meta: Metadata
    ): AbstractCodeGen = {
      new AbstractCodeGen(config, env, defns) {
        override val os: OsCompat = {
          if (this.config.targetsWindows) new WindowsCompat(this)
          else new UnixCompat(this)
        }
      }
    }
  }

  val depends: Seq[Global] = {
    val buf = mutable.UnrolledBuffer.empty[Global]
    buf ++= Lower.depends
    buf ++= Generate.depends
    buf += Rt.Object.name member Rt.ScalaEqualsSig
    buf += Rt.Object.name member Rt.ScalaHashCodeSig
    buf += Rt.Object.name member Rt.JavaEqualsSig
    buf += Rt.Object.name member Rt.JavaHashCodeSig
    buf.toSeq
  }
}
