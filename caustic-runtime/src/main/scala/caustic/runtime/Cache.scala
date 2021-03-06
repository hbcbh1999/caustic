package caustic.runtime

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * An asynchronous, transactional write-through cache.
 */
trait Cache extends Database {

  /**
   * Returns the underlying [[Database]].
   *
   * @return Underlying [[Database]].
   */
  def database: Database

  /**
   * Asynchronously returns the cached [[Revision]] of the specified keys.
   *
   * @param keys Keys to fetch.
   * @param ec Implicit execution context.
   * @return Cached [[Revision]] of the specified keys.
   */
  def fetch(keys: Set[Key])(
    implicit ec: ExecutionContext
  ): Future[Map[Key, Revision]]

  /**
   * Asynchronously applies the specified changes to the [[Cache]].
   *
   * @param changes Updates to make.
   * @param ec Implicit execution context.
   * @return Future that completes when successful, or an exception otherwise.
   */
  def update(changes: Map[Key, Revision])(
    implicit ec: ExecutionContext
  ): Future[Unit]

  /**
   * Asynchronously removes the specified keys from the [[Cache]].
   *
   * @param keys Keys to purge.
   * @param ec Implicit execution context.
   * @return Future that completes when successful, or an exception otherwise.
   */
  def invalidate(keys: Set[Key])(
    implicit ec: ExecutionContext
  ): Future[Unit]

  override def get(keys: Set[Key])(
    implicit ec: ExecutionContext
  ): Future[Map[Key, Revision]] =
  // Determine if there are any cache misses.
    fetch(keys) flatMap { hits =>
      val misses = hits.keySet diff keys
      if (misses.nonEmpty) {
        // Reload any cache misses from the underlying database.
        this.database.get(misses) flatMap  { changes =>
          update(changes).map(_ => hits ++ changes)
        }
      } else {
        // Return the cache hits otherwise.
        Future(hits)
      }
    }

  override def cput(depends: Map[Key, Version], changes: Map[Key, Revision])(
    implicit ec: ExecutionContext
  ): Future[Unit] =
    this.database.cput(depends, changes) transformWith {
      case Success(_) =>
        // Update the values of changed keys.
        update(changes)
      case Failure(e: thrift.ConflictException) =>
        // Invalidate cached keys to force them to reload from the database.
        invalidate(e.keys.asScala.toSet).transform(_ => Failure(e))
      case Failure(_) =>
        // Ignore all other failures.
        Future.unit
    }

  override def close(): Unit = {
    // Propagate close to the underlying database.
    this.database.close()
  }

}

object Cache {

  /**
   * Constructs a [[Cache]] that corresponds to the specified name.
   *
   * @param name Cache name.
   * @param database Underlying [[Database]].
   * @return Cache instance.
   */
  def forName(name: String, database: Database): Cache = name match {
    case "local" => local.LocalCache(database)
    case "redis" => redis.RedisCache(database)
  }

}