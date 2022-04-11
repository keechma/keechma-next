(ns keechma.next.tracing
  #?(:cljs (:require-macros [keechma.next.tracing])))

(def ^:dynamic *tracing* {:depth 0 :storage nil})

(defmulti format-log-head (fn [{:keys [type]}] type))

(defmethod format-log-head :default [{:keys [type]}]
  [type])

(defmethod format-log-head :keechma.controller/meta-state* [{:keys [type payload]}]
  [type (:keechma/controller payload)])

(defmethod format-log-head :keechma.controller/state* [{:keys [type payload]}]
  [type (:keechma/controller payload)])

(defmethod format-log-head :keechma.controller/dispatch [{:keys [type payload]}]
  [type (:keechma/controller payload) (:event payload)])

(defmethod format-log-head :keechma/dispatch [{:keys [type payload]}]
  [type (:keechma/controller payload) (:event payload)])

(defmethod format-log-head :keechma.controller/broadcast [{:keys [type payload]}]
  [type (:keechma/controller payload) (:event payload)])

(defmethod format-log-head :keechma/broadcast [{:keys [type payload]}]
  [type (:keechma/controller payload) (:event payload)])

(defn format-tracing [trace]
  [(-> trace :log first format-log-head)
   trace])

(defn dedupe-changes [[log-head {:keys [log] :as trace}]]
  (let [deduped-log (reduce
                     (fn [acc log-item]
                       (let [[first-log-item & rest] acc]
                         (if (and
                              (or (and (= :keechma.controller/meta-state* (:type log-item))
                                       (= :keechma.controller/meta-state* (:type first-log-item)))
                                  (and (= :keechma.controller/state* (:type log-item))
                                       (= :keechma.controller/state* (:type first-log-item))))
                              (= (get-in log-item [:payload :keechma/controller])
                                 (get-in first-log-item [:payload :keechma/controller])))
                           (let [payload (:payload log-item)
                                 deduped (update first-log-item :payload merge (select-keys payload [:state/next :state.derived/next]))]
                             (conj rest deduped))
                           (conj acc log-item))))
                     '()
                     log)]
    [log-head (assoc trace :log (into [] (reverse deduped-log)))]))

(defn get-running-controllers [app-db]
  (->> app-db
       (filter (fn [[_ v]] (= :running (:phase v))))
       (into {})))

(defn initial-log-state [app-state]
  {:log []
   :keechma.controllers/prev (-> app-state :app-db get-running-controllers)})

(defmacro with-tracing [app-state* & body]
  `(let [trace# (-> ~app-state* deref :keechma/trace)]
     (binding [*tracing* (when trace#
                           (-> *tracing*
                               (update :depth inc)
                               (update :storage #(or % (atom (initial-log-state (deref ~app-state*)))))))]
       (let [res# (do ~@body)]
         (when (and trace# (= 1 (:depth *tracing*)))
           (let [tracings# (-> *tracing* :storage deref)]
             (when (-> tracings# :log seq)
               (trace#
                (-> tracings#
                    (assoc :keechma.controllers/next (-> ~app-state* deref :app-db get-running-controllers))
                    format-tracing)))))
         res#))))

(defn trace! [type payload]
  (when (-> *tracing* :depth pos?)
    (swap! (:storage *tracing*) update :log conj {:type type :payload payload})))