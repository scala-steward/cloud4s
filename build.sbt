import sbt.*
import sbt.Keys.*

import java.io.File
import scala.language.postfixOps
import scala.sys.process.*
import scala.scalanative.build.*
import bindgen.interface.Binding
import bindgen.plugin.BindgenMode
import com.indoorvivants.detective.Platform

resolvers += Resolver.mavenLocal

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / scalafmtOnCompile := true
ThisBuild / organization := "io.cloud4s.cli"
ThisBuild / version := "0.0.5"
ThisBuild / scalacOptions ++= Seq(
  "-new-syntax",
  "-Wvalue-discard",
  "-Wunused:all",
  "-deprecation",
  "-explain",
  "-explain-cyclic",
  "-rewrite",
  "-source:future"
)
ThisBuild / Compile / run / fork := true
ThisBuild / usePipelining := true
ThisBuild / envVars := Map(
  "ENVIRONMENT" -> "development"
)

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.github.scopt" %% "scopt" % Deps.scoptVersion,
      "com.lihaoyi" %% "upickle" % Deps.upickleVersion,
      "com.softwaremill.sttp.client4" %% "core" % Deps.sttpVersion,
      "org.scalameta" %% "munit" % Deps.munitVersion % Test
    )
  )

lazy val `cloud4s-jvm` = project
  .in(file("cloud4s-jvm"))
  .dependsOn(core.jvm)
  .settings(
    name := "cloud4s-jvm",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.3.6" % Test
    )
  )

lazy val `cloud4s-native` = project
  .in(file("cloud4s-native"))
  .enablePlugins(ScalaNativePlugin, BindgenPlugin)
  .dependsOn(core.native)
  .settings(
    name := "cloud4s",

    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % Deps.javaTimeVersion,
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % Deps.javaTimeVersion,
      "com.github.scopt" %%% "scopt" % Deps.scoptVersion,
      "com.lihaoyi" %%% "upickle" % Deps.upickleVersion,
      "com.softwaremill.sttp.client4" %%% "core" % Deps.sttpVersion
    ),

    // 2. Modos e caminhos de saída do Bindgen
    bindgenMode := BindgenMode.Manual(
      scalaDir =
        (Compile / sourceDirectory).value / "scala" / "io" / "cloud4s" / "cli" / "bindings",
      cDir = (Compile / resourceDirectory).value / "scala-native" / "ssh"
    ),

    // 3. Definição segura das bindings do Bindgen
    bindgenBindings += {
      val headerFile = file("/usr/include/libssh/libssh.h")

      Binding(headerFile, "ssh")
        .withLinkName("ssh")
        .withCImports(List("libssh/libssh.h"))
        .withClangFlags(List("-I/usr/include", "-std=gnu99"))
        .withNoLocation(true)
    },

    // 4. Configuração unificada do Scala Native
    nativeConfig := {
      val conf = nativeConfig.value

      // Validação de plataforma para Apple Silicon (macOS arm64)
      conf
        .withLinkingOptions(
          conf.linkingOptions ++ Seq(
            "-fuse-ld=lld",
            "-lcurl",
            "-lssh",
            "-lssl",
            "-lcrypto",
            "-lstdc++"
          )
        )
        .withLTO(LTO.none)
        .withMode(Mode.debug)
        .withGC(GC.immix)
        .withSourceLevelDebuggingConfig(_.enableAll)
        .withIncrementalCompilation(true)
        .withOptimize(false)
    },

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-s", "-v")
  )

commands += Command.command("release") { state =>
  println("Iniciando build de produção (Release)...")

  // 1. Modifica a configuração para produção temporariamente
  val stateWithConfig = Project
    .extract(state)
    .appendWithoutSession(
      Seq(
        `cloud4s-native` / Compile / nativeConfig ~= {
          _.withMode(scala.scalanative.build.Mode.releaseFast)
            .withLTO(scala.scalanative.build.LTO.thin)
            .withGC(scala.scalanative.build.GC.commix)
            .withOptimize(true)
        }
      ),
      state
    )

  // 2. Executa o build e pega o caminho do binário gerado
  val (nextState, artifactFile) = Project
    .extract(stateWithConfig)
    .runTask(`cloud4s-native` / Compile / nativeLink, stateWithConfig)

  // 3. Define a pasta de destino (dist/) e o nome do arquivo final
  val destFile = baseDirectory.value / "dist" / artifactFile.getName

  println(s"Copiando executável final para: ${destFile.getAbsolutePath}")
  IO.copyFile(artifactFile, destFile)

  println("Build de produção concluído com sucesso!")
  nextState
}
