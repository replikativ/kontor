(ns kontor.account
  "Account resolution helpers — the ONE place a `:kontor.account/code` is
   turned into an entity-id.

   ## Why this namespace exists (note 198 audit, finding N1/ADR-119)

   `:kontor.account/path` is `:db.unique/identity`; `:kontor.account/code` is
   only `:db/index true` (see `kontor.schema`). Codes are a per-chart
   convenience — SKR04 \"1200\", QBO \"1010\", SAT \"601.05.001\" — and NOTHING
   holds them apart across charts. The audit measured real collisions between
   charts kontor already ships: l10n-uk × l10n-us 34 colliding codes, at × us
   20, ca × uk 16, de × us 15, and `payroll-jp` + `l10n-jp` both define
   \"610000\" under different paths in a stock install.

   Roughly three dozen call sites resolved a POSTING account with a scalar
   query:

       (d/q '[:find ?a . :in $ ?c :where [?a :kontor.account/code ?c]] db code)

   `d/q` returns a SET, and `:find ?a .` binds an ARBITRARY member of it. So
   in a book holding two charts, that query does not fail — it silently picks
   one of the colliding accounts and the eid goes straight into
   `:kontor.posting/account`. Money lands in the wrong GL account, the
   transaction still balances, and nothing downstream can tell.

   `resolve-code` therefore THROWS on ambiguity rather than picking. There is
   no defensible tie-break: two accounts with the same code in one book are
   two different accounts and only the caller knows which one it meant. The
   error names the fix — resolve on `:kontor.account/path` (unique) or pass an
   explicit override map. A missing code stays `nil`, because \"this chart
   doesn't carry that account\" is a normal condition every caller already
   handles.

   ADR-119 / note-196 N1 / note 198."
  (:require [datahike.api :as d]))

(defn resolve-code
  "Resolve `code` to an account entity-id within `db`.

     exactly one match → its eid
     no match          → nil (the caller decides: throw, skip, fall back)
     more than one     → throws `:kontor.account/ambiguous-code`

   `opts` may carry `:context` — a short string naming the caller (e.g.
   \"DE payroll (SKR04)\") that is prefixed onto the ambiguity message so the
   operator knows which module tripped.

   See the namespace docstring for why >1 is an error and not a choice."
  ([db code] (resolve-code db code nil))
  ([db code {:keys [context]}]
   (when (some? code)
     (let [eids (d/q '[:find [?a ...]
                       :in $ ?c
                       :where [?a :kontor.account/code ?c]]
                     db code)]
       (cond
         (= 1 (count eids)) (first eids)
         (empty? eids)      nil
         :else
         (throw (ex-info (str (when context (str context ": "))
                              "account code " (pr-str code) " matches "
                              (count eids) " accounts — :kontor.account/code is "
                              "not unique (ADR-119). Resolve on "
                              ":kontor.account/path (which IS unique) or pass an "
                              "explicit account override.")
                         {:type    :kontor.account/ambiguous-code
                          :code    code
                          :context context
                          :matches (vec (sort eids))
                          :paths   (vec (sort (keep #(:kontor.account/path
                                                      (d/pull db [:kontor.account/path] %))
                                                    eids)))})))))))

(def resolve-code-strict
  "Alias for `resolve-code` — kept because \"strict\" reads clearly at call
   sites that used to have a lenient (arbitrary-pick) resolver next to them."
  resolve-code)

(defn resolve-code!
  "`resolve-code`, but a missing code is an error too. For callers that
   have no sensible nil branch."
  ([db code] (resolve-code! db code nil))
  ([db code {:keys [context] :as opts}]
   (or (resolve-code db code opts)
       (throw (ex-info (str (when context (str context ": "))
                            "no account with code " (pr-str code))
                       {:type :kontor.account/code-not-found
                        :code code :context context})))))
