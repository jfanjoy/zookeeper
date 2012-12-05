package com.loopfor.zookeeper.cli

import scala.language._
import jline.console.ConsoleReader
import jline.console.completer.StringsCompleter
import scala.collection.JavaConverters._
import scala.annotation.tailrec
import java.net.InetSocketAddress
import com.loopfor.zookeeper.Configuration
import scala.concurrent.duration._
import com.loopfor.zookeeper.Zookeeper
import com.loopfor.zookeeper.Node
import com.loopfor.zookeeper.Path
import com.loopfor.zookeeper._
import java.text.DateFormat
import java.util.Date

object CLI {
  def main(args: Array[String]) {
    try {
      val status = run(args)
      sys.exit(status)
    } catch {
      case e: Exception =>
        import Console.err
        err.println("internal error: " + e.getMessage)
        err.println(">> stack trace")
        e.printStackTrace(err)
        sys.exit(1)
    }
  }

  private val USAGE = """
usage: zk [OPTIONS] SERVER...
       zk [-? | --help]

  An interactive client capable of connecting to a ZooKeeper cluster. At least
  one SERVER in the cluster must be specified, which is defined as `host:port`.

options:
  --path, -p                 : root path (/=default)
  --timeout, -t SECONDS      : session timeout (0=default)
  --readonly, -r             : allow readonly connection
"""

  private val COMMANDS = Seq("ls", "quit")

