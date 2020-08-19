(ns lambdaisland.chui.test-data
  (:require [cljs.env :as env]))

(defn- var-ns [var]
  (symbol (namespace (:name var))))

(defn- var-name [var]
  (symbol (name (:name var))))

(defn- test-var-info [var]
  {:name `'~(:name var)
   :test `(or (:test (meta (var ~(:name var))))
              (fn []
                (cljs.test/do-report {:type :fail
                                      :file (:file (meta (var ~(:name var))))
                                      :line (:line (meta (var ~(:name var))))
                                      :message (str "Test var " '~(:name var) " is known to the compiler, but was not defined at runtime. Did the file load correctly?")})))
   :ns   `'~(var-ns var)
   :var  `(var ~(:name var))
   :meta `'~(:meta var)})

(defn- cljs-nss [env]
  (vals (:cljs.analyzer/namespaces env)))

(defn- ns-vars [ns-info]
  (vals (:defs ns-info)))

(defn- test-data [env]
  (reduce
   (fn [m {:keys [name meta] :as ns-info}]
     (let [vars (ns-vars ns-info)]
       (if (seq (filter :test vars))
         (reduce
          (fn [m v]
            (cond
              (:test v)
              (update-in m [`'~name :tests] (fnil conj []) (test-var-info v))

              (=  'cljs-test-once-fixtures (var-name v))
              (assoc-in m [`'~name :once-fixtures] (:name v))

              (=  'cljs-test-each-fixtures (var-name v))
              (assoc-in m [`'~name :each-fixtures] (:name v))

              :else
              m))
          (-> m
              (assoc-in [`'~name :name] `'~name)
              (assoc-in [`'~name :meta] `'~meta))
          vars)
         m)))
   {}
   (cljs-nss env)))

(defmacro capture-test-data! []
  `(do
     (reset! test-ns-data ~(test-data @env/*compiler*))
     (lambdaisland.glogi/debug :lambdaisland.chui/capture-test-data! @test-ns-data)))
