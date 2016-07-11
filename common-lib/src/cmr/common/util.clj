(ns cmr.common.util
  "Utility functions that might be useful throughout the CMR."
  (:require [cmr.common.log :refer (debug info warn error)]
            [cmr.common.services.errors :as errors]
            [camel-snake-kebab.core :as csk]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.walk :as w]
            [clojure.template :as template]
            [clojure.test :as test]
            [clojure.data.codec.base64 :as b64])
  (:import java.text.DecimalFormat
           java.util.zip.GZIPInputStream
           java.util.zip.GZIPOutputStream
           java.io.ByteArrayOutputStream
           java.io.ByteArrayInputStream
           java.sql.Blob))

(defmacro are2
  "DEPRECATED. Use are3 instead.

   Based on the are macro from clojure.test. Checks multiple assertions with a template expression.
   Wraps each tested expression in a testing block to identify what's being tested.
   See clojure.template/do-template for an explanation of templates.

   Example: (are2 [x y] (= x y)
                 \"The most basic case with 1\"
                 2 (+ 1 1)
                 \"A more complicated test\"
                 4 (* 2 2))
   Expands to:
            (do
               (testing \"The most basic case with 1\"
                 (is (= 2 (+ 1 1))))
               (testing \"A more complicated test\"
                 (is (= 4 (* 2 2)))))

   Note: This breaks some reporting features, such as line numbers."
  [argv expr & args]
  (if (or
        ;; (are2 [] true) is meaningless but ok
        (and (empty? argv) (empty? args))
        ;; Catch wrong number of args
        (and (pos? (count argv))
             (pos? (count args))
             (zero? (mod (count args) (inc (count argv))))))
    (let [testing-var (gensym "testing-msg")
          argv (vec (cons testing-var argv))]
      `(template/do-template ~argv (test/testing ~testing-var (test/is ~expr)) ~@args))
    (throw (IllegalArgumentException.
             "The number of args doesn't match are2's argv or testing doc string may be missing."))))

(defmacro are3
  "Similar to the are2 macro with the exception that it expects that your assertion expressions will
   be explicitly wrapped in is calls. This gives better error messages in the case of failures than
   if ANDing them together.

  Example: (are3 [x y]
             (do
               (is (= x y))
               (is (= y x)))
             \"The most basic case with 1\"
             2 (+ 1 1)
             \"A more complicated test\"
             4 (* 2 2))
  Expands to:
           (do
             (testing \"The most basic case with 1\"
               (do
                 (is (= 2 (+ 1 1)))
                 (is (= (+ 1 1) 2))))
             (testing \"A more complicated test\"
               (do
                 (is (= 4 (* 2 2)))
                 (is (= (* 2 2) 4)))))

  Note: This breaks some reporting features, such as line numbers."
  [argv expr & args]
  (if (or
        ;; (are3 [] true) is meaningless but ok
        (and (empty? argv) (empty? args))
        ;; Catch wrong number of args
        (and (pos? (count argv))
             (pos? (count args))
             (zero? (mod (count args) (inc (count argv))))))
    (let [testing-var (gensym "testing-msg")
          argv (vec (cons testing-var argv))]
      `(template/do-template ~argv (test/testing ~testing-var ~expr) ~@args))
    (throw (IllegalArgumentException.
             "The number of args doesn't match are3's argv or testing doc string may be missing."))))

(defn trunc
  "Returns the given string truncated to n characters."
  [s n]
  (when s
    (subs s 0 (min (count s) n))))

(defn sequence->fn
  [vals]
  "Creates a stateful function that returns individual values from the sequence. It returns the first
  value when called the first time, the second value on the second call and so on until the sequence
  is exhausted of values. Returns nil forever after that.

      user=> (def my-ints (sequence->fn [1 2 3]))
      user=> (my-ints)
      1
      user=> (my-ints)
      2
      user=> (my-ints)
      3
      user=> (my-ints)
      nil"
  (let [vals-atom (atom {:curr-val nil :next-vals (seq vals)})]
    (fn []
      (:curr-val (swap! vals-atom
                        (fn [{:keys [next-vals]}]
                          {:curr-val (first next-vals)
                           :next-vals (rest next-vals)}))))))

