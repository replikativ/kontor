(ns kontor.clock
  "The one place the kernel asks what time it is.

  ## Why this exists

  ADR-068 splits every business write in two: a pure `*-tx-data` builder and
  an effectful `!` wrapper. The split is what makes tx-data composable — a
  builder's output can be inspected, merged into a larger transaction, run
  through `kontor.workflow.process`, or validated before anything is
  transacted.

  A builder that reads the wall clock is not pure, and the failure is silent:
  it produces *different tx-data for identical inputs*, and nothing in the
  return value says so. `kontor.posting.build/post-transaction-tx-data`
  defaulted `:posted-at` this way, so `kontor.book/entry-tx-data` -- whose
  docstring promised purity -- returned transactions that differed in
  `:kontor.transaction/posted-at` and `:kontor.posting/posted-at` when called
  twice 25ms apart. See `kontor.clock-test`.

  ## What binding it buys

  With `*now*` bound, the whole kernel write path becomes a function of its
  inputs. That matters for three things kontor already cares about, plus one
  it does not yet:

    TESTS         a golden-tx-data assertion stops needing to elide
                  timestamps, which is what made these defaults invisible.

    IMPORT        a Beancount or bank-statement import that replays historical
                  data can stamp the instant it is replaying, rather than the
                  instant it happens to run.

    REPLAY        `kontor.workflow.process` step lists become reproducible.

    REPLICATION   two nodes handed the same transaction must produce the same
                  datoms. This is why the change was made; it is not why it is
                  correct.

  ## Scope, deliberately

  Only sites whose value reaches tx-data are routed through here. The
  read-side defaults in `kontor.reporting.*` (`as-of-tx` / `as-of-valid`
  defaulting to now) are left alone: they change what a caller *sees*, never
  what is stored, and each already carries its own private `now`. Companion
  modules are untouched. Both are follow-ups, not oversights.

  ## The one sharp edge

  This is a dynamic var, so it follows Clojure's binding conveyance rules: it
  crosses `future`, `send`, `pmap` and `clojure.core.async/thread`, but NOT a
  raw `Thread` or a bare executor `submit`. Code that hands work to its own
  thread pool must re-establish the binding inside the task, or capture
  `(now)` before dispatching. Nothing in the kernel does this today."
  #?(:clj (:import [java.util Date])))

(def ^:dynamic *now*
  "The instant the kernel should treat as `now`, or nil to read the wall clock.

  Bind to a `java.util.Date` (JVM) or `js/Date` (ClojureScript) to pin every
  timestamp the kernel defaults. Unbound behaviour is unchanged from before
  this var existed, which is what keeps the change non-breaking:

    (binding [kontor.clock/*now* #inst \"2026-01-01\"]
      (book/entry-tx-data opts))    ; => identical on every call"
  nil)

(defn now
  "The current instant: `*now*` if bound, otherwise the wall clock.

  Every kernel site that would have written `(java.util.Date.)` into tx-data
  calls this instead."
  []
  (or *now* #?(:clj (Date.) :cljs (js/Date.))))
