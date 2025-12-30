package io.cloud.cli

import cats.effect.*
import cats.implicits.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import io.cloud.cli.AppCmds.*
import io.cloud.cli.AppConfigs.Config
import scala.language.postfixOps
import scala.sys.process.*

object DockerApp extends CommandIOApp(
  name = "cloud",
  header = "Mobile Mind Cloud Tool",
  version = "0.0.2"
) {

  override def main: Opts[IO[ExitCode]] =
    cmds.map:
      c => runCmd(c)


  def runCmd(cmd: Cmd): IO[ExitCode] =
    for
      cfg <- AppConfigs.getConfigs()
      red <-
        given configs: Config = cfg
        cmd match
          case CodeBuildInc(ecrName) => codebuild.inc(ecrName)
          case CodeBuildDec(ecrName) => codebuild.inc(ecrName)
          case CodeBuildCurr(ecrName) => codebuild.curr(ecrName)
          case cb: CodeBuildStart => codebuild.start(cb)
          case cb: CodeBuildStop => codebuild.stop(cb)
          case cb: CodeBuildStatus => codebuild.status(cb)
          case cb: CodeBuildLogs => codebuild.logs(cb)
          case cb: CodeBuildInfo => codebuild.info(cb)
          case cb: CodeBuildProjects => codebuild.projects(cb)
          case StackDeploy(_) => docker.runOnMainHost(cmd)
          case StackRemove(_) => docker.runOnMainHost(cmd)
          case ServiceUpdate(_, _) => docker.runOnMainHost(cmd)
          case ServicePS(_, _) => docker.runOnMainHost(cmd)
          case ServiceStop(_) => docker.runOnMainHost(cmd)
          case ServiceGetLogs(_) => docker.runOnMainHost(cmd, Some(docker.logAnalyzer))
          case ServiceList() => docker.runOnMainHost(cmd)
          case DockerPrune() => docker.runOnAllHosts(cmd)
          case DockerPS() => docker.runOnAllHosts(cmd)
          case DockerDF() => docker.runOnAllHosts(cmd)
          case DockerStats() => docker.runOnAllHosts(cmd)
    yield red
}
// https://ben.kirw.in/decline/usage.html

type IOResult = Config ?=> IO[ExitCode]
type Analyzer = Seq[String] => IOResult

def say(s: String): IO[Unit] =
  IO.blocking:
    Console.print(Console.BLUE)
    Console.print(s"\n:: cloud ::> \n\n$s\n\n")
    Console.print(Console.RESET)

def sayError(s: String): IO[ExitCode] =
  IO.blocking:
    Console.print(Console.RED)
    Console.print(s"\n:: cloud ::> \n\n$s\n\n")
    Console.print(Console.RESET)
  *> IO.unit.as(ExitCode.Error)

def sayOk(s: String): IO[ExitCode] =
  say(s) *> IO.unit.as(ExitCode.Success)

def showLogs(out: String): IO[ExitCode] =
  IO.blocking {
    Console.print(Console.BLUE)
    Console.print(s"\n:: cloud ::> \n\nLOGS\n\n")
    Console.print(out)
    Console.print("\n\n")
    Console.print(Console.RESET)
  } *> IO.unit.as(ExitCode.Success)

