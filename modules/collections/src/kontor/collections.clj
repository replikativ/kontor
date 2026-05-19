(ns kontor.collections
  "Public surface of the kontor-collections companion — ADR-043.

   Re-exports key transactor and query fns from the per-concern
   namespaces. Most callers can require just this namespace and
   reach what they need."
  (:require [kontor.collections.aging :as kaging]
            [kontor.collections.case :as kcase]
            [kontor.collections.credit-hold :as chold]
            [kontor.collections.dispute :as kdispute]
            [kontor.collections.dunning :as kdunning]
            [kontor.collections.pause :as kpause]
            [kontor.collections.promise :as kpromise]
            [kontor.collections.schema :as schema]
            [kontor.collections.writeoff :as kwo]))

(def install! schema/install!)

;; --- Case ---
(def open-case!         kcase/open-case!)
(def close-case!        kcase/close-case!)
(def advance-case-state!         kcase/advance-case-state!)
(def advance-case-state-tx-data  kcase/advance-case-state-tx-data)
(def assign-collector!  kcase/assign-collector!)
(def pull-case          kcase/pull-case)
(def open-case-for      kcase/open-case-for)
(def cases-by-state     kcase/cases-by-state)
(def refresh-denorms!   kcase/refresh-denorms!)

;; --- Promise ---
(def record-promise!         kpromise/record-promise!)
(def mark-promise-kept!      kpromise/mark-promise-kept!)
(def mark-promise-broken!    kpromise/mark-promise-broken!)
(def renegotiate-promise!    kpromise/renegotiate!)
(def sweep-broken-promises!  kpromise/sweep-broken-promises!)
(def open-promises-for-case  kpromise/open-promises-for-case)
(def open-promises-for-invoice kpromise/open-promises-for-invoice)

;; --- Dispute ---
(def raise-dispute!              kdispute/raise-dispute!)
(def resolve-dispute!            kdispute/resolve-dispute!)
(def advance-dispute-state!         kdispute/advance-dispute-state!)
(def advance-dispute-state-tx-data  kdispute/advance-dispute-state-tx-data)
(def open-disputes-for-invoice   kdispute/open-disputes-for-invoice)
(def any-open-dispute-for-invoice? kdispute/any-open-dispute-for-invoice?)

;; --- Credit hold + utilization + unapplied-cash ---
(def place-hold!             chold/place-hold!)
(def release-hold!           chold/release-hold!)
(def release-all-for!        chold/release-all-for!)
(def credit-status-for       chold/credit-status-for)
(def credit-utilization      chold/credit-utilization)
(def unapplied-cash-balance  chold/unapplied-cash-balance)
(def active-holds-for        chold/active-holds-for)

;; --- Pause ---
(def place-pause!            kpause/place-pause!)
(def release-pause!          kpause/release-pause!)
(def active-pauses-for-case  kpause/active-pauses-for-case)
(def any-active-pause?       kpause/any-active-pause?)

;; --- Dunning ---
(def DunningTemplateProvider     kdunning/DunningTemplateProvider)
(def static-template-provider    kdunning/static-template-provider)
(def resolve-policy              kdunning/resolve-policy)
(def plan-dunning-run            kdunning/plan-dunning-run)
(def emit-dunning-event!         kdunning/emit-dunning-event!)
(def frequency-cap-violated?     kdunning/frequency-cap-violated?)
(def default-policy-levels-edn   kdunning/default-policy-levels-edn)

;; --- Aging ---
(def aging-rows       kaging/aging-rows)
(def aging-summary    kaging/aging-summary)
(def aging-by-partner kaging/aging-by-partner)
(def open-ar-invoices kaging/open-ar-invoices)

;; --- Write-off ---
(def write-off-case! kwo/write-off-case!)
