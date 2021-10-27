(ns keechma.next.spec
  (:require
   [keechma.next.protocols :as pt]
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

(defn no-root-parent? [subapp]
  (not (contains? subapp :keechma.root/parent)))

(defn inline-app-variant [[variant app]]
  (assoc app :keechma.app/variant variant))

(>def :keechma.root/parent
      #(satisfies? pt/IRootAppInstance %))

(>def :keechma.controller.factory/produce
      fn?)

(>def :keechma.controller/dep-map
      (s/map-of dep? dep?))

(>def :keechma.controller/dep-keyword
      dep?)

(>def :keechma.controller/dep
      (s/or
       :dep-map :keechma.controller/dep-map
       :dep-keyword :keechma.controller/dep-keyword))

(>def :keechma.controller.deps/input
      (s/or
       :dep-map :keechma.controller/dep-map
       :dep-coll (s/coll-of :keechma.controller/dep :kind vector? :min-count 1)))

(>def :keechma.controller/deps
      (s/coll-of :keechma.controller/dep-keyword :kind vector? :min-count 1))

(>def :keechma.controller.name/singleton
      keyword?)

(>def :keechma.controller/proxy
      (s/and keyword? #(isa? % :keechma/controller)))

(>def :keechma.controller.name/identity
      (s/tuple keyword? (complement nil?)))

(>def :keechma.controller.name/factory
      (s/tuple keyword?))

(>def :keechma.controller/type
      (s/or
       :static (s/and keyword? #(isa? % :keechma/controller))
       :dynamic fn?))

(>def :keechma.controller/is-global
      boolean)

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
                           :keechma.controller/type]
                     :opt [:keechma.controller/is-global])
             dynamic-config?))

(>def :keechma.controller.config/static
      (s/and (s/keys :req [:keechma.controller/params
                           :keechma.controller/type]
                     :opt [:keechma.controller/is-global])
             static-config?))

(>def :keechma.controller.config/proxy
      (s/keys
       :req [:keechma.controller/proxy]))

(>def :keechma.controller.config/factory
      (s/keys
       :req [:keechma.controller/deps
             :keechma.controller/type
             :keechma.controller.factory/produce]
       :opt [:keechma.controller/is-global]))

(>def :keechma.controller/config
      (s/or :proxy :keechma.controller.config/proxy
            :static :keechma.controller.config/static
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
       :opt [:keechma.controller/deps
             :keechma.controller/is-global]))

(>def :keechma/controllers
      (s/and (s/map-of #(or (keyword? %) (vector? %)) map?)
             (s/coll-of :keechma/controller :into {})))

(>def :keechma/app
      (s/keys
       :req [:keechma/controllers]
       :opt [:keechma/apps :keechma.root/parent]))

(>def :keechma/nested-app
      (s/and
       no-root-parent?
       (s/or
        :dynamic (s/keys :req [:keechma.app/load :keechma.app/should-run? :keechma.app/deps])
        :static (s/merge :keechma/app (s/keys :req [:keechma.app/should-run? :keechma.app/deps])))))

(>def :keechma.app/should-run? fn?)
(>def :keechma.app/load fn?)
(>def :keechma.app/deps (s/merge :keechma.controller/deps))

(>def :keechma/apps
      (s/map-of keyword? :keechma/nested-app))

(>def :keechma/app-instance map?)