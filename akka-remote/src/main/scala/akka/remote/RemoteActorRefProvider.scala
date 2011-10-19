/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.routing._
import akka.actor.Actor._
import akka.actor.Status._
import akka.dispatch._
import akka.event.EventHandler
import akka.util.duration._
import akka.config.ConfigurationException
import akka.AkkaException
import RemoteProtocol._
import RemoteSystemDaemonMessageType._
import akka.serialization.{ Serialization, Serializer, ActorSerialization, Compression }
import Compression.LZF
import java.net.InetSocketAddress
import com.google.protobuf.ByteString
import akka.AkkaApplication

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteActorRefProvider(val app: AkkaApplication) extends ActorRefProvider {

  import java.util.concurrent.ConcurrentHashMap
  import akka.dispatch.Promise

  val local = new LocalActorRefProvider(app)
  val remote = new Remote(app)

  private val actors = new ConcurrentHashMap[String, Promise[ActorRef]]

  private val remoteDaemonConnectionManager = new RemoteConnectionManager(app, remote)

  def defaultDispatcher = app.dispatcher
  def defaultTimeout = app.AkkaConfig.ActorTimeout

  def actorOf(props: Props, address: String): ActorRef = actorOf(props, address, false)

  def actorOf(props: Props, address: String, systemService: Boolean): ActorRef = {
    Address.validate(address)

    val newFuture = Promise[ActorRef](5000)(defaultDispatcher) // FIXME is this proper timeout?
    val oldFuture = actors.putIfAbsent(address, newFuture)

    if (oldFuture eq null) { // we won the race -- create the actor and resolve the future
      val actor: ActorRef = try {
        app.deployer.lookupDeploymentFor(address) match {
          case Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, failureDetectorType, DeploymentConfig.RemoteScope(remoteAddresses))) ⇒

            val failureDetector = DeploymentConfig.failureDetectorTypeFor(failureDetectorType) match {
              case FailureDetectorType.NoOp                           ⇒ new NoOpFailureDetector
              case FailureDetectorType.RemoveConnectionOnFirstFailure ⇒ new RemoveConnectionOnFirstFailureFailureDetector
              case FailureDetectorType.BannagePeriod(timeToBan)       ⇒ new BannagePeriodFailureDetector(timeToBan)
              case FailureDetectorType.Custom(implClass)              ⇒ FailureDetector.createCustomFailureDetector(implClass)
            }

            val thisHostname = remote.address.getHostName
            val thisPort = remote.address.getPort

            def isReplicaNode: Boolean = remoteAddresses exists { remoteAddress ⇒
              remoteAddress.hostname == thisHostname && remoteAddress.port == thisPort
            }

            if (isReplicaNode) {
              // we are on one of the replica node for this remote actor
              val localProps =
                if (props.dispatcher == Props.defaultDispatcher) props.copy(dispatcher = app.dispatcher)
                else props
              new LocalActorRef(app, localProps, address, false)
            } else {

              // we are on the single "reference" node uses the remote actors on the replica nodes
              val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
                case RouterType.Direct ⇒
                  if (remoteAddresses.size != 1) throw new ConfigurationException(
                    "Actor [%s] configured with Direct router must have exactly 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new DirectRouter

                case RouterType.Random ⇒
                  if (remoteAddresses.size < 1) throw new ConfigurationException(
                    "Actor [%s] configured with Random router must have at least 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new RandomRouter

                case RouterType.RoundRobin ⇒
                  if (remoteAddresses.size < 1) throw new ConfigurationException(
                    "Actor [%s] configured with RoundRobin router must have at least 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new RoundRobinRouter

                case RouterType.ScatterGather ⇒
                  if (remoteAddresses.size < 1) throw new ConfigurationException(
                    "Actor [%s] configured with ScatterGather router must have at least 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new ScatterGatherFirstCompletedRouter()(defaultDispatcher, defaultTimeout)

                case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
                case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
                case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
                case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
              }

              var connections = Map.empty[InetSocketAddress, ActorRef]
              remoteAddresses foreach { remoteAddress: DeploymentConfig.RemoteAddress ⇒
                val inetSocketAddress = new InetSocketAddress(remoteAddress.hostname, remoteAddress.port)
                connections += (inetSocketAddress -> RemoteActorRef(remote.server, inetSocketAddress, address, None))
              }

              val connectionManager = new RemoteConnectionManager(app, remote, connections)

              connections.keys foreach { useActorOnNode(_, address, props.creator) }

              actorOf(RoutedProps(routerFactory = routerFactory, connectionManager = connectionManager), address)
            }

          case deploy ⇒ local.actorOf(props, address, systemService)
        }
      } catch {
        case e: Exception ⇒
          newFuture completeWithException e // so the other threads gets notified of error
          throw e
      }

      // actor foreach app.registry.register // only for ActorRegistry backward compat, will be removed later

      newFuture completeWithResult actor
      actor

    } else { // we lost the race -- wait for future to complete
      oldFuture.await.resultOrException.get
    }
  }

  /**
   * Copied from LocalActorRefProvider...
   */
  def actorOf(props: RoutedProps, address: String): ActorRef = {
    if (props.connectionManager.size == 0) throw new ConfigurationException("RoutedProps used for creating actor [" + address + "] has zero connections configured; can't create a router")
    new RoutedActorRef(props, address)
  }

  def actorFor(address: String): Option[ActorRef] = actors.get(address) match {
    case null   ⇒ None
    case future ⇒ Some(future.get)
  }

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = actors.remove(address) ne null

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = {
    local.actorFor(actor.address) orElse {
      Some(RemoteActorRef(remote.server, new InetSocketAddress(actor.hostname, actor.port), actor.address, None))
    }
  }

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(remoteAddress: InetSocketAddress, actorAddress: String, actorFactory: () ⇒ Actor) {
    app.eventHandler.debug(this, "Instantiating Actor [%s] on node [%s]".format(actorAddress, remoteAddress))

    val actorFactoryBytes =
      app.serialization.serialize(actorFactory) match {
        case Left(error) ⇒ throw error
        case Right(bytes) ⇒
          if (remote.shouldCompressData) LZF.compress(bytes)
          else bytes
      }

    val command = RemoteSystemDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorAddress(actorAddress)
      .setPayload(ByteString.copyFrom(actorFactoryBytes))
      .build()

    val connectionFactory =
      () ⇒ remote.server.actorFor(
        remote.remoteDaemonServiceName, remoteAddress.getHostName, remoteAddress.getPort)

    // try to get the connection for the remote address, if not already there then create it
    val connection = remoteDaemonConnectionManager.putIfAbsent(remoteAddress, connectionFactory)

    sendCommandToRemoteNode(connection, command, withACK = true) // ensure we get an ACK on the USE command
  }

  private def sendCommandToRemoteNode(
    connection: ActorRef,
    command: RemoteSystemDaemonMessageProtocol,
    withACK: Boolean) {

    if (withACK) {
      try {
        (connection ? (command, remote.remoteSystemDaemonAckTimeout)).as[Status] match {
          case Some(Success(receiver)) ⇒
            app.eventHandler.debug(this, "Remote system command sent to [%s] successfully received".format(receiver))

          case Some(Failure(cause)) ⇒
            app.eventHandler.error(cause, this, cause.toString)
            throw cause

          case None ⇒
            val error = new RemoteException("Remote system command to [%s] timed out".format(connection.address))
            app.eventHandler.error(error, this, error.toString)
            throw error
        }
      } catch {
        case e: Exception ⇒
          app.eventHandler.error(e, this, "Could not send remote system command to [%s] due to: %s".format(connection.address, e.toString))
          throw e
      }
    } else {
      connection ! command
    }
  }
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
  val remote: RemoteSupport,
  val remoteAddress: InetSocketAddress,
  val address: String,
  loader: Option[ClassLoader])
  extends ActorRef with ScalaActorRef {

  @volatile
  private var running: Boolean = true

  def isShutdown: Boolean = !running

  def postMessageToMailbox(message: Any, channel: UntypedChannel) {
    val chSender = if (channel.isInstanceOf[ActorRef]) Some(channel.asInstanceOf[ActorRef]) else None
    remote.send[Any](message, chSender, None, remoteAddress, true, this, loader)
  }

  def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Timeout,
    channel: UntypedChannel): Future[Any] = {

    val chSender = if (channel.isInstanceOf[ActorRef]) Some(channel.asInstanceOf[ActorRef]) else None
    val chFuture = if (channel.isInstanceOf[Promise[_]]) Some(channel.asInstanceOf[Promise[Any]]) else None
    val future = remote.send[Any](message, chSender, chFuture, remoteAddress, false, this, loader)

    if (future.isDefined) ActorPromise(future.get)(timeout)
    else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
  }

  def suspend(): Unit = unsupported

  def resume(): Unit = unsupported

  def stop() { //FIXME send the cause as well!
    synchronized {
      if (running) {
        running = false
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
      }
    }
  }

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = {
    SerializedActorRef(uuid, address, remoteAddress.getAddress.getHostAddress, remoteAddress.getPort)
  }

  def startsMonitoring(actorRef: ActorRef): ActorRef = unsupported

  def stopsMonitoring(actorRef: ActorRef): ActorRef = unsupported

  protected[akka] def restart(cause: Throwable): Unit = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}