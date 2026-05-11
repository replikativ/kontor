(ns kontor.einvoice-provider
  "EInvoiceProvider — protocol for emitting and transmitting
   e-invoice artifacts per jurisdiction (ADR-017).

   Sibling to `kontor.tax-provider/TaxProvider` (ADR-005). The kernel
   ships the protocol seam + a `PureXmlProvider` shape; concrete
   per-country implementations live in `kontor-l10n-<cc>` modules and
   in partner artifacts (e.g. `kontor-l10n-br-nfe` for SEFAZ
   attestation, `kontor-l10n-cn-fapiao` for STA-platform integration).

   Three intended-for modes:

     :keep-on-file  — emit the artifact; caller files / archives. DE
                      Factur-X PDF, JP QIS XML. No transmission.
     :transmit      — emit + ship to a non-authority recipient. AU
                      Peppol via access point; JP Peppol PINT JP.
     :clearance     — emit + ship to a government endpoint that signs
                      / blesses the artifact and returns a token.
                      BR NF-e (SEFAZ), CN fapiao (STA platform).

   This file ships zero customer-specific configuration. API keys,
   certificate keystores, endpoint URLs all belong with the concrete
   provider implementation. The kernel never sees credentials.")

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol EInvoiceProvider
  "Per-jurisdiction e-invoice emitter / transmitter.

   Lifecycle expectation:
     1. `envelope-format` returns the format keyword (stable; used for
        provider dispatch).
     2. `emit` produces the wire artifact (bytes or string) for a given
        :invoice entity. Pure with respect to external state — does not
        contact networks.
     3. `transmit!` is the side-effecting step. Optional: providers in
        :keep-on-file mode implement it as a no-op."

  (envelope-format [this]
    "Return a keyword identifying the wire format this provider emits.
     Convention: <jurisdiction-or-network>/<format-name>.
     Examples:
       :peppol/pint-jp     :peppol/pint-anz
       :br/nfe-4.0         :br/nfs-e
       :cn/e-fapiao
       :de/xrechnung       :de/factur-x")

  (emit [this invoice]
    "Given an :invoice entity (already at :sent or transitioning to it,
     and posted in the kernel), produce the wire artifact for this
     provider's format.

     Returns:
       {:einvoice/format        keyword
        :einvoice/payload       string-or-bytes
        :einvoice/content-type  mime-string
        :einvoice/intended-for  :keep-on-file | :transmit | :clearance}

     The kernel does not interpret the payload; downstream code that
     transmits or files it does. The function should be pure — no
     network calls — so it can be tested deterministically.")

  (transmit! [this invoice payload]
    "Send the emitted payload to whatever endpoint this provider
     targets. Returns:

       {:einvoice/transmitted?     boolean
        :einvoice/clearance-token  string-or-nil
        :einvoice/raw-response     provider-specific
        :einvoice/error            optional-ex-info-data}

     For :keep-on-file providers this returns
     {:einvoice/transmitted? false} without error — the caller
     interprets that as 'I should file the payload myself'.

     For :clearance providers this is the call that brings back the
     SEFAZ access key / fapiao number. Implementations are expected
     to do retries and rate-limit handling internally; the kernel
     does not retry."))

;; ============================================================================
;; Reference implementation — PureXmlProvider
;;
;; A no-network provider that just produces XML from an emit-fn supplied
;; at construction. Use this for jurisdictions / use cases that don't
;; have a transmission step (DE Factur-X kept on file, BR NF-e draft for
;; partner module to sign + ship, JP / AU Peppol PINT XML kept in a
;; queue for the access point to pick up).
;; ============================================================================

(defrecord PureXmlProvider [format emit-fn]
  EInvoiceProvider
  (envelope-format [_] format)

  (emit [_ invoice]
    (let [payload (emit-fn invoice)]
      {:einvoice/format       format
       :einvoice/payload      payload
       :einvoice/content-type (if (bytes? payload)
                                "application/octet-stream"
                                "application/xml")
       :einvoice/intended-for :keep-on-file}))

  (transmit! [_ _invoice _payload]
    {:einvoice/transmitted? false
     :einvoice/raw-response {:reason :pure-xml-provider-does-not-transmit}}))

(defn pure-xml-provider
  "Construct a PureXmlProvider.
     format   — keyword identifying the wire format, e.g. :peppol/pint-jp
     emit-fn  — function (invoice → payload) that produces the XML
                (or other bytes) for one invoice."
  [format emit-fn]
  (->PureXmlProvider format emit-fn))

;; ============================================================================
;; Provider registry (optional — country modules may use directly)
;; ============================================================================

(defonce ^:private registry
  (atom {}))

(defn register-provider!
  "Register a provider under a country/format key. Country modules
   call this at install time; consumers fetch via `provider-for`."
  [country-code provider]
  (swap! registry assoc country-code provider)
  provider)

(defn provider-for
  "Return the registered provider for a country, or nil."
  [country-code]
  (get @registry country-code))

(defn clear-registry!
  "Reset the registry — testing helper."
  []
  (reset! registry {}))

;; ============================================================================
;; Result helpers
;; ============================================================================

(defn successful?
  "True iff a transmit! result indicates success (transmitted with no
   error and a clearance token if one was expected)."
  [{:einvoice/keys [transmitted? error]}]
  (and transmitted? (nil? error)))

(defn needs-clearance?
  "True iff the emit-result is marked :clearance — i.e. the caller
   must `transmit!` to get a legal token, not just file the payload."
  [{:einvoice/keys [intended-for]}]
  (= :clearance intended-for))
