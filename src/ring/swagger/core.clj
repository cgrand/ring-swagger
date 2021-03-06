(ns ring.swagger.core
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [ring.util.response :refer :all]
            [schema.core :as s]
            [schema.utils :as su]
            [ring.swagger.data :as data]
            [ring.swagger.schema :as schema]
            [ring.swagger.coerce :as coerce]
            [ring.swagger.common :refer :all]
            [cheshire.generate :refer [add-encoder]]
            [camel-snake-kebab :refer [->camelCase]])
  (:import [com.fasterxml.jackson.core JsonGenerator]))

;;
;; Models
;;

(s/defschema Route {:method   s/Keyword
                    :uri      [s/Any]
                    :metadata {s/Keyword s/Any}})

;;
;; JSON Encoding
;;

(add-encoder clojure.lang.Var
  (fn [x ^JsonGenerator jg]
    (.writeString jg (name-of x))))

(add-encoder schema.utils.ValidationError
  (fn [x ^JsonGenerator jg]
    (.writeString jg
      (str (su/validation-error-explain x)))))

(defn date-time-encoder [x ^JsonGenerator jg]
  (.writeString jg (coerce/unparse-date-time x)))

(add-encoder java.util.Date date-time-encoder)
(add-encoder org.joda.time.DateTime date-time-encoder)

(add-encoder org.joda.time.LocalDate
  (fn [x ^JsonGenerator jg]
    (.writeString jg (coerce/unparse-date x))))

;;
;; Schema Transformations
;;

