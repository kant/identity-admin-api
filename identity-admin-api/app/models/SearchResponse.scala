package models

import org.joda.time.DateTime
import play.api.libs.json.Json
import play.api.mvc.{Results, Result}
import scala.language.implicitConversions
import MongoJsFormats._

case class UserSummary(id: String, 
                       email: String, 
                       username: Option[String], 
                       firstName: Option[String], 
                       lastName: Option[String],
                       creationDate: Option[DateTime],
                       lastActivityDate: Option[DateTime])

object UserSummary {
  implicit val format = Json.format[UserSummary]
  
  def fromUser(user: User): UserSummary =
    UserSummary(
      id = user._id.getOrElse(""),
      email = user.primaryEmailAddress,
      username = user.publicFields.flatMap(_.username),
      firstName = user.privateFields.flatMap(_.firstName),
      lastName = user.privateFields.flatMap(_.secondName),
      creationDate = user.dates.flatMap(_.accountCreatedDate),
      lastActivityDate = user.dates.flatMap(_.lastActivityDate)
    )
  
}

case class SearchResponse(results: Seq[UserSummary] = Nil)

object SearchResponse {
  implicit val format = Json.format[SearchResponse]
  
  implicit def searchResponseToResult(searchResponse: SearchResponse): Result =
    Results.Ok(Json.toJson(searchResponse))
}