(defmacro future-with-logging
  "Creates a future that will log when a task starts and completes or if exceptions occur."
  [taskname & body]
  `(future
    (info "Starting " ~taskname)
    (try
      (let [result# (do ~@body)]
        (info ~taskname " completed without exception")
        result#)
      (catch Throwable e#
        (error e# "Exception in " ~taskname)
        (throw e#))
      (finally
        (info ~taskname " complete.")))))

(defmacro time-execution
  "Times the execution of the body and returns a tuple of time it took and the results"
  [& body]
  `(let [start# (System/currentTimeMillis)
         result# (do ~@body)]
     [(- (System/currentTimeMillis) start#) result#]))

(defmacro defn-timed
  "Creates a function that logs how long it took to execute the body. It supports multiarity functions
  but only times how long the last listed arity version takes. This means it should be used with
  multiarity functions where it calls itself with the extra arguments."
  [fn-name & fn-tail]
  (let [fn-name-str (name fn-name)
        ns-str (str *ns*)
        ;; Extract the doc string from the function if present
        [doc-string fn-tail] (if (string? (first fn-tail))
                               [(first fn-tail) (next fn-tail)]
                               [nil fn-tail])
        ;; Wrap single arity functions in a list
        fn-tail (if (vector? (first fn-tail))
                  (list fn-tail)
                  fn-tail)
        ;; extract other arities defined in the function which will not be timed.
        other-arities (drop-last fn-tail)
        ;; extract the last arity definitions bindings and body
        [timed-arity-bindings & timed-arity-body] (last fn-tail)]
    `(defn ~fn-name
       ~@(when doc-string [doc-string])
       ~@other-arities
       (~timed-arity-bindings
         (let [start# (System/currentTimeMillis)]
           (try
             ~@timed-arity-body
             (finally
               (let [elapsed# (- (System/currentTimeMillis) start#)]
                 (debug (format
                          "Timed function %s/%s took %d ms." ~ns-str ~fn-name-str elapsed#))))))))))

(defn build-validator
  "Creates a function that will call f with it's arguments. If f returns any errors then it will
  throw a service error of the type given.
  DEPRECATED: we should use the validations namespace"
  [error-type f]
  (fn [& args]
    (when-let [errors (apply f args)]
      (when (seq errors)
        (errors/throw-service-errors error-type errors)))))

(defn apply-validations
  "Applies the arguments to each validation concatenating all errors and returning them
  DEPRECATED: we should use the validations namespace"
  [validations & args]
  (reduce (fn [errors validation]
            (if-let [new-errors (apply validation args)]
              (concat errors new-errors)
              errors))
          []
          validations))

(defn compose-validations
  "Creates a function that will compose together a list of validation functions into a
  single function that will perform all validations together
  DEPRECATED: we should use the validations namespace"
  [validation-fns]
  (partial apply-validations validation-fns))

(defmacro record-fields
  "Returns the set of fields in a record type as keywords. The record type passed in must be a java
  class. Uses the getBasis function on record classes which returns a list of symbols of the fields of
  the record."
  [record-type]
  `(map keyword  ( ~(symbol (str record-type "/getBasis")))))

(defn remove-map-keys
  "Removes all keys from a map where the provided function returns true for the value of that key.
  The supplied function must take a single value as an argument."
  [f m]
  (apply dissoc m (for [[k v] m
                        :when (f v)]
                    k)))

(defn remove-nil-keys
  "Removes keys mapping to nil values in a map."
  [m]
  (remove-map-keys #(nil? %) m))

(defn map-keys [f m]
  "Maps f over the keys in map m and updates all keys with the result of f.
  This is a recommended function from the Camel Snake Kebab library."
  (when m
    (letfn [(handle-value [v]
                          (cond
                            (map? v) (map-keys f v)
                            (vector? v) (mapv handle-value v)
                            (seq? v) (map handle-value v)
                            :else v))
            (mapper [[k v]]
                    [(f k) (handle-value v)])]
      (into {} (map mapper m)))))

(defn map-values
  "Maps f over all the values in m returning a new map with the updated values"
  [f m]
  (into {} (for [[k v] m] [k (f v)])))

(defn map-keys->snake_case
  "Converts map keys to snake_case."
  [m]
  (map-keys csk/->snake_case_keyword m))

(defn map-keys->kebab-case
  "Converts map keys to kebab-case"
  [m]
  (map-keys csk/->kebab-case-keyword m))

(defn mapcatv
  "An eager version of mapcat that returns a vector of the results."
  [f sequence]
  (reduce (fn [v i]
            (into v (f i)))
          []
          sequence))

(defn any?
  "Returns true if predicate f returns a truthy value against any of the items. This is very similar
  to some but it's faster through it's use of reduce."
  [f items]
  (reduce (fn [_ i]
            (if (f i)
              (reduced true) ;; short circuit
              false))
          false
          items))

(defn map-n
  "Calls f with every step count elements from items. Equivalent to (map f (partition n step items))
  but faster. Note that it drops partitions at the end that would be less than a length of n."
  ([f n items]
   (map-n f n n items))
  ([f ^long n ^long step items]
   (let [items (vec items)
         size (count items)]
     (loop [index 0 results (transient [])]
       (let [subvec-end (+ index n)]
         (if (or (>= index size) (> subvec-end size))
           (persistent! results)
           (let [sub (subvec items index subvec-end)]
             (recur (+ index step) (conj! results (f sub))))))))))

(defn map-n-all
  "Calls f with every step count elements from items. Equivalent to (map f (partition-all n step items))
  but faster. Includes sets at the end that could be less than a lenght of n."
  ([f n items]
   (map-n-all f n n items))
  ([f ^long n ^long step items]
   (let [items (vec items)
         size (count items)]
     (loop [index 0 results (transient [])]
       (let [subvec-end (min (+ index n) size)]
         (if (>= index size)
           (persistent! results)
           (let [sub (subvec items index subvec-end)]
             (recur (+ index step) (conj! results (f sub))))))))))

(defn pmap-n-all
  "Splits work up n ways across futures and executes it in parallel. Calls the function with a set of
  n or fewer at the end. Not lazy - Items will be evaluated fully.

  Note that n is _not_ the number of threads that will be used. It's the number of items that will be
  processed in each parallel batch."
  [f n items]
  (let [build-future (fn [subset]
                       (future
                         (try
                           (f subset)
                           (catch Throwable t
                             (error t (.getMessage t))
                             (throw t)))))
        futures (map-n-all build-future n items)]
    (mapv deref futures)))

(defmacro while-let
  "A macro that's similar to when let. It will continually evaluate the bindings and execute the body
  until the binding results in a nil value."
  [bindings & body]
  `(loop []
     (when-let ~bindings
       ~@body
       (recur))))

(defn double->string
  "Converts a double to string without using exponential notation or loss of accuracy."
  [d]
  (when d (.format (DecimalFormat. "#.#####################") d)))

(defn numeric-string?
  "Returns true if the string can be converted to a double. False otherwise."
  [val]
  (try
    (Double. ^String val)
    true
    (catch NumberFormatException _
      false)))

(defn rename-keys-with [m kmap merge-fn]
  "Returns the map with the keys in kmap renamed to the vals in kmap. Values of renamed keys for which
  there is already existing value will be merged using the merge-fn. merge-fn will be called with
  the original keys value and the renamed keys value."
  (let [rename-subset (select-keys m (keys kmap))
        renamed-subsets  (map (fn [[k v]]
                                (set/rename-keys {k v} kmap))
                              rename-subset)
        m-without-renamed (apply dissoc m (keys kmap))]
    (reduce #(merge-with merge-fn %1 %2) m-without-renamed renamed-subsets)))

(defn binary-search
  "Does a binary search between minv and maxv searching for an acceptable value. middle-fn should
  be a function taking two values and finding the midpoint. matches-fn should be a function taking a
  value along with the current recursion depth. matches-fn should return a keyword of :less-than,
  :greater-than, or :matches to indicate if the current value is an acceptable response."
  [minv maxv middle-fn matches-fn]
  (loop [minv minv
         maxv maxv
         depth 0]
    (let [current (middle-fn minv maxv)
          matches-result (matches-fn current minv maxv depth)]
      (case matches-result
        :less-than (recur current maxv (inc depth))
        :greater-than (recur minv current (inc depth))
        matches-result))))

(defn- compare-results-match?
  "Returns true if the given values are in order based on the given matches-fn, otherwise returns false."
  [matches-fn values]
  (->> (partition 2 1 values)
       (map #(apply compare %))
       (every? matches-fn)))

(defn greater-than?
  "Returns true if the given values are in descending order. This is similar to core/> except it uses
  compare function underneath and applies to other types other than just java.lang.Number."
  [& values]
  (compare-results-match? pos? values))

(defn less-than?
  "Returns true if the given values are in ascending order. This is similar to core/< except it uses
  compare function underneath and applies to other types other than just java.lang.Number."
  [& values]
  (compare-results-match? neg? values))

(defn get-keys-in
  "Returns a set of all of the keys in the given nested map or collection."
  ([m]
   (get-keys-in m #{}))
  ([m key-set]
   (cond
     (map? m)
     (-> key-set
         (into (keys m))
         (into (get-keys-in (vals m))))

     (sequential? m)
     (reduce #(into %1 (get-keys-in %2)) key-set m))))

(defn unbatch
  "Returns a lazy seq of individual results from a lazy sequence of
  sequences. For example: return a lazy sequence of each item in a
  sequence of batches of items in search results."
  [coll]
  (mapcat seq coll))

(defn delete-recursively
  "Recursively delete the directory or file by the given name. Does nothing if the file does not exist."
  [fname]
  (when (.exists (io/file fname))
    (letfn [(delete-recursive
              [^java.io.File file]
              (when (.isDirectory file)
                (dorun (map delete-recursive (.listFiles file))))
              (io/delete-file file))]
      (delete-recursive (io/file fname)))))

(defn gzip-blob->string
  "Convert a gzipped BLOB to a string"
  [^Blob blob]
  (-> blob .getBinaryStream GZIPInputStream. slurp))

(defn string->gzip-bytes
  "Convert a string to an array of compressed bytes"
  [input]
  (let [output (ByteArrayOutputStream.)
        gzip (GZIPOutputStream. output)]
    (io/copy input gzip)
    (.finish gzip)
    (.toByteArray output)))

(defn string->gzip-base64
  "Converts a string to another string that is the base64 encoded bytes obtained by gzip
  compressing the bytes of the original string."
  [input]
  (let [^bytes b64-bytes (-> input string->gzip-bytes b64/encode)]
   (String. b64-bytes (java.nio.charset.Charset/forName "UTF-8"))))

(defn gzip-base64->string
  "Converts a base64 encoded gzipped string back to the original string."
  [^String input]
  (-> input
      .getBytes
      b64/decode
      ByteArrayInputStream.
      GZIPInputStream.
      slurp))

(defn map->path-values
  "Takes a map and returns a map of a sequence of paths through the map to values contained in that
  map. A path is a sequence of keys to a value in the map like that taken by the get-in function.

  Example:

  (map->path-values {:a 1
  :b {:c 2}})
  =>
  {
    [:a] 1
    [:b] 2
  }"
  [matching-map]
  (into {}
        (mapcatv
          (fn [[k v]]
            (if (map? v)
              (mapv (fn [[path value]]
                      [(vec (cons k path)) value])
                    (map->path-values v))
              [[[k] v]]))
          matching-map)))

(defn map-matches-path-values?
  "Returns true if the map matches the given path values. Path values are described in the
  map->path-values function documentation."
  [path-values m]
  (every? (fn [[path value]]
            (= (get-in m path) value))
          path-values))

(defn filter-matching-maps
  "Keeps all the maps which match the given matching map. The matching map is a set of nested maps
  with keys and values. A map matches it if the matching map is a subset of the map."
  [matching-map maps]
  (let [path-values (map->path-values matching-map)]
    (filter #(map-matches-path-values? path-values %) maps)))

(defn update-in-each
  "Like update-in but applied to each value in seq at path."
  [m path f & args]
  (update-in m path (fn [xs]
                      (when xs
                        (map (fn [x]
                               (apply f x args))
                             xs)))))

(defn update-in-all
  "For nested maps, this is identical to clojure.core/update-in. If it encounters
   a sequential structure at one of the keys, though, it applies the update to each
   value in the sequence. If it encounters nil at a parent key, it does nothing."
  [m [k & ks] f & args]
  (let [v (get m k)]
    (if (nil? v)
      m
      (if (sequential? v)
        (if ks
          (assoc m k (mapv #(apply update-in-all %1 ks f args) v))
          (assoc m k (mapv #(apply f %1 args) v)))
        (if ks
          (assoc m k (apply update-in-all v ks f args))
          (assoc m k (apply f v args)))))))

(defn get-in-all
  "Similar to clojure.core/get-in, but iterates over sequence values found along
  the key path and returns an array of all matching values."
  [m [k & ks]]
  (let [v (get m k)]
    (cond
      (nil? v) []                                    ;; Return empty results if the path can't be followed
      (nil? ks) [v]                                  ;; Return just the value if we're at the end of the path
      (sequential? v) (mapcat #(get-in-all %1 ks) v) ;; Iterate and recurse through sequences
      :else (get-in-all v ks))))                     ;; Recurse on ordinary keys (assumed to be maps)

(defn- key->delay-name
  "Returns the key that the delay is stored in for a lazy value"
  [k]
  {:pre [(keyword? k)]}
  (keyword (str "cmr.common.util/" (name k) "-delay")))

(defmacro lazy-assoc
  "Associates a value in a map in a way that the expression isn't evaluated until the value is
  retrieved from the map. The value must be retrieved using lazy-get. A different key is built
  using the one specified so that only lazy-get can be used to retrieve the value. It also allows
  a map to contain either the original value with the same key and a lazily determined value."
  [m k value-expression]
  (let [delay-name (key->delay-name k)]
    `(assoc ~m ~delay-name (delay ~value-expression))))

(defn lazy-get
  "Realizes and retrieves a value stored via lazy-assoc."
  [m k]
  (some-> m (get (key->delay-name k)) deref))

(defn get-real-or-lazy
  "Retrieves the value directly from the map with the key k or if not set looks for a lazily
  associated value."
  [m k]
  (or (get m k) (lazy-get m k)))

(defn extract-between-strings
  "Extracts a substring from s that begins with start and ends with end."
  ([^String s ^String start ^String end]
   (extract-between-strings s start end true))
  ([^String s ^String start ^String end include-start-and-end?]
   (let [start-index (.indexOf s start)]
     (when (not= start-index -1)
       (let [end-index (.indexOf s end (+ start-index (count start)))]
         (when (not= end-index -1)
           (if include-start-and-end?
             (.substring s start-index (+ end-index (count end)))
             (let [substr (.substring s (+ start-index (count start)) end-index)]
               ;; Return nil if there's no data between the two
               (when (not= 0 (count substr))
                 substr)))))))))

(defn map-by
  "Like group-by but assumes that all the keys returned by f will be unique per item."
  [f items]
  (into {} (for [item items] [(f item) item])))

(defn truncate-nils
  "Truncates the nil elements from the end of the given sequence, returns the truncated sequence.
  e.g (truncate-nils [1 2 nil 3 nil nil]) => '(1 2 nil 3)"
  [coll]
  (reverse (drop-while nil? (reverse coll))))

(defn map-longest
  "Similar to map function, but applies the function to the longest of the sequences,
  use the given default to pad the shorter sequences.
  See http://stackoverflow.com/questions/18940629/using-map-with-different-sized-collections-in-clojure"
  [f default & colls]
  (lazy-seq
    (when (some seq colls)
      (cons
        (apply f (map #(if (seq %) (first %) default) colls))
        (apply map-longest f default (map rest colls))))))

(defn key-sorted-map
  "Creates an empty map whose keys are sorted by the order given. Keys not in the set will appear
  after the specified keys. Keys must all be of the same type."
  [key-order]
  ;; Create a map of the keys to a numeric number indicating their position.
  (let [key-order-map (zipmap key-order (range))]
    (sorted-map-by
      (fn [k1 k2]
        (let [k1-order (key-order-map k1)
              k2-order (key-order-map k2)]
          (cond
            ;; k1 and k2 are both in the key-order-map.
            (and k1-order k2-order) (compare k1-order k2-order)

            ;; k1 is in the map but not k2. k1 should appear earlier than k2
            k1-order -1

            ;; k2 is in the map but not k1. k1 should appear after k2
            k2-order 1

            ;; Neither is in the map so compare them directly
            :else (compare k1 k2)))))))

;; Copied from clojure.core.incubator. We were having issues referring to this after updating to Clojure 1.7.
(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn seqv
  "Returns (vec coll) when (seq coll) is not nil."
  [coll]
  (when (seq coll)
    (vec coll)))

(defn seqify
  "When x is non-nil, returns x if it is sequential, or else returns a sequential collection containing only x."
  [x]
  (when (some? x)
    (if (coll? x)
      x
      [x])))
