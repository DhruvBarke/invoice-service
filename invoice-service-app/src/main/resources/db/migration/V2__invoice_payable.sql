-- ============================================================================
-- V2: the shared invoice-payable tables.
--
-- Reconstructed from the JPA entities InvoicePayableEntity, InvoiceItemEntity
-- and InvoiceDocumentPayableEntity (com.sg.jpa.entity). These tables are NOT
-- owned by e-invoicing — manual capture and SGAi write the same rows. Nothing
-- here may narrow what those producers can store. The e-invoicing-specific
-- columns are added separately in V3 so the delta against a live database is
-- reviewable on its own.
--
-- Postgres, matching production: jsonb payloads, native uuid keys, the
-- publicinvoice schema, Envers-audited invoice-payable rows.
--
-- TWO THINGS THIS FILE GUESSES, because the entities do not state them:
--
--   1. Column widths. No @Column(length=...) anywhere, so every width below is
--      a choice, not a transcription. Free text is `text` (no width to get
--      wrong); codes are varchar with room to spare. If the real table is
--      narrower, this file is wrong and the real DDL wins.
--   2. Nullability. Only invoice_payable carries @NotNull. Everything else is
--      left nullable here, which is the safe direction: a column that is
--      nullable in production and NOT NULL here would reject valid rows.
--
-- Column types deliberately mirror production warts rather than correcting
-- them. re_attachment_date, arrival_time, traded_amount and fx_rate are all
-- declared as String on the entities despite being a date, a timestamp and two
-- numbers. They stay varchar. "Fixing" the type here would not fix the entity
-- and would break every other writer.
--
-- Envers: InvoicePayableEntity is @Audited, so production also has a
-- t_invoice_payable_aud shadow table plus a revision table. Hibernate generates
-- those; this service uses plain JDBC and does not, so they are not created
-- here. A row written by this service will therefore have no audit revision.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS publicinvoice;


-- ----------------------------------------------------------------------------
-- t_invoice_payable — the invoice envelope.
--
-- The InvoicePayable payload travels whole in the invoice_payable jsonb column.
-- Everything alongside it is hoisted because something queries, sorts or
-- displays it.
-- ----------------------------------------------------------------------------
CREATE TABLE publicinvoice.t_invoice_payable (
    id                       uuid          PRIMARY KEY,

    invoice_reference        varchar(64),
    sg_entity                varchar(64),
    fee_category             varchar(64),
    provider_id              varchar(64),

    -- @NotNull on the entity: the payload is the row's reason to exist.
    invoice_payable          jsonb         NOT NULL,

    created_date             date,
    last_updated_date        date,
    created_by_user          varchar(64),

    -- String on the entity, despite the name. Mirrored as-is; see header.
    re_attachment_date       varchar(64),

    last_updated_by_user     varchar(64),

    invoice_date             date,
    trading_start_date       date,
    trading_end_date         date,

    ref_cpty_id              varchar(64),
    invoice_type             varchar(32),

    -- No CHECK constraint. This column carries the whole invoice lifecycle for
    -- every producer, not just the three values e-invoicing writes; constraining
    -- it to what this service happens to emit would reject the others.
    invoice_status           varchar(32),

    assigned_to              varchar(64),
    ssi_status               varchar(32),
    priority                 varchar(16),

    amount                   numeric(19, 4),
    currency                 varchar(3),

    corail_response          jsonb,
    isdeleted                boolean       NOT NULL DEFAULT false,

    asset_class              varchar(64),

    -- The producer discriminator: EINVOICE / MANUAL / SGAI. This is the column
    -- the "e-invoicing only" scoping turns on.
    invoice_flow             varchar(32),

    payment_details          jsonb,
    recon_process            varchar(64)
);

-- Written by every producer, read by every screen.
CREATE INDEX ix_ip_invoice_reference ON publicinvoice.t_invoice_payable (invoice_reference);
CREATE INDEX ix_ip_status_flow       ON publicinvoice.t_invoice_payable (invoice_status, invoice_flow);


-- ----------------------------------------------------------------------------
-- t_invoice_items — one row per fee line.
--
-- Correlates on inv_reference_sg -> t_invoice_payable.invoice_reference. Not a
-- foreign key: lines are added to an invoice after the fact (that is what the
-- INCOMPLETE status is for), and a late-arriving line must be able to land
-- without the envelope row being rewritten.
-- ----------------------------------------------------------------------------
CREATE TABLE publicinvoice.t_invoice_items (
    invoice_item_id            uuid          PRIMARY KEY,

    inv_reference_sg           varchar(64),

    fee_type                   varchar(64),
    grouping_key               varchar(64),
    nature_of_expense          varchar(64),
    account_number             varchar(64),
    product                    varchar(64),

    notional_quantity          numeric(19, 4),
    fee_amount                 numeric(19, 4),
    fee_currency               varchar(3),

    provider_rate              numeric(19, 8),
    exchanged_rate             numeric(19, 8),
    exchanged_amount           numeric(19, 4),
    exchanged_amount_currency  varchar(3),

    vat_amount                 numeric(19, 4),
    vat_amount_currency        varchar(3),

    debit_credit               varchar(8),

    items_creation_date        date,
    items_creation_user        varchar(64),
    items_last_update_date     date,
    items_last_update_user     varchar(64),

    -- The invoice line's own label. On the e-invoicing path this is the
    -- supplier's Item.name, verbatim.
    item_description           text,

    market_region              varchar(64),
    fee_agreement              varchar(64),
    business                   varchar(32),

    -- String on the entity. traded_amount and fx_rate are numbers stored as
    -- text; mirrored rather than corrected. See header.
    traded_currency            varchar(3),
    traded_amount              varchar(64),
    fx_rate                    varchar(64)
);

CREATE INDEX ix_ii_inv_reference_sg ON publicinvoice.t_invoice_items (inv_reference_sg);


-- ----------------------------------------------------------------------------
-- t_invoice_document_payable — one row per document.
--
-- METADATA ONLY. The bytes live in SGDoc; sg_doc_id is the handle returned by
-- the upload and is the only way back to the content. There is deliberately no
-- content column: a second copy of a document that SGDoc already owns is a
-- second thing to keep in step, and the one that would go stale is this one.
--
-- Correlates on invoice_reference, again without a foreign key: a document can
-- arrive before its invoice is registered, or long after.
-- ----------------------------------------------------------------------------
CREATE TABLE publicinvoice.t_invoice_document_payable (
    id                     uuid          PRIMARY KEY,

    invoice_reference      varchar(64),

    -- SGDoc's handle for the content. NULL means the upload has not happened
    -- (or did not succeed) — the row still records that the document existed.
    sg_doc_id              varchar(128),

    document_name          varchar(512),
    document_type          varchar(64),
    created_by             varchar(64),
    comment                text,
    isdeleted              boolean       NOT NULL DEFAULT false,

    -- String on the entity. Mirrored; see header.
    arrival_time           varchar(64),

    document_reference     varchar(128),
    document_status        varchar(32),
    format                 varchar(128),

    -- Which channel delivered it.
    incoming_line          varchar(64),
    sender_address         varchar(256),

    registration_status    boolean,
    registration_type      varchar(32),

    subject                text,
    body                   text,

    created_date           timestamp,
    last_updated_date      timestamp,
    last_updated_by_user   varchar(64),

    parser_id              varchar(64),
    parser_response        text,
    parser_source          varchar(64)
);

CREATE INDEX ix_idp_invoice_reference ON publicinvoice.t_invoice_document_payable (invoice_reference);
