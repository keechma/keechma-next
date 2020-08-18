(ns keechma.next.spec
  (:require
    [cljs.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>def]]))

(defn dynamic-config? [{:keys [:keechma.controller/params]}]
  (= :dynamic (first params)))

(defn static-config? [{:keys [:keechma.controller/params]}]
  (= :static (first params)))

(defn dep? [val]
  (or (keyword? val)
      (and (vector? val) (= 1 (count val)))
      (and (vector? val) (= 2 (count val)))))

(defn inline-app-variant [[variant app]]
  (assoc app :keechma.app/variant variant))

(>def :keechma.controller.factory/produce
  fn?)

(>def :keechma.controller/deps
  (s/coll-of dep? :kind vector? :min-count 1))

(>def :keechma.controller.name/singleton
  keyword?)

(>def :keechma.controller.name/identity
  (s/tuple keyword? (complement nil?)))

(>def :keechma.controller.name/factory
  (s/tuple keyword?))

(>def :keechma.controller/type
  (s/or
    :static (s/and keyword? #(isa? % :keechma/controller))
    :dynamic fn?))

(>def :keechma.controller.params/dynamic
  fn?)

(>def :keechma.controller.params/static
  (s/and (complement fn?) boolean))

(>def :keechma.controller/params
  (s/or :dynamic :keechma.controller.params/dynamic
        :static :keechma.controller.params/static))

(>def :keechma.controller.config/dynamic
  (s/and (s/keys :req [:keechma.controller/deps
                       :keechma.controller/params
                       :keechma.controller/type])
         dynamic-config?))

(>def :keechma.controller.config/static
  (s/and (s/keys :req [:keechma.controller/params
                       :keechma.controller/type])
         static-config?))

(>def :keechma.controller.config/factory
  (s/keys :req [:keechma.controller/deps
                :keechma.controller/type
                :keechma.controller.factory/produce]))

(>def :keechma.controller/config
  (s/or :static :keechma.controller.config/static
    :dynamic :keechma.controller.config/dynamic))

(>def :keechma.controller.variant/singleton
  (s/tuple :keechma.controller.name/singleton :keechma.controller/config))

(>def :keechma.controller.variant/identity
  (s/tuple :keechma.controller.name/identity :keechma.controller/config))

(>def :keechma.controller.variant/factory
  (s/tuple :keechma.controller.name/factory :keechma.controller.config/factory))

(>def :keechma/controller
  (s/or
    :singleton :keechma.controller.variant/singleton
    :identity :keechma.controller.variant/identity
    :factory :keechma.controller.variant/factory))

(>def :keechma.controller.factory/produced
  (s/keys
    :req [:keechma.controller/params]
    :opt [:keechma.controller/deps]))

(>def :keechma/controllers
  (s/and (s/map-of #(or (keyword? %) (vector? %)) map?)
         (s/coll-of :keechma/controller :into {})))

(>def :keechma/app
  (s/keys
    :req [:keechma/controllers]
    :opt [:keechma/apps]))

(>def :keechma/nested-app
  (s/or
    :dynamic (s/keys :req [:keechma.app/load :keechma.app/should-run? :keechma.app/deps])
    :static (s/merge :keechma/app (s/keys :req [:keechma.app/should-run? :keechma.app/deps]))))

(>def :keechma.app/should-run? fn?)
(>def :keechma.app/load fn?)
(>def :keechma.app/deps (s/merge :keechma.controller/deps))

(>def :keechma/apps
  (s/map-of keyword? :keechma/nested-app))

(>def :keechma/app-instance map?)