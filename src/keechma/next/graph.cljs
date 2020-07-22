(ns keechma.next.graph
  (:require [com.stuartsierra.dependency :as dep]
            [clojure.set :as set]))

(defn cleanup-dependencies [nodes dependencies]
  (reduce-kv
    (fn [m k v]
      (assoc m k (set/intersection v nodes)))
    {}
    dependencies))

(defn subgraph-reachable-from-set [g start-nodes]
  (let [start-nodes' (set start-nodes)
        t-dependents (dep/transitive-dependents-set g start-nodes')
        t-dependents-w-start-nodes (set/union t-dependents start-nodes')
        dependencies (cleanup-dependencies t-dependents-w-start-nodes (select-keys (:dependencies g) t-dependents))
        dependents (select-keys (:dependents g) t-dependents-w-start-nodes)]
    (dep/->MapDependencyGraph dependencies dependents)))

(defn subgraph-reachable-from [g start]
  (subgraph-reachable-from-set g #{start}))