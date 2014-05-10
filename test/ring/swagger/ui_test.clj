(ns ring.swagger.ui-test
  (:require [midje.sweet :refer :all]
            [ring.mock.request :as mock]
            [ring.swagger.ui :refer :all]))

(tabular
  (fact get-path
    (get-path ?root ?uri) => ?path)

  ?root           ?uri                      ?path
  ""              "/"                       ""
  "/"             "/"                       ""
  ""              "/index.html"             "index.html"
  "/"             "/index.html"             "index.html"
  "/ui-docs"      "/index.html"             nil
  "/ui-docs"      "/ui-docs/index.html"     "index.html"
  )

(tabular
  (fact index-path
    (index-path ?path) => ?redirect)
  ?path           ?redirect
  ""              "/index.html"
  "/"             "/index.html"
  "/ui-docs"      "/ui-docs/index.html"
  "/ui-docs/"     "/ui-docs/index.html")

;; Utils for testing swagger-ui works with real requests

(defn GET [app uri & kwargs]
  (app (mock/request :get uri)))

(defn status? [{:keys [status]} expected]
  (= status expected))

(defn redirect? [uri]
  (fn [{{location "Location"} :headers :as res}]
    (and (status? res 302) (= location uri))))

(defn html? [{{content-type "Content-Type"} :headers :as res}]
  (and (status? res 200) (= content-type "text/html")))

(facts "swagger-ui integration"
  (facts "simple use at doc root"
    (let [handler (swagger-ui)
          GET (partial GET handler)]
      ;; Uri will always start with "/"
      (GET "/") => (redirect? "/index.html")
      (GET "/index.html") => html?
      ))
  (facts "simple use at context"
    (let [handler (swagger-ui "/ui-docs")
          GET (partial GET handler)]
      (GET "/") => nil
      (GET "/index.html") => nil
      ;; Request to directory, uri doesn't necessarily end with "/"
      (GET "/ui-docs/") => (redirect? "/ui-docs/index.html")
      (GET "/ui-docs") => (redirect? "/ui-docs/index.html")
      (GET "/ui-docs/index.html") => html?
      ))
  (facts "servlet context"
    (let [handler (swagger-ui "/ui-docs")
          GET (fn [uri & kwargs] (apply GET handler uri :servlet-context "todo" kwargs))]))
  (facts "nginx proxy")
  )