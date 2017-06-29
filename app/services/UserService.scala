package services

import javax.inject.{Inject, Singleton}

import actors.EventPublishingActor.{DisplayNameChanged, EmailValidationChanged}
import actors.EventPublishingActorProvider
import com.gu.identity.util.Logging
import util.UserConverter._
import models._
import repositories.{DeletedUser, DeletedUsersRepository, IdentityUser, IdentityUserUpdate, Orphan, ReservedUserNameWriteRepository, UsersReadRepository, UsersWriteRepository}
import uk.gov.hmrc.emailaddress.EmailAddress
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import configuration.Config.PublishEvents.eventsEnabled

import scalaz.{-\/, EitherT, OptionT, \/-}
import scalaz.std.scalaFuture._

@Singleton
class UserService @Inject() (usersReadRepository: UsersReadRepository,
                             usersWriteRepository: UsersWriteRepository,
                             reservedUserNameRepository: ReservedUserNameWriteRepository,
                             identityApiClient: IdentityApiClient,
                             eventPublishingActorProvider: EventPublishingActorProvider,
                             deletedUsersRepository: DeletedUsersRepository,
                             salesforceService: SalesforceService,
                             madgexService: MadgexService,
                             exactTargetService: ExactTargetService) extends Logging {

  private lazy val UsernamePattern = "[a-zA-Z0-9]{6,20}".r

  def update(user: User, userUpdateRequest: UserUpdateRequest): ApiResponse[User] = {
    val emailValid = isEmailValid(user, userUpdateRequest)
    val usernameValid = isUsernameValid(user, userUpdateRequest)

    (emailValid, usernameValid) match {
      case (true, true) =>
        val userEmailChanged = !user.email.equalsIgnoreCase(userUpdateRequest.email)
        val userEmailValidated = if(userEmailChanged) Some(false) else user.status.userEmailValidated
        val userEmailValidatedChanged = isEmailValidationChanged(userEmailValidated, user.status.userEmailValidated)
        val usernameChanged = isUsernameChanged(userUpdateRequest.username, user.username)
        val displayNameChanged = isDisplayNameChanged(userUpdateRequest.displayName, user.displayName)
        val update = IdentityUserUpdate(userUpdateRequest, userEmailValidated)

        EitherT(usersWriteRepository.update(user, update)).map { result =>
          triggerEvents(
            userId = user.id,
            usernameChanged = usernameChanged,
            displayNameChanged = displayNameChanged,
            emailValidatedChanged = userEmailValidatedChanged
          )

          if(userEmailChanged) {
            identityApiClient.sendEmailValidation(user.id)
            exactTargetService.updateEmailAddress(user.email, userUpdateRequest.email)
          }

          if (userEmailChanged && eventsEnabled) {
            SalesforceIntegration.enqueueUserUpdate(user.id, userUpdateRequest.email)
          }

          if (isJobsUser(user) && isJobsUserChanged(user, userUpdateRequest)) {
            madgexService.update(GNMMadgexUser(user.id, userUpdateRequest))
          }

          result
        }.run

      case (false, true) => Future.successful(-\/(ApiError("Email is invalid")))
      case (true, false) => Future.successful(-\/(ApiError("Username is invalid")))
      case _ => Future.successful(-\/(ApiError("Email and username are invalid")))
    }
  }

  def isDisplayNameChanged(newDisplayName: Option[String], existingDisplayName: Option[String]): Boolean = {
    newDisplayName != existingDisplayName
  }

  def isUsernameChanged(newUsername: Option[String], existingUsername: Option[String]): Boolean = {
    newUsername != existingUsername
  }

  def isJobsUser(user: User) = isAMemberOfGroup("/sys/policies/guardian-jobs", user)

  def isAMemberOfGroup(groupPath: String, user: User): Boolean = user.groups.filter(_.path == groupPath).size > 0

  def isEmailValidationChanged(newEmailValidated: Option[Boolean], existingEmailValidated: Option[Boolean]): Boolean = {
    newEmailValidated != existingEmailValidated
  }

  def isJobsUserChanged(user: MadgexUser, userUpdateRequest: MadgexUser): Boolean = !user.equals(userUpdateRequest)

  private def triggerEvents(userId: String, usernameChanged: Boolean, displayNameChanged: Boolean, emailValidatedChanged: Boolean) = {
    if (eventsEnabled) {
      if (usernameChanged || displayNameChanged) {
        eventPublishingActorProvider.sendEvent(DisplayNameChanged(userId))
      }
      if (emailValidatedChanged) {
        eventPublishingActorProvider.sendEvent(EmailValidationChanged(userId))
      }
    }
  }

  private def isUsernameValid(user: User, userUpdateRequest: UserUpdateRequest): Boolean = {
    def validateUsername(username: Option[String]): Boolean =  username match {
      case Some(newUsername) => UsernamePattern.pattern.matcher(newUsername).matches()
      case _ => true
    }

    user.username match {
      case None => validateUsername(userUpdateRequest.username)
      case Some(existing) => if(!existing.equalsIgnoreCase(userUpdateRequest.username.mkString)) validateUsername(userUpdateRequest.username) else true
    }
  }

  private def isEmailValid(user: User, userUpdateRequest: UserUpdateRequest): Boolean = {
    if (!user.email.equalsIgnoreCase(userUpdateRequest.email))
      EmailAddress.isValid(userUpdateRequest.email)
    else
      true
  }

  def search(query: String, limit: Option[Int] = None, offset: Option[Int] = None): ApiResponse[SearchResponse] = {
   def searchIdentity(activeUsers: SearchResponse, deletedUsers: SearchResponse) = {
      val combinedTotal = activeUsers.total + deletedUsers.total
      val combinedResults = activeUsers.results ++ deletedUsers.results
      activeUsers.copy(total = combinedTotal, results = combinedResults)
    }

    // execute all these in parallel
    val usersByMemNumF = EitherT(searchIdentityByMembership(query))
    val orphansF = EitherT(searchOrphan(query))
    val usersBySubIdF = EitherT(searchIdentityBySubscriptionId(query))
    val activeUsersF = EitherT(usersReadRepository.search(query, limit, offset))
    val deletedUsersF = EitherT(deletedUsersRepository.search(query))

    (for {
      usersByMemNum <- usersByMemNumF
      orphans <- orphansF
      usersBySubId <- usersBySubIdF
      activeUsers <- activeUsersF
      deletedUsers <- deletedUsersF
    } yield {
      val idUsers = searchIdentity(activeUsers, deletedUsers)

      if (idUsers.results.size > 0)
        idUsers
      else if (usersBySubId.results.size > 0)
        usersBySubId
      else if (orphans.results.size > 0)
        orphans
      else
        usersByMemNum
    }).run
  }

  def unreserveEmail(id: String) = deletedUsersRepository.remove(id)


  def searchOrphan(email: String): ApiResponse[SearchResponse] = {
    def isEmail(query: String) = query.matches("""^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r.toString())

    if (isEmail(email)) {
      EitherT(salesforceService.getSubscriptionByEmail(email)).map(subOpt =>
        subOpt.fold(SearchResponse.create(0, 0, Nil))(sub => SearchResponse.create(1, 0, List(Orphan(email = sub.email))))
      ).run
    } else Future.successful(\/-(SearchResponse.create(0, 0, Nil)))
  }

  private def salesforceSubscriptionToIdentityUser(sfSub: SalesforceSubscription) =
    SearchResponse.create(1, 0, List(IdentityUser(_id = Option(sfSub.identityId), primaryEmailAddress = sfSub.email)))

  def searchIdentityByMembership(membershipNumber: String): ApiResponse[SearchResponse] = {
    def couldBeMembershipNumber(query: String) = query forall Character.isDigit

    if (couldBeMembershipNumber(membershipNumber)) {
      EitherT(salesforceService.getMembershipByMembershipNumber(membershipNumber)).map(memOpt =>
        memOpt.fold
          (SearchResponse.create(0, 0, Nil))
          (mem => salesforceSubscriptionToIdentityUser(mem))
      ).run
    } else Future.successful(\/-(SearchResponse.create(0, 0, Nil)))
  }

  def searchIdentityBySubscriptionId(subscriberId: String): ApiResponse[SearchResponse] = {
    def isSubscriberId(query: String) = List("A-S", "GA0").contains(query.take(3))

    // execute these in parallel
    val memOptF = EitherT(salesforceService.getMembershipBySubscriptionId(subscriberId))
    val subOptF = EitherT(salesforceService.getSubscriptionBySubscriptionId(subscriberId))

    if (isSubscriberId(subscriberId)) {
      (for {
        memOpt <- memOptF
        subOpt <- subOptF
      } yield {
        if (memOpt.isDefined)
          salesforceSubscriptionToIdentityUser(memOpt.get)
        else if (subOpt.isDefined)
          salesforceSubscriptionToIdentityUser(subOpt.get)
        else
          SearchResponse.create(0, 0, Nil)
      }).run
    }
    else Future.successful(\/-(SearchResponse.create(0, 0, Nil)))
  }

  /* If it cannot find an active user, tries looking up a deleted one */
  def findById(id: String): ApiResponse[User] = {
    def deletedUserToActiveUser(user: DeletedUser) =
      User(id = user.id, email = user.email, username = Some(user.username), deleted = true)

    val deletedUserOptF = deletedUsersRepository.findBy(id)
    val activeUserOptF = usersReadRepository.findById(id)

    for {
      activeUserOpt <- activeUserOptF
      deletedUserOpt <- deletedUserOptF
    } yield {
      if (activeUserOpt.isDefined)
        \/-(activeUserOpt.get)
      else if (deletedUserOpt.isDefined)
        \/-(deletedUserToActiveUser(deletedUserOpt.get))
      else
        -\/(ApiError("User not found"))
    }
  }

  def delete(user: User): ApiResponse[ReservedUsernameList] =
    EitherT(usersWriteRepository.delete(user)).fold(
      error => Future.successful(-\/(error)),
      _ => user.username.map(username => reservedUserNameRepository.addReservedUsername(username)).getOrElse {
        reservedUserNameRepository.loadReservedUsernames
      }
    ).flatMap(identity)

  def validateEmail(user: User, emailValidated: Boolean = true): ApiResponse[Boolean] =
    EitherT(usersWriteRepository.updateEmailValidationStatus(user, emailValidated)).fold(
      apiError => -\/(apiError),
      _ => {
        triggerEvents(
          userId = user.id,
          usernameChanged = false,
          displayNameChanged = false,
          emailValidatedChanged = true
        )
        \/-(true)
      }
    )

  def sendEmailValidation(user: User): ApiResponse[Boolean] = {
    validateEmail(user, emailValidated = false).flatMap {
      case \/-(_) => identityApiClient.sendEmailValidation(user.id)
      case -\/(r) => Future.successful(-\/(r))
    }
  }

  def unsubscribeFromMarketingEmails(email: String): ApiResponse[User] = {
    usersWriteRepository.unsubscribeFromMarketingEmails(email)
  }
}
