(ns compojure-throttle.core
  (:require [clojure.core.cache :as cache]
            [environ.core :refer [env]]
            [clj-time.local :as local-time]
            [clj-time.core  :as core-time]))

(def ^:private defaults
  {:compojure-throttle-enabled "true"
   :compojure-throttle-ttl    1000
   :compojure-throttle-tokens 3
   :compojure-throttle-response-code 420})

(defn enabled?
  []
  (boolean (Boolean/valueOf
            (or (env :compojure-throttle-enabled)
                (defaults :compojure-throttle-enabled)))))

(defn- prop
  [key]
  (Integer. (or (env key)
                (defaults key))))

(def ^:private requests
  (atom (cache/ttl-cache-factory {} :ttl (prop :compojure-throttle-ttl))))

(defn- update-cache
  [id tokens]
  (reset! requests (cache/miss @requests id tokens)))

(defn- token-period
  []
  (/ (prop :compojure-throttle-ttl)
     (prop :compojure-throttle-tokens)))

(defn- record
  [tokens]
  {:tokens tokens
   :datetime (local-time/local-now)})

(defn- throttle?
  [id]
  (when-not (cache/has? @requests id)
    (update-cache id (record (prop :compojure-throttle-tokens))))
  (let [entry (cache/lookup @requests id)
        spares (/ (core-time/in-msecs (core-time/interval
                                       (:datetime entry)
                                       (local-time/local-now)))
                  (token-period))
        remaining (dec (+ (:tokens entry) spares))]
    (update-cache id (record remaining))
    (not (pos? remaining))))

(defn- by-ip
  [req]
  (:remote-addr req))

(defn throttle
"Throttle incoming connections from a given source. By default this is based on IP.

Optionally takes a second argument which is a function used to lookup the 'token'
that determines whether or not the request is unique. For example a function that
returns a user token to limit by user id rather than ip. This function should accept
the request as its single argument"
([finder handler]
   (fn [req]
     (if (and (enabled?)
              (throttle? (finder req)))
       {:status (prop :compojure-throttle-response-code)
        :body "You have sent too many requests. Please wait before retrying."}
       (handler req))))
([handler]
   (throttle by-ip handler)))
