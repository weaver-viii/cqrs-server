(ns cqrs-server.async-test
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [schema.core :as s]
   [cqrs-server.module :as module]
   [cqrs-server.async :as async]
   [cqrs-server.cqrs :as cqrs]
   [cqrs-server.async :as async]
   [onyx.peer.task-lifecycle-extensions :as l-ext]
   
   [onyx.plugin.datomic]
   [onyx.plugin.core-async]
   [onyx.api]
   
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [taoensso.timbre :as log]))

;; A fully self-contained cqrs test, with in-memory datomic, zookeeper, hornetq and using async
;; channels.
;; This differs from the other tests in that it uses internal zookeeper - the rest use seperate
;; process (port 2181) zookeeper instance.

(def onyxid (java.util.UUID/randomUUID))

(def env-config
  {:hornetq/mode :vm
   :hornetq/server? true
   :hornetq.server/type :vm
   :zookeeper/address "127.0.0.1:2185"
   :zookeeper/server? true
   :zookeeper.server/port 2185
   :onyx/id onyxid
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def peer-config
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2185"   
   :onyx/id onyxid
   :onyx.peer/inbox-capacity 100
   :onyx.peer/outbox-capacity 100
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def config
  {:datomic-uri "datomic:mem://cqrsasync"
   :command-stream (atom nil)
   :event-stream (atom nil)
   :event-store-stream (atom nil)
   :aggregate-out-stream (atom nil)
   :channels [:command-stream :event-stream :event-store-stream :aggregate-out-stream]
   :env env-config
   :peer peer-config
   :onyxid onyxid})

(def catalog
  (cqrs/catalog
   {:command-queue (async/chan-stream :input)
    :out-event-queue (async/chan-stream :output)
    :in-event-queue (async/chan-stream :input)
    :event-store (async/chan-stream :output)
    :aggregate-out (async/chan-stream :output)}))


(async/chan-register :command/in-queue :input (:command-stream config))
(async/chan-register :event/out-queue :output (:event-stream config))
(async/chan-register :event/in-queue :input (:event-stream config))
(async/chan-register :event/store :output (:event-store-stream config))
(async/chan-register :event/aggregate-out :output (:aggregate-out-stream config))

(defn setup-env [db-schema]
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))
  (let [env (onyx.api/start-env env-config)
        peers (onyx.api/start-peers! 10 peer-config)
        dturi (:datomic-uri config)]
    (d/create-database dturi)
    (d/transact (d/connect dturi) (ds/generate-schema d/tempid db-schema))
    (reset! cqrs/datomic-uri dturi)
    {:env env
     :peers peers
     :job (onyx.api/submit-job
           peer-config
           {:catalog catalog :workflow cqrs/command-workflow :task-scheduler :onyx.task-scheduler/round-robin})}))

(defn stop-env [env]
  (onyx.api/kill-job peer-config (:job env))
  (doseq [p (:peers env)] (onyx.api/shutdown-peer p))
  (onyx.api/shutdown-env (:env env))
  (d/delete-database (:datomic-uri config))
  (doseq [c (:channels config)]
    (a/close! @(get config c))
    (reset! (get config c) nil))
  true)

(defn command [type data]
  (cqrs/command (d/basis-t (d/db (d/connect (:datomic-uri config)))) type data))

(defn send-command [type data]
  (a/>!! @(:command-stream config) (command type data)))

(def db-schema
  (concat
   cqrs/db-schema
   [(schema
     base
     (fields
      [uuid :uuid :unique-identity]))]
   module/db-schema))

(deftest run-test []
  (let [env (setup-env db-schema)
        event (delay (first (a/alts!! [@(:event-store-stream config) (a/timeout 1000)])))
        aggregate (delay (first (a/alts!! [@(:aggregate-out-stream config) (a/timeout 1000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (println @event)
      (assert (= (:tp @event) :user/registered))
      (assert @aggregate)
      (assert (= #{["Bob" 33]} (d/q '[:find ?n ?a :where [?e :user/name ?n] [?e :user/age ?a]] (d/as-of (d/db (d/connect (:datomic-uri config))) (:t @aggregate)))))
      (finally
        (stop-env env)))))
