(ns cqrs-server.simple
  (:require
   [clojure.core.async :as a]
   [schema.core :as s]
   [cqrs-server.simple :as simple]
   [cqrs-server.async :as async]
   [cqrs-server.cqrs :as cqrs]

   [clojure.test :refer :all]
   [taoensso.timbre :as log]))

(def catalog
  [{:onyx/name :in
    :onyx/medium :core.async
    :onyx/type :input}

   {:onyx/name :increment
    :onyx/type :function}

   {:onyx/name :mult
    :onyx/type :function
    :multiplier 2
    :onyx/params [:multiplier]
    :onyx/fn :clojure.core/*}

   {:onyx/name :edge-out
    :onyx/medium :core.async
    :onyx/type :output}
   
   {:onyx/name :edge-in
    :onyx/medium :core.async
    :onyx/type :input}
   
   {:onyx/name :dup
    :onyx/type :function}
   
   {:onyx/name :out
    :onyx/medium :core.async
    :onyx/type :output}])


(def workflow
  [[:in :increment]
   [:increment :dup]
   [:dup :edge-out]
   [:edge-in :mult]
   [:mult :out]
   ])

(defn environment []
  (let [in (a/chan 100)
        out (a/chan 100)
        edge (a/chan 100)]
    {:chans {:in in :out out}
     :lifecycle
     {:in (fn [tmap] {:core.async/chan in})
      :out (fn [tmap] {:core.async/chan out})
      :edge-in (fn [tmap] {:core.async/chan edge})
      :edge-out (fn [tmap] {:core.async/chan edge})
      :increment (fn [tmap] {:onyx.core/fn + :onyx.core/params [5]})
      :dup (fn [tmap] {:onyx.core/fn (fn [x] (repeat 2 x))})}}))

(defn in-out [s x]
  (a/>!! (-> s :env :chans :in) x)
  [(a/<!! (-> s :env :chans :out)) (a/<!! (-> s :env :chans :out))])


(defn setup []
  (let [env (environment)
        ctor-catalog (construct-catalog env catalog)]
    {:env env :pipeline (build-pipeline ctor-catalog workflow)}))

(deftest simple []
  (let [s (setup)
        out-timeout (-> s :env :chans :out)
        source [1 10 43 12 59 -133000]]
    (a/onto-chan (-> s :env :chans :in) source false)
    (let [output (vec (map (fn [_] (a/<!! out-timeout)) (range 12)))]
      (log/info "Result: " output)
      (assert (= (vec (map (fn [i] (* (+ i 5) 2))
                           (mapcat (fn [i] [i i]) source)))
                 (vec output))))))