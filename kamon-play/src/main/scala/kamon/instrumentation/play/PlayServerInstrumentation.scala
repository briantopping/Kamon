package kamon.instrumentation.play

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

import io.netty.channel.Channel
import io.netty.handler.codec.http.{HttpRequest, HttpResponse}
import io.netty.util.concurrent.GenericFutureListener
import kamon.Kamon
import kamon.context.Storage
import kamon.instrumentation.akka.http.ServerFlowWrapper
import kamon.instrumentation.context.{CaptureCurrentTimestampOnExit, HasTimestamp}
import kamon.instrumentation.http.HttpServerInstrumentation.RequestHandler
import kamon.instrumentation.http.{HttpMessage, HttpServerInstrumentation}
import kamon.util.CallingThreadExecutionContext
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.api.instrumentation.mixin.Initializer
import kanela.agent.libs.net.bytebuddy.asm.Advice
import play.api.mvc.RequestHeader
import play.api.routing.{HandlerDef, Router}
import play.core.server.NettyServer

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success}

class PlayServerInstrumentation extends InstrumentationBuilder {

  if(isAkkaHttpAround) {

    /**
      * When using the Akka HTTP server, we will use the exact same instrumentation that comes from the Akka HTTP module,
      * the only difference here is that we will change the component name.
      */
    onType("play.core.server.AkkaHttpServer")
      .advise(anyMethods("createServerBinding", "play$core$server$AkkaHttpServer$$createServerBinding"), CreateServerBindingAdvice)
  }

  if(isNettyAround) {

    /**
      * When using the Netty HTTP server we are rolling our own instrumentation which simply requires us to create the
      * HttpServerInstrumentation instance and call the expected callbacks on it.
      */
    onTypes("play.core.server.NettyServer")
      .mixin(classOf[HasServerInstrumentation.Mixin])
      .advise(isConstructor, NettyServerInitializationAdvice)

    onType("play.core.server.netty.PlayRequestHandler")
      .mixin(classOf[HasServerInstrumentation.Mixin])
      .mixin(classOf[HasTimestamp.Mixin])
      .advise(isConstructor, PlayRequestHandlerConstructorAdvice)
      .advise(isConstructor, CaptureCurrentTimestampOnExit)
      .advise(method("handle"), NettyPlayRequestHandlerHandleAdvice)
  }

  /**
    * This final bit ensures that we will apply an operation name right before filters get to execute.
    */
  onType("play.api.http.DefaultHttpRequestHandler")
    .advise(method("filterHandler").and(takesArguments(2)), GenerateOperationNameOnFilterHandler)


  private def isNettyAround(): Boolean =
    try { Class.forName("play.core.server.NettyServerProvider", false, getClass.getClassLoader) != null } catch {
      case _: Throwable => false
    }

  private def isAkkaHttpAround(): Boolean =
    try { Class.forName("play.core.server.AkkaHttpServerProvider", false, getClass.getClassLoader) != null } catch {
      case _: Throwable => false
    }
}


object CreateServerBindingAdvice {

  @Advice.OnMethodEnter
  def enter(): Unit =
    ServerFlowWrapper.changeSettings("play.server.akka-http", "kamon.instrumentation.play.http.server")

  @Advice.OnMethodExit
  def exit(): Unit =
    ServerFlowWrapper.resetSettings()

}

object NettyServerInitializationAdvice {

  @Advice.OnMethodExit
  def exit(@Advice.This server: NettyServer): Unit = {
    val serverWithInstrumentation = server.asInstanceOf[HasServerInstrumentation]
    val config = Kamon.config().getConfig("kamon.instrumentation.play.http.server")
    val instrumentation = HttpServerInstrumentation.from(
      config,
      component = "play.server.netty",
      interface = server.mainAddress.getHostName,
      port = server.mainAddress.getPort
    )

    serverWithInstrumentation.setServerInstrumentation(instrumentation)
  }
}

object NettyPlayRequestHandlerHandleAdvice {

