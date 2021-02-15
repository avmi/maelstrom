(ns maelstrom.client
  "A synchronous client for the Maelstrom network. Handles sending and
  receiving messages, performing RPC calls, and throwing exceptions from
  errors."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [maelstrom [net :as net]
                       [util :as u]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (java.util.concurrent PriorityBlockingQueue
                                 TimeUnit)))

(def default-timeout
  "The default timeout for receiving messages, in millis."
  5000)

(def common-errors
  "Errors which all Maelstrom tests support. A map of error codes to keyword
  names for those errors, and whether or not that error is definite."
  {0  {:definite? false   :name :timeout}
   1  {:definite? true    :name :node-not-found}
   10 {:definite? true    :name :not-supported}
   11 {:definite? true    :name :temporarily-unavailable}})

(defn open!
  "Creates a new synchronous network client, which can only do one thing at a
  time: send a message, or wait for a response. Mutates network to register the
  client node. Options are:

      :errors   A structure defining additional error types. See common-errors."
  ([net]
   (open! net {}))
  ([net opts]
   (let [id (str "c" (:next-client-id (swap! net update :next-client-id inc)))]
     (net/add-node! net id)
     {:net         net
      :node-id     id
      :next-msg-id (atom 0)
      :waiting-for (atom nil)
      :errors      (merge common-errors (:errors opts))})))

(defn close!
  "Closes a sync client."
  [client]
  (reset! (:waiting-for client) :closed)
  (net/remove-node! (:net client) (:node-id client)))

(defn msg-id!
  "Generates a new message ID for a client."
  [client]
  (swap! (:next-msg-id client) inc))

(defn send!
  "Sends a message over the given client. Fills in the message's :src and
  [:body :msg_id]"
  [client msg]
  (let [msg-id (or (:msg_id (:body msg)) (msg-id! client))
        net    (:net client)
        ok?    (compare-and-set! (:waiting-for client) nil msg-id)
        msg    (-> msg
                   (assoc :src (:node-id client))
                   (assoc-in [:body :msg_id] msg-id))]
    (when-not ok?
      (throw (IllegalStateException.
               "Can't send more than one message at a time!")))
    (net/send! net msg)))

(defn recv!
  "Receives a message for the given client. Times out after timeout ms."
  ([client]
   (recv! client default-timeout))
  ([client timeout-ms]
   (let [target-msg-id @(:waiting-for client)
         net           (:net client)
         node-id       (:node-id client)
         deadline      (+ (System/nanoTime) (* timeout-ms 1e6))]
     (assert target-msg-id "This client isn't waiting for any response!")
     (try
       (loop []
         ; (info "Waiting for message" (pr-str target-msg-id) "for" node-id)
         (let [timeout (/ (- deadline (System/nanoTime)) 1e6)
               msg     (net/recv! net node-id timeout)]
           (cond ; Nothing in queue
                 (nil? msg)
                 (throw+ {:type       ::timeout
                          :name       :timeout
                          :definite?  false
                          :code       0}
                         nil
                         "Client read timeout")

                 ; Reply to some other message we sent (e.g. that we gave up on)
                 (not= target-msg-id (:in_reply_to (:body msg)))
                 (recur)

                 ; Hey it's for us!
                 true
                 msg)))
       (finally
         (when-not (compare-and-set! (:waiting-for client)
                                     target-msg-id
                                     nil)
           (throw (IllegalStateException.
                    "two concurrent calls of sync-client-recv!?"))))))))

(defn send+recv!
  "Sends a request and waits for a response."
  [client req-msg timeout]
  (send! client req-msg)
  (recv! client timeout))

(defn throw-errors!
  "Takes a client and a message m, and throws if m's body is of :type \"error\".
  Returns m otherwise."
  [client m]
  (let [body (:body m)]
    (when (= "error" (:type body))
      (let [code      (:code body)
            error     (get (:errors client) code)]
        (throw+ {:type      :rpc-error
                 :code      code
                 :name      (:name error :unknown)
                 :definite? (:definite? error false)
                 :body      body}))))
  m)

(defn rpc!
  "Takes a client, a destination node, and a message body. Sends a message to
  that node, and waits for a response. Returns response body, interpreting
  error codes, if any, as exceptions. Options are:

  :timeout - in milliseconds, how long to wait for a response"
  ([client dest body]
   (rpc! client dest body default-timeout))
  ([client dest body timeout]
     (->> (send+recv! client {:dest dest, :body body} timeout)
          (throw-errors! client)
          :body)))

(def rpc-registry
  "A persistent registry of all RPC calls we've defined. Used to automatically
  generate documentation!"
  (atom []))

