(ns ^:no-doc onyx.peer.window-state
    (:require [onyx.state.protocol.db :as st]
              [onyx.windowing.window-extensions :as we]
      #?(:clj [onyx.protocol.task-state :as ts])
              [onyx.types :as t]
              [onyx.static.util :as u]))

(defn default-state-value 
  [init-fn window state-value]
  (or state-value (init-fn window)))

(defprotocol StateEventReducer
  (window-id [this])
  (trigger-extent! [this state-event trigger-record extent])
  (trigger [this state-event trigger-record])
  (segment-triggers! [this state-event])
  (all-triggers! [this state-event])
  (apply-extents [this state-event])
  (apply-event [this state-event]))

(defn rollup-result [segment]
  (cond (sequential? segment) 
        segment 
        (map? segment)
        (list segment)
        :else
        (throw (ex-info "Value returned by :trigger/emit must be either a hash-map or a sequential of hash-maps." 
                        {:value segment}))))

(defn trigger-state-index-id [{:keys [trigger/id trigger/window-id]}]
  [id window-id])

(defn state-indices 
  ([{:keys [onyx.core/windows onyx.core/triggers]}]
   (state-indices windows triggers))
  ([windows triggers]
   (assert (= 1 (count (distinct (map :window/task windows)))))
   #?(:clj (into {} 
                 (map vector 
                      (concat [:group-idx :group-reverse-idx]
                              (vec (sort (map :window/id windows)))
                              (vec (map (fn [w] [(:window/id w) :state-entry]) windows))
                              (sort (map trigger-state-index-id triggers))) 
                      (map short (range (inc Short/MIN_VALUE) Short/MAX_VALUE))))
      :cljs (into {}
                  (map (juxt identity identity)
                       (into (vec (sort (map :window/id windows)))
                             (sort (map (fn [{:keys [trigger/id trigger/window-id]}]
                                          [id window-id])
                                        triggers))))))))

(defn apply-refinement [{:keys [create-state-update apply-state-update]} state-store idx group-id extent state-event extent-state]
  (if create-state-update
    (let [state-update (create-state-update trigger @extent-state state-event)
          next-extent-state (apply-state-update trigger @extent-state state-update)]
      (st/put-extent! state-store idx group-id extent next-extent-state)
      (assoc state-event :next-state next-extent-state))
    state-event))

