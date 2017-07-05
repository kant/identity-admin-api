package repositories

import javax.inject.{Inject, Singleton}

import com.gu.identity.util.Logging
import models.{ApiError, ApiResponse, SearchResponse, User}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.play.json.collection._
import reactivemongo.play.json._
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}

import scala.concurrent.Future
import scalaz.{-\/, OptionT, \/-}
import scalaz.std.scalaFuture._

@Singleton class UsersReadRepository @Inject() (reactiveMongoApi: ReactiveMongoApi) extends Logging {

  private val MaximumResults = 20
  private lazy val usersCollectionF = reactiveMongoApi.database.map(_.collection("users"))

  def search(query: String, limit: Option[Int] = None, offset: Option[Int] = None): ApiResponse[SearchResponse] =  {
    usersCollectionF.flatMap { usersCollection =>
      val totalF = usersCollection.count(Some(selector(query)))

      val batchSizeN = limit.getOrElse(MaximumResults)
      val skipN = offset.getOrElse(0)

      val resultsF = usersCollection
        .find(selector(query))
        .options(QueryOpts(skipN, batchSizeN))
        .cursor[IdentityUser](ReadPreference.primaryPreferred)
        .collect[List](batchSizeN, Cursor.FailOnError[List[IdentityUser]]())

      for {
        total <- totalF
        results <- resultsF
      } yield {
        \/-(SearchResponse.create(total, skipN, results))
      }
    }
  }

  private def selector(key: String): JsObject =
    Json.obj(
      "$or" -> Json.arr(
        Json.obj("_id" -> key.toLowerCase),
        Json.obj("searchFields.emailAddress" -> key.toLowerCase),
        Json.obj("searchFields.username" -> key.toLowerCase),
        Json.obj("searchFields.postcode" -> key.toLowerCase),
        Json.obj("searchFields.postcodePrefix" -> key.toLowerCase),
        Json.obj("searchFields.displayName" -> key.toLowerCase),
        Json.obj("privateFields.registrationIp" -> key.toLowerCase),
        Json.obj("privateFields.lastActiveIpAddress" -> key.toLowerCase)
      )
    )

  def find(key: String): ApiResponse[Option[User]] = {
    val identityUserOptF = usersCollectionF.flatMap(_.find(selector(key)).one[IdentityUser])

    OptionT(identityUserOptF).fold(
      identityUser => \/-(Some(User.fromIdentityUser(identityUser))),
      \/-(None)
    ).recover { case error =>
      val title = s"Failed to perform search in MongoDB"
      logger.error(title, error)
      -\/(ApiError(title, error.getMessage))
    }
  }

  def count(): Future[Int] = usersCollectionF.flatMap(_.count())
}
