(ns kontor.authz.util
  "Small datahike helpers shared across kontor-authz (ADR-066)."
  (:require [datahike.api :as d]))

(defn entid
  "Resolve `x` to a datahike eid. datahike has no `d/entid`; an eid
   passes through, a lookup-ref / ident resolves via `d/entity`,
   `nil` → `nil`."
  [db x]
  (cond
    (number? x) x
    (nil? x)    nil
    :else       (:db/id (d/entity db x))))
