(ns cmr.common.api.web-server
  "Defines a web server component."
  (:require
   [clojure.core.reducers :as reducers]
   [clojure.java.io :as io]
   [cmr.common.config :refer [defconfig]]
   [cmr.common.lifecycle :as lifecycle]
   [cmr.common.log :refer [debug info warn error]]
   [cmr.common.mime-types :as mt]
   [ring.adapter.jetty :as jetty])
  (:import
   (java.io ByteArrayInputStream InputStream)
   (org.eclipse.jetty.server Server NCSARequestLog Connector HttpConnectionFactory)
   (org.eclipse.jetty.server.handler RequestLogHandler)
   (org.eclipse.jetty.servlets.gzip GzipHandler)))

(def MIN_THREADS
  "The minimum number of threads for Jetty to use to process requests. The was originally set to the
  ring jetty adapter default of 8."
  8)

(def MAX_THREADS
  "The maximum number of threads for Jetty to use to process requests. This was originally set to
  the ring jetty adapter default of 50.

  VERY IMPORTANT NOTE: The value set here must correspond to the number of persistent HTTP
  connections we use in the transmit library. Do not change this or refactor this code without
  making sure that the transmit library uses the same amount."
  50)

(def MIN_GZIP_SIZE
  "The size that will be used to determine if responses should be GZIP'd. See the following Stack
  Overflow question:
  http://webmasters.stackexchange.com/questions/31750/what-is-recommended-minimum-object-size-for-gzip-performance-benefits
  Akamai recommend 860 bytes. We're transmitting UTF-8 which should be about a byte a character."
  860)

(def ONE_MB 1048576)

(def MAX_REQUEST_HEADER_SIZE
  "The maximum request header size. This is set to 1MB to handle requests with long urls."
  ONE_MB)

(def MAX_REQUEST_BODY_SIZE
 "The maximum request body size which the application will accept. This is set to prevent large,
  invalid requests coming in that cause out of memory exceptions. Requests to save the humanizer
  report in Cubby can be in the 5 to 10 MB range."
 (* 50 ONE_MB))

(def buffer-size
  "Number of bytes to allocate for the buffer used for verifying request body size is not too
  large. We want to use a smaller buffer size than the max request body size so for small requests
  we use a small amount of memory to perform the verification."
  512)

(defn- read-into-buffer
  "Reads from a stream into the provided byte-array buffer. Returns a map with details about the
  read operation. The keys in the map are:
  :at-end? - indicates whether the entire stream has been read.
  :bytes-read - number of bytes read from the stream. If nothing was read return 0 rather than -1.
  :buffer-full? - indicates whether the max-bytes-to-read were read."
  [input-stream buffer offset max-bytes-to-read]
  (let [bytes-read (.read input-stream buffer offset max-bytes-to-read)]
    {:at-end? (= -1 bytes-read)
     :bytes-read (max 0 bytes-read) ;; Java input stream read will return -1 when no bytes are read
                                    ;; but we want to return the actual bytes read of 0.
     :buffer-full? (= bytes-read max-bytes-to-read)}))

(def request-body-too-large-response
  "Response to return when the request body is larger than our allowed MAX_REQUEST_BODY_SIZE."
  {:status 413
   :content-type :text
   :body "Request body exceeds maximum size"})

(defn- byte-stream-from-buffers
  "Reconstructs request body into a new input stream from all of the buffers used to validate the
  request body size. This is required so that the request body can be read successfully from
  within application routes code."
  [buffers total-bytes]
  (let [request-body-bytes (->> buffers
                                persistent!
                                (reducers/mapcat vec)
                                reducers/foldcat
                                (into [])
                                byte-array)]
    (ByteArrayInputStream. request-body-bytes 0 total-bytes)))

