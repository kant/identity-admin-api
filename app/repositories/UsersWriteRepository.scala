package repositories

import javax.inject.{Inject, Singleton}

import com.gu.identity.util.Logging
import com.mongodb.casbah.WriteConcern
import com.mongodb.casbah.commons.MongoDBObject
import models._
import salat.dao.SalatDAO

import scala.util.{Failure, Success, Try}

@Singleton
class UsersWriteRepository @Inject() (salatMongoConnection: SalatMongoConnection)
  extends SalatDAO[PersistedUser, String](collection=salatMongoConnection.db()("users")) with Logging {

  private[repositories] def createUser(user: PersistedUser) = {
    val userToCreate = user.copy(
      primaryEmailAddress = user.primaryEmailAddress.toLowerCase,
      publicFields = user.publicFields.map(pf => pf.copy(usernameLowerCase = pf.username.map(_.toLowerCase))))
    insert(userToCreate)
  }
  
  def update(user: User, userUpdateRequest: PersistedUserUpdate): Either[ApiError, User] = {
    Try {
      findOne(MongoDBObject("_id" -> user.id)).map { persistedUser =>
        prepareUserForUpdate(userUpdateRequest, persistedUser)
      }
    } match {
      case Success(Some(userToSave)) =>
          doUpdate(userToSave)
      case Success(None) =>
        Left(ApiErrors.notFound)
       case Failure(t) =>
        logger.error(s"Failed to update user. id: ${user.id}", t)
        Left(ApiErrors.internalError(t.getMessage))
    }
  }

  def updateEmailValidationStatus(user: User, emailValidated: Boolean): Either[ApiError, User] = {
    Try {
      findOne(MongoDBObject("_id" -> user.id)).map { persistedUser =>
        val statusFields = persistedUser.statusFields.getOrElse(StatusFields()).copy(
          userEmailValidated = Some(emailValidated)
        )
        persistedUser.copy(statusFields = Some(statusFields))
      }
    } match {
      case Success(Some(userToSave)) =>
          doUpdate(userToSave)
      case Success(None) =>
        Left(ApiErrors.notFound)
       case Failure(t) =>
        logger.error(s"Failed to update email validation status to $emailValidated for user id: ${user.id}", t)
        Left(ApiErrors.internalError(t.getMessage))
    }
  }

  private def prepareUserForUpdate(userUpdateRequest: PersistedUserUpdate, persistedUser: PersistedUser): PersistedUser = {
    val publicFields = persistedUser.publicFields.getOrElse(PublicFields()).copy(
      username = userUpdateRequest.username,
      usernameLowerCase = userUpdateRequest.username.map(_.toLowerCase),
      displayName = userUpdateRequest.displayName,
      vanityUrl = userUpdateRequest.username,
      location = userUpdateRequest.location,
      aboutMe = userUpdateRequest.aboutMe,
      interests = userUpdateRequest.interests
    )
    val privateFields = persistedUser.privateFields.getOrElse(PrivateFields()).copy(
      firstName = userUpdateRequest.firstName,
      secondName = userUpdateRequest.lastName
    )
    val statusFields = persistedUser.statusFields.getOrElse(StatusFields()).copy(
      receive3rdPartyMarketing = userUpdateRequest.receive3rdPartyMarketing,
      receiveGnmMarketing = userUpdateRequest.receiveGnmMarketing,
      userEmailValidated = userUpdateRequest.userEmailValidated
    )
    val searchFields = persistedUser.searchFields.getOrElse(SearchFields()).copy(
      emailAddress = Some(userUpdateRequest.email.toLowerCase),
      username = userUpdateRequest.username.map(_.toLowerCase),
      displayName = userUpdateRequest.displayName
    )
    persistedUser.copy(
      primaryEmailAddress = userUpdateRequest.email.toLowerCase,
      publicFields = Some(publicFields),
      privateFields = Some(privateFields),
      statusFields = Some(statusFields),
      searchFields = Some(searchFields)
    )
  }

  private def doUpdate(userToSave: PersistedUser): Either[ApiError, User] = {
    Try {
      update(MongoDBObject("_id" -> userToSave._id), userToSave, upsert = false, multi = false, wc = WriteConcern.Safe)
    } match {
      case Success(_) =>
        Right(User.fromPersistedUser(userToSave))
      case Failure(t) =>
        logger.error(s"Failed to update user. id: ${userToSave._id}", t)
        val errorMessage = generateErrorMessage(t)
        Left(ApiErrors.internalError(errorMessage))
    }
  }

  private def generateErrorMessage(error: Throwable): String = {
    val errorText = error.toString
    if (errorText contains "E11000 duplicate key error")
      "this data is already in use in the database"
    else
      "update could not be performed contact identitydev@guardian.co.uk"
  }

  def delete(userId: String): Either[ApiError, Boolean] = {
    Try {
      removeById(userId)
    } match {
      case Success(r) =>
        Right(true)
      case Failure(t) =>
        logger.error(s"Failed to delete user. id: $userId", t)
        Left(ApiErrors.internalError(t.getMessage))
    }
  }
}