package caustic.runtime
package service

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSuite, Matchers}

import scala.util.Success

@RunWith(classOf[JUnitRunner])
class ConnectionTest extends FunSuite with Matchers {

  test("Execute works on in-memory server.") {
    // Bootstrap an in-memory database server.
    val server = Server()

    // Connect and execute transactions.
    val client = Connection(server.port)
    client.execute(write("x", 3)) shouldBe Success(thrift.Literal.real(3))

    // Cleanup client and server.
    client.close()
    server.close()
  }

}
