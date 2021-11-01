(ns keechma.next.core.event-test
  (:require
   [cljs.test :refer-macros [deftest is]]
   [keechma.next.controller :as ctrl]
   [keechma.next.core :refer [start! dispatch get-derived-state]]
   [keechma.next.event :as event]))

(def vec-conj (fnil conj []))

(derive ::event-producer :keechma/controller)

(defmethod ctrl/handle ::event-producer [{:keys [on-dispatch on-broadcast on-sequence] :as ctrl} ev payload]
  (case ev
    :on-dispatch-with-payload (event/trigger on-dispatch ctrl payload)
    :on-dispatch-without-payload (event/trigger on-dispatch ctrl)

    :on-broadcast-with-payload (event/trigger on-broadcast ctrl payload)
    :on-broadcast-without-payload (event/trigger on-broadcast ctrl)

    :on-sequence-with-payload (event/trigger on-sequence ctrl payload)
    :on-sequence-without-payload (event/trigger on-sequence ctrl)

    nil))

(derive ::event-consumer :keechma/controller)

(defmethod ctrl/handle ::event-consumer [{:keys [state*]} ev payload]
  (swap! state* vec-conj [ev payload]))

(deftest events-1
  (let [app {:keechma/controllers
             {::event-producer {:keechma.controller/params true
                                :on-dispatch (event/to-dispatch [::event-consumer 1] :dispatch :dispatch-payload)
                                :on-broadcast (event/to-broadcast :broadcast :broadcast-payload)
                                :on-sequence (event/sequence
                                              (event/to-dispatch [::event-consumer 1] :sequence-dispatch :sequence-dispatch-payload)
                                              (event/to-broadcast :sequence-broadcast :sequence-broadcast-payload))}
             [::event-consumer 1] {:keechma.controller/params true}
             [::event-consumer 2] {:keechma.controller/params true}}}
        app-instance (start! app)]
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]]
            [::event-consumer 2] [[:keechma.on/start true]]}
         (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-dispatch-with-payload :dispatch-payload-override)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]]
            [::event-consumer 2] [[:keechma.on/start true]]}
           (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-dispatch-without-payload)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]]
            [::event-consumer 2] [[:keechma.on/start true]]}
           (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-dispatch-with-payload nil)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]]
            [::event-consumer 2] [[:keechma.on/start true]]}
           (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-broadcast-with-payload :broadcast-payload-override)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload-override]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload-override]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-broadcast-without-payload)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]]}
       (get-derived-state app-instance)))
    

    (dispatch app-instance ::event-producer :on-broadcast-with-payload nil)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-sequence-with-payload :sequence-payload-override)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-dispatch :sequence-payload-override]
                                  [:sequence-broadcast :sequence-payload-override]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-broadcast :sequence-payload-override]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-sequence-without-payload)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-dispatch :sequence-payload-override]
                                  [:sequence-broadcast :sequence-payload-override]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-broadcast :sequence-payload-override]
                                  [:sequence-broadcast :sequence-broadcast-payload]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-sequence-with-payload nil)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload-override]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-dispatch :sequence-payload-override]
                                  [:sequence-broadcast :sequence-payload-override]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload-override]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-broadcast :sequence-payload-override]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]}
       (get-derived-state app-instance)))))


(deftest events-2
  (let [app {:keechma/controllers
             {::event-producer {:keechma.controller/params true
                                :on-dispatch (event/to-dispatch [::event-consumer 1] :dispatch (constantly :dispatch-payload))
                                :on-broadcast (event/to-broadcast :broadcast (constantly :broadcast-payload))
                                :on-sequence (event/sequence
                                              (event/to-dispatch [::event-consumer 1] :sequence-dispatch (constantly :sequence-dispatch-payload))
                                              (event/to-broadcast :sequence-broadcast (constantly :sequence-broadcast-payload)))}
             [::event-consumer 1] {:keechma.controller/params true}
             [::event-consumer 2] {:keechma.controller/params true}}}
        app-instance (start! app)]
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]]
            [::event-consumer 2] [[:keechma.on/start true]]}
         (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-dispatch-with-payload :dispatch-payload-override)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]]
            [::event-consumer 2] [[:keechma.on/start true]]}
           (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-dispatch-without-payload)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]]
            [::event-consumer 2] [[:keechma.on/start true]]}
           (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-dispatch-with-payload nil)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]]
            [::event-consumer 2] [[:keechma.on/start true]]}
           (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-broadcast-with-payload :broadcast-payload-override)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-broadcast-without-payload)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]]}
       (get-derived-state app-instance)))
    

    (dispatch app-instance ::event-producer :on-broadcast-with-payload nil)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-sequence-with-payload :sequence-payload-override)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-sequence-without-payload)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]}
       (get-derived-state app-instance)))
    
    (dispatch app-instance ::event-producer :on-sequence-with-payload nil)
    (is (= {::event-producer nil
            [::event-consumer 1] [[:keechma.on/start true]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:dispatch :dispatch-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-dispatch :sequence-dispatch-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]
            [::event-consumer 2] [[:keechma.on/start true]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:broadcast :broadcast-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]
                                  [:sequence-broadcast :sequence-broadcast-payload]]}
       (get-derived-state app-instance)))))

(deftest noop
  (let [app-1 {:keechma/controllers
               {::event-producer {:keechma.controller/params true}}}
        app-2 {:keechma/controllers
               {::event-producer {:keechma.controller/params true
                                  :on-dispatch event/noop}}}
        app-3 {:keechma/controllers
               {::event-producer {:keechma.controller/params true
                                  :on-dispatch (event/noop)}}}
        app-1-instance (start! app-1)
        app-2-instance (start! app-2)
        app-3-instance (start! app-3)]
    
    (is (thrown? js/Error (dispatch app-1-instance ::event-producer :on-dispatch-without-payload)))
    (is (nil? (dispatch app-2-instance ::event-producer :on-dispatch-without-payload)))
    (is (nil? (dispatch app-3-instance ::event-producer :on-dispatch-without-payload)))))