(defrecord WindowExecutor [window-extension grouped? triggers window id idx state-store 
                           init-fn emitted create-state-update apply-state-update super-agg-fn
                           event-results lazy? incremental?]
  StateEventReducer
  (window-id [this] id)

  (trigger-extent! [this state-event trigger-record extent]
    (let [{:keys [sync-fn emit-fn trigger]} trigger-record
          {:keys [group-id extent-state]} state-event
          state-event (apply-refinement trigger-record state-store idx group-id extent state-event extent-state) 
          emit-segment (when emit-fn 
                         (emit-fn (:task-event state-event) 
                                  window trigger state-event @extent-state))]
      (when sync-fn 
        (sync-fn (:task-event state-event) window trigger state-event @extent-state))
      (when emit-segment 
        (swap! emitted (fn [em] (into em (rollup-result emit-segment)))))
      (when (= :all (:trigger/post-evictor trigger)) 
        (when incremental? 
          (st/delete-extent! state-store idx group-id extent))
        (when lazy?  ;; Alternatively, materialize LOG?
          (let [[lower upper] (we/bounds window-extension extent)]
            (st/delete-state-entries! state-store idx group-id lower upper))))))

  (trigger [this state-event trigger-record]
    (let [{:keys [trigger trigger-fire? fire-all-extents?]} trigger-record 
          state-event (-> state-event 
                          (assoc :window window) 
                          (assoc :trigger-state trigger-record))
          group-id (:group-id state-event)
          trigger-idx (:idx trigger-record)
          trigger-state (st/get-trigger state-store trigger-idx group-id)
          trigger-state (if (= :not-found trigger-state)
                          ((:init-state trigger-record) trigger)
                          trigger-state)
          next-trigger-state-fn (:next-trigger-state trigger-record)
          new-trigger-state (next-trigger-state-fn trigger trigger-state state-event)
          fire-all? (or fire-all-extents? (not= (:event-type state-event) :segment))
          fire-extents (if fire-all? 
                         (st/group-extents state-store idx group-id)
                         (:extents state-event))]
      ;; FIXME, don't put trigger for other state context
      (st/put-trigger! state-store trigger-idx group-id new-trigger-state)
      (run! (fn [extent] 
              (let [[lower upper] (we/bounds window-extension extent)
                    extent-state (if incremental? 
                                   (delay (st/get-extent state-store idx group-id extent))
                                   (delay (reduce (fn [state entry]
                                                    (apply-state-update window state entry)) 
                                                  (init-fn window)
                                                  (st/get-state-entries state-store idx group-id lower upper))))
                    state-event (-> state-event
                                    (assoc :extent extent)
                                    (assoc :extent-state extent-state)
                                    (assoc :lower-bound lower)
                                    (assoc :upper-bound upper))]
                (when (trigger-fire? trigger new-trigger-state state-event)
                  (trigger-extent! this state-event trigger-record extent))))
            fire-extents)
      this))

  (segment-triggers! [this state-event]
    (run! (fn [[_ trigger-state]] 
            (trigger this state-event trigger-state))
          triggers)
    state-event)

  (all-triggers! [this state-event]
    (run! (fn [[trigger-idx record]] 
            (run! (fn [[group-bytes group-key]] 
                    (trigger this
                             (-> state-event
                                 (assoc :group-id group-bytes)
                                 (assoc :group-key group-key))
                             record))
                  (st/trigger-keys state-store trigger-idx)))
          triggers)
    state-event)

  (apply-extents [this state-event]
    (let [{:keys [segment group-id]} state-event
          time-index (we/time-index window-extension segment)
          operations (we/extent-operations window-extension 
                                           (delay (st/group-extents state-store idx group-id))
                                           segment
                                           time-index)
          updated-extents (distinct (keep (fn [[op extent]] (if (= op :update) extent))
                                          operations))
          transition-entry (create-state-update window segment)]
      (when lazy? 
        (st/put-state-entry! state-store idx group-id time-index transition-entry))
      (run! (fn [[action :as args]] 
              (case action
                :update (let [extent (second args)
                              store-extent? (or incremental? 
                                                ;; FIXME, need linter
                                                (= :session (:window/type window)))]
                          (when store-extent?
                            (let [extent-state (->> extent
                                                    (st/get-extent state-store idx group-id)
                                                    (default-state-value init-fn window))] 
                              (->> transition-entry
                                   (apply-state-update window extent-state)
                                   (st/put-extent! state-store idx group-id extent)))))

                :merge-extents 
                (let [[_ extent-1 extent-2 extent-merged] args
                      agg-merged (super-agg-fn window-extension
                                               (st/get-extent state-store idx group-id extent-1)
                                               (st/get-extent state-store idx group-id extent-2))]
                  (st/put-extent! state-store idx group-id extent-merged agg-merged)
                  (st/delete-extent! state-store idx group-id extent-1)
                  (st/delete-extent! state-store idx group-id extent-2))

                :alter-extents (let [[_ from-extent to-extent] args
                                     v (st/get-extent state-store idx group-id from-extent)]
                                 (st/put-extent! state-store idx group-id to-extent v)
                                 (st/delete-extent! state-store idx group-id from-extent))))
            operations)
      (assoc state-event :extents updated-extents)))

  (apply-event [this state-event]
    (if (= (:event-type state-event) :new-segment)
      (->> state-event
           (apply-extents this)
           (segment-triggers! this))
      (all-triggers! this state-event))
    this))

(defn fire-state-event [windows-state state-event]
  (mapv (fn [ws]
          (apply-event ws state-event))
        windows-state))

#?(:clj 
   (defn process-segment
     [state state-event]
     (let [{:keys [grouping-fn onyx.core/results] :as event} (ts/get-event state)
           state-store (ts/get-state-store state)
           grouped? (not (nil? grouping-fn))
           state-event* (assoc state-event :grouped? grouped?)
           windows-state (ts/get-windows-state state)
           updated-states (reduce 
                           (fn [windows-state* segment]
                             (if (u/exception? segment)
                               windows-state*
                               (let [state-event** (if grouped?
                                                     (let [group-key (grouping-fn segment)]
                                                       (-> state-event* 
                                                           (assoc :segment segment)
                                                           (assoc :group-id (st/group-id state-store group-key))
                                                           (assoc :group-key group-key)))
                                                     (assoc state-event* :segment segment))]
                                 (fire-state-event windows-state* state-event**))))
                           windows-state
                           (mapcat :leaves (:tree results)))
           emitted (doall (mapcat (comp deref :emitted) updated-states))]
       (run! (fn [w] (reset! (:emitted w) [])) windows-state)
       (-> state 
           (ts/set-windows-state! updated-states)
           (ts/update-event! (fn [e] (update e :onyx.core/triggered into emitted)))))))

#?(:clj 
   (defn process-event [state state-event]
     (ts/set-windows-state! state (fire-state-event (ts/get-windows-state state) state-event))))

#?(:clj (defn assign-windows [state event-type]
          (let [state-event (t/new-state-event event-type (ts/get-event state))] 
            (if (= :new-segment event-type)
              (process-segment state state-event)
              (process-event state state-event)))))