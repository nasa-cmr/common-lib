(ns cmr.common.cache.fallback-cache
  "This defines a cache which caches to two different stores; a primary store and a backup store.

  ## How it Works:

  Every time a value is stored in the cache, it will be saved to both the primary store and the
  backup store. When a value is retrieved from the cache it will first look in the primary store.
  If the value is not found in the primary store, it will be looked up from the backup store. If
  found in the backup store, the value will be saved to the primary store. If it is not present in
  the backup store nil will be returned. Optionally a lookup function can be used to retrieve the
  value. If a value is found using the lookup function the value will be stored in both the primary
  and the backup store.

  ## Benefits:

  The purpose of the fallback cache is to be able to support multiple stores. The primary store is
  expected to be faster or less resource intensive than the backup store, however there are times
  when it is expected that the primary store does not contain the values. For example, one could
  use this to implement a local in-memory cache as the primary store and a distributed cache as the
  backup store. When first restarting an application the in-memory cache will be empty, and so the
  values will be retrieved from the distributed cache.

  ## Downsides and Caveats:

  For most use cases only a single store is required with the fallback being to use a provided
  lookup function to obtain the value. This cache should only be used when a secondary store is
  needed rather than just a lookup function."
  (:require [cmr.common.cache :as c]
            [clojure.set :as set]))

(defrecord FallbackCache
  [
   ;; The primary cache used first when looking for values. Must implement CmrCache protocol.
   primary-cache

   ;; The backup cache which is used when values are not found in the primary cache. Must implement
   ;; CmrCache protocol.
   backup-cache
   ]

  c/CmrCache
  (get-keys
    [this]
    ;; Returns any keys present in either the primary or backup store.
    (set/union (set (c/get-keys primary-cache))
               (set (c/get-keys backup-cache))))

  (get-value
    [this key]
    (or (c/get-value primary-cache key)
        (when-let [value (c/get-value backup-cache key)]
          (c/set-value this key value)
          value)))

  (get-value
    [this key lookup-fn]
    (or (c/get-value this key)
        (when-let [value (lookup-fn)]
          (c/set-value this key value)
          value)))

  (reset
    [this]
    (c/reset primary-cache)
    (c/reset backup-cache))

  (set-value
    [this key value]
    (c/set-value backup-cache key value)
    (c/set-value primary-cache key value)))

(defn create-fallback-cache
  "Creates an instance of the fallback cache."
  [primary-cache backup-cache]
  (->FallbackCache primary-cache backup-cache))
