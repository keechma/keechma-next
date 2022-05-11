(ns keechma.next.event
  (:refer-clojure :exclude [sequence])
  (:require [keechma.next.controller :as ctrl]))

(defn get-payload [payload payload-override]
  (if (fn? payload)
    (payload payload-override)
    (or payload-override payload)))

(defprotocol IEvent
  (trigger [this ctrl] [this ctrl payload-override]))

(defrecord DispatchEvent [controller-name event payload]
  IEvent
  (trigger [this ctrl]
    (trigger this ctrl nil))
  (trigger [_ ctrl payload-override]
    (ctrl/dispatch ctrl controller-name event (get-payload payload payload-override))))

(defrecord BroadcastEvent [event payload]
  IEvent
  (trigger [this ctrl]
    (trigger this ctrl nil))
  (trigger [_ ctrl payload-override]
    (ctrl/broadcast ctrl event (get-payload payload payload-override))))

(defrecord Sequence [events]
  IEvent
  (trigger [this ctrl]
    (trigger this ctrl nil))
  (trigger [_ ctrl payload]
    (ctrl/transact ctrl (fn []
                          (doseq [event events]
                            (trigger event ctrl payload))))))

(defn to-dispatch
  ([controller-name event]
   (to-dispatch controller-name event nil))
  ([controller-name event payload]
   (->DispatchEvent controller-name event payload)))

(defn to-broadcast
  ([event] (to-broadcast event nil))
  ([event payload]
   (->BroadcastEvent event payload)))

(defn sequence [& events]
  (->Sequence events))

(def noop
  (reify
    IEvent
    (trigger [_ _])
    (trigger [_ _ _])
    IFn
    (-invoke [_]
      noop)))
