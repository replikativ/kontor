(ns kontor.l10n-cn.fapiao
  "Fapiao (发票) — Chinese VAT invoice tracking.

   Per ADR-018 the fapiao lifecycle uses `:kontor.transaction/state` value
   `:pending-attestation` for transactions whose invoice has been
   submitted to the State Taxation Administration (STA / 国家税务总局)
   electronic invoice service platform but not yet returned with an
   e-fapiao number. On successful response the state advances to
   `:posted` and `:kontor.transaction/clearance-token` holds the e-fapiao
   number (32-character QR signature in the fully-digital regime,
   8-digit number in legacy paper/electronic fapiao).

   What this module does NOT do:
     - Call the STA platform. Programmatic fapiao issuance requires
       a tax-digital-account (税务数字账户) on the STA platform with
       integration terms that are opaque outside China and managed
       through Chinese vendors (Aisino 航天信息, Baiwang 百望).
       The partner integration belongs in `kontor-l10n-cn-fapiao`.
     - Manage the legacy tax-control hardware device (税控盘) — the
       device requirement is being phased out as of the December
       2024 nationwide fully-digital e-fapiao rollout.

   What this module DOES:
     - Validate fapiao number formats.
     - Provide the EInvoiceProvider scaffold (PureXmlProvider variant)
       that emits a draft fapiao XML for partner-side STA submission.
     - Define the kernel-side fapiao tracking shape on transactions."
  (:require [clojure.string :as str]
            [kontor.provider.einvoice-provider :as einvoice]))

;; ============================================================================
;; Fapiao number validation
;; ============================================================================

;; Fapiao identifier formats (corrected 2026-05-11 per second-pass
;; verification — the first pass incorrectly described an "18-digit
;; special-VAT combined" form; that does NOT exist as a standard):
;;
;; - **数电票 (fully-digital 全面数字化电子发票)**: 20-digit
;;   发票号码 only — there is no separate 代码 in the 数电票
;;   format. Mandatory nationwide from the 2024-12 STA rollout.
;;
;; - **Legacy 增值税专用/普通发票 (post-2018)**: 12-digit 代码 +
;;   8-digit 号码. Stored separately on paper/electronic fapiao;
;;   when concatenated into a single clearance-token, the result
;;   is a 20-character composite. This COLLIDES with the
;;   数电票 20-digit form — disambiguate via `:fapiao/type`.
;;
;; - **Legacy 增值税普通发票 (pre-2018-01-01)**: 10-digit 代码 +
;;   8-digit 号码. Concatenated form is 18 characters. The
;;   增值税普通发票 代码 was widened to 12 digits from 2018-01-01
;;   onward; this 18-digit form is retained only for historical /
;;   archival records of pre-2018 invoices.
;;
;; - **Bare 8-digit 号码**: just the number portion of any legacy
;;   fapiao. Most kontor-side tracking only needs the number; the
;;   代码 is administrative metadata.

(def ^:private legacy-fapiao-number-pattern
  ;; 8-digit invoice number alone (legacy 号码 portion).
  #"^\d{8}$")

(def ^:private legacy-pre-2018-combined-pattern
  ;; 10-digit pre-2018 普通发票 代码 + 8-digit 号码 = 18 chars.
  ;; Retained only for historical records.
  #"^\d{18}$")

(def ^:private legacy-post-2018-combined-pattern
  ;; 12-digit post-2018 代码 + 8-digit 号码 = 20 chars.
  ;; NOTE: collides with the 数电票 20-digit identifier; the
  ;; `:fapiao/type` discriminates.
  #"^\d{20}$")

(def ^:private digital-fapiao-number-pattern
  ;; 数电票 fully-digital 发票号码: 20-digit unified identifier.
  #"^\d{20}$")

(defn fapiao-number-valid?
  "True iff `s` matches one of the accepted fapiao identifier formats.
   Accepts:
     - 8-digit legacy 号码 (number portion alone)
     - 18-digit pre-2018 legacy 普通发票 combined 代码+号码 (10+8)
     - 20-digit either: post-2018 legacy combined 代码+号码 (12+8)
       OR 数电票 fully-digital 发票号码 — these collide; use
       `:fapiao/type` to discriminate.

   Removed per CN verification: the alleged 32-character QR
   signature variant. The QR on a 数电票 embeds an SM2/SM3/SM4
   cryptographic signature, but that signature is not a regulated
   invoice-identifier format; store it on a separate
   `:fapiao/qr-signature` attribute if needed."
  [s]
  (boolean
   (and (string? s)
        (or (re-matches legacy-fapiao-number-pattern s)
            (re-matches legacy-pre-2018-combined-pattern s)
            (re-matches digital-fapiao-number-pattern s)))))

(defn assert-fapiao-number!
  [s]
  (when-not (fapiao-number-valid? s)
    (throw (ex-info "Invalid fapiao number format"
                    {:value s
                     :accepted "8-digit | 18-digit (pre-2018) | 20-digit"})))
  s)

;; ============================================================================
;; Fapiao type codes (per STA convention)
;; ============================================================================

(def fapiao-types
  "STA fapiao type codes used in e-fapiao XML/OFD:
     :special   增值税专用发票 (Special VAT Fapiao — for B2B input-VAT credit)
     :general   增值税普通发票 (General VAT Fapiao — B2C, no input-VAT credit)
     :electronic-general  增值税电子普通发票 (Electronic General — older 2016+)
     :fully-digital       全面数字化电子发票 (Fully Digital — Dec 2024 nationwide)"
  {:special              "01"
   :general              "02"
   :electronic-general   "10"
   :fully-digital        "65"})

;; ============================================================================
;; Draft fapiao XML emitter (placeholder; partner module replaces)
;; ============================================================================

(defn emit-draft-fapiao-xml
  "Emit a *draft* fapiao envelope for partner-side STA submission.

   The STA-bound XML structure is documented through the STA
   electronic-invoice platform vendor partnerships, not openly. This
   function ships a minimal envelope with the kernel-side data; a
   partner adapter (`kontor-l10n-cn-fapiao`) overlays the
   STA-required fields, signs (if required), and submits.

   Returns a string."
  [invoice]
  ;; Intentionally minimal — partner adapter overrides this.
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<DraftFapiao>\n"
       "  <!-- This is a kernel-side draft envelope. The partner adapter\n"
       "       overlays STA-required fields, signs, and submits. -->\n"
       "  <InvoiceNumber>" (:kontor.invoice/external-id invoice) "</InvoiceNumber>\n"
       "  <IssueDate>" (str (:kontor.invoice/issue-date invoice)) "</IssueDate>\n"
       "  <Currency>" (:kontor.invoice/currency invoice) "</Currency>\n"
       "  <TotalGross>" (str (:kontor.invoice/total-gross invoice)) "</TotalGross>\n"
       "</DraftFapiao>\n"))

(defn provider
  "Construct an EInvoiceProvider for the kernel-side draft envelope.
   Partner artifacts replace this with a real attesting provider."
  []
  (einvoice/pure-xml-provider :cn/draft-fapiao emit-draft-fapiao-xml))
