(ns keechma.next.tracing
  #?(:cljs (:require-macros [keechma.next.tracing])))

(def ^:dynamic *tracing* nil)

(defmacro with-tracing [app-state* & body]
  `(binding [*tracing* (or *tracing* (atom []))]
     (let [res# (do ~@body)]
       (when-let [trace# (-> ~app-state* deref :keechma/trace)]
         (trace# @*tracing*))
       res#)))

(defn trace! [type payload]
  (when *tracing*
    (swap! *tracing* conj {:type type :payload payload})))