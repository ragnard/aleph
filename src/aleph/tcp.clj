(ns aleph.tcp
  (:require
    [potemkin :as p]
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log])
  (:import
    [java.io
     IOException]
    [java.net
     InetSocketAddress]
    [io.netty.channel
     Channel
     ChannelHandler
     ChannelPipeline]))

(p/def-derived-map TcpConnection [^Channel ch]
  :server-name (some-> ch ^InetSocketAddress (.localAddress) .getHostName)
  :server-port (some-> ch ^InetSocketAddress (.localAddress) .getPort)
  :remote-addr (some-> ch ^InetSocketAddress (.remoteAddress) .getAddress .getHostAddress))

(alter-meta! #'->TcpConnection assoc :private true)

(defn- ^ChannelHandler server-channel-handler
  [handler options]
  (let [in (s/stream)]
    (netty/channel-handler

      :exception-caught
      ([_ ctx ex]
         (when-not (instance? IOException ex)
           (log/warn ex "error in TCP server")))

      :channel-inactive
      ([_ ctx]
         (s/close! in))

      :channel-active
      ([_ ctx]
         (let [ch (.channel ctx)]
           (handler
             (s/splice
               (netty/sink ch true netty/to-byte-buf)
               in)
             (->TcpConnection ch))))

      :channel-read
      ([_ ctx msg]
         (netty/put! (.channel ctx) in msg)))))

(defn start-server
  "Takes a two-arg handler function which for each connection will be called with a duplex
   stream and a map containing information about the client.  Returns a server object that can
   be shutdown via `java.io.Closeable.close()`, and whose port can be discovered via `aleph.netty.port`.

   |:---|:-----
   | `port` | the port the server will bind to.  If `0`, the server will bind to a random port.
   | `socket-address` | a `java.net.SocketAddress` specifying both the port and interface to bind to.
   | `ssl-context` | an `io.netty.handler.ssl.SslContext` object. If a self-signed certificate is all that's required, `(aleph.netty/self-signed-ssl-context)` will suffice.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it."
  [handler
   {:keys [port socket-address ssl-context bootstrap-transform pipeline-transform]
    :or {bootstrap-transform identity
         pipeline-transform identity}
    :as options}]
  (netty/start-server
    (fn [^ChannelPipeline pipeline]
      (.addLast pipeline "handler" (server-channel-handler handler options))
      (pipeline-transform pipeline))
    ssl-context
    bootstrap-transform
    nil
    (if socket-address
      socket-address
      (InetSocketAddress. port))))

(defn- ^ChannelHandler client-channel-handler
  [options]
  (let [d (d/deferred)
        in (s/stream)]
    [d

     (netty/channel-handler

       :exception-caught
       ([_ ctx ex]
          (when-not (d/error! d ex)
            (log/warn ex "error in TCP client")))

       :channel-inactive
       ([_ ctx]
          (s/close! in))

       :channel-active
       ([_ ctx]
          (let [ch (.channel ctx)]
            (d/success! d
              (s/splice
                (netty/sink ch true netty/to-byte-buf)
                in))))

       :channel-read
       ([_ ctx msg]
          (netty/put! (.channel ctx) in msg))

       :close
       ([_ ctx promise]
          (.close ctx promise)
          (d/error! d (IllegalStateException. "unable to connect"))))]))

(defn client
  "Given a host and port, returns a deferred which yields a duplex stream that can be used
   to communicate with the server.

   |:---|:----
   | `host` | the hostname of the server.
   | `port` | the port of the server.
   | `remote-address` | a `java.net.SocketAddress` specifying the server's address.
   | `local-address` | a `java.net.SocketAddress` specifying the local network interface to use.
   | `ssl?` | if true, the client attempts to establish a secure connection with the server.
   | `insecure?` | if true, the client will ignore the server's certificate.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it."
  [{:keys [host port remote-address local-address ssl? insecure? pipeline-transform bootstrap-transform]
    :or {bootstrap-transform identity}
    :as options}]
  (let [[s handler] (client-channel-handler options)]
    (->
      (netty/create-client
        (fn [^ChannelPipeline pipeline]
          (.addLast pipeline "handler" ^ChannelHandler handler)
          (when pipeline-transform
            (pipeline-transform pipeline)))
        (when ssl?
          (if insecure?
            (netty/insecure-ssl-client-context)
            (netty/ssl-client-context)))
        bootstrap-transform
        (or remote-address (InetSocketAddress. ^String host (int port)))
        local-address)
      (d/catch #(d/error! s %)))
    s))