  private def run(args: Array[String]): Int = {
    import Console.err
    if (args.size == 0) {
      println(USAGE)
      return 1
    }
    val opts = try {
      parseOptions(args)
    } catch {
      case e: OptionException =>
        err.println(e.getMessage)
        return 1
      case e: Exception =>
        err.println("internal error: " + e.getMessage)
        err.println(">> stack trace")
        e.printStackTrace(err)
        return 1
    }
    if (opts contains 'help) {
      println(USAGE)
      return 0
    }
    val servers = opts get 'params match {
      case Some(params) =>
        params.asInstanceOf[Seq[String]] map { p =>
          val i = p indexOf ':'
          if (i == -1) {
            println(p + ": missing port; expecting `host:port`")
            return 1
          } else if (i == 0) {
            println(p + ": missing host; expecting `host:port`")
            return 1
          } else {
            new InetSocketAddress(p take i, try {
              (p drop i + 1).toInt
            } catch {
              case _: NumberFormatException =>
                println(p + ": port invalid; expecting `host:port`")
                return 1
            })
          }
        }
      case _ =>
        println("no servers specified")
        return 1
    }
    val path = opts get 'path match {
      case Some(p) => p.asInstanceOf[String]
      case _ => ""
    }
    val timeout = opts get 'timeout match {
      case Some(p) => p.asInstanceOf[Int] seconds
      case _ => 60 seconds
    }
    val readonly = opts get 'readonly match {
      case Some(p) => p.asInstanceOf[Boolean]
      case _ => false
    }

    // todo: add watcher to config so we can change cursor when client state changes
    val config = Configuration(servers) withPath(path) withTimeout(timeout) withAllowReadOnly(readonly)
    val zk = Zookeeper(config)

    val commands = Map[String, Command](
          "config" -> ConfigCommand(config),
          "cd" -> CdCommand(zk),
          "pwd" -> PwdCommand(zk),
          "ls" -> LsCommand(zk),
          "stat" -> StatCommand(zk),
          "get" -> GetCommand(zk),
          "getacl" -> GetACLCommand(zk),
          "mk" -> CreateCommand(zk),
          "create" -> CreateCommand(zk),
          "rm" -> DeleteCommand(zk),
          "del" -> DeleteCommand(zk),
          "quit" -> QuitCommand(zk),
          "exit" -> QuitCommand(zk)) withDefaultValue UnrecognizedCommand(zk)

    val reader = new ConsoleReader
    reader.setBellEnabled(false)
    reader.addCompleter(new StringsCompleter(commands.keySet.asJava))

    @tailrec def process(context: Path) {
      if (context != null) {
        val args = readCommand(reader)
        val _context = try {
          if (args.size > 0) commands(args.head)(args.head, args.tail, context) else context
        } catch {
          case _: ConnectionLossException =>
            println("connection lost")
            context
          case _: SessionExpiredException =>
            println("session has expired; `quit` and restart program")
            context
          case e: KeeperException =>
            println("internal zookeeper error: " + e.getMessage)
            context
        }
        process(_context)
      }
    }

    process(Path("/"))
    0
  }

  private def readCommand(reader: ConsoleReader): Array[String] = {
    val line = reader.readLine("zk> ")
    val args = if (line == null) Array("quit") else line split ' '
    args.headOption match {
      case Some(a) => if (a == "") args.tail else args
      case _ => args
    }
  }

  private def parseOptions(args: Array[String]): Map[Symbol, Any] = {
    @tailrec def parse(args: Stream[String], opts: Map[Symbol, Any]): Map[Symbol, Any] = {
      if (args.isEmpty)
        opts
      else {
        val arg = args.head
        val rest = args.tail
        arg match {
          case "--" => opts + ('params -> rest.toList)
          case LongOption("help") | ShortOption("?") => parse(rest, opts + ('help -> true))
          case LongOption("path") | ShortOption("p") => rest.headOption match {
            case Some(path) => parse(rest.tail, opts + ('path -> path))
            case _ => throw new OptionException(arg + ": missing argument")
          }
          case LongOption("timeout") | ShortOption("t") => rest.headOption match {
            case Some(seconds) =>
              val _seconds = try {
                seconds.toInt
              } catch {
                case _: NumberFormatException => throw new OptionException(seconds + ": SECONDS must be an integer")
              }
              parse(rest.tail, opts + ('timeout -> _seconds))
            case _ => throw new OptionException(arg + ": missing argument")
          }
          case LongOption("readonly") | ShortOption("r") => parse(rest, opts + ('readonly -> true))
          case LongOption(_) | ShortOption(_) => throw new OptionException(arg + ": unrecognized option")
          case _ => opts + ('params -> args.toList)
        }
      }
    }

    parse(args.toStream, Map())
  }
}

private object LongOption {
  def unapply(arg: String): Option[String] = if (arg startsWith "--") Some(arg drop 2) else None
}

private object ShortOption {
  def unapply(arg: String): Option[String] = if (arg startsWith "-") Some(arg drop 1) else None
}

private class OptionException(message: String, cause: Throwable) extends Exception(message, cause) {
  def this(message: String) = this(message, null)
}

private trait Command extends ((String, Seq[String], Path) => Path)

private object ConfigCommand {
  def apply(config: Configuration) = new Command {
    def apply(cmd: String, args: Seq[String], context: Path) = {
      println("servers: " + (config.servers map { s => s.getHostName + ":" + s.getPort } mkString ","))
      println("path: " + Path("/").resolve(config.path).normalize)
      println("timeout: " + config.timeout)
      context
    }
  }
}

private object LsCommand {
  def apply(zk: Zookeeper) = new Command {
    private implicit val _zk = zk

    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      val opts = try {
        parse(args)
      } catch {
        case e: OptionException => println(e.getMessage)
        return context
      }
      val recurse = opts contains 'recursive
      val paths = opts get 'params match {
        case Some(p) => p.asInstanceOf[Seq[String]]
        case None => Seq("")
      }
      val count = paths.size
      (1 /: paths) { case (i, path) =>
        val node = Node(context resolve path)
        try {
          val children = node.children() sortBy { _.name }
          if (count > 1)
            println(node.path + ":")
          if (recurse)
            traverse(children, 0)
          else
            children foreach { child => println(child.name) }
          if (count > 1 && i < count)
            println()
        } catch {
          case _: NoNodeException => println(node.path + ": no such node")
        }
        i + 1
      }
      context
    }
  }

  private def traverse(children: Seq[Node], depth: Int) {
    children foreach { child =>
      if (depth > 0)
        print(pad(depth) + "+ ")
      println(child.name)
      try {
        traverse(child.children() sortBy { _.name }, depth + 1)
      } catch {
        case _: NoNodeException => // just ignore nodes that disappear during traversal
      }
    }
  }

  private def pad(depth: Int) = (0 until (depth - 1) * 2) map { _ => ' ' } mkString

  private def parse(args: Seq[String]): Map[Symbol, Any] = {
    def parse(args: Seq[String], opts: Map[Symbol, Any]): Map[Symbol, Any] = {
      if (args.isEmpty)
        opts
      else {
        val arg = args.head
        val rest = args.tail
        arg match {
          case "--" => opts + ('params -> rest)
          case LongOption("recursive") | ShortOption("r") => parse(rest, opts + ('recursive -> true))
          case LongOption(_) | ShortOption(_) => throw new OptionException(arg + ": unrecognized option")
          case _ => opts + ('params -> args)
        }
      }
    }
    parse(args, Map())
  }
}

private object CdCommand {
  def apply(zk: Zookeeper) = new Command {
    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      if (args.isEmpty) Path("/") else context.resolve(args.head).normalize
    }
  }
}

private object PwdCommand {
  def apply(zk: Zookeeper) = new Command {
    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      println(context.path)
      context
    }
  }
}

private object StatCommand {
  def apply(zk: Zookeeper) = new Command {
    private implicit val _zk = zk
    private val formatter = DateFormat.getDateTimeInstance

    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      val paths = if (args.isEmpty) args :+ "" else args
      val count = paths.size
      (1 /: paths) { case (i, path) =>
        val node = Node(context resolve path)
        node.exists() match {
          case Some(status) =>
            if (count > 1)
              println(node.path + ":")
            println("czxid: " + status.czxid)
            println("mzxid: " + status.mzxid)
            println("pzxid: " + status.pzxid)
            println("ctime: " + status.ctime + " (" + formatter.format(new Date(status.ctime)) + ")")
            println("mtime: " + status.mtime + " (" + formatter.format(new Date(status.mtime)) + ")")
            println("version: " + status.version)
            println("cversion: " + status.cversion)
            println("aversion: " + status.aversion)
            println("owner: " + status.ephemeralOwner)
            println("datalen: " + status.dataLength)
            println("children: " + status.numChildren)
            if (count > 1 && i < count)
              println()
          case _ => println(node.path + ": no such node")
        }
        i + 1
      }
      context
    }
  }
}

private object GetCommand {
  def apply(zk: Zookeeper) = new Command {
    private implicit val _zk = zk

    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      val paths = if (args.isEmpty) args :+ "" else args
      val count = paths.size
      (1 /: paths) { case (i, path) =>
        val node = Node(context resolve path)
        try {
          val (data, status) = node.get()
          if (count > 1)
            println(node.path + ":")
          display(data)
          if (count > 1 && i < count)
            println()
        } catch {
          case _: NoNodeException => println(node.path + ": no such node")
        }
        i + 1
      }
      context
    }
  }

  private def display(data: Array[Byte]) {
    @tailrec def display(n: Int) {
      if (n < data.length) {
        val l = Math.min(n + 16, data.length) - n
        print("%08x  " format n)
        print((for (i <- n until (n + l)) yield "%02x " format data(i)).mkString)
        print(pad((16 - l) * 3))
        print(" |")
        print((for (i <- n until (n + l)) yield charOf(data(i))).mkString)
        print(pad(16 - l))
        println("|")
        display(n + l)
      }
    }
    display(0)
  }

  private def pad(n: Int): String = (0 until n) map { _ => ' ' } mkString

  private def charOf(b: Byte): Char = if (b >= 32 && b < 127) b.asInstanceOf[Char] else '.'
}

private object GetACLCommand {
  def apply(zk: Zookeeper) = new Command {
    implicit private val _zk = zk

    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      val paths = if (args.isEmpty) args :+ "" else args
      val count = paths.size
      (1 /: paths) { case (i, path) =>
        val node = Node(context resolve path)
        try {
          val (acl, status) = node.getACL()
          if (count > 1)
            println(node.path + ":")
          display(acl)
          if (count > 1 && i < count)
            println()
        } catch {
          case _: NoNodeException => println(node.path + ": no such node")
        }
        i + 1
      }
      context
    }
  }

  private def display(acl: Seq[ACL]) {
    acl foreach { a =>
      print(a.id.scheme + ":" + a.id.id + "=")
      val p = a.permission
      print(if ((p & ACL.Read) == 0) "-" else "r")
      print(if ((p & ACL.Write) == 0) "-" else "w")
      print(if ((p & ACL.Create) == 0) "-" else "c")
      print(if ((p & ACL.Delete) == 0) "-" else "d")
      print(if ((p & ACL.Admin) == 0) "-" else "a")
      println()
    }
  }
}

private object CreateCommand {
  def apply(zk: Zookeeper) = new Command {
    private implicit val _zk = zk

    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      val opts = try {
        parse(args)
      } catch {
        case e: OptionException => println(e.getMessage)
        return context
      }
      val recurse = opts('recursive).asInstanceOf[Boolean]
      val disp = disposition(opts)
      val path = opts('params).asInstanceOf[Seq[String]].head
      val node = Node(context resolve path)
      try {
        node.create(Array(), ACL.EveryoneAll, disp)
      } catch {
        case e: NodeExistsException => println(Path(e.getPath).normalize + ": node already exists")
        case _: NoNodeException => println(node.parent.path + ": no such parent node")
        case _: NoChildrenForEphemeralsException => println(node.parent.path + ": parent node is ephemeral")
      }
      context
    }
  }

  private def disposition(opts: Map[Symbol, Any]): Disposition = {
    val sequential = opts('sequential).asInstanceOf[Boolean]
    val ephemeral = opts('ephemeral).asInstanceOf[Boolean]
    if (sequential && ephemeral) EphemeralSequential
    else if (sequential) PersistentSequential
    else if (ephemeral) Ephemeral
    else Persistent
  }

  private def parse(args: Seq[String]): Map[Symbol, Any] = {
    def parse(args: Seq[String], opts: Map[Symbol, Any]): Map[Symbol, Any] = {
      if (args.isEmpty)
        opts
      else {
        val arg = args.head
        val rest = args.tail
        arg match {
          case "--" => opts + ('params -> rest)
          case LongOption("recursive") | ShortOption("r") => parse(rest, opts + ('recursive -> true))
          case LongOption("sequential") | ShortOption("s") => parse(rest, opts + ('sequential -> true))
          case LongOption("ephemeral") | ShortOption("e") => parse(rest, opts + ('ephemeral -> true))
          case LongOption(_) | ShortOption(_) => throw new OptionException(arg + ": unrecognized option")
          case _ => opts + ('params -> args)
        }
      }
    }
    parse(args, Map(
          'recursive -> false,
          'sequential -> false,
          'ephemeral -> false,
          'params -> Seq("")))
  }
}

private object DeleteCommand {
  def apply(zk: Zookeeper) = new Command {
    private implicit val _zk = zk

    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      val opts = try {
        parse(args)
      } catch {
        case e: OptionException => println(e.getMessage)
        return context
      }
      val recurse = opts('recursive).asInstanceOf[Boolean]
      val force = opts('force).asInstanceOf[Boolean]
      val version = opts('version).asInstanceOf[Option[Int]]
      val path = opts('params).asInstanceOf[Seq[String]].head
      if (!force && version.isEmpty) {
        println("version must be specified; otherwise use --force")
        return context
      }
      val node = Node(context resolve path)
      try {
        node.delete(if (force) None else version)
      } catch {
        case _: NoNodeException => println(node.path + ": no such node")
        case _: BadVersionException => println(version.get + ": version does not match")
        case _: NotEmptyException => println(node.path + ": node has children")
      }
      context
    }
  }

  private def parse(args: Seq[String]): Map[Symbol, Any] = {
    def parse(args: Seq[String], opts: Map[Symbol, Any]): Map[Symbol, Any] = {
      if (args.isEmpty)
        opts
      else {
        val arg = args.head
        val rest = args.tail
        arg match {
          case "--" => opts + ('params -> rest)
          case LongOption("recursive") | ShortOption("r") => parse(rest, opts + ('recursive -> true))
          case LongOption("force") | ShortOption("f") => parse(rest, opts + ('force -> true))
          case LongOption("version") | ShortOption("v") => rest.headOption match {
            case Some(version) =>
              val _version = try {
                version.toInt
              } catch {
                case _: NumberFormatException => throw new OptionException(version + ": VERSION must be an integer")
              }
              parse(rest.tail, opts + ('version -> Some(_version)))
            case _ => throw new OptionException(arg + ": missing argument")
          }
          case LongOption(_) | ShortOption(_) => throw new OptionException(arg + ": unrecognized option")
          case _ => opts + ('params -> args)
        }
      }
    }
    parse(args, Map(
          'recursive -> false,
          'force -> false,
          'version -> None,
          'params -> Seq("")))
  }
}

private object QuitCommand {
  def apply(zk: Zookeeper) = new Command {
    private implicit val _zk = zk

    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      zk.close()
      null
    }
  }
}

private object UnrecognizedCommand {
  def apply(zk: Zookeeper) = new Command {
    def apply(cmd: String, args: Seq[String], context: Path): Path = {
      println(cmd + ": unrecognized command")
      context
    }
  }
}