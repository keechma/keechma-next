(ns keechma.next.tracing
  #?(:cljs (:require-macros [keechma.next.tracing])))

(def ^:dynamic *tracing* {:depth 0 :storage nil})

(defmacro with-tracing [app-state* & body]
  `(let [trace# (-> ~app-state* deref :keechma/trace)]
     (binding [*tracing* (when trace#
                           (-> *tracing*
                               (update :depth inc)
                               (update :storage #(or % (atom {:log [] :keechma.controllers/prev (-> ~app-state* deref :app-db)})))))]
       (let [res# (do ~@body)]
         (when (and trace# (= 1 (:depth *tracing*)))
           (let [tracings# (-> *tracing* :storage deref)]
             (when (-> tracings# :log seq)
               (trace# (assoc tracings# :keechma.controllers/next (-> ~app-state* deref :app-db))))))
         res#))))

(defn trace! [type payload]
  (when (-> *tracing* :depth pos?)
    (swap! (:storage *tracing*) update :log conj {:type type :payload payload})))