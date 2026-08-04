-- ============================================================================
-- V3: the e-invoicing delta on the shared invoice-payable table.
--
-- Everything the registration pipeline needs that the production entity does
-- not already have. Kept in its own migration because this is the file that has
-- to be applied to a live database that other producers are writing to, and it
-- should be reviewable without the noise of V2's CREATE TABLEs.
--
-- Every column added here is NULLABLE and has no default. Manual and SGAi rows
-- simply leave them NULL — no existing writer changes, no backfill, and the
-- ALTERs take no table rewrite in Postgres.
--
-- The CHECK constraints below only constrain the e-invoicing columns, and all
-- admit NULL, so a row written by another producer trivially satisfies them.
-- Contrast with invoice_status in V2, which is shared and therefore unconstrained.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- The invoice_reference series.
--
-- invoice_reference is minted here, not taken from the incoming e-invoice. The
-- e-invoice's own id is the SUPPLIER's reference for the invoice and is unique
-- only within that supplier — two suppliers can and do both call an invoice
-- "INV-001". That id is stored as provider_reference below, which is what the
-- duplicate check keys on.
--
-- FORMAT IS UNCONFIRMED. Existing references in the system look like
-- "CUS0226368" — a short alphabetic prefix followed by a zero-padded number.
-- This sequence supplies the number; the prefix is applied in Java
-- (JdbcInvoicePayableStore.REFERENCE_PREFIX) and is currently empty, so
-- references come out as bare zero-padded digits. If e-invoicing rows are meant
-- to carry a prefix, that constant is the one line to change.
-- ----------------------------------------------------------------------------
CREATE SEQUENCE publicinvoice.seq_invoice_reference START WITH 1000000 INCREMENT BY 1;


-- ----------------------------------------------------------------------------
-- t_invoice_payable
-- ----------------------------------------------------------------------------
ALTER TABLE publicinvoice.t_invoice_payable
    -- The supplier's own id for this invoice (UBL Invoice.id). Also present
    -- inside the invoice_payable payload as providerReference; hoisted because
    -- the duplicate check runs on every single registration and an expression
    -- index over jsonb is a worse thing to depend on than a plain column.
    ADD COLUMN provider_reference     varchar(64),

    -- Routing marker fields, parsed from the receiver endpoint
    -- (<siren>_MARK_<FEETYPE>). NULL when the marker was absent or unparseable
    -- — which is itself recorded, as a BUSINESS_UNKNOWN / MARKER_MALFORMED
    -- entry in registration_errors.
    ADD COLUMN business               varchar(32),
    ADD COLUMN fee_id                 varchar(64),
    ADD COLUMN fee_type               varchar(64),

    -- The registration verdict, in words. Deliberately NOT the shared
    -- `comments` field inside the invoice_payable payload: that one belongs to
    -- whoever is working the invoice, and a pipeline that overwrote it would
    -- destroy an operator's note.
    ADD COLUMN registration_comment   text,

    -- Every MappingError raised during registration, as a JSON array. The whole
    -- list, not just the one that decided the outcome — the deciding error is
    -- rarely the most useful one when working out what actually went wrong.
    ADD COLUMN registration_errors    jsonb,

    -- Lifecycle event owed back to the e-invoice-service. Written PENDING at
    -- registration; a scheduler (not in this service yet) drains and posts it.
    ADD COLUMN lifecycle_event_type   varchar(16),
    ADD COLUMN lifecycle_reason_code  varchar(32),
    ADD COLUMN lifecycle_event_status varchar(16),
    ADD COLUMN lifecycle_payload      jsonb;

ALTER TABLE publicinvoice.t_invoice_payable
    ADD CONSTRAINT ck_ip_business
        CHECK (business IS NULL OR business IN ('MARK', 'SGSS', 'GTPS', 'GLBA')),
    ADD CONSTRAINT ck_ip_lifecycle_event_type
        CHECK (lifecycle_event_type IS NULL OR lifecycle_event_type IN ('REFUSED', 'SUSPENDED')),
    ADD CONSTRAINT ck_ip_lifecycle_event_status
        CHECK (lifecycle_event_status IS NULL OR lifecycle_event_status IN ('PENDING', 'SENT', 'FAILED')),
    -- A lifecycle event without a status would never be drained; a status
    -- without a type would be drained into nothing. Neither is a state the
    -- publisher can produce, and both are silent when they happen.
    ADD CONSTRAINT ck_ip_lifecycle_paired
        CHECK ((lifecycle_event_type IS NULL) = (lifecycle_event_status IS NULL));


-- The duplicate check: has this supplier's invoice already been registered?
--
-- Partial on isdeleted, because a soft-deleted row must not block a
-- re-registration — that is precisely how an operator retracts a bad
-- registration so a corrected one can be sent. The partial predicate also keeps
-- the index off every historical row.
CREATE INDEX ix_ip_provider_ref_active
    ON publicinvoice.t_invoice_payable (provider_reference, invoice_status)
    WHERE isdeleted = false;

-- The scheduler's drain: oldest PENDING event first. Partial, because PENDING
-- is a transient state and the index should only ever hold the backlog.
CREATE INDEX ix_ip_lifecycle_pending
    ON publicinvoice.t_invoice_payable (created_date)
    WHERE lifecycle_event_status = 'PENDING';

-- The ops screens filter e-invoicing rows by business and status.
CREATE INDEX ix_ip_business_status
    ON publicinvoice.t_invoice_payable (business, invoice_status)
    WHERE business IS NOT NULL;
