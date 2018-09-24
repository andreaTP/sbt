/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package testpkg

import org.scalatest._
import scala.concurrent._
import scala.annotation.tailrec
import sbt.protocol.ClientSocket
import TestServer.withTestServer
import java.io.File
import sbt.io.syntax._
import sbt.io.IO
import sbt.RunFromSourceMain

class ServerSpec extends AsyncFreeSpec with Matchers {
  "server" - {
    "should start" in withTestServer("handshake") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id": "3", "method": "sbt/setting", "params": { "setting": "root/name" } }"""
      )
      assert(p.waitForString(10) { s =>
        s contains """"id":"3""""
      })
    }

    "return number id when number id is sent" in withTestServer("handshake") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id": 3, "method": "sbt/setting", "params": { "setting": "root/name" } }"""
      )
      assert(p.waitForString(10) { s =>
        s contains """"id":3"""
      })
    }

    "report task failures in case of exceptions" in withTestServer("events") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id": 11, "method": "sbt/exec", "params": { "commandLine": "hello" } }"""
      )

      assert(p.waitForString(10) { s =>
        (s contains """"id":11""") && (s contains """""error":""")
      })
    }
  }

  // "failingServer" - {
  //   "report task failures in case of exceptions" in {
  //     val p = Promise[Boolean]
  //     val futureServer = Future {
  //       val testServer =
  //         IO.withTemporaryDirectory { temp =>
  //           IO.copyDirectory(TestServer.serverTestBase / "events", temp / "events")
  //           TestServer(temp / "events")
  //         }

  //       testServer.writeLine(
  //         """{ "jsonrpc": "2.0", "id": 11, "method": "sbt/exec", "params": { "commandLine": "hello" } }"""
  //       )
  //       testServer.waitForString(10)(s => {
  //         println("contains funziona? " + s)
  //         val res =
  //           (s contains """"id":11""") && (s contains """"error":""")

  //         if (res) p.success(true)
  //         println(res)
  //         res
  //       })
  //     }

  //     val firstCompleted = (Future firstCompletedOf Seq(p.future, futureServer))

  //     firstCompleted.map(x => assert(x == true))
  //   }
  // }
}

object TestServer {
  val serverTestBase: File = new File(".").getAbsoluteFile / "sbt" / "src" / "server-test"

  def withTestServer(testBuild: String)(f: TestServer => Future[Assertion]): Future[Assertion] = {
    IO.withTemporaryDirectory { temp =>
      IO.copyDirectory(serverTestBase / testBuild, temp / testBuild)
      withTestServer(temp / testBuild)(f)
    }
  }

  def withTestServer(baseDirectory: File)(f: TestServer => Future[Assertion]): Future[Assertion] = {
    var testServer: TestServer = null
    try {
      testServer = TestServer(baseDirectory)
      f(testServer)
    } finally {
      if (testServer != null)
        testServer.bye()
    }
  }

  def hostLog(s: String): Unit = {
    println(s"""[${scala.Console.MAGENTA}build-1${scala.Console.RESET}] $s""")
  }
}

case class TestServer(baseDirectory: File) {
  import TestServer.hostLog

  val readBuffer = new Array[Byte](4096)
  var buffer: Vector[Byte] = Vector.empty
  var bytesRead = 0
  private val delimiter: Byte = '\n'.toByte
  private val RetByte = '\r'.toByte

  hostLog("fork to a new sbt instance")
  import scala.concurrent.ExecutionContext.Implicits.global
  Future {
    RunFromSourceMain.fork(baseDirectory)
    ()
  }
  lazy val portfile = baseDirectory / "project" / "target" / "active.json"

  hostLog("wait 30s until the server is ready to respond")
  def waitForPortfile(n: Int): Unit =
    if (portfile.exists) ()
    else {
      if (n <= 0) sys.error(s"Timeout. $portfile is not found.")
      else {
        Thread.sleep(1000)
        if ((n - 1) % 10 == 0) {
          hostLog("waiting for the server...")
        }
        waitForPortfile(n - 1)
      }
    }
  waitForPortfile(90)

  // make connection to the socket described in the portfile
  val (sk, tkn) = ClientSocket.socket(portfile)
  val out = sk.getOutputStream
  val in = sk.getInputStream

  // initiate handshake
  sendJsonRpc(
    """{ "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": { "initializationOptions": { } } }"""
  )

  def test(f: TestServer => Future[Assertion]): Future[Assertion] = {
    f(this)
  }

  def bye(): Unit = {
    hostLog("sending exit")
    sendJsonRpc(
      """{ "jsonrpc": "2.0", "id": 9, "method": "sbt/exec", "params": { "commandLine": "exit" } }"""
    )
  }

  def sendJsonRpc(message: String): Unit = {
    writeLine(s"""Content-Length: ${message.size + 2}""")
    writeLine("")
    writeLine(message)
  }

  def writeLine(s: String): Unit = {
    def writeEndLine(): Unit = {
      val retByte: Byte = '\r'.toByte
      val delimiter: Byte = '\n'.toByte
      out.write(retByte.toInt)
      out.write(delimiter.toInt)
      out.flush
    }

    if (s != "") {
      out.write(s.getBytes("UTF-8"))
    }
    writeEndLine
  }

  def readFrame: Option[String] = {
    def getContentLength: Int = {
      readLine map { line =>
        line.drop(16).toInt
      } getOrElse (0)
    }

    val l = getContentLength
    readLine
    readLine
    readContentLength(l)
  }

  @tailrec
  final def waitForString(num: Int)(f: String => Boolean): Boolean = {
    if (num < 0) false
    else
      readFrame match {
        case Some(x) if f(x) => true
        case _ =>
          waitForString(num - 1)(f)
      }
  }

  def readLine: Option[String] = {
    if (buffer.isEmpty) {
      val bytesRead = in.read(readBuffer)
      if (bytesRead > 0) {
        buffer = buffer ++ readBuffer.toVector.take(bytesRead)
      }
    }
    val delimPos = buffer.indexOf(delimiter)
    if (delimPos > 0) {
      val chunk0 = buffer.take(delimPos)
      buffer = buffer.drop(delimPos + 1)
      // remove \r at the end of line.
      val chunk1 = if (chunk0.lastOption contains RetByte) chunk0.dropRight(1) else chunk0
      Some(new String(chunk1.toArray, "utf-8"))
    } else None // no EOL yet, so skip this turn.
  }

  def readContentLength(length: Int): Option[String] = {
    if (buffer.isEmpty) {
      val bytesRead = in.read(readBuffer)
      if (bytesRead > 0) {
        buffer = buffer ++ readBuffer.toVector.take(bytesRead)
      }
    }
    if (length <= buffer.size) {
      val chunk = buffer.take(length)
      buffer = buffer.drop(length)
      Some(new String(chunk.toArray, "utf-8"))
    } else None // have not read enough yet, so skip this turn.
  }

}