  @Advice.OnMethodEnter
  def enter(@Advice.This requestHandler: Any, @Advice.Argument(0) channel: Channel, @Advice.Argument(1) request: HttpRequest): RequestProcessingContext = {
    val playRequestHandler = requestHandler.asInstanceOf[HasServerInstrumentation]
    val serverInstrumentation = playRequestHandler.serverInstrumentation()

    val serverRequestHandler = serverInstrumentation.createHandler(
      request = toRequest(request, serverInstrumentation.interface(), serverInstrumentation.port()),
      deferSamplingDecision = true
    )

    if(!playRequestHandler.hasBeenUsedBefore()) {
      playRequestHandler.markAsUsed()
      channel.closeFuture().addListener(new GenericFutureListener[io.netty.util.concurrent.Future[_ >: Void]] {
        override def operationComplete(future: io.netty.util.concurrent.Future[_ >: Void]): Unit = {
          val connectionEstablishedTime = Kamon.clock().toInstant(playRequestHandler.asInstanceOf[HasTimestamp].timestamp)
          val aliveTime = Duration.between(connectionEstablishedTime, Kamon.clock().instant())
          serverInstrumentation.connectionClosed(aliveTime, playRequestHandler.handledRequests())
        }
      })
    }

    playRequestHandler.requestHandled()
    RequestProcessingContext(serverRequestHandler, Kamon.storeContext(serverRequestHandler.context))
  }

  @Advice.OnMethodExit
  def exit(@Advice.Enter rpContext: RequestProcessingContext, @Advice.Return result: scala.concurrent.Future[HttpResponse]): Unit = {
    val reqHandler = rpContext.requestHandler

    result.onComplete {
      case Success(value) =>
        reqHandler.buildResponse(toResponse(value), rpContext.scope.context)
        reqHandler.responseSent()

      case Failure(exception) =>
        reqHandler.span.fail(exception)
        reqHandler.responseSent()

    }(CallingThreadExecutionContext)

    rpContext.scope.close()
  }

  case class RequestProcessingContext(requestHandler: RequestHandler, scope: Storage.Scope)

  private def toRequest(request: HttpRequest, serverHost: String, serverPort: Int): HttpMessage.Request = new HttpMessage.Request {
    override def url: String = request.uri()
    override def path: String = request.uri()
    override def method: String = request.method().name()
    override def host: String = serverHost
    override def port: Int = serverPort

    override def read(header: String): Option[String] =
      Option(request.headers().get(header))

    override def readAll(): Map[String, String] =
      request.headers().entries().asScala.map(e => (e.getKey -> e.getValue)).toMap
  }

  private def toResponse(response: HttpResponse): HttpMessage.ResponseBuilder[HttpResponse] = new HttpMessage.ResponseBuilder[HttpResponse] {
    override def build(): HttpResponse =
      response

    override def statusCode: Int =
      response.status().code()

    override def write(header: String, value: String): Unit =
      response.headers().add(header, value)
  }
}

object PlayRequestHandlerConstructorAdvice {

  @Advice.OnMethodExit
  def exit(@Advice.This playRequestHandler: HasServerInstrumentation, @Advice.Argument(0) server: Any): Unit = {
    val instrumentation = server.asInstanceOf[HasServerInstrumentation].serverInstrumentation()
    playRequestHandler.setServerInstrumentation(instrumentation)
    instrumentation.connectionOpened()
  }
}


trait HasServerInstrumentation {
  def serverInstrumentation(): HttpServerInstrumentation
  def setServerInstrumentation(serverInstrumentation: HttpServerInstrumentation): Unit
  def hasBeenUsedBefore(): Boolean
  def markAsUsed(): Unit
  def requestHandled(): Unit
  def handledRequests(): Long
}

object HasServerInstrumentation {

  class Mixin(var serverInstrumentation: HttpServerInstrumentation, var hasBeenUsedBefore: Boolean) extends HasServerInstrumentation {
    private var _handledRequests: AtomicLong = null

    override def setServerInstrumentation(serverInstrumentation: HttpServerInstrumentation): Unit =
      this.serverInstrumentation = serverInstrumentation

    override def markAsUsed(): Unit =
      this.hasBeenUsedBefore = true

    override def requestHandled(): Unit =
      this._handledRequests.incrementAndGet()

    override def handledRequests(): Long =
      this._handledRequests.get()

    @Initializer
    def init(): Unit = {
      _handledRequests = new AtomicLong()
    }
  }
}


object GenerateOperationNameOnFilterHandler {

  private val _operationNameCache = TrieMap.empty[String, String]
  private val _normalizePattern = """\$([^<]+)<[^>]+>""".r

  @Advice.OnMethodEnter
  def enter(@Advice.Argument(0) request: RequestHeader): Unit = {
    request.attrs.get(Router.Attrs.HandlerDef).map(handler => {
      val span = Kamon.currentSpan()
      span.name(generateOperationName(handler))
      span.takeSamplingDecision()
    })
  }

  private def generateOperationName(handlerDef: HandlerDef): String =
    _operationNameCache.getOrElseUpdate(handlerDef.path, {
      // Convert paths of form /foo/bar/$paramname<regexp>/blah to /foo/bar/paramname/blah
      _normalizePattern.replaceAllIn(handlerDef.path, "$1")
  })

}