(defn resolve-model-var [x]
  (cond
    (map? x)    (or (-> x meta :model) x)
    (symbol? x) (-> x eval recur)
    :else       (let [x' (eval x)]
                  (if (= (class x) (class x')) x (recur x')))))

;;
;; Json Schema transformations
;;

(declare json-type)

(defn ->json
  [x & {:keys [top] :or {top false}}]
  (letfn [(type-of [x] (json-type (or (schema/type-map x) x)))]
    (cond
      (nil? x)        {:type "void"}
      (sequential? x) {:type "array"
                       :items (type-of (first x))}
      (set? x)        {:type "array"
                       :uniqueItems true
                       :items (type-of (first x))}
      :else           (if top
                        {:type (schema/model-name x)}
                        (type-of x)))))
;;
;; dispatch
;;

(defmulti json-type identity)
(defmulti json-type-class (fn [e] (class e)))

;;
;; identity-based dispatch
;;

(defmethod json-type data/Long*     [_] {:type "integer" :format "int64"})
(defmethod json-type data/Double*   [_] {:type "number" :format "double"})
(defmethod json-type data/String*   [_] {:type "string"})
(defmethod json-type data/Boolean*  [_] {:type "boolean"})
(defmethod json-type data/Keyword*  [_] {:type "string"})
(defmethod json-type data/DateTime* [_] {:type "string" :format "date-time"})
(defmethod json-type data/Date*     [_] {:type "string" :format "date"})

(defmethod json-type :default [e]
  (or
    (json-type-class e)
    (cond
      (schema/model? e) {:$ref (schema/model-name e)}
      (schema/model? (value-of (resolve-model-var e))) {:$ref (schema/model-name e)}
      :else (throw (IllegalArgumentException. (str "don't know how to create json-type of: " e))))))

;;
;; class-based dispatch
;;

(defmethod json-type-class schema.core.EnumSchema [e] (merge (->json (class (first (:vs e)))) {:enum (seq (:vs e))}))
(defmethod json-type-class schema.core.Maybe      [e] (->json (:schema e)))
(defmethod json-type-class schema.core.Both       [e] (->json (first (:schemas e))))
(defmethod json-type-class schema.core.Recursive  [e] (->json (:schema-var e)))
(defmethod json-type-class schema.core.EqSchema   [e] (->json (class (:v e))))
(defmethod json-type-class :default [e])

;;
;; Common
;;

(defn properties [schema]
  (into {}
    (for [[k v] schema
          :let [k (s/explicit-schema-key k)]]
      [k (merge
           (dissoc (meta v) :model :name)
           (try (->json v)
             (catch Exception e
               (throw
                 (IllegalArgumentException.
                   (str "error converting to json schema [" k " " (s/explain v) "]") e)))))])))

(defn required-keys [schema]
  (filter s/required-key? (keys schema)))

(defn resolve-model-vars [form]
  (walk/prewalk (fn [x] (or (schema/model-var x) x)) form))

;;
;; public Api
;;

(defn transform [schema*]
  (let [schema (value-of schema*)
        required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:id (name-of schema*)
       :properties (properties schema)
       :required required})))

(defn collect-models [x]
  (set
    (let [value (value-of x)
          model  (schema/model-var value)
          values (cond
                   (map? value) (vals value)
                   (sequential? value) value
                   :else [])
          cols   (filter coll? values)
          models (->> cols (map meta) (keep :model))
          models (if model (conj models model) model)]
      (reduce concat models (map collect-models cols)))))

(defn transform-models [& schemas*]
  (->> schemas*
    (mapcat collect-models)
    (map transform)
    (map (juxt (comp keyword :id) identity))
    (into {})))

(defn extract-models [details]
  (let [route-meta (->> details :routes (map :metadata))
        return-models (->> route-meta (keep :return) flatten)
        body-models (->> route-meta (mapcat :parameters) (filter (fn-> :type (= :body))) (keep :model) flatten)]
    (-> return-models
      (into body-models)
      flatten
      distinct
      vec)))

;;
;; Route generation
;;

(defn path-params [s]
  (map (comp keyword second) (re-seq #":(.[^:|(/]*)[/]?" s)))

(defn string-path-parameters [uri]
  (let [params (path-params uri)]
    (if (seq params)
      {:type :path
       :model (zipmap params (repeat String))})))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn generate-nick [{:keys [method uri]}]
  (-> (str (name method) " " uri)
    (str/replace #"/" " ")
    (str/replace #"-" "_")
    (str/replace #":" " by ")
    ->camelCase))

(def swagger-defaults      {:swaggerVersion "1.2" :apiVersion "0.0.1"})
(def resource-defaults     {:produces ["application/json"]
                            :consumes ["application/json"]})
(def api-declaration-keys  [:title :description :termsOfServiceUrl :contact :license :licenseUrl])

(defn join-paths
  "Join several paths together with \"/\". If path ends with a slash,
   another slash is not added."
  [& paths]
  (str/replace (str/replace (str/join "/" (remove nil? paths)) #"([/]+)" "/") #"/$" ""))

(defn context
  "Context of a request. Defaults to \"\", but has the
   servlet-context in the legacy app-server environments."
  [{:keys [servlet-context]}]
  (if servlet-context (.getContextPath servlet-context) ""))

(defn basepath
  "extract a base-path from ring request. Doesn't return default ports
   and reads the header \"x-forwarded-proto\" only if it's set to value
   \"https\". (e.g. your ring-app is behind a nginx reverse https-proxy).
   Adds possible servlet-context when running in legacy app-server."
  [{:keys [scheme server-name server-port headers] :as request}]
  (let [x-forwarded-proto (headers "x-forwarded-proto")
        context (context request)
        scheme (if (= x-forwarded-proto "https") "https" (name scheme))
        port (if (#{80 443} server-port) "" (str ":" server-port))]
    (str scheme "://" server-name port context)))

;;
;; Convert parameters
;;

(defn strict-schema
  "removes open keys from schema"
  [schema]
  {:pre [(map? schema)]}
  (dissoc schema s/Keyword))

(defn loose-schema
  "add open keys for top level schema"
  [schema]
  {:pre [(map? schema)]}
  (assoc schema s/Keyword s/Any))

(defn- convert-query-or-path-parameter [{:keys [model type] :as it}]
  (assert (#{:query :path} type) (str "wrong type: " type "<-- " it))
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :let [rk (s/explicit-schema-key k)]]
      (merge
        (->json v)
        {:name (name rk)
         :description ""
         :required (s/required-key? k)
         :paramType type}))))

(defn- convert-body-parameter [{:keys [model type meta] :or {meta {}}}]
  (if model
    (vector
      (merge
        {:name (some-> model schema/find-model-name str/lower-case)
         :description ""
         :required true}
        meta
        {:paramType type
         :type (resolve-model-vars model)}))))

(defn convert-parameters [parameters]
  (apply concat
    (for [{type :type :as parameter} parameters]
      (do
        (if (= type :body)
            (convert-body-parameter (resolve-model-vars parameter))
            (convert-query-or-path-parameter parameter))))))

;;
;; Routing
;;

(defn api-listing [parameters swagger]
  (response
    (merge
      swagger-defaults
      (select-keys parameters [:apiVersion])
      {:info (select-keys parameters api-declaration-keys)
       :apis (for [[api details] swagger]
               {:path (str "/" (name api))
                :description (or (:description details) "")})})))

(defn api-declaration [parameters swagger api basepath]
  (if-let [details (and swagger (swagger api))]
    (response
      (merge
        swagger-defaults
        resource-defaults
        (select-keys parameters [:apiVersion :produces :consumes])
        {:basePath basepath
         :resourcePath ""
         :models (apply transform-models (extract-models details))
         :apis (for [{:keys [method uri metadata] :as route} (:routes details)
                     :let [{:keys [return summary notes nickname parameters]} metadata]]
                 {:path (swagger-path uri)
                  :operations [(merge
                                 (->json return :top true)
                                 {:method (-> method name .toUpperCase)
                                  :summary (or summary "")
                                  :notes (or notes "")
                                  :nickname (or nickname (generate-nick route))
                                  :responseMessages [] ;; TODO
                                  :parameters (convert-parameters parameters)})]})}))))
