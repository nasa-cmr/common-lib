(ns cmr.common.validations.core
  "Validation framework created from scratch. Both bouncer and validateur are Clojure Validation
  frameworks but they have bugs and limitations that made them inappropriate for use.

  A validation is a function. It takes 2 arguments a field path vector and a value. It returns either
  nil or a map of field paths to a list of errors. Maps and lists will automatically be converted
  into record-validation or seq-of-validations."
  (:require [clojure.string :as str]
            [cmr.common.services.errors :as errors]))

(comment

  ;; Example Validations

  (def address-validations
    {:city required
     :street required})

  (defn last-not-first
    [field-path person-name]
    (when (= (:last person-name) (:first person-name))
      {field-path ["Last name must not equal first name"]}))

  (def person-validations
    {:addresses (every address-validations)
     :name (first-failing {:first required
                           :last required}
                          last-not-first)
     :age [required integer]})

  (validate person-validations {:addresses [{:street "5 Main"
                                             :city "Annapolis"}
                                            {:city "dd"}
                                            {:city "dd"}
                                            {:street "dfkkd"}]
                                :name {:first "Jason"
                                       :last "Jason"}
                                :age "35"}))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Support functions

(declare auto-validation-convert)

(defn record-validation
  "Converts a map into a record validator. Each field of the map passed in corresponds to a field
  in a record being validated."
  [field-map]
  (fn [field-path value]
    (when value
      (reduce (fn [field-errors [validation-field validation]]
                (let [validation (auto-validation-convert validation)
                      errors (validation (conj field-path validation-field)
                                         (validation-field value))]
                  (if (seq errors)
                    (merge field-errors errors)
                    field-errors)))
              {}
              field-map))))

(defn seq-of-validations
  "Returns a validator merging results from a list of validators. Takes an optional argument
  indicating if validations should short circuit. When short circuiting the error messages from the
  first validation will be returned without running subsequent validations."
  ([validators]
   (seq-of-validations validators false))
  ([validators short-circuit?]
   (let [validators (map auto-validation-convert validators)]
     (fn [field-path value]
       (reduce (fn [field-errors validator]
                 (let [errors (validator field-path value)]
                   (if (seq errors)
                     (if short-circuit?
                       ;; The call to reduced here will make reduce exit early with only these errors
                       (reduced errors)
                       (merge-with concat field-errors errors))
                     field-errors)))
               {}
               validators)))))

(defn first-failing
  "Syntactic sugar for creating a sequence of validations that will short circuit."
  [& validators]
  (seq-of-validations validators true))

(defn auto-validation-convert
  "Handles converting basic clojure data structures into a validation function."
  [validation]
  (cond
    (map? validation) (record-validation validation)
    (sequential? validation) (seq-of-validations validation)
    :else validation))

(defn humanize-field
  "Converts a keyword to a humanized field name"
  [field]
  (when field
    (->> (str/split (name field) #"-")
         (map str/capitalize)
         (str/join " "))))

(defn create-error-message
  "Formats a single error message using the field path and the error format."
  [field-path error]
  ;; Get the last field path value that's not a number.
  (let [field (last (remove number? field-path))]
    (format error (humanize-field field))))

(defn create-error-messages
  "Creates error messages with the response from validate."
  [field-errors]
  (for [[field-path errors] field-errors
        error errors]
    (create-error-message field-path error)))

(defn validate
  "Validates the given value with the validation. Returns a map of fields to error formats."
  [validation value]
  (let [validation (auto-validation-convert validation)]
    (validation [] value)))

(defn validate!
  "Validates the given value with the validation. If there are any errors they are thrown as a service
  error"
  [validation value]
  (let [errors (validate validation value)]
    (when (seq errors)
      (errors/throw-service-errors :bad-request (create-error-messages errors)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validations

(defn pre-validation
  "Runs a function on the value before validation begins. The result of prefn is passed to the
  validation."
  [prefn validation]
  (let [validation (auto-validation-convert validation)]
    (fn [field-path value]
      (validation field-path (prefn value)))))

(defn required-msg
  []
  "%s is required.")

(defn required
  "Validates that the value is not nil"
  [field-path value]
  (when (nil? value)
    {field-path [(required-msg)]}))

(defn every
  "Validate a validation against every item in a sequence. The field path will contain the index of
  the item that had an error"
  [validation]
  (let [validator (auto-validation-convert validation)]
    (fn [field-path values]
      (let [error-maps (for [[idx value] (map-indexed vector values)
                             :let [errors-map (validator (conj field-path idx) value)]
                             :when (seq errors-map)]
                         errors-map)]
        (apply merge-with concat error-maps)))))

(defn integer-msg
  [value]
  (format "%%s must be an integer but was [%s]." value))

(defn integer
  "Validates that the value is an integer"
  [field-path value]
  (when (and value (not (integer? value)))
    {field-path [(integer-msg value)]}))

(defn number-msg
  [value]
  (format "%%s must be a number but was [%s]." value))

(defn number
  "Validates the value is a number"
  [field-path value]
  (when (and value (not (number? value)))
    {field-path [(number-msg value)]}))

(defn within-range-msg
  [minv maxv value]
  (format "%%s must be within [%s] and [%s] but was [%s]." minv maxv value))

(defn within-range
  "Creates a validator within a specified range"
  [minv maxv]
  (fn [field-path value]
    (when (and value (or (< (compare value minv) 0) (> (compare value maxv) 0)))
      {field-path [(within-range-msg minv maxv value)]})))

(defn field-cannot-be-changed-msg
  [existing-value new-value]
  (format "%%s cannot be modified. Attempted to change existing value [%s] to [%s]"
          existing-value new-value))

(defn field-cannot-be-changed
  "Validation that a field in a object has not been modified. Accepts optional nil-allowed? parameter
  which indicates the validation should be skipped if the new value is nil."
  ([field]
   (field-cannot-be-changed field false))
  ([field nil-allowed?]
   (fn [field-path object]
     (let [existing-value (get-in object [:existing field])
           new-value (get object field)
           ;; if nil is allowed and the new value is nil we skip validation.
           skip-validation? (and nil-allowed? (nil? new-value))]
       (when (and (not skip-validation?)
                  (not= existing-value new-value))
         {(conj field-path field)
          [(field-cannot-be-changed-msg existing-value new-value)]})))))

