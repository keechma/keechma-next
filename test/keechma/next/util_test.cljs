(ns keechma.next.util-test
  (:require
    [cljs.test :refer-macros [deftest is testing use-fixtures async]]
    [cljs.pprint :as pprint]
    [keechma.next.util :refer [get-lowest-common-ancestor-for-paths]]))

(deftest lowest-common-ancestor-for-paths
  (is (= [] (get-lowest-common-ancestor-for-paths [[] [:foo] [:bar] [:foo :bar :baz] [:bar :baz :qux] [:bar :baz]])))
  (is (= [] (get-lowest-common-ancestor-for-paths [[:foo] [:bar] [:foo :bar :baz] [:bar :baz :qux] [:bar :baz]])))
  (is (= [:foo] (get-lowest-common-ancestor-for-paths [[:foo] [:foo :bar :baz] [:foo :qux] [:foo :baz]])))
  (is (= [:foo :bar] (get-lowest-common-ancestor-for-paths [[:foo :bar] [:foo :bar :baz] [:foo :bar :qux :foo] [:foo :bar :baz :qux] [:foo :bar :foo]]))))