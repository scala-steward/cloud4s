package io.cloud.cli

import com.monovore.decline.Opts
import cats.implicits.*

object AppCmds:

  trait Cmd:
    def cmd: String


  def runOnCluster(cmd: String) = s"cd ./cluster && ./docker $cmd"

  case class StackDeploy(stack: String) extends Cmd:
    def cmd = runOnCluster(s"deploy $stack")

  case class ServiceUpdate(stack: String, service: String) extends Cmd:
    def cmd = s"docker service update --force ${stack}_$service"

  case class ServicePS(service: String, running: Boolean) extends Cmd:
    def cmd =
      if running
      then runOnCluster(s"ps $service | grep Running")
      else runOnCluster(s"ps $service")

  case class ServiceStop(service: String) extends Cmd:
    def cmd = runOnCluster(s"stop $service")

  case class ServiceList()extends Cmd:
    def cmd = runOnCluster("ls")

  case class ServiceGetLogs(service: String)extends Cmd:
    def cmd = runOnCluster(s"getlogs $service")

  case class StackRemove(stack: String)extends Cmd:
    def cmd = runOnCluster(s"rm $stack")

  case class DockerPrune() extends Cmd:
    def cmd = "docker system prune -a -f"

  case class DockerPS() extends Cmd:
    def cmd = "docker ps"

  case class DockerDF() extends Cmd:
    def cmd = "docker df"

  case class DockerStats() extends Cmd:
    def cmd = "docker stats --no-stream --no-trunc"

  trait CodeBuild extends Cmd:
    def cmd = ""
  case class CodeBuildInc(ecrName: String) extends CodeBuild
  case class CodeBuildDec(ecrName: String) extends CodeBuild
  case class CodeBuildCurr(ecrName: String) extends CodeBuild
  case class CodeBuildStart(projectName: String) extends CodeBuild:
    override def cmd = s"aws codebuild start-build --project-name $projectName"
  case class CodeBuildShowBuildId(projectName: String) extends CodeBuild:
    override def cmd =  s"aws codebuild list-builds-for-project --project-name $projectName"
  case class CodeBuildStop(projectName: String) extends CodeBuild:
    override def cmd = s"aws codebuild stop-build --id __build_id__"
  case class CodeBuildInfo(projectName: String) extends CodeBuild:
    override def cmd = s"aws codebuild batch-get-builds --ids __build_id__"
  case class CodeBuildStatus(projectName: String) extends CodeBuild:
    override def cmd =  s"aws codebuild batch-get-builds --ids __build_id__"
  case class CodeBuildLogs(projectName: String) extends CodeBuild:
    override def cmd =  s"aws logs get-log-events --log-group-name __group_name__ --log-stream-name __stream_name__"
  case class CodeBuildProjects() extends CodeBuild:
    override def cmd = s"aws codebuild list-projects"


  val codebuildInc: Opts[CodeBuild] =
    Opts.subcommand("inc", "Increment service version") {
      Opts.argument[String](metavar = "ECR repository name")
        .map(CodeBuildInc.apply)
    }

  val codebuildDec: Opts[CodeBuild] =
    Opts.subcommand("dec", "Decrement service version") {
      Opts.argument[String](metavar = "ECR repository name")
        .map(CodeBuildDec.apply)
    }

  val codebuildCurr: Opts[CodeBuild] =
    Opts.subcommand("curr", "Show current service version") {
      Opts.argument[String](metavar = "ECR repository name")
        .map(CodeBuildCurr.apply)
    }

  val codebuildStart: Opts[CodeBuild] =
    Opts.subcommand("start", "Start build (AWS CodeBuild)") {
      Opts.argument[String](metavar = "AWS CodeBuild project name")
        .map(CodeBuildStart.apply)
    }

  val codebuildStop: Opts[CodeBuild] =
    Opts.subcommand("stop", "Stop build (AWS CodeBuild)") {
      Opts.argument[String](metavar = "AWS CodeBuild project name")
        .map(CodeBuildStop.apply)
    }

  val codebuildInfo: Opts[CodeBuild] =
    Opts.subcommand("info", "Show build info (AWS CodeBuild)") {
      Opts.argument[String](metavar = "AWS CodeBuild project name")
        .map(CodeBuildInfo.apply)
    }

  val codebuildStatus: Opts[CodeBuild] =
    Opts.subcommand("status", "Show build status (AWS CodeBuild)") {
      Opts.argument[String](metavar = "AWS CodeBuild project name")
        .map(CodeBuildStatus.apply)
    }

  val codebuildLogs: Opts[CodeBuild] =
    Opts.subcommand("logs", "Show logs for last build (AWS CodeBuild)") {
      Opts.argument[String](metavar = "AWS CodeBuild project name")
          .map(CodeBuildLogs.apply)
    }

  val codebuildList: Opts[CodeBuild] =
    Opts.subcommand("projects", "List all projects (AWS CodeBuild)") {
      Opts(CodeBuildProjects())
    }

  val codebuild: Opts[CodeBuild] =
    Opts.subcommand("cb", "CodeBuild actions") {
      (codebuildInc
        orElse codebuildDec
        orElse codebuildCurr
        orElse codebuildStart
        orElse codebuildStop
        orElse codebuildInfo
        orElse codebuildStatus
        orElse codebuildLogs
        orElse codebuildList)
    }

  val stackDeploy: Opts[StackDeploy] =
    Opts.subcommand("deploy", "Swarm stack deploy") {
      Opts.argument[String](metavar = "stack name").map(StackDeploy.apply)
    }

  val stackRemove: Opts[StackRemove] =
    Opts.subcommand("rm", "Swarm stack remove") {
      Opts.argument[String](metavar = "stack name").map(StackRemove.apply)
    }

  val serviceStop: Opts[ServiceStop] =
    Opts.subcommand("stop", "Swarm service remove") {
      Opts.argument[String](metavar = "service name").map(ServiceStop.apply)
    }

  val serviceUpdate: Opts[ServiceUpdate] =
    Opts.subcommand("update", "Swarm force service update") {
      (Opts.argument[String](metavar = "stack name"),
        Opts.argument[String](metavar = "service name")).mapN(ServiceUpdate.apply)
    }

  val servicePS: Opts[ServicePS] =
    Opts.subcommand("ps", "Swarm show service info") {
      (Opts.argument[String](metavar = "service name"),
        Opts.flag("running", "filter by running services").orFalse
      ).mapN(ServicePS.apply)
    }

  val serviceLS: Opts[ServiceList] =
    Opts.subcommand("ls", "Swarm list all services") {
      Opts(ServiceList())
    }


  //val serviceRM: Opts[ServiceStop] =
  //  Opts.subcommand("stop", "Swarm stop service") {
  //    Opts.argument[String](metavar = "service name").map(ServiceStop.apply)
  //  }

  val serviceGetLogs: Opts[ServiceGetLogs] =
    Opts.subcommand("get-logs", "Swarm get service logs") {
      Opts.argument[String](metavar = "service name").map(ServiceGetLogs.apply)
    }

  val dockerPrune: Opts[DockerPrune] =
    Opts.subcommand("docker-prune", "Docker remove unused data") {
      Opts(DockerPrune())
    }

  val dockerPS: Opts[DockerPS] =
    Opts.subcommand("docker-ps", "Docker lists containers") {
      Opts(DockerPS())
    }

  val dockerDF: Opts[DockerDF] =
    Opts.subcommand("docker-df", "Docker file system space usage") {
      Opts(DockerDF())
    }

  val dockerStats: Opts[DockerStats] =
    Opts.subcommand("docker-stats", "Docker containers resource usage statistics") {
      Opts(DockerStats())
    }

  val cmds = (
    stackDeploy
      orElse stackRemove
      orElse serviceStop
      orElse serviceUpdate
      orElse servicePS
      orElse serviceLS
      orElse serviceGetLogs
      orElse dockerPrune
      orElse dockerPS
      orElse dockerDF
      orElse dockerStats
      orElse codebuild)


