create table tenant (
    id         bigserial primary key,
    code       varchar(64)  not null unique,
    name       varchar(128) not null,
    status     varchar(16)  not null default 'ACTIVE',
    created_at timestamptz  not null default now()
);

create table app_user (
    id            bigserial primary key,
    tenant_id     bigint       not null references tenant (id),
    username      varchar(64)  not null unique,
    password_hash varchar(100) not null,
    role          varchar(32)  not null,
    status        varchar(16)  not null default 'ACTIVE',
    created_at    timestamptz  not null default now()
);

create table product (
    id          bigserial primary key,
    tenant_id   bigint       not null references tenant (id),
    product_key varchar(32)  not null unique,
    name        varchar(128) not null,
    description varchar(512),
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);
create index idx_product_tenant on product (tenant_id);

create table device (
    id          bigserial primary key,
    tenant_id   bigint       not null references tenant (id),
    product_id  bigint       not null references product (id),
    device_key  varchar(32)  not null unique,
    name        varchar(128) not null,
    secret_hash varchar(64)  not null,
    secret_salt varchar(32)  not null,
    status      varchar(16)  not null default 'ENABLED',
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);
create index idx_device_tenant_product on device (tenant_id, product_id);
