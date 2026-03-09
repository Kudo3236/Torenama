create table if not exists trend_snapshots (
    id bigserial primary key,
    payload text not null,
    created_at timestamptz not null default now()
);