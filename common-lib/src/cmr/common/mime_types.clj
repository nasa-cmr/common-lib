(ns cmr.common.mime-types
  "Provides vars and functions for parsing and generating between MIME
  type and HTTP Content-Type strings and data formats supported by the
  CMR."
  (:refer-clojure :exclude [atom])
  (:require [clojure.string :as str]
            [cmr.common.util :as util]
            [cmr.common.services.errors :as svc-errors]
            [clojure.set :as set]))

;;; Core Functions

;; These functions are useful for all operations on MIME (or media) types and are not strictly CMR related.

(defn- parse-mime-type*
  [mt]
  (when mt
    (let [[base & parts] (str/split mt #"\s*;\s*")
          params (into {}
                       (for [part parts]
                         (let [[k v] (str/split part #"=")]
                           [k v])))]
      {:base       base
       :parameters params})))

(def parse-mime-type
  "Returns a Clojure map of components parsed from the given mime type string."
  (memoize parse-mime-type*))

(defn base-mime-type-of
  "Returns the base MIME type of the given MIME type string."
  [mt]
  (:base (parse-mime-type mt)))

(defn version-of
  "Returns the version parameter, if present, of the given mime type."
  [mt]
  (get-in (parse-mime-type mt) [:parameters "version"]))

(defn with-version
  "Returns MIME type with version parameter appended."
  [mt v]
  (if v
    (str mt ";version=" v)
    mt))

(defn keep-version
  "Returns MIME type with only the version parameter preserved, if present."
  [mt]
  (with-version (base-mime-type-of mt) (version-of mt)))

;;; Officially-Recognized CMR Media Types

;; The following media types are recognized by the CMR for use in various places. The map below is used
;; to dynamically create vars containing the recognized base MIME type for each keyword on the left.

(def format->mime-type
  "A map of format keywords to MIME types. Each keyword will have a corresponding var def'ed in this
  namespace for other namespaces to reference."
  {:json             "application/json"
   :umm-json         "application/vnd.nasa.cmr.umm+json"
   ;; Search results containing UMM JSON
   :umm-json-results "application/vnd.nasa.cmr.umm_results+json"

   ;; Search results containing the original alpha version of UMM JSON search results.
   :legacy-umm-json "application/vnd.nasa.cmr.legacy_umm_results+json"

   :xml              "application/xml"
   :form-url-encoded "application/x-www-form-urlencoded"
   :echo10           "application/echo10+xml"
   :iso-smap         "application/iso:smap+xml"
   :iso19115         "application/iso19115+xml"
   :dif              "application/dif+xml"
   :dif10            "application/dif10+xml"
   :csv              "text/csv"
   :html             "text/html"
   :atom             "application/atom+xml"
   :kml              "application/vnd.google-earth.kml+xml"
   :opendata         "application/opendata+json"
   :native           "application/metadata+xml"
   :edn              "application/edn"
   :opendap          "application/x-netcdf"
   :serf             "application/serf+xml"})


;; TODO it seems like these aliases really should go with path-w-extension->mime-type in search routes
;; They're really specific for mapping URL extensions. Also the underscore and dash thing should be
;; handled there automatically as well without having to resort to many aliases.
; (def ^:private format-aliases
;   "A map of alternative format keyword aliases to format keywords."
;   {:iso :iso19115
;    :iso_smap :iso-smap
;
;    ;; Map UMM JSON to the legacy UMM JSON search format for now to avoid breaking clients.
;    :umm-json :legacy-umm-json
;    :umm_json :legacy-umm-json
;    :legacy_umm_json :legacy-umm-json})

; (def format->mime-type
;   "A map of CMR data format keywords and aliases to base MIME types."
;   (merge core-formats->mime-types
;          ;; lookup aliases
;          (zipmap (keys format-aliases)
;                  (map core-formats->mime-types
;                       (vals format-aliases)))))

;; Intern vars for each of the mime type formats, e.g. (def json "application/json")

(doseq [[format-key mime-type] format->mime-type]
  (intern *ns* (symbol (name format-key)) mime-type))

(defn umm-json?
  "Returns true if the given mime type is recognized as UMM JSON."
  [mt]
  (when mt
    (= (:umm-json format->mime-type) (base-mime-type-of mt))))

(def any "*/*")

(def base-mime-type-to-format
  "A map of MIME type strings to CMR data format keywords."
  (set/map-invert format->mime-type))

(def all-supported-mime-types
  "A superset of all mime types supported by any CMR applications."
  (vals format->mime-type))

(def all-formats
  "A set of all format keywords supported by CMR."
  (set (keys format->mime-type)))

(defn mime-type->format
  "Returns a format keyword for the given MIME type and optional default MIME type."
  ([mime-type]
   (mime-type->format mime-type (:json format->mime-type)))
  ([mime-type default-mime-type]
   (get base-mime-type-to-format (base-mime-type-of mime-type)
        (get base-mime-type-to-format default-mime-type))))

(defn format-key
  "Returns CMR format keyword from given value. Value may be a keyword, a MIME type string or a map."
  [x]
  (cond
    (string? x) (mime-type->format x nil)
    (keyword? x) (get all-formats x)
    (map? x) (:format x)
    :else nil))

;; Content-Type utilities

(defn with-charset
  "Returns a Content-Type header string with the given mime-type and charset."
  [mime-type charset]
  (str mime-type "; charset=" charset))

(defn with-utf-8
  "Returns mimetype with utf-8 charset specified."
  [mime-type]
  (with-charset mime-type "utf-8"))

;;; HTTP (Ring) Header-Specific Functions

;; These functions deal with extracting MIME types from HTTP headers, and from Ring request header maps.

(defn extract-mime-types
  "Returns a seq of base MIME types from a HTTP header media range string.

  Based on the example from RFC 2616:

    (extract-mime-types \"audio/*; q=0.2, audio/basic\")

  will return: (\"audio/*\" \"audio/basic\").

  Wildcards (e.g. \"*/xml\") are not supported."
  [header-value]
  (when header-value
    (map base-mime-type-of (str/split header-value #"\s*,\s*"))))

(defn get-header
  "Gets a value from a header map in a case-insensitive way."
  [m k]
  (get (util/map-keys str/lower-case m) (str/lower-case k)))

(defn- header-mime-type-getter
  "Returns a function which uses the supplied header key k to retrieve supplied mime types."
  [k]
  (fn f
    ([headers]
     (f headers all-supported-mime-types))
    ([headers valid-mime-types]
     (some (set valid-mime-types)
           (extract-mime-types (get-header headers k))))))

(def accept-mime-type
  "Returns the first accepted mime type passed in the Accept header"
  (header-mime-type-getter "accept"))

(def content-type-mime-type
  "Returns the mime type passed in the Content-Type header"
  (header-mime-type-getter "content-type"))

(def extension-aliases
  "TODO"
  {:iso :iso19115})

(defn path->mime-type
  "Parses the search path with extension and returns the requested mime-type or nil if no extension
  was passed."
  ([search-path-w-extension]
   (path->mime-type search-path-w-extension nil))
  ([search-path-w-extension valid-mime-types]
   (when-let [extension (second (re-matches #"[^.]+(?:\.(.+))$" search-path-w-extension))]
     ;; Convert extension into a keyword. We don't use camel snake kebab as it would convert "echo10" to "echo-10"
     (let [extension-key (keyword (str/replace extension #"_" "-"))
           mime-type (format->mime-type (get extension-aliases extension-key extension-key))]
       (if (and (some? valid-mime-types) (not (contains? valid-mime-types mime-type)))
         (svc-errors/throw-service-error
          :bad-request (format "The URL extension [%s] is not supported." extension))
         mime-type)))))

(defn extract-header-mime-type
  "Extracts the given header value from the headers and returns the first valid preferred mime type.
  If validate? is true it will throw an error if the header was passed by the client but no mime type
  in the header value was acceptable."
  [valid-mime-types headers header validate?]

  (when-let [header-value (get headers header)]
    (or (some valid-mime-types (extract-mime-types header-value))
        (when validate?
          (svc-errors/throw-service-error
            :bad-request (format "The mime types specified in the %s header [%s] are not supported."
                                 header header-value))))))