(defn unindent
  "Strips leading whitespace from all lines in a string."
  [s]
  (str/replace s #"(^|\n)\s+" "$1"))

(def workloads-preamble
  "A *workload* specifies the semantics of a distributed system: what
  operations are performed, how clients submit requests to the system, what
  those requests mean, what kind of responses are expected, which errors can
  occur, and how to check the resulting history for safety.

  For instance, the *broadcast* workload says that clients submit `broadcast`
  messages to arbitrary servers, and can send a `read` request to obtain the
  set of all broadcasted messages. Clients mix reads and broadcast operations
  throughout the history, and at the end of the test, perform a final read
  after allowing a brief period for convergence. To check broadcast histories,
  Maelstrom looks to see how long it took for messages to be broadcast, and
  whether any were lost.

  This is a reference document, automatically generated from Maelstrom's source
  code by running `lein run doc`. For each workload, it describes the general
  semantics of that workload, what errors are allowed, and the structure of RPC
  messages that you'll need to handle.")

(defn print-registry
  "Prints out the RPC registry to the console, for help messages."
  ([]
   (print-registry @rpc-registry))
  ([rpcs]
   ; Group RPCs by namespace
   (let [ns->rpcs (->> rpcs
                       (group-by (fn [rpc]
                                   (-> rpc
                                       :ns
                                       ns-name
                                       name
                                       (str/split #"\.")
                                       last)))
                       (sort-by key))]

     (println "# Workloads\n")
     (println (unindent workloads-preamble) "\n")

     (println "## Table of Contents\n")
     (doseq [[ns rpcs] ns->rpcs]
       (println (str "- [" (str/capitalize ns) "](#workload-" ns ")")))
     (println)

     (doseq [[ns rpcs] ns->rpcs]
       (println "## Workload:" (str/capitalize ns) "\n")

       (println (unindent (:doc (meta (:ns (first rpcs))))) "\n")

       (doseq [rpc rpcs]
         (println "### RPC:" (str/capitalize (:name rpc)) "\n")
         (println (unindent (:doc rpc)) "\n")
         (println "Request:\n")
         (pprint (:send rpc))
         (println "\nResponse:\n")
         (pprint (:recv rpc))
         (println "\n"))

       (println)))))

(defn check-body
  "Uses a schema checker to validate `data`. Throws an exception if validation
  fails, explaining why the message failed validation. Returns message
  otherwise. Type is either :send or :recv, and is used to construct an
  appropriate type and error message."
  [type schema checker dest req body]
  (when-let [errs (checker body)]
    (throw+ {:type     (case type
                         :send :malformed-rpc-request
                         :recv :malformed-rpc-response)
             :body     body
             :error    errs}
            nil
            (str "Malformed RPC "
                 (case type
                   :send "request. Maelstrom should have constructed a message body like:"
                   :recv (str "response. Maelstrom sent node " dest
                              " the following request:\n\n"
                              (with-out-str (pprint req))
                              "\nAnd expected a response of the form:"))
                 "\n\n"
                 (with-out-str (pprint schema))
                 "\n... but instead " (case type
                                        :send "sent"
                                        :recv "received")
                 "\n\n"
                 (with-out-str (pprint body))
                 "\nThis is malformed because:\n\n"
                 (with-out-str (pprint errs))))))

(defn send-schema
  "Takes a partial schema for an RPC request, and enhances it to include a
  :msg_id field."
  [schema]
  (assoc schema :msg_id s/Int))

(defn recv-schema
  "Takes a partial schema for an RPC response, and enhances it to include a
  :msg_id optional field, and an in_reply_to field."
  [schema]
  (assoc schema
         (s/optional-key :msg_id) s/Int
         :in_reply_to s/Int))

(defmacro defrpc
  "Defines a typed RPC call: a function called `fname`, which takes arguments
  as for `rpc!`, and validates that the sent and received bodies conform to the
  given schema. This is a macro because we want to re-use the schema
  checkers--they're expensive to validate ad-hoc."
  [fname docstring send-schema recv-schema]
  `(let [; Enhance schemas to include message ids and reply_to fields.
         send-schema#  (send-schema ~send-schema)
         recv-schema#  (recv-schema ~recv-schema)
         ; Construct persistent checkers
         send-checker# (s/checker send-schema#)
         recv-checker# (s/checker recv-schema#)

         ; Extract the message type string from the request schema
         message-type# (-> ~send-schema s/explain :type second)]
     (assert (string? message-type#))

     ; Record RPC spec in registry
     (swap! rpc-registry conj
            {:ns   *ns*
             :name (quote ~fname)
             :doc  ~docstring
             :send send-schema#
             :recv recv-schema#})

     (defn ~fname
       ([client# dest# body#]
        (~fname client# dest# body# default-timeout))

       ([client# dest# body# timeout#]
        ; Generate a message ID here, so it passes the schema checker. This is
        ; a little duplicated effort, but it means that schemas say *exactly*
        ; what bodies should be, and I think that will help implementers.
        (let [body# (assoc body#
                           :type   message-type#
                           :msg_id (msg-id! client#))]
          ; Validate request
          (check-body :send send-schema# send-checker# dest# body# body#)
          ; Make RPC call
          (let [res# (rpc! client# dest# body# timeout#)]
            ; Validate response
            (check-body :recv recv-schema# recv-checker# dest# body# res#)
            res#))))))