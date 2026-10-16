-- V0.2-C 多租户与用户管理：平台管理员不属于任何租户；旧 ADMIN 角色迁移为 TENANT_ADMIN；
-- 角色、状态、租户编码加约束；删除租户时其用户随之删除。
alter table app_user alter column tenant_id drop not null;
update app_user set role = 'TENANT_ADMIN' where role = 'ADMIN';

alter table app_user
    add column display_name         varchar(64),
    add column must_change_password boolean     not null default false,
    add column updated_at           timestamptz not null default now();

alter table app_user
    add constraint ck_app_user_role check (role in ('PLATFORM_ADMIN', 'TENANT_ADMIN', 'TENANT_VIEWER')),
    add constraint ck_app_user_status check (status in ('ACTIVE', 'DISABLED')),
    add constraint ck_app_user_tenant check ((role = 'PLATFORM_ADMIN') = (tenant_id is null));

alter table app_user drop constraint app_user_tenant_id_fkey;
alter table app_user
    add constraint fk_app_user_tenant foreign key (tenant_id) references tenant (id) on delete cascade;
create index idx_app_user_tenant on app_user (tenant_id);

alter table tenant add column updated_at timestamptz not null default now();
alter table tenant
    add constraint ck_tenant_status check (status in ('ACTIVE', 'DISABLED')),
    add constraint ck_tenant_code check (code ~ '^[a-z][a-z0-9-]{1,31}$');
