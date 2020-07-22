(ns keechma.next.conformer
  (:require [com.fulcrologic.guardrails.core :refer [>defn | ? =>]]
            [keechma.next.spec]))

(declare conform-apps)

(defn get-controller-variant [controller-name]
  (cond
    (and (vector? controller-name) (= 2 (count controller-name))) :identity
    (and (vector? controller-name) (= 1 (count controller-name))) :factory
    :else :singleton))

(defn get-params-variant [controller-def]
  (if (fn? (:keechma.controller/params controller-def)) :dynamic :static))

(defn conform-controller [[controller-name controller-def]]
  (let [controller-variant (get-controller-variant controller-name)
        params-variant    (get-params-variant controller-def)]
    [controller-name
     (cond-> controller-def
       true (update :keechma.controller/type #(or % (if (vector? controller-name) (first controller-name) controller-name)))
       true (assoc :keechma.controller/variant controller-variant)
       (not= :factory controller-variant) (assoc :keechma.controller.params/variant params-variant))]))

(defn conform-controllers [controllers]
  (->> controllers
    (map conform-controller)
    (into {})))

(defn conform-app [[app-name app-def]]
  (let [app-variant (if (contains? app-def :keechma.app/load) :dynamic :static)]
    [app-name
     (cond-> app-def
       true (assoc :keechma.app/variant app-variant)
       (contains? app-def :keechma/controllers) (update :keechma/controllers conform-controllers)
       (contains? app-def :keechma/apps) (update :keechma/apps conform-apps))]))

(defn conform-apps [apps]
  (->> apps
    (map conform-app)
    (into {})))

(>defn conform [app]
  [any? => :keechma/app]
  (-> app
    (update :keechma/controllers conform-controllers)
    (update :keechma/apps conform-apps)))

(>defn conform-factory-produced [controller-def]
  [any? => :keechma.controller.factory/produced]
  (assoc controller-def :keechma.controller.params/variant (get-params-variant controller-def)))