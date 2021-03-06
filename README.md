# Ring-Swagger

[![Build Status](https://travis-ci.org/metosin/ring-swagger.png?branch=master)](https://travis-ci.org/metosin/ring-swagger)

[Swagger](https://helloreverb.com/developers/swagger) implementation for Ring using Prismatic [Schema](https://github.com/Prismatic/schema) for data models and coercion.

- Provides functions to create both Swagger [Resource listing](https://github.com/wordnik/swagger-core/wiki/Resource-Listing) and [Api declarations](https://github.com/wordnik/swagger-core/wiki/API-Declaration).
- Schema extensions for modelling, coersion and [JSON Schema](http://json-schema.org/) generation
- Does not cover how the routes and models are collected from web apps (and by so should be compatible with all Ring-based libraries)
   - Provides a Map-based interface for higher level web libs to create Swagger Spec out of their route definitions

## Latest version

```clojure
[metosin/ring-swagger "0.8.7"]
```

## Web libs using Ring-Swagger

- [Compojure-Api](https://github.com/metosin/compojure-api) for Compojure
- [fnhouse-swagger](https://github.com/metosin/fnhouse-swagger) for fnhouse

If your favourite web lib doesn't have an client adapter, you could write an it yourself. Here's howto:

1. Define routes to serve the `ring.swagger.core/api-listing` and `ring.swagger.core/api-declaration`
2. Create a route-collector to [collect the route metadata](https://github.com/metosin/ring-swagger/blob/master/test/ring/swagger/core_test.clj#L247-L348).
3. Optionally define a route for the swagger-ui
4. Publish it.
5. List your adapter here

# Usage

## Models

The building blocks for creating Web Schemas are found in package `ring.swagger.schema`. All schemas must be declared by `defmodel`, which set up the needed meta-data. Otherwise, it's just a normal [Schema](https://github.com/Prismatic/schema).

### Supported Schema elements

| Clojure | JSON Schema | Sample  |
| --------|-------|:------------:|
| `Long`, `schema/Int`        | integer, int64 | `1`|
| `Double`                    | number, double | `1.2`
| `String`, `schema/Str`, Keyword, `schema/Keyword`      | string | `"kikka"`
| `Boolean`                   | boolean | `true`
| `nil`                       | void |
| `java.util.Date`, `org.joda.time.DateTime`  | string, date-time | `"2014-02-18T18:25:37.456Z"`, consumes also without millis: `"2014-02-18T18:25:37Z"`
| `org.joda.time.LocalDate`   | string, date | `"2014-02-19"`
| `(schema/enum X Y Z)`       | *type of X*, enum(X,Y,Z)
| `(schema/maybe X)`          | *type of X*
| `(schema/both X Y Z)`       | *type of X*
| `(schema/recursive Var)`    | *Ref to (model) Var*
| `(schema/eq X)`    | *type of class of X*

- Vectors, Sets and Maps can be used as containers
  - Maps are presented as Complex Types and References. Model references are resolved automatically.
  - Nested maps are transformed automatically into flat maps with generated child references.

### Schema coersion

Ring-swagger utilizes [Schema coercions](http://blog.getprismatic.com/blog/2014/1/4/schema-020-back-with-clojurescript-data-coercion) for transforming the input data into vanilla Clojure and back. There are two modes for coersions: json and query.

#### Json-coercion

- numbers -> `Long` or `Double`
- string -> Keyword
- string -> `java.util.Date`, `org.joda.time.DateTime` or `org.joda.time.LocalDate`
- vectors -> Sets

#### Query-coersion:

extends the json-coersion with the following transformations:

- string -> Long
- string -> Double
- string -> Boolean

### A Sample Model usage

```clojure
(require '[ring.swagger.schema :refer :all])
(require '[schema.core :as s])

(defmodel Country {:code (s/enum :fi :sv)
                   :name String})
; #'user/Country

(defmodel Customer {:id Long
                    :name String
                    (s/optional-key :address) {:street String
                                               :country Country}})
; #'user/Customer

Country
; {:code (enum :fi :sv), :name java.lang.String}

Customer
; {:id java.lang.Long, :name java.lang.String, #schema.core.OptionalKey{:k :address} {:street java.lang.String, :country {:code (enum :fi :sv), :name java.lang.String}}}

CustomerAddress
; {:street java.lang.String, :country {:code (enum :fi :sv), :name java.lang.String}}

(def matti {:id 1 :name "Matti Mallikas"})
(def finland {:code :fi :name "Finland"})

(coerce Customer matti)
; {:name "Matti Mallikas", :id 1}

(coerce Customer (assoc matti :address {:street "Leipätie 1" :country finland}))
; {:address {:country {:name "Finland", :code :fi}, :street "Leipätie 1"}, :name "Matti Mallikas", :id 1}

(coerce Customer {:id 007})
; #schema.utils.ErrorContainer{:error {:name missing-required-key}}

(coerce! Customer {:id 007})
; ExceptionInfo throw+: {:type :ring.swagger.schema/validation, :error {:name missing-required-key}}  ring.swagger.schema/coerce! (schema.clj:89)
```
## Creating your own schema-types

JSON Schema generation is implemented using multimethods. You can register your own schema types by installing new methods to the multimethods.

### Class-based dispatch

```clojure
(require '[ring.swagger.core :as swagger])
(require '[schema.core :as s])
(defmethod swagger/json-type-class s/Maybe [e] (swagger/->json (:schema e)))
```

### Identity-based dispatch

```clojure
(require '[ring.swagger.core :as swagger])
(require '[schema.core :as s])
(defmethod swagger/json-type s/Any [_] {:type "string"})
```

## TODO

- support for vanilla schemas (removes the Var-resolving)
- consumes
- authorization
- files
- non-json produces & consumes
- full spec

## Contributing

Pull Requests welcome. Please run the tests (`lein midje`) and make sure they pass before you submit one.

## License

Copyright © 2014 Metosin Oy

Distributed under the Eclipse Public License, the same as Clojure.
