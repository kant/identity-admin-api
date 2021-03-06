import play.api.http.HttpErrorHandler
import play.api.mvc._
import play.api.mvc.Results._
import scala.concurrent._
import javax.inject.Singleton
import com.gu.identity.util.Logging
import models.ApiError

@Singleton
class ErrorHandler extends HttpErrorHandler with Logging {
  def onClientError(request: RequestHeader, statusCode: Int, message: String) = {
    if(statusCode == play.mvc.Http.Status.BAD_REQUEST) {
      logger.debug(s"Bad request: $request, error: $message")
      Future.successful(BadRequest(ApiError("Client error", message)))
    } else if(statusCode == play.mvc.Http.Status.NOT_FOUND) {
        logger.debug(s"Handler not found for request: $request")
        Future.successful(NotFound)
    } else
        Future.successful(Status(statusCode)("A client error occurred: " + message))
  }

  def onServerError(request: RequestHeader, exception: Throwable) = {
    logger.error(s"Error handling request request: $request", exception)
    Future.successful(InternalServerError(ApiError("Server error", exception.getMessage)))
  }
}
