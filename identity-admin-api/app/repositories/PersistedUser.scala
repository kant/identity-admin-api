package repositories

import models.MongoJsFormats
import org.joda.time.DateTime
import play.api.libs.json._
import MongoJsFormats._
import play.api.libs.functional.syntax._

import scala.language.implicitConversions

case class SocialLink(socialId: String, 
                      network: String, 
                      profileData: Map[String, Any] = Map.empty)

object SocialLink {
  implicit val format = Json.format[SocialLink]
}

case class GroupMembership(path: String, 
                           packageCode: String, 
                           joinedDate: Option[DateTime] = None)

object GroupMembership {
  implicit val format = Json.format[GroupMembership]
}

case class LastActiveLocation(countryCode: Option[String] = None, 
                              cityCode : Option[String] = None)

object LastActiveLocation {
  implicit val format = Json.format[LastActiveLocation]
}


case class PrivateFields(firstName: Option[String] = None,
                          secondName: Option[String] = None,
                          gender: Option[String] = None,
                          registrationIp: Option[String] = None,
                          postcode: Option[String] = None,
                          country: Option[String] = None,
                          address1: Option[String] = None,
                          address2: Option[String] = None,
                          address3: Option[String] = None,
                          address4: Option[String] = None,
                          billingAddress1: Option[String] = None,
                          billingAddress2: Option[String] = None,
                          billingAddress3: Option[String] = None,
                          billingAddress4: Option[String] = None,
                          billingCountry: Option[String] = None,
                          billingPostcode: Option[String] = None,
                          socialAvatarUrl: Option[String] = None,
                          lastActiveIpAddress: Option[String] = None,
                          lastActiveLocation: Option[LastActiveLocation] = None,
                          registrationType: Option[String] = None
                          )

object PrivateFields {
  implicit val format = Json.format[PrivateFields]
}


case class PublicFields(username: Option[String] = None,
                        usernameLowerCase: Option[String] = None,
                        displayName: Option[String] = None,
                        vanityUrl: Option[String] = None,
                        aboutMe: Option[String] = None,
                        interests: Option[String] = None,
                        webPage: Option[String] = None,
                        location: Option[String] = None,
                        avatarUrl: Option[String] = None)

object PublicFields {
  implicit val format = Json.format[PublicFields]
}

case class UserDates(lastActivityDate: Option[DateTime] = None,
                     accountCreatedDate: Option[DateTime] = Some(DateTime.now()),
                     birthDate: Option[DateTime] = None,
                     lastExportedFromDiscussion: Option[DateTime] = None)

object UserDates {
  implicit val format = Json.format[UserDates]
}

case class StatusFields(receive3rdPartyMarketing: Option[Boolean] = None,
                        receiveGnmMarketing: Option[Boolean] = None,
                        userEmailValidated: Option[Boolean] = None)

object StatusFields {
  implicit val format = Json.format[StatusFields]
}

case class PersistedUser(primaryEmailAddress: String,
                _id: Option[String] = None,
                publicFields: Option[PublicFields] = None,
                privateFields: Option[PrivateFields] = None,
                statusFields: Option[StatusFields] = None,
                dates: Option[UserDates] = Some(new UserDates()),
                password: Option[String] = None,
                userGroups: List[GroupMembership] = Nil,
                socialLinks: List[SocialLink] = Nil,
                adData: Map[String, Any] = Map.empty
                 )

object PersistedUser {
  val persistedUserReads: Reads[PersistedUser] = (
  (JsPath \ "primaryEmailAddress").read[String] and
  (JsPath \ "_id").readNullable[String] and
  (JsPath \ "publicFields").readNullable[PublicFields] and
  (JsPath \ "privateFields").readNullable[PrivateFields] and
  (JsPath \ "statusFields").readNullable[StatusFields] and
  (JsPath \ "dates").readNullable[UserDates] and
  (JsPath \ "password").readNullable[String] and
  (JsPath \ "userGroups").readNullable[List[GroupMembership]].map(_.getOrElse(Nil)) and
  (JsPath \ "socialLinks").readNullable[List[SocialLink]].map(_.getOrElse(Nil)) and
  (JsPath \ "adData").readNullable[Map[String, Any]].map(_.getOrElse(Map.empty))
)(PersistedUser.apply _)

  val persistedUserWrites: Writes[PersistedUser] = (
  (JsPath \ "primaryEmailAddress").write[String] and
  (JsPath \ "_id").writeNullable[String] and
  (JsPath \ "publicFields").writeNullable[PublicFields] and
  (JsPath \ "privateFields").writeNullable[PrivateFields] and
  (JsPath \ "statusFields").writeNullable[StatusFields] and
  (JsPath \ "dates").writeNullable[UserDates] and
  (JsPath \ "password").writeNullable[String] and
  (JsPath \ "userGroups").write[List[GroupMembership]] and
  (JsPath \ "socialLinks").write[List[SocialLink]] and
  (JsPath \ "adData").write[Map[String, Any]]
)(unlift(PersistedUser.unapply))

  implicit val format: Format[PersistedUser] =
    Format(persistedUserReads, persistedUserWrites)
}
