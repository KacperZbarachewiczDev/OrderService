create table orders
(
    order_id               varchar(64) primary key,
    customer_id            varchar(64),
    status                 varchar(32)              not null,
    currency               varchar(3),
    total_amount           numeric(19, 4),
    source_updated_at      timestamptz,
    last_event_id          varchar(64),
    last_event_type        varchar(64),
    last_event_occurred_at timestamptz,
    last_event_sequence    bigint,
    first_seen_at          timestamptz              not null,
    updated_at             timestamptz              not null
);

create index idx_orders_updated_at on orders (updated_at, order_id);

create table order_lines
(
    id         bigserial primary key,
    order_id   varchar(64)    not null references orders (order_id) on delete cascade,
    product_id varchar(64)    not null,
    quantity   integer        not null,
    unit_price numeric(19, 4) not null,
    currency   varchar(3)     not null,
    constraint uq_order_lines_product unique (order_id, product_id)
);

create table payments
(
    payment_id             varchar(64) primary key,
    order_id               varchar(64)              not null,
    status                 varchar(32)              not null,
    amount                 numeric(19, 4),
    currency               varchar(3),
    last_event_id          varchar(64),
    last_event_type        varchar(64),
    last_event_occurred_at timestamptz,
    last_event_sequence    bigint,
    first_seen_at          timestamptz              not null,
    updated_at             timestamptz              not null
);

create unique index uq_payments_order on payments (order_id);

create table ingested_events
(
    id           bigserial primary key,
    event_id     varchar(64)  not null,
    source       varchar(16)  not null,
    order_id     varchar(64)  not null,
    aggregate_id varchar(64),
    event_type   varchar(64)  not null,
    occurred_at  timestamptz  not null,
    sequence_no  bigint,
    payload      jsonb        not null,
    received_at  timestamptz  not null,
    origin       varchar(16)  not null,

    constraint uq_ingested_events unique (source, event_id)
);

create index idx_events_order_source on ingested_events (order_id, source, occurred_at, event_id);

create table audit_runs
(
    id                   bigserial primary key,
    trigger_type         varchar(16)  not null,
    status               varchar(16)  not null,
    window_from          timestamptz,
    window_to            timestamptz  not null,
    started_at           timestamptz  not null,
    finished_at          timestamptz,
    orders_checked       integer      not null default 0,
    orders_with_issues   integer      not null default 0,
    orders_resolved      integer      not null default 0,
    discrepancies_found  integer      not null default 0,
    orders_inconclusive  integer      not null default 0,
    failure_reason       text
);

create index idx_audit_runs_started on audit_runs (started_at desc);

create index idx_audit_runs_completed_window on audit_runs (window_to desc) where status = 'COMPLETED';

create table audit_issues
(
    id                bigserial primary key,
    order_id          varchar(64) not null,
    status            varchar(16) not null,
    highest_severity  varchar(16) not null,
    discrepancy_count integer     not null,
    first_detected_at timestamptz not null,
    last_detected_at  timestamptz not null,
    resolved_at       timestamptz,
    last_audit_run_id bigint references audit_runs (id),
    constraint uq_audit_issues_order unique (order_id)
);

create index idx_audit_issues_status on audit_issues (status, last_detected_at desc);
create index idx_audit_issues_open_order on audit_issues (order_id) where status = 'OPEN';

create table audit_discrepancies
(
    id             bigserial primary key,
    issue_id       bigint      not null references audit_issues (id) on delete cascade,
    audit_run_id   bigint,
    type           varchar(48) not null,
    severity       varchar(16) not null,
    field          varchar(128),
    expected_value text,
    actual_value   text,
    detected_at    timestamptz not null
);

create index idx_audit_discrepancies_issue on audit_discrepancies (issue_id);

create table resync_jobs
(
    id                      bigserial primary key,
    order_id                varchar(64) not null,
    status                  varchar(16) not null,
    requested_at            timestamptz not null,
    started_at              timestamptz,
    finished_at             timestamptz,
    attempts                integer     not null default 0,
    remaining_discrepancies integer,
    failure_reason          text
);

create unique index uq_resync_active on resync_jobs (order_id) where status in ('PENDING', 'RUNNING');
create index idx_resync_order on resync_jobs (order_id, requested_at desc);
create index idx_resync_pending on resync_jobs (requested_at) where status = 'PENDING';

create table notification_outbox
(
    id              bigserial primary key,
    recipients      text        not null,
    subject         varchar(512) not null,
    body            text        not null,
    status          varchar(16) not null,
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null,
    created_at      timestamptz not null,
    sent_at         timestamptz,
    last_error      text,
    audit_run_id    bigint references audit_runs (id)
);

create index idx_outbox_due on notification_outbox (next_attempt_at) where status = 'PENDING';

create table scheduler_locks
(
    name         varchar(64) primary key,
    locked_until timestamptz not null,
    locked_by    varchar(128) not null,
    locked_at    timestamptz not null
);