(defn- routes-fn-verify-size
  "Takes the passed in routes function and wraps it with another function that will verify request
   sizes do not exceed the maximum size.
   Before calling routes-fn function, check to make sure the request body size is not too large (greater
   than MAX_REQUEST_BODY_SIZE). If the body size is too large, throw an error, otherwise call the
   routes-fn function"
  [routes-fn]
  (fn [request]
    (let [^InputStream body-input-stream (:body request)]
      (loop [total-bytes-read 0
             bytes-read-for-current-buffer 0
             input-byte-arrays (transient [])
             current-buffer (byte-array buffer-size)]
        (let [{:keys [bytes-read at-end? buffer-full?]}
              (read-into-buffer body-input-stream current-buffer bytes-read-for-current-buffer
                                (- buffer-size bytes-read-for-current-buffer))
              input-byte-arrays (if (or at-end? buffer-full?)
                                  (conj! input-byte-arrays current-buffer)
                                  input-byte-arrays)
              current-buffer (if (and buffer-full? (not at-end?))
                               (byte-array buffer-size)
                               current-buffer)
              bytes-read-for-current-buffer (if buffer-full?
                                              0
                                              (+ bytes-read-for-current-buffer bytes-read))
              total-bytes-read (+ total-bytes-read bytes-read)]

          ;; If the entire request body has been read or if the amount of bytes read is
          ;; greater than MAX_REQUEST_BODY_SIZE, process based on num bytes read
          ;; otherwise loop to continue reading the request body
          (if (or at-end? (> total-bytes-read MAX_REQUEST_BODY_SIZE))
            (if (> total-bytes-read MAX_REQUEST_BODY_SIZE)
              request-body-too-large-response
              ;; Reconstruct request body into a new input stream since the current has been read
              (let [request-body (byte-stream-from-buffers input-byte-arrays total-bytes-read)]
                (routes-fn (assoc request :body request-body))))
            (recur total-bytes-read
                   bytes-read-for-current-buffer
                   input-byte-arrays
                   current-buffer)))))))

(defn create-access-log-handler
  "Setup access logging for each application. Access log entries will go to stdout similar to
  application logging. As a result the access log entries will be in the same log as the
  application log."
  [existing-handler]
  (doto (RequestLogHandler.)
    (.setHandler existing-handler)
    (.setRequestLog
      (doto (NCSARequestLog.)
        (.setLogLatency true)
        (.setLogDateFormat "yyyy-MM-dd HH:mm:ss.SSS")))))

(defn- create-gzip-handler
  "Setup gzip compression for responses.  Compression will be used for any response larger than
  the configured minimum size."
  [existing-handler min-gzip-size]
  (doto (GzipHandler.)
    (.setHandler existing-handler)
    ;; All the mime types that we want to support compression with must be specified here.
    (.setMimeTypes ^java.util.Set (set mt/all-supported-mime-types))
    (.setMinGzipSize min-gzip-size)))

(defrecord WebServer
  [
   ;; The port Jetty will be running on
   port

   ;; Whether gzip compressed responses are enabled or not
   use-compression?

   ;; Whether access log is enabled or not.
   use-access-log?

   ;; A function that will return the routes. Should accept a single argument of the system.
   routes-fn

   ;;The actual Jetty instance
   server]

  lifecycle/Lifecycle

  (start
    [this system]
    (try
      (let [{:keys [port routes-fn use-compression?]} this
            routes (routes-fn-verify-size (routes-fn system))
            ^Server server (jetty/run-jetty
                             routes
                             {:port port
                              :join? false
                              :min-threads MIN_THREADS
                              :max-threads MAX_THREADS
                              :configurator (fn [^Server jetty]
                                              (doseq [^Connector connector (.getConnectors jetty)]
                                                (let [^HttpConnectionFactory http-conn-factory
                                                      (first (.getConnectionFactories connector))]
                                                  (.setRequestHeaderSize
                                                    (.getHttpConfiguration http-conn-factory)
                                                    MAX_REQUEST_HEADER_SIZE))))})]


        (.stop server)

        (let [request-handler (if use-compression?
                                (create-gzip-handler (.getHandler server) MIN_GZIP_SIZE)
                                (.getHandler server))
              request-handler (if use-access-log?
                                (create-access-log-handler request-handler)
                                request-handler)]
          (doto server
            (.setHandler request-handler)
            (.start)))
        (info "Jetty started on port" port)
        (assoc this :server server))
      (catch Exception e
        (info "Failed to start jetty on port" port)
        (throw e))))

  (stop
    [this system]
    (when-let [^Server server (:server this)]
      (.stop server))
    (assoc this :server nil)))

(defn create-web-server
  "Creates a new web server. Accepts argument of port and a routes function that should accept
  system argument and return compojure routes to use."
  ([port routes-fn]
   (create-web-server port routes-fn true true))
  ([port routes-fn use-compression use-access-log]
   (map->WebServer {:port port
                    :use-compression? use-compression
                    :use-access-log? use-access-log
                    :routes-fn routes-fn})))
