(ns scylla.client
  "Basic Scylla client operations."
  (:require [qbits.alia :as alia]
            [qbits.hayt :as hayt]
            [dom-top.core :as dt]
            [clojure.tools.logging :refer [info warn]]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (com.datastax.driver.core.exceptions NoHostAvailableException
                                                ReadTimeoutException
                                                UnavailableException
                                                WriteFailureException
                                                WriteTimeoutException)
           (com.datastax.driver.core Session
                                     Cluster
                                     Metadata
                                     Host)
           (com.datastax.driver.core.policies RetryPolicy
                                              RetryPolicy$RetryDecision)))

(defn open
  "Returns an map of :cluster :session bound to the given node."
  [node]
  ; TODO: Reconnection (see logs from
  ; com.datastax.driver.core.ControlConnection) has an exponential backoff by
  ; default which could mask errors--tune this to be more aggressive.

  ; I've also seen clients created with custom load balancing policies like so:
  ;(alia/cluster
  ;  {:contact-points (:nodes test)
  ;   :load-balancing-policy (load-balancing/whitelist-policy
  ;                            (load-balancing/round-robin-policy)
  ;                            (map (fn [node-name]
  ;                                   (InetSocketAddress. node-name 9042))
  ;                                 (if (= connect-type :single)
  ;                                   (do
  ;                                     (info "load balancing only" node)
  ;                                     [(name node)])
  ;                                   (:nodes test))))})
  (let [cluster (alia/cluster
                  {:contact-points [node]
                   ; We want to force all requests to go to this particular
                   ; node, to make sure that every node actually tries to
                   ; execute requests--if we allow the smart client to route
                   ; requests to other nodes, we might fail to observe behavior
                   ; on isolated nodes during a partition.
                   :load-balancing-policy {:whitelist [{:hostname node
                                                        :port 9042}]}})]
    (try (let [session (alia/connect cluster)]
           {:cluster cluster
            :session session})
         (catch Throwable t
           (alia/shutdown cluster)
           (throw t)))))

(defn close!
  "Closes a connection map--both cluster and session."
  [conn]
  (alia/shutdown (:session conn))
  (alia/shutdown (:cluster conn)))

(def await-open-interval
  "How long to sleep between connection attempts, in ms"
  5000)

(defn await-open
  "Blocks until a connection is available, then returns that connection."
  [node]
  (dt/with-retry [tries 32]
    (let [c (open node)]
      (info :session (:session c))
      (info :desc-cluster
            (alia/execute (:session c)
                          (hayt/->raw (hayt/select :system.peers))))

      c)
    (catch NoHostAvailableException e
      (when (zero? tries)
        (throw+ {:type :await-open-timeout
                 :node node}))
      (info node "not yet available, retrying")
      (Thread/sleep await-open-interval)
      (retry (dec tries)))))

; This policy should only be used for final reads! It tries to
; aggressively get an answer from an unstable cluster after
; stabilization
(def aggressive-read
  (proxy [RetryPolicy] []
    (onReadTimeout [statement cl requiredResponses
                    receivedResponses dataRetrieved nbRetry]
      (if (> nbRetry 100)
        (RetryPolicy$RetryDecision/rethrow)
        (RetryPolicy$RetryDecision/retry cl)))

    (onWriteTimeout [statement cl writeType requiredAcks
                     receivedAcks nbRetry]
      (RetryPolicy$RetryDecision/rethrow))

    (onUnavailable [statement cl requiredReplica aliveReplica nbRetry]
      (info "Caught UnavailableException in driver - sleeping 2s")
      (Thread/sleep 2000)
      (if (> nbRetry 100)
        (RetryPolicy$RetryDecision/rethrow)
        (RetryPolicy$RetryDecision/retry cl)))))

(defmacro remap-errors-helper
  "Basic error remapping. See remap-errors."
  [& body]
  `(try+ ~@body
         (catch NoHostAvailableException e#
           (throw+ {:type      :no-host-available
                    :definite? true}))
         (catch ReadTimeoutException e#
           (throw+ {:type      :read-timeout
                    :definite? false}))
         (catch UnavailableException e#
           (throw+ {:type      :unavailable
                    :definite? true}))
         (catch WriteFailureException e#
           (throw+ {:type      :write-failure
                    :definite? false}))
         (catch WriteTimeoutException e#
           (throw+ {:type      :write-timeout
                    :definite? false}))))

(defmacro remap-errors
  "Evaluates body, catching known client errors and remapping them to Slingshot
  exceptions for ease of processing."
  [& body]
  `(try+ (remap-errors-helper ~@body)
         ; Sometimes, but not always, Alia wraps exceptions in its own ex-info,
         ; which *would* be helpful if we didn't already have to catch the
         ; Cassandra driver exceptions on our own. We extract the cause of the
         ; ex-info in this case, and try remapping it.
         (catch [:type :qbits.alia/execute] e#
           (remap-errors-helper (throw (:cause ~'&throw-context))))))

(defmacro slow-no-host-available
  "Introduces artificial latency for NoHostAvailableExceptions, which
  prevents us from performing a million no-op requests a second when the client
  thinks every node is down."
  [& body]
  `(try ~@body
        (catch NoHostAvailableException e#
          (Thread/sleep 2000)
          (throw e#))))

(defmacro with-errors
  "Takes an operation, a set of :f's which are idempotent, and a body to
  evaluate. Evaluates body, slowing no-host-available errors, remapping errors
  to friendly ones. When a known error is caught, returns op with :type :fail
  or :info, depending on whether or not it is a definite error, and whether the
  operation is idempotent."
  [op idempotent? & body]
  `(try+ (remap-errors (slow-no-host-available ~@body))
         (catch (contains? ~'% :definite?) e#
           (assoc ~op
                  :type (if (or (~idempotent? (:f ~op))
                                (:definite? e#))
                          :fail
                          :info)
                  :error e#))))