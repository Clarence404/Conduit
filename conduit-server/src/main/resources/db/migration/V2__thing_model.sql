alter table product add column codec varchar(32) not null default 'standard-json';

create table product_property (
    id          bigserial primary key,
    tenant_id   bigint           not null references tenant (id),
    product_id  bigint           not null references product (id) on delete cascade,
    identifier  varchar(64)      not null,
    name        varchar(64)      not null,
    data_type   varchar(16)      not null,
    unit        varchar(16),
    min_value   double precision,
    max_value   double precision,
    decimals    smallint,
    enum_items  jsonb,
    description varchar(256),
    created_at  timestamptz      not null default now(),
    updated_at  timestamptz      not null default now(),
    constraint uk_product_property_identifier unique (product_id, identifier),
    constraint ck_product_property_identifier check (identifier ~ '^[A-Za-z][A-Za-z0-9_]{0,63}$'),
    constraint ck_product_property_data_type check (data_type in ('DOUBLE', 'INT', 'BOOL', 'TEXT', 'ENUM')),
    constraint ck_product_property_decimals check (decimals between 0 and 6),
    constraint ck_product_property_range check (min_value is null or max_value is null or min_value <= max_value),
    constraint ck_product_property_range_type check (
        data_type in ('DOUBLE', 'INT') or (min_value is null and max_value is null)),
    constraint ck_product_property_enum check (
        (data_type = 'ENUM' and enum_items is not null and jsonb_typeof(enum_items) = 'array')
        or (data_type <> 'ENUM' and enum_items is null))
);
create index idx_product_property_tenant_product on product_property (tenant_id, product_id);
