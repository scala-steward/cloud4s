import sbt.*
import sbt.Keys.*

import java.io.File
import scala.language.postfixOps
import scala.sys.process.*

val scala3Version = "3.7.1"

lazy val nativeCompile = inputKey[Unit]("Create native image")
lazy val nativeConfig = inputKey[Unit]("Create configs to native image")
lazy val dist = inputKey[Unit]("run dist")

lazy val root = project
  .in(file("."))
  .settings(
    name := "cloud",
    version := "0.1.0",

    scalaVersion := scala3Version,
    organization     := "io.cloud.cli",

    libraryDependencies ++= Seq(
      ///"ch.qos.logback" % "logback-classic" % "1.5.3",
      "com.jcraft" % "jsch" % "0.1.55",
      "org.typelevel" %% "cats-effect" % "3.6.2",
      "com.monovore" %% "decline" % "2.5.0",
      "com.monovore" %% "decline-effect" % "2.5.0",
      "org.scalameta" %% "munit" % "1.1.2" % Test
    ),

    nativeConfig := {
      val logger: TaskStreams = streams.value
      val targetName = s"${name.value}-assembly-${version.value}.jar"
      val target = new File(new File("target", s"scala-${scalaVersion.value}"), targetName)

      val shell: Seq[String] = if (sys.props("os.name").contains("Windows")) Seq("cmd", "/c") else Seq("bash", "-c")
      val cmd = shell ++ Seq(
        "java",
        s"-agentlib:native-image-agent=config-output-dir=./src/main/resources/META-INF/native-image/${organization.value}",
        "-jar",
        target.getAbsolutePath
      )
      val result = (cmd !)
      if(result == 0){
        logger.log.success("image native config generate successful")
      }else{
        logger.log.success("image native config generate failure")
      }
    },

    nativeCompile := {
      val logger: TaskStreams = streams.value
      val targetName = s"${name.value}-assembly-${version.value}.jar"
      val target = new File(new File("target", s"scala-${scalaVersion.value}"), targetName)
      val executable = s"./target/${name.value}"
      if(!target.exists()) {
        logger.log.error("target not found. do you assembly?")
      }else {

        //val shell: Seq[String] = if (sys.props("os.name").contains("Windows")) Seq("cmd", "/c") else Seq("bash", "-c")
        val cmd = Seq(
          "native-image",
          //"--static",
          "--verbose",
          "--allow-incomplete-classpath",
          "--report-unsupported-elements-at-runtime",
          "--no-fallback",
          "-jar",
          target.getAbsolutePath,
          executable
        )

        logger.log.info(s"execute: ${cmd.mkString(" ")}")

        val result = cmd ! logger.log
        if (result == 0) {
          logger.log.success(s"image native compile successful, executable at $executable")
        } else {
          logger.log.success("image native compile failure")
        }
      }
    }
  )

(assembly / assemblyMergeStrategy) := {
  case "reference.conf" => MergeStrategy.concat
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

ThisBuild / Compile / run / fork := true

ThisBuild / usePipelining := true

nativeCompile := (nativeCompile dependsOn assembly).evaluated
