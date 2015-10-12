package controllers

import models.ApiErrors
import play.api.mvc.{Action, Results}

class LoggingTestController extends Results {

  def notFound() = Action { ApiErrors.notFound }
  def badRequest() = Action { ApiErrors.badRequest("invalid") }
  def internalError() = Action { ApiErrors.internalError }
  def unauthorized() = Action { ApiErrors.unauthorized }


}
