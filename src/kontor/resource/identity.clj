(ns kontor.resource.identity
  "Resource account identities and lookup refs, without database initialization.
   These are names, not authority: resource operations still require Kontor's
   validation gate and writer governor."
  (:import [java.util UUID]))

(def source-id (UUID/fromString "00000000-0000-0000-0000-000000000171"))
(def sink-id (UUID/fromString "00000000-0000-0000-0000-000000000172"))

(defn account-ref [id] [:kontor.resource-account/id id])

(def source-account (account-ref source-id))
(def sink-account (account-ref sink-id))
