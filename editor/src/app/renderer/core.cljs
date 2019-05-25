(ns app.renderer.core
  (:require [reagent.core :as reagent :refer [atom]]
            ["react" :refer [createRef]]
            ["ace-builds" :as ace-editor]
            ["/js/sexpAtPoint" :as sexp-at-point]
            ["/js/nrepl-client" :as nrepl-client]
            ["bencode" :as bencode]
            ["net" :as net]
            ["vex-js" :as vex]
            [clojure.core.async :as async]
            [clojure.string :as string :refer [split-lines]]))

(.registerPlugin vex (js/require "vex-dialog"))
(set! (.-className (.-defaultOptions vex)) "vex-theme-os")
(set! (.-text (.-YES (.-buttons (.-dialog vex)))) "Okiedokie")
(set! (.-text (.-NO (.-buttons (.-dialog vex)))) "Aahw hell no")

;; (def nrepl-port 8912)

(enable-console-print!)

(defonce state (atom {:ace-ref nil :nrepl-callbacks {} :inline-ranges []}))

(def electron (js/require "electron"))

(def process (js/require "process"))

(def cwd (.cwd process))

(def nrepl-connection (clojure.core/atom nil))

(defn nrepl-connect! [nrepl-port]
  (.connect @nrepl-connection nrepl-port "127.0.0.1" (fn [])))

(defn nrepl-handler [msg id callback]
  (when @nrepl-connection
    (swap! state assoc-in [:nrepl-callbacks id] callback)
    ;; timeout if something fails
    (async/go (async/<! (async/timeout (* 2 60 1000)))
              (when (contains? (:nrepl-callbacks @state) id)
                (swap! state update-in [:nrepl-callbacks] dissoc id)))
    (.write @nrepl-connection
            (bencode/encode #js {:op "eval"
                                 :id id
                                 :code (str (clojure.string/escape msg {"\\" "\\\\"}) "\n")}))))

#_(defn nrepl-handler [msg callback]
    (.eval @nrepl-connection
           (str (clojure.string/escape msg {"\\" "\\\\"}) "\n")
           (fn [res err]
             (prn "RESPONSE" res "error" err)
             (callback {:stdout res :stderr err}))))

#_(defonce eval-response-handler
    (.on (.-ipcRenderer electron) "response"
         (fn [resp] (when-let [response-chan (get @async-channels (:id resp))]
                      (async/go (async/>! response-chan resp))))))

(defn register-nrepl-receiver []
  (.on @nrepl-connection "data"
       (fn [data]
         (let [decoded-data (bencode/decode data)]
           (if-not (exists? (.-status decoded-data)) ;; status indicates a failure?
             (when (exists? (.-value decoded-data))
               (let [return-value (.toString (.-value decoded-data))
                     id (.toString (.-id decoded-data))]
                 (when-let [callback (get-in @state [:nrepl-callbacks id])]
                   (callback return-value)
                   (swap! state update-in [:nrepl-callbacks] dissoc id))))
             (let [status (.-status (bencode/decode data))
                   status1 (.toString (aget status 0))
                   status2 (when (< 1 (.-length status))
                             (.toString (aget status 1)))
                   status3 (when (< 2 (.-length status))
                             (.toString (aget status 2)))]
               (when-not (= "done" status1)
                 (js/console.error  "REPL FAILURE: " status1 status2 status3))))))))

(defn nrepl-initialize [port]
  (do
    (reset! nrepl-connection (new (.-Socket (.require js/window "net"))))
    (nrepl-connect! port)
    (register-nrepl-receiver)))

(defonce nrepl-status-handler
  (.on (.-ipcRenderer electron) "nrepl"
       (fn [event resp]
         (case (aget resp 0)
           "started" (nrepl-initialize (js/parseInt (aget resp 1)))
           nil))))

#_(defn nrepl-async [data]
    (let [response-chan (async/chan 1)
          symb (str (gensym))]
      (swap! async-channels assoc symb response-chan)
      (.send  (.-ipcRenderer electron) "eval" #js [symb data])
      (async/go (async/<! (async/timeout (* 2 60 1000)))
                (when (contains? @async-channels symb)
                  (swap! async-channels dissoc symb)))
      response-chan))


(defn flash-region [ace-ref sexp-positions]
  (when (and ace-ref (exists? (.-startIndex sexp-positions)))
    (let [pointACoord (.indexToPosition (.-doc (.-session ace-ref)) (.-startIndex sexp-positions))
          pointBCoord (.indexToPosition (.-doc (.-session ace-ref)) (.-endIndex sexp-positions))
          range (new (.-Range js/ace)
                     (.-row pointACoord)
                     (.-column pointACoord)
                     (.-row pointBCoord)
                     (.-column pointBCoord))]
      (set! (.-id range) (.addMarker (.-session ^js ace-ref) range "flashEval" "text"))
      (js/setTimeout #(.removeMarker (.-session ace-ref) (.-id range)) 300))))

(defn evaluate-outer-sexp []
  (when-let [ace-ref (:ace-ref @state)]
    (let [current-text (.getValue ace-ref)
          sexp-positions (sexp-at-point current-text  (.positionToIndex (.-doc (.-session ace-ref)) (.getCursorPosition ace-ref)))]
      (when sexp-positions
        (let [trimmed-bundle (clojure.string/trim (subs current-text
                                                        (.-startIndex sexp-positions)
                                                        (.-endIndex sexp-positions)))]
          (when-not (empty? trimmed-bundle)
            (let [id (str (gensym))
                  react-node (atom nil)]
              (nrepl-handler trimmed-bundle id
                             (fn [res]
                               (when (and ace-ref (exists? (.-startIndex sexp-positions)))
                                 (let [session (.getSession ace-ref)
                                       pointBCoord (.indexToPosition (.-doc session) (.-endIndex sexp-positions))
                                       range (new (.-Range js/ace)
                                                  (.-row pointBCoord)
                                                  (inc (.-column pointBCoord))
                                                  (.-row pointBCoord)
                                                  (+ (.-column pointBCoord) 7 (count res)))]
                                   (flash-region ace-ref sexp-positions)
                                   (.remove (.-doc session)
                                            (new (.-Range js/ace)
                                                 (.-row pointBCoord)
                                                 (.-column pointBCoord)
                                                 (.-row pointBCoord)
                                                 (count (.getLine (.-doc session) (.-row pointBCoord)))))
                                   (.insert session #js {:row (.-row pointBCoord) :column (inc (.-column pointBCoord))} (str " ;; => " res))
                                   (println "=> " res)

                                   (swap! state update :inline-ranges conj range)
                                   #_(set! (.-id range) (.addMarker (.-session ^js ace-ref) range "inlineEval" "text")))))))
            #_(async/go (when-let [ret (async/<! (ipc-async trimmed-bundle))]
                          (prn "RETURN VAL" ret)))
            #_(.send  (.-ipcRenderer electron) "eval" trimmed-bundle)))))))

(def ctrl-down? (atom false))

(def eval-throttle? (atom false))

(defn keydown-listener [evt]
  (let [key-code (.-keyCode evt)]
    (cond
      (= key-code 17) (reset! ctrl-down? true)
      (and @ctrl-down? (= key-code 13) (not @eval-throttle?))
      (do
        (evaluate-outer-sexp)
        (reset! eval-throttle? true)
        (js/setTimeout #(reset! eval-throttle? false) 5))
      (and @ctrl-down? (= key-code 81))
      (.confirm (.-dialog vex)
                #js {:message "You are NOT standing in front of an audience, performing music, and you really mean to quit?"
                     :callback (fn [true?] (when true? (.send (.-ipcRenderer electron) "quit" nil)))})
      (= key-code 123)
      (.toggleDevTools (.getCurrentWebContents (.-remote electron)))
      :else nil)))

(defn keyup-listener [evt]
  (let [key-code (.-keyCode evt)]
    (cond
      (= key-code 17) (reset! ctrl-down? false)
      :else nil)))

;; "FireCode-Medium"  "Space Mono"
(defn powerline []
  [:ul {:className "powerline"}
   [:li {:className "left"}
    [:div
     [:a {:href "#"} "177 "]
     [:a {:href "#"} "*scratch*"]]
    [:div {:className "endsection"}]
    [:div [:a {:href "#"} "Clojure"]]
    [:div {:className "shrinkable"} [:a {:href "#"} "Panaeolus version 0.4.0-alpha"]]
    [:div {:className "endsection"}]]
   [:div {:className "center"}
    [:a {:href "#"} " 8e4c32f32ec869fe521fb4d3c0a69406830b4178"]]
   [:li {:className "right"}
    [:div {:className "endsection"}]
    [:div [:a {:href "#"}
           (let [current-row (str (:current-row @state))
                 current-col (str (:current-column @state))
                 empty-str-row (apply str (repeat (max 0(- 4 (count current-row))) " "))
                 empty-str-col (apply str (repeat (max 0(- 3 (count current-col))) " "))]
             (str "" empty-str-row current-row ":" empty-str-col current-col))]]
    [:div {:className "endsection"}]
    [:div [:a {:href "#"} "Top"]]]])

(defn get-public-ns []
  (.send (.-ipcRenderer electron) "eval" "(ns-publics 'panaeolus.all)"))

(defn request-jre-boot []
  (.send  (.-ipcRenderer electron) "boot-jre" nil))

(defn on-edit-handler [event]
  (let [edit-event? (not (.-readOnly (.-command event)))]
    (if edit-event?
      nil nil)
    #_(js/console.log event))
  nil)


(defn right-click-menu [evt]
  (let [remote (.-remote electron)
        menu (new (.-Menu (.-remote electron)))
        MenuItem (.-MenuItem (.-remote electron))]
    (.append menu (new MenuItem (clj->js {:label "kbd mode"
                                          :submenu [{:label "default"}
                                                    {:label "emacs"
                                                     :click (fn [] (when-let [ace-ref (:ace-ref @state)]
                                                                     (.setKeyboardHandler ace-ref "ace/keyboard/emacs")))}
                                                    {:label "vim"}]})))
    (.popup menu #js {:window (.getCurrentWindow remote)})))

(defn root-component []
  (reagent/create-class
   {:componentWillUnmount
    (fn []
      (.removeEventListener js/document "keydown" keydown-listener)
      (.removeEventListener js/document "keyup" keyup-listener)
      (set! js/window.oncontextmenu nil))
    :componentDidMount
    (fn [this]
      (let [ace-ref (.edit ace-editor "ace")
            editor-session (.getSession ace-ref)]
        (.on (.-commands ace-ref) "exec" on-edit-handler)
        (.set (.-config js/ace) "basePath" "./ace")
        (.setTheme ace-ref "ace/theme/cyberpunk")
        (.setMode editor-session "ace/mode/clojure")
        (.setOption ace-ref "displayIndentGuides" false)
        (.setFontSize ace-ref 23)
        (.addEventListener js/document "keydown" keydown-listener)
        (.addEventListener js/document "keyup" keyup-listener)
        (set! js/window.oncontextmenu right-click-menu)
        (swap! state assoc :ace-ref ace-ref)
        (.focus ace-ref)))
    :reagent-render
    (fn []
      [:div
       [:div {:id "ace"}]
       #_[:> Ace {:mode "clojure"
                  :ref ace-ref
                  ;; (fn [ref] (when-not (:ace-ref @state) (prn "NOOOO") (swap! state assoc :ace-ref ref)))
                  :theme "cyberpunk"
                  :style {:font-family "Space Mono" :font-size "22px"}
                  :maxLines js/Infinity
                  :indentedSoftWrap true
                  :cursorStyle "wide"
                  :showPrintMargin false
                  ;; :markers flash-queue
                  :markers (:flash-queue @state)
                  ;; :markers (if (empty? (:flash-queue @state)) #js [] (clj->js (:flash-queue @state)))
                  ;; :editorProps {:$blockScrolling js/Infinity}
                  :onCursorChange (fn [evt]
                                    (let [current-row (.-row (.-selectionLead evt))
                                          current-row (if (and (string? current-row) (not (empty? current-row)))
                                                        (js/parseInt current-row) current-row)
                                          current-column (.-column (.-selectionLead evt))
                                          current-column (if (and (string? current-column) (not (empty? current-column)))
                                                           (js/parseInt current-column) current-column)]
                                      (swap! state assoc :current-row current-row)
                                      (swap! state assoc :current-column current-column)))
                  :onChange (fn [evt] (swap! state assoc :current-text evt))}]
       [powerline]])}))

(defn reload! []
  (.send (.-ipcRenderer electron) "dev-reload"))

(defn start! []
  (request-jre-boot)
  (reagent/render
   [root-component]
   (js/document.getElementById "app-container")))

(start!)



;; marker-fn (fn [html marker-layer session config]
;;             ;; (str " ;; => " res)
;;             (let [dom-node (aget (.-childNodes (.-element marker-layer)) 1)
;;                   _ (js/console.log dom-node)
;;                   class-name (and dom-node (.-className dom-node))]
;;               (when (and class-name (.includes class-name id))
;;                 (when @react-node (reagent/unmount-component-at-node dom-node))
;;                 (reset! react-node
;;                         (reagent/render
;;                          (let [class (reagent/create-class
;;                                       {:component-will-unmount (fn [] (prn "UNMO" res))
;;                                        :component-will-mount (fn [] (prn "MOUNT" res))
;;                                        :render
;;                                        (fn [] [:p {:style {:margin 0 :padding 0 :margin-left "12px"}} (do (prn "RENDER" res) (str " ;; => " res))])})]
;;                            [class])
;;                          dom-node))
;;                 (js/setTimeout #(let [session (.getSession ace-ref)
;;                                       string-builder #js []
;;                                       start (.createAnchor (.-doc (.getSession ace-ref)) (.-start range))
;;                                       end (.createAnchor (.-doc (.getSession ace-ref)) (.-end range))]
;;                                   (set! (.-start range) start)
;;                                   (set! (.-end range) end)
;;                                   (.drawSingleLineMarker marker-layer string-builder range id config)
;;                                   ) 0)))
;;             ;; (js/console.log html marker-layer session config)
;;             ;; (js/console.log (ace-editor/getMarkerHTML html marker-layer session config range "inlineEval"))
;;             )
