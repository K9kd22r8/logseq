(ns frontend.db.rtc.debug-ui
  "Debug UI for rtc module"
  (:require-macros
   [frontend.db.rtc.macro :refer [with-sub-data-from-ws get-req-id get-result-ch]])
  (:require [frontend.ui :as ui]
            [rum.core :as rum]
            [frontend.db.rtc.core :as rtc-core]
            [cljs.core.async :as async :refer [go <!]]
            [cljs.core.async.interop :refer [p->c]]
            [frontend.db.rtc.op :as op]
            [frontend.state :as state]
            [frontend.db.rtc.ws :as ws]
            [fipp.edn :as fipp]
            [frontend.db.rtc.full-upload-download-graph :as full-upload-download-graph]
            [frontend.util :as util]
            [frontend.handler.notification :as notification]))

(defonce debug-state (atom nil))
;; (def debug-graph-uuid "c9d334d8-977a-428c-af53-25261de27db5")


(defn- <start-rtc
  ([]
   (go
     (let [state (<! (rtc-core/<init-state))]
       (<! (<start-rtc state)))))
  ([state]
   (go
     (let [repo (state/get-current-repo)]
       (<! (<start-rtc state repo)))))
  ([state repo]
   (go
     (if-let [graph-uuid (<! (p->c (op/<get-graph-uuid repo)))]
       (do (reset! debug-state state)
           (<! (rtc-core/<loop-for-rtc state graph-uuid repo))
           state)
       (do (notification/show! "not a rtc-graph" :error false)
           nil)))))

(defn- stop
  []
  (async/close! @(:*stop-rtc-loop-chan @debug-state))
  (reset! debug-state nil))

(defn- push-pending-ops
  []
  (async/put! (:client-op-update-chan @debug-state) true))

(defn- <download-graph
  [repo graph-uuid]
  (go
    (let [state (<! (rtc-core/<init-state))]
      (<! (full-upload-download-graph/<download-graph state repo graph-uuid)))))

(defn- <upload-graph
  []
  (go
    (let [state (<! (rtc-core/<init-state))
          repo (state/get-current-repo)]
      (<! (full-upload-download-graph/<upload-graph state repo)))))

(rum/defcs rtc-debug-ui <
  rum/reactive
  (rum/local nil ::graph-uuid)
  (rum/local nil ::local-tx)
  (rum/local nil ::ops)
  (rum/local nil ::ws-state)
  (rum/local nil ::download-graph-to-repo)
  (rum/local nil ::remote-graphs)
  (rum/local nil ::graph-uuid-to-download)
  [state]
  (let [s (rum/react debug-state)
        rtc-state (and s (rum/react (:*rtc-state s)))]
    [:div
     [:div.flex
      (ui/button "local-state"
                 :class "mr-2"
                 :icon "refresh"
                 :on-click (fn [_]
                             (go
                               (let [repo (state/get-current-repo)
                                     {:keys [local-tx ops]}
                                     (<! (p->c (op/<get-ops&local-tx repo)))
                                     graph-uuid (<! (p->c (op/<get-graph-uuid repo)))]
                                 (reset! (::local-tx state) local-tx)
                                 (reset! (::ops state) (count ops))
                                 (reset! (::graph-uuid state) graph-uuid)
                                 (reset! (::ws-state state) (and s (ws/get-state @(:*ws s))))))))
      (ui/button "graph-list"
                 :icon "refresh"
                 :on-click (fn [_]
                             (go
                               (let [s (or s (<! (rtc-core/<init-state)))
                                     graph-list (with-sub-data-from-ws s
                                                  (<! (ws/<send! s {:req-id (get-req-id)
                                                                    :action "list-graphs"}))
                                                  (:graphs (<! (get-result-ch))))]
                                 (reset! (::remote-graphs state) (map :graph-uuid graph-list))
                                 (reset! debug-state s)))))]

     [:pre (-> {:graph @(::graph-uuid state)
                :rtc-state rtc-state
                :ws (and s (ws/get-state @(:*ws s)))
                :local-tx @(::local-tx state)
                :pending-ops @(::ops state)
                :remote-graphs @(::remote-graphs state)}
               (fipp/pprint {:width 20})
               with-out-str)]
     (if (or (nil? s)
             (= :closed rtc-state))
       (ui/button "start" {:class "my-2"
                           :on-click (fn []
                                       (prn :start-rtc)
                                       (if s
                                         (<start-rtc s)
                                         (<start-rtc)))})

       [:div.my-2.flex
        [:div.mr-2 (ui/button (str "send pending ops")
                              {:on-click (fn [] (push-pending-ops))})]
        [:div (ui/button "stop" {:on-click (fn [] (stop))})]])
     [:hr]
     [:div.flex.flex-row
      (ui/button (str "download graph to")
                 {:class "mr-2"
                  :on-click (fn []
                              (go
                                (when-let [repo @(::download-graph-to-repo state)]
                                  (when-let [graph-uuid @(::graph-uuid-to-download state)]
                                    (prn :download-graph graph-uuid :to repo)
                                    (<! (<download-graph repo graph-uuid))
                                    (notification/show! "download graph successfully")))))})
      [:div.flex.flex-col
       [:select
        {:on-change (fn [e]
                      (let [value (util/evalue e)]
                        (reset! (::graph-uuid-to-download state) value)))}
        (if (seq @(::remote-graphs state))
          (cons [:option {:key "select a remote graph" :value nil} "select a remote graph"]
                (for [graph-uuid @(::remote-graphs state)]
                  [:option {:key graph-uuid :value graph-uuid} (str (subs graph-uuid 0 14) "...")]))
          (list [:option {:key "refresh-first" :value nil} "refresh remote-graphs first"]))]
       [:input.form-input.my-2
        {:on-change (fn [e] (reset! (::download-graph-to-repo state) (util/evalue e)))
         :on-focus (fn [e] (let [v (.-value (.-target e))]
                             (when (= v "repo name here")
                               (set! (.-value (.-target e)) ""))))
         :default-value "repo name here"}]]]
     [:div.flex.my-2
      (ui/button (str "upload current repo") {:on-click (fn []
                                                          (go
                                                            (<! (<upload-graph))
                                                            (notification/show! "upload graph successfully")))})]]))