object codebuild:

  def inc(service: String): IOResult =
    cb(service, "increment")

  def dec(service: String): IOResult =
    cb(service, "decrement")

  def curr(service: String): IOResult =
    cb(service, "current")

  private def cb(service: String, action: String): IOResult =
    val cfg = summon[Config]
    val url = s"${cfg.codebuildUrl}/app/version/$action/$service"
    val auth = AuthBasic(cfg.codebuildUsername, cfg.codebuildPassword)
    Http(url, Some(auth))
      .getAsString
      .flatMap:
        resp =>
          say(s"Server response ${resp.statusCode()}: ${resp.body()}") *>
            (resp.statusCode() match
              case 200 => IO.unit.as(ExitCode.Success)
              case _ => IO.unit.as(ExitCode.Error))

  private def getBuildId(cb: CodeBuildShowBuildId): IO[String] =
    IO.blocking:
      val filterCmd = "jq -r '.ids[0]'"
      (cb.cmd #| filterCmd) !!

  def start(cb : CodeBuildStart): IOResult =
    IO.blocking((cb.cmd !!)).flatMap(sayOk)
  
  def status(cb: CodeBuildStatus): IOResult =
    getBuildId(CodeBuildShowBuildId(cb.projectName))
      .flatMap:
        bid =>
          if bid.isEmpty
          then sayOk(s"build not found to project ${cb.projectName}")
          else
            IO.blocking:
              val cmd = cb.cmd.replace("__build_id__", bid)
              val filterCmd = "jq '.builds[].phases[] | select (.phaseType==\"BUILD\") | .phaseStatus'"
              (cmd #| filterCmd) !!
            .flatMap:
              r => sayOk(s"build status: ${if r == "null" || r.isEmpty then "BUILDING" else r}")

  private def getLogInfo(bid: String): IO[Either[String, (String, String)]] =
      IO.blocking:

        val cmd = CodeBuildStatus("").cmd.replace("__build_id__", bid)
        val filterCmd = "jq '.builds[0].logs.groupName,.builds[0].logs.streamName'"
        ((cmd #| filterCmd) !!)
      .map:
        r =>
          if r == "null"
          then Left("cannot get logs")
          else {
            r.split("\n").toList match
              case first :: second :: Nil => Right((first, second))
              case _ => Left("cannot parse log name")
          }

  def logs(cb: CodeBuildLogs): IOResult =
    getBuildId(CodeBuildShowBuildId(cb.projectName))
        .flatMap:
          bid =>
            if bid.isEmpty
            then sayOk(s"build not found to project ${cb.projectName}")
            else
              getLogInfo(bid)
                .flatMap {
                  case Left(msg) => sayOk(msg)
                  case Right((groupName, streamName)) =>
                    IO.blocking {
                      val cmd = cb.cmd
                        .replace("__group_name__", groupName)
                        .replace("__stream_name__", streamName)
                      val filterCmd = "jq -r '.events[].message'"
                      ((cmd #| filterCmd) !!)
                    }.map(_.split("\n").toList.filter(_.trim().nonEmpty).mkString("\n"))
                      .flatMap(showLogs)
                }


  def info(cb: CodeBuildInfo): IOResult =
    getBuildId(CodeBuildShowBuildId(cb.projectName))
      .flatMap:
        bid =>
          if bid.isEmpty
          then sayOk(s"build not found to project ${cb.projectName}")
          else
            IO.blocking:
              cb.cmd.replace("__build_id__", bid) !!
            .flatMap:
              r => sayOk(s"build info:\n\n$r")

  def stop(cb: CodeBuildStop): IOResult =
    getBuildId(CodeBuildShowBuildId(cb.projectName))
      .flatMap:
        bid =>
          if bid.isEmpty
          then sayOk(s"build not found to project ${cb.projectName}")
          else
            IO.blocking:
              cb.cmd.replace("__build_id__", bid) !!
            .flatMap(sayOk)
  
  def projects(cb: CodeBuildProjects): IOResult =
    IO.blocking((cb.cmd !!))
      .flatMap:
        r => sayOk(s"projects:\n\n$r")
      
object docker:

  def runOnMainHost(cmd: Cmd, analyzer: Option[Analyzer] = None): IOResult =
    val cfg = summon[Config]
    exec(cmd.cmd, None, cfg.hostMain)

  def runOnAllHosts(cmd: Cmd): IOResult =
    val cfg = summon[Config]
    exec(cmd.cmd, None, cfg.hosts*)

  private def exec(cmd: String, analyzer: Option[Analyzer], hosts: String*): IOResult =
    Ssh.configure() *>
      hosts.map:
        host => runEach(cmd, host, analyzer)
      .parSequence
      .flatMap:
        codes =>
          IO.pure:
            codes.find(_ != ExitCode.Success)
              .getOrElse(ExitCode.Success)

  private def runEach(cmd: String, host: String, analyzer: Option[Analyzer] = None): IOResult =
    Ssh.connectAndExec(cmd, host):
      lines =>
        analyzer match
          case Some(f) => f(lines)
          case None =>
            sayOk(lines.mkString("\n"))

  def logAnalyzer(lines: Seq[String]): IOResult =
    lines.lastOption match
      case None => sayError("can't get log lines")
      case Some(lastLine) =>
        if lastLine.contains("DONE! save log at")
        then
          lastLine.split("logs/").lastOption match
            case Some(filename) => scpLog(filename.trim)
            case _ => sayError("can't get log name")
        else sayError("can't get log from server response")    
            
  def scpLog(filename: String) : IOResult =
    val cfg = summon[Config]
    val to = s"${cfg.logsPath}/${filename}"
    val from = s"${cfg.username}@${cfg.hostMain}.${cfg.domain}:~/cluster/logs/${filename}"
    val scp = Seq("scp", "-i", cfg.keyIdentity, from, to)
    IO.blocking:
      scp ! ProcessLogger(s => println(s"SCP: $s"))
    .flatMap:
      code =>
        if code != 0
        then sayError("can't save log")
        else sayOk(s"log saved at $to")