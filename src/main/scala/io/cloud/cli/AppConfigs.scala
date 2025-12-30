package io.cloud.cli

import cats.effect.{IO, Resource}

import java.io.File
import scala.annotation.tailrec
import scala.io.Source

object AppConfigs:
  
  case class Config(hosts: Seq[String] = Nil,
                    hostMain: String = "",
                    domain: String = "",
                    username: String = "",
                    port: Int = 0,
                    keyIdentity: String = "",
                    logsPath: String = "",
                    codebuildUrl: String = "",
                    codebuildUsername: String = "",
                    codebuildPassword: String = "",
                    validations: Seq[String] = Seq.empty):

    private def isEmpty(text: String)  = text == null || text.isBlank

    private def checkField(name: String, value: String) : Config =
      if value == null || value.isBlank
      then copy(validations = validations :+ s"value $name required")
      else this

    def valid = validations.isEmpty

    def validate(): Config =
      def fields = Seq(
        ("hostMain", hostMain) ,
        ("domain", domain) ,
        ("username", username) ,
        ("keyIdentity", keyIdentity) ,
        ("logsPath", logsPath) ,
        ("codebuildUrl", codebuildUrl) ,
        ("codebuildUsername", codebuildUsername),
        ("codebuildPassword", codebuildPassword),
        ("port", port),
        ("hosts", hosts),
      )

      fields.foldLeft(this) {
        case (acc, (name, value)) =>
          name match
            case "port" if port == 0 => copy(validations = validations :+ s"value $name required")
            case "hosts" if hosts.isEmpty => copy(validations = validations :+ s"value $name required")
            case value: String => acc.checkField(name, value)
      }
  
  private def home = System.getenv("HOME")
  
  private def fileConfigs(): Resource[IO, Source] =
    val file = new File(home, ".infra-cli.cfg")
    Resource.make {
      IO.blocking(Source.fromFile(file))
    } { s =>
      IO.blocking(s.close())
    }
  
  @tailrec
  private def readLines(lines: Seq[String], config: Config = Config()): Config =
    lines match
      case Nil => config
      case line :: rest =>
        line.split("=").toList match
          case "hosts" :: hosts :: Nil =>
            readLines(rest, config.copy(hosts = hosts.split(",")))
          case "host.main" :: main :: Nil =>
            readLines(rest, config.copy(hostMain = main))
          case "host.domain" :: domain :: Nil =>
            readLines(rest, config.copy(domain = domain))
          case "ssh.username" :: username :: Nil =>
            readLines(rest, config.copy(username = username))
          case "ssh.port" :: port :: Nil =>
            readLines(rest, config.copy(port = port.toInt))
          case "ssh.key.identity" :: keyIdentity :: Nil =>
            readLines(rest, config.copy(keyIdentity = keyIdentity.replace("$HOME", home)))
          case "logs.path" :: logsPath :: Nil =>
            readLines(rest, config.copy(logsPath = logsPath.replace("$HOME", home)))
          case "codebuild.url" :: url :: Nil =>
            readLines(rest, config.copy(codebuildUrl = url))
          case "codebuild.username" :: username :: Nil =>
            readLines(rest, config.copy(codebuildUsername = username))
          case "codebuild.password" :: password :: Nil =>
            readLines(rest, config.copy(codebuildPassword = password))
          case _ => 
            println(s"wrong config: ${line}")
            readLines(rest, config)
  
  def getConfigs(): IO[Config]  =
    fileConfigs().use:
      buffer => IO(readLines(buffer.getLines().toSeq))
    .flatMap: cfg =>
        cfg.validate() match
          case c if c.valid => IO.pure(cfg)
          case c =>
            IO.raiseError(
              new Exception(s"Validation error: \n${c.validations.mkString("\n")}"))