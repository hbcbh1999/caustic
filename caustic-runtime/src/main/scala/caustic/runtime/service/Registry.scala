package caustic.runtime.service

import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.CreateMode
import pureconfig._

import java.io.Closeable
import scala.collection.mutable
import scala.concurrent.duration.Duration

/**
 * An instance registry. Thread-safe.
 *
 * @param curator ZooKeeper connection.
 * @param paths Instance ZooKeeper paths.
 * @param announcers Instance announcers.
 */
case class Registry(
  curator: CuratorFramework,
  paths: mutable.Map[Address, String] = mutable.Map.empty,
  announcers: mutable.Map[Address, ConnectionStateListener] = mutable.Map.empty
) extends Closeable {

  override def close(): Unit = Unit

  /**
   * A ZooKeeper listener that re-registers an instance Address after disruptions in connectivity.
   *
   * @param address Instance location.
   */
  case class Announcer(address: Address) extends ConnectionStateListener {

    override def stateChanged(curator: CuratorFramework, state: ConnectionState): Unit = {
      // Re-register the instance each time the ZooKeeper connection is established.
      if (state == ConnectionState.CONNECTED || state == ConnectionState.RECONNECTED)
        register(address)
    }

  }

  /**
   * Registers the instance in ZooKeeper. A serialized representation of the instance is written to
   * a ephemeral, sequential znode, which ensures that instances are automatically unregistered
   * during ZooKeeper connectivity disruptions. Instances are automatically re-registered whenever
   * ZooKeeper connectivity is resumed.
   *
   * @param address Instance location.
   */
  def register(address: Address): Unit = this.synchronized {
    // Unregister the instance if it already exists.
    unregister(address)

    // Announce the instance in ZooKeeper.
    this.curator.blockUntilConnected()
    this.paths += address -> curator.create()
      .creatingParentContainersIfNeeded()
      .withProtection()
      .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
      .forPath(s"/instance", address.toBytes)

    // Re-announce the instance every time connection is reestablished.
    val announcer = Announcer(address)
    this.announcers += address -> announcer
    this.curator.getConnectionStateListenable.addListener(announcer)
  }

  /**
   * Unregisters the instance in ZooKeeper. Instances are automatically unregistered during
   * ZooKeeper connectivity disruptions, but instances must still unregister themselves whenever
   * they terminate.
   *
   * @param address Instance location.
   */
  def unregister(address: Address): Unit = this.synchronized {
    if (this.paths.contains(address)) {
      // Remove the instance from ZooKeeper.
      this.curator.blockUntilConnected()
      this.curator.delete().forPath(this.paths(address))
      this.curator.getConnectionStateListenable.removeListener(this.announcers(address))

      // Delete the instance.
      this.paths -= address
      this.announcers -= address
    }
  }

}

object Registry {

  /**
   * A Registry configuration.
   *
   * @param zookeeper Zookeeper connect string. (eg. "localhost:3192,localhost:2811")
   * @param namespace Zookeeper namespace.
   * @param connectionTimeout ZooKeeper connection timeout.
   * @param sessionTimeout ZooKeeper session timeout.
   */
  case class Config(
    zookeeper: String,
    namespace: String,
    connectionTimeout: Duration,
    sessionTimeout: Duration
  )

  /**
   * Constructs a Registry by loading the configuration from the classpath.
   *
   * @return Classpath-configured Registry.
   */
  def apply(): Registry =
    Registry(loadConfigOrThrow[Config]("caustic.registry"))

  /**
   * Constructs a Registry from the provided configuration.
   *
   * @param config Configuration.
   * @return Dynamically-configured Registry.
   */
  def apply(config: Config): Registry = {
    val curator = CuratorFrameworkFactory.builder()
      .connectString(config.zookeeper)
      .namespace(config.namespace)
      .retryPolicy(new ExponentialBackoffRetry(1000, 3))
      .connectionTimeoutMs(config.connectionTimeout.toMillis.toInt)
      .sessionTimeoutMs(config.sessionTimeout.toMillis.toInt)
      .build()

    // Close the generated CuratorFramework connection.
    new Registry(curator) {
      override def close(): Unit = this.curator.close()
    }
  }

}