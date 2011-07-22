package org.fusesource.fabric.apollo.amqp.protocol.interceptors

import org.fusesource.fabric.apollo.amqp.protocol.interfaces.Interceptor
import org.fusesource.fabric.apollo.amqp.codec.interfaces.AMQPFrame
import collection.mutable.Queue
import java.io.IOException
import org.apache.activemq.apollo.transport.{Transport, TransportListener}
import org.apache.activemq.apollo.util.Logging
import org.fusesource.hawtdispatch._
import org.fusesource.fabric.apollo.amqp.protocol.AMQPCodec
import org.apache.activemq.apollo.broker.{OverflowSink, TransportSink, Sink, SessionSinkMux}
import org.fusesource.fabric.apollo.amqp.protocol.commands.ConnectionCreated
import org.fusesource.fabric.apollo.amqp.codec.types.{AMQPProtocolHeader, AMQPTransportFrame}

/**
 *
 */

class TransportInterceptor extends Interceptor with TransportListener with Logging {

  var dispatch_queue:DispatchQueue = null
  var session_manager: SessionSinkMux[AMQPFrame] = null
  var connection_sink: Sink[AMQPFrame] = null
  var transport: Transport = null

  var _on_connect:Option[() => Unit] = None

  def onTransportCommand(command: AnyRef) {
    command match {
      case f:AMQPFrame =>
        receive(f, new Queue[() => Unit])
      case _ =>
        // TODO - handle this case in some way
    }
  }

  def onRefill() {}

  def onTransportFailure(error: IOException) {}

  def onTransportConnected() {
    trace("Connected to %s:/%s", transport.getTypeId, transport.getRemoteAddress)
    dispatch_queue = transport.getDispatchQueue
    val transport_sink = new TransportSink(transport)

    session_manager = new SessionSinkMux[AMQPFrame](transport_sink.map {
      x =>
        x
    }, dispatch_queue, AMQPCodec)
    connection_sink = new OverflowSink(session_manager.open(dispatch_queue))
    connection_sink.refiller = NOOP
    receive(ConnectionCreated.INSTANCE, new Queue[() => Unit])
    _on_connect.foreach( x => x() )
    transport.resumeRead
  }

  def onTransportDisconnected() {
    trace("Disconnected from %s", transport.getRemoteAddress)
  }

  def send(frame: AMQPFrame, tasks: Queue[() => Unit]) = {
    trace("Sending : %s", frame)
    frame match {
      case f:AMQPTransportFrame =>
        connection_sink.offer(f)
      case f:AMQPProtocolHeader =>
        connection_sink.offer(f)
      case _ =>
    }
    tasks.dequeueAll( (x) => {
      x()
      true
    })
  }

  def on_connect_=(func:() => Unit) = _on_connect = Option(func)
  def on_connect = _on_connect.getOrElse(() => {})

  def receive(frame: AMQPFrame, tasks: Queue[() => Unit]) = {
    trace("Received : %s", frame)
    incoming.receive(frame, tasks)
  }
}