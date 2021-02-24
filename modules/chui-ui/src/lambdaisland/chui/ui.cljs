(ns lambdaisland.chui.ui
  (:require [goog.date :as gdate]
            [goog.date.relative :as date-relative]
            [lambdaisland.chui.runner :as runner]
            [lambdaisland.chui.test-data :as test-data]
            [lambdaisland.chui.report :as report]
            [lambdaisland.glogi :as log]
            [reagent.core :as reagent]
            [reagent.dom :as reagent-dom]
            [clojure.string :as str]
            [lambdaisland.deep-diff2 :as ddiff])
  (:require-macros [lambdaisland.chui.styles :as styles])
  (:import (goog.i18n DateTimeFormat)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State

(defonce ui-state (reagent/atom {:hide-passing? false}))

;; Replace the regular atom with an ratom
(set! runner/state (reagent/atom @runner/state))

(declare run-tests)

(defn set-ns-select [ns-names]
  (swap! runner/state
         assoc
         :selected
         (set ns-names)))

(defn toggle-ns-select [namespace-name]
  (swap! runner/state
         update
         :selected
         (fn [selected]
           (let [selected (set selected)]
             (if (contains? selected namespace-name)
               (disj selected namespace-name)
               (conj selected namespace-name))))))

(defn filtered-nss []
  (let [{:keys [query regexp?]} @ui-state
        query (if (string? query)
                (str/trim query)
                "")
        nss (map val (sort-by key @test-data/test-ns-data))]
    (cond
      (str/blank? query)
      nss

      regexp?
      (filter #(re-find (js/RegExp. query) (str (:name %))) nss)

      :else
      (filter #(str/includes? (str (:name %)) query) nss))))

(defn test-plan []
  (let [tests @test-data/test-ns-data]
    (cond
      (seq (:selected @runner/state))
      (select-keys tests (:selected @runner/state))

      (not (str/blank? (:query @ui-state)))
      (into {} (map (juxt :name identity)) (filtered-nss))

      :else
      (into {}
            (remove (comp :test/skip :meta val))
            tests))))

(defn selected-run []
  (or (:selected-run @ui-state)
      (last (:runs @runner/state))))

(defn failing-tests []
  (filter #(runner/fail? (runner/var-summary %))
          (mapcat :vars (:nss (selected-run)))))

(defn selected-tests []
  (let [{:keys [selected-tests]} @ui-state]
    (set
     (if (seq selected-tests)
       (filter #(some #{(:name %)} selected-tests)
               (mapcat :vars (:nss (selected-run))))
       (failing-tests)))))

(defn set-state-from-location []
  (let [params (js/URLSearchParams. js/location.search)
        match (.get params "match")
        include (.get params "include")]
    (cond
      match
      (swap! ui-state assoc :query match :regexp? true)
      include
      (swap! ui-state assoc :query include :regexp? false))))

(defn push-state-to-location []
  (let [{:keys [query regexp?]} @ui-state
        params (js/URLSearchParams.)]
    (when (not (str/blank? query))
      (.set params (if regexp? "match" "include") query))
    (js/window.history.pushState
     {:query query :regexp? regexp?}
     "lambdaisland.chui"
     (str "?" params))))

(defn set-query! [query]
  (swap! ui-state
         #(assoc % :query query))
  (set-ns-select
   (when-not (str/blank? (str/trim query))
     (map :name (filtered-nss))))
  (push-state-to-location))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def iso-time-pattern "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00")
(def human-time-pattern "yyyy-MM-dd HH:mm:ss")

(defn reltime-str [date]
  (date-relative/format (.getTime date)))

(defn iso-time-str [date]
  (.format (DateTimeFormat. iso-time-pattern) date))

(defn human-time-str [date]
  (.format (DateTimeFormat. human-time-pattern) date))

(defn reltime [date]
  [:time {:dateTime (iso-time-str date)} (reltime-str date)])

(defn summary [sum]
  (let [{:keys [tests pass error fail]} sum]
    (str tests " tests, " (+ pass error fail) " assertions"
         (when (pos-int? error)
           (str ", " error " errors"))
         (when (pos-int? fail)
           (str ", " fail " failures")))))

(defn result-class [summary]
  (cond
    (runner/error? summary) "error"
    (runner/fail? summary) "fail"
    :else "pass"))

(defn result-viz-var [{var-name :name :keys [assertions]}]
  [:output.var
   {:title (str var-name)}
   (for [[i {:keys [type]}] (map vector (range) assertions)]
     ^{:key (str i)}
     [:output.assertion {:class (name type)} " ​"])])

(defn result-viz [nss selected]
  [:section.test-results
   (interpose
    " "
    (for [{:keys [ns vars]} nss]
      ^{:key (str ns)}
      [:span.ns
       {:title ns
        :class (when (or (empty? selected)
                         (contains? selected ns))
                 "selected-ns")}
       (for [var-info vars]
         ^{:key (str (:name var-info))}
         [result-viz-var var-info])]))])

(defn run-results [{:keys [ns vars]
                    :as the-ns}]
  (let [{:keys [hide-passing?]} @ui-state
        selected-tests (selected-tests)
        sum (runner/ns-summary the-ns)
        success? (not (runner/fail? sum))]
    (when-not (and hide-passing? success?)
      [:article.ns-run.card
       [:header.ns-run--header
        [:h2 (str ns)]
        [:small.filename (:file (:meta (first vars)))]]
       [:div
        (for [{var-name :name :keys [assertions] :as var-info} (sort-by (comp :line :meta) vars)
              :when (or (not hide-passing?) (some (comp #{:fail :error} :type) assertions))
              :let [selected? (some (comp #{var-name} :name) selected-tests)
                    sum (runner/var-summary var-info)
                    error? (runner/error? sum)
                    fail? (runner/fail? sum)]]
          ^{:key (str var-name)}
          [:article.ns-run-var.selection-target.inner-card
           {:class (str/join " "
                             [(when selected? "selected")
                              (cond
                                error? "ns-error"
                                fail?  "ns-fail"
                                :else  "ns-pass")])
            :on-click #(swap! ui-state
                              (fn [s]
                                (assoc s :selected-tests #{var-name})))}
           [:header.result-var-card
            [:div.var-name-result
             [:h3.ns-run--assertion (name var-name)]
             [:output.test-results [:span.ns [result-viz-var var-info]]]]
            [:p.ns-run--result [:strong (cond error? "Error"
                                              fail?  "Fail"
                                              :else  "Pass")]]]])]])))

(defn test-stop-button []
  (let [{:keys [runs]} @runner/state
        test-plan (test-plan)
        test-count (apply + (map (comp count :tests val) test-plan))]
    (if (false? (:done? (last runs)))
      [:button.button.stop-tests {:on-click #(runner/terminate! (fn [ctx] (log/info :terminated! ctx)))} "Stop"]
      [:button.button.run-tests
       {:on-click #(run-tests)
        :disabled (= 0 test-count)}
       "Run " test-count " tests"])))

(defn general-toggles []
  [:div.general-toggles
   [:button.button {:on-click #(swap! runner/state assoc :runs [])} "Clear results"]
   [:input#regexp
    {:type "checkbox"
     :on-change (fn [e]
                  (swap! ui-state assoc :regexp? (.. e -target -checked))
                  (push-state-to-location))
     :checked (boolean (:regexp? @ui-state))}]
   [:label {:for "regexp"} "Regexp search"]
   [:input#hide-passing
    {:type "checkbox"
     :checked (boolean (:hide-passing? @ui-state))
     :on-change #(swap! ui-state assoc :hide-passing? (.. % -target -checked))}]
   [:label {:for "hide-passing"} "Hide passing tests"]])

(defn header [last-run]
  (let [sum (runner/run-summary last-run)]
    [:header.top-bar
     {:class (cond
               (not last-run)      ""
               (runner/error? sum) "error"
               (runner/fail? sum)  "fail"
               :else               "pass")}
     [general-toggles]
     [:a.name {:href "https://github.com/lambdaisland/chui"} "lambdaisland.chui"]]))

(defn results []
  [:section.column
   [:div.results
    (for [ns (sort-by :ns (:nss (selected-run)))]
      ^{:key (:ns ns)}
      [run-results ns])]])

(defn history [runs]
  [:section.column.history
   [:div.option
    (let [{:keys [selected]} @runner/state
          {:keys [hide-passing?]} @ui-state
          selected-run (selected-run)]
      (for [{:keys [id nss start done? terminated?] :as run} (reverse runs)
            :let [selected? (= id (:id selected-run))
                  active? (and (not selected-run) (= id (:id (last runs))))]]
        (let [sum (runner/run-summary run)]
          ^{:key id}
          [:article.run.selection-target.card
           {:class (cond
                     selected? "selected active"
                     active? "active")
            :on-click (fn [_]
                        (swap! ui-state
                               (fn [s]
                                 (assoc s :selected-run run))))}
           [:header.run-header
            [:progress {:class (cond
                                 (runner/error? sum) "error"
                                 (runner/fail? sum)  "fail"
                                 :else               "pass")
                        :max (:test-count run)
                        :value (:tests (runner/run-summary run))}]
            [:p (reltime-str start)]
            [:small
             (when-not done? "Running")
             (when terminated? "Aborted")]]
           [result-viz (if hide-passing?
                         (filter #(runner/fail? (runner/ns-summary %)) nss)
                         nss) selected]
           [:footer
            [:small [summary sum]]]])))]])

(defn- filter'n-run []
  (let [{:keys [query]} @ui-state]
    [:div.search-bar.card
     [:input {:type :search
              :value query
              :on-change (fn [e]
                           (let [query (.. e -target -value)]
                             (set-query! query)))
              :on-key-up (fn [e]
                           (when (= (.-key e) "Enter")
                             (run-tests)))
              :placeholder "namespace"}]
     [test-stop-button]]))

(defn test-selector []
  (reagent/with-let [this (reagent/current-component)
                     _ (add-watch test-data/test-ns-data ::rerender #(reagent/force-update this))]
    (let [{:keys [selected]} @runner/state
          {:keys [query]} @ui-state]
      [:section.column-namespaces
       [filter'n-run]
       [:div.namespace-selector
        (for [{tests :tests
               ns-sym :name
               ns-meta :meta} (filtered-nss)
              :let [ns-str (str ns-sym)
                    test-count (count tests)]
              :when (< 0 test-count)]
          ^{:key ns-str}
          [:div.namespace-links.selection-target
           {:class (when (contains? selected ns-sym) "selected")
            :on-click #(when (str/blank? query)
                         (toggle-ns-select ns-sym))}
           [:span ns-str]
           [:aside
            [:small test-count (if (= 1 test-count)
                                 " test"
                                 " tests")]]])]])))

(defn comparison [{:keys [actual expected]}]
  [:div
   [:pre [:code (pr-str expected)]]
   [:div "▶" [:pre [:code (pr-str actual)]]]])

(defn error-comparison [{:keys [expected actual]}]
  [:div
   [:pre [:code (pr-str expected)]]
   [:div
    [:span "Error: "]
    (when actual
      [:span (.-message actual)])
    #_(when actual
        (let [error-number (next-error-count)]
          (js/console.log "CLJS Test Error #" error-number)
          (js/console.error actual)
          [:div :view-stacktrace
           (str "For stacktrace: See error number " error-number " in console")]))]])

(defn test-assertions [{var-name :name :keys [assertions] :as var-info}]
  (reagent/with-let [pass? (comp #{:pass} :type)
                     show-passing? (reagent/atom false)]
    [:div.test-info.card
     [:h2.section-header var-name]
     (into [:div]
           (comp
            (if @show-passing?
              identity
              (remove pass?))
            (map (fn [m]
                   [:div.inner-card.assertion {:class (name (:type m))}
                    [report/fail-summary m]])))
           assertions)
     (let [pass-count (count (filter pass? assertions))]
       (when (and (not @show-passing?) (< 0 pass-count))
         [:a.bottom-link {:on-click #(do (reset! show-passing? true) (.preventDefault %)) :href "#"}
          "Show " pass-count " passing assertions"]))]))

(defn assertion-details []
  [:section.column
   (if-let [tests (sort-by (juxt :ns (comp :line :meta)) (selected-tests))]
     (map (fn [test]
            ^{:key (:name test)}
            [test-assertions test])
          tests)
     [:p "All tests pass!"])])

(defn col-count []
  (let [runs? (seq (:runs @runner/state))]
    (cond
      runs?
      4
      :else
      2)))

(defn app []
  (let [{:keys [selected runs]} @runner/state
        runs? (seq runs)]
    [:div#chui
     [:style (styles/inline)]
     [header (last runs)]
     [:main
      {:class (str "cols-" (col-count))}
      [test-selector]
      [history runs]
      (when runs?
        [results])
      (when runs?
        [assertion-details])]]))

(defn run-tests []
  (let [tests (test-plan)]
    (when (seq tests)
      (runner/run-tests tests)
      (swap! ui-state dissoc :selected-run :selected-tests))))

(defn terminate! [done]
  (runner/terminate! done))

(defn render! [element]
  (set-state-from-location)
  (reagent-dom/render [app] element))
