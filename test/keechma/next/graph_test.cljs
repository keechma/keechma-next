(ns keechma.next.graph-test
  (:require
    [keechma.next.graph :refer [subgraph-reachable-from subgraph-reachable-from-set]]
    [cljs.test :refer-macros [deftest is testing use-fixtures async]]
    [com.stuartsierra.dependency :as dep]))

;;       :a
;;      / |
;;    :b  |
;;      \ |
;;       :c
;;      / |
;;    :d :e
;;        |
;;       :f
(def g1 (-> (dep/graph)
            (dep/depend :b :a)                              ; "B depends on A"
            (dep/depend :c :b)                              ; "C depends on B"
            (dep/depend :c :a)                              ; "C depends on A"
            (dep/depend :d :c)                              ; "D depends on C"
            (dep/depend :e :c)                              ; "E depends on C"
            (dep/depend :f :e)))                            ; "F depends on E"

;;      'one    'five
;;        |       |
;;      'two      |
;;       / \      |
;;      /   \     |
;;     /     \   /
;; 'three   'four
;;    |      /
;;  'six    /
;;    |    /
;;    |   /
;;    |  /
;;  'seven
;;
(def g2 (-> (dep/graph)
            (dep/depend 'two 'one)
            (dep/depend 'three 'two)
            (dep/depend 'four 'two)
            (dep/depend 'four 'five)
            (dep/depend 'six 'three)
            (dep/depend 'seven 'six)
            (dep/depend 'seven 'four)))

(deftest subgraph-reachable-from-1
  (let [s1 (-> (dep/graph)
               (dep/depend :d :c)
               (dep/depend :e :c)
               (dep/depend :f :e))]
    (is (= (subgraph-reachable-from g1 :c) s1))))

(deftest subgraph-reachable-from-2
  (let [s1 (-> (dep/graph)
               (dep/depend 'three 'two)
               (dep/depend 'four 'two)
               (dep/depend 'six 'three)
               (dep/depend 'seven 'six)
               (dep/depend 'seven 'four))
        s2 (-> (dep/graph)
               (dep/depend 'four 'five)
               (dep/depend 'seven 'four))]
    (is (= (subgraph-reachable-from g2 'two) s1))
    (is (= (subgraph-reachable-from g2 'five) s2))))

(deftest subgraph-reachable-from-set-1
  (let [s1 (-> (dep/graph)
               (dep/depend :d :c)
               (dep/depend :e :c)
               (dep/depend :f :e))]
    (is (= (subgraph-reachable-from-set g1 #{:c :e}) s1))))

(deftest subgraph-reachable-from-set-2
  (let [s1 (-> (dep/graph)
               (dep/depend 'three 'two)
               (dep/depend 'four 'two)
               (dep/depend 'six 'three)
               (dep/depend 'seven 'six)
               (dep/depend 'seven 'four)
               (dep/depend 'four 'five)
               (dep/depend 'seven 'four))]
    (is (= (subgraph-reachable-from-set g2 #{'two 'five}) s1))))