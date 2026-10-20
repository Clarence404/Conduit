-- 演示基线数据：新环境迁移后即可看到与开发环境一致的租户、产品、设备和物模型。
-- 在线状态、最新值和历史曲线不属于关系库种子，由模拟器启动后持续生成。
-- 设备初始哈希没有对应的公开明文；模拟器会在开始上报前通过 API 重置所模拟设备的密钥。

insert into tenant (code, name, status)
values ('default', '默认租户', 'ACTIVE'),
       ('huadong-power', '华东电力运维', 'ACTIVE'),
       ('huanan-water', '华南水务', 'ACTIVE'),
       ('xibei-energy', '西北新能源', 'ACTIVE')
on conflict (code) do nothing;

with seed(tenant_code, product_key, name, description) as (
    values ('default', 'dejtincij1l0yqa4', '三相电表（演示）', '园区配电室三相电表'),
           ('default', 'w2fd1snbpqtlbzhr', '环境传感器', '楼宇温湿度、空气质量监测'),
           ('huadong-power', '9jgfofkz0juzitzu', '三相电表（华东）', '华东配电站电能监测'),
           ('huadong-power', 'z60fbocjqk1cffaf', '变压器温控器', '变压器温度与风机状态监测'),
           ('huanan-water', 'qd68hs6nyyvjzdlp', '智能水表', '小区供水流量与压力监测'),
           ('huanan-water', 'krt4olr7kdxcmn3i', '水质监测仪', '加压站水质监测'),
           ('xibei-energy', 'a84uiv3uvcdrlci5', '光伏逆变器', '光伏阵列发电监测'),
           ('xibei-energy', 'yl6159y2bl3xnt5y', '气象站', '光伏场站环境监测')
)
insert into product (tenant_id, product_key, name, description, codec)
select t.id, s.product_key, s.name, s.description, 'standard-json'
from seed s
join tenant t on t.code = s.tenant_code
where not exists (
    select 1 from product p where p.tenant_id = t.id and p.name = s.name
)
on conflict (product_key) do nothing;

with seed(tenant_code, product_name, identifier, name, data_type, unit, decimals, enum_items) as (
    values
        ('default', '三相电表（演示）', 'Ua', 'A 相电压', 'DOUBLE', 'V', 1, null),
        ('default', '三相电表（演示）', 'Ub', 'B 相电压', 'DOUBLE', 'V', 1, null),
        ('default', '三相电表（演示）', 'Uc', 'C 相电压', 'DOUBLE', 'V', 1, null),
        ('default', '三相电表（演示）', 'Ia', 'A 相电流', 'DOUBLE', 'A', 1, null),
        ('default', '三相电表（演示）', 'Ib', 'B 相电流', 'DOUBLE', 'A', 1, null),
        ('default', '三相电表（演示）', 'Ic', 'C 相电流', 'DOUBLE', 'A', 1, null),
        ('default', '三相电表（演示）', 'EPimp', '正向有功电能', 'DOUBLE', 'kWh', 3, null),
        ('default', '三相电表（演示）', 'st', '运行状态', 'ENUM', null, null,
         '[{"label":"停机","value":0},{"label":"运行","value":1},{"label":"检修","value":2}]'),
        ('default', '环境传感器', 'temp', '温度', 'DOUBLE', '℃', 1, null),
        ('default', '环境传感器', 'humidity', '相对湿度', 'DOUBLE', '%', 1, null),
        ('default', '环境传感器', 'pm25', 'PM2.5', 'DOUBLE', 'μg/m³', 0, null),
        ('default', '环境传感器', 'co2', '二氧化碳', 'DOUBLE', 'ppm', 0, null),
        ('huadong-power', '三相电表（华东）', 'Ua', 'A 相电压', 'DOUBLE', 'V', 1, null),
        ('huadong-power', '三相电表（华东）', 'Ub', 'B 相电压', 'DOUBLE', 'V', 1, null),
        ('huadong-power', '三相电表（华东）', 'Uc', 'C 相电压', 'DOUBLE', 'V', 1, null),
        ('huadong-power', '三相电表（华东）', 'Ia', 'A 相电流', 'DOUBLE', 'A', 2, null),
        ('huadong-power', '三相电表（华东）', 'Ib', 'B 相电流', 'DOUBLE', 'A', 2, null),
        ('huadong-power', '三相电表（华东）', 'Ic', 'C 相电流', 'DOUBLE', 'A', 2, null),
        ('huadong-power', '三相电表（华东）', 'EPimp', '正向有功电能', 'DOUBLE', 'kWh', 1, null),
        ('huadong-power', '变压器温控器', 'oilTemp', '绕组温度', 'DOUBLE', '℃', 1, null),
        ('huadong-power', '变压器温控器', 'fanState', '风机状态', 'ENUM', null, null,
         '[{"label":"停止","value":0},{"label":"运行","value":1}]'),
        ('huadong-power', '变压器温控器', 'overTemp', '超温告警', 'BOOL', null, null, null),
        ('huanan-water', '智能水表', 'flow', '瞬时流量', 'DOUBLE', 'm³/h', 2, null),
        ('huanan-water', '智能水表', 'totalVol', '累计用量', 'DOUBLE', 'm³', 1, null),
        ('huanan-water', '智能水表', 'pressure', '水压', 'DOUBLE', 'MPa', 2, null),
        ('huanan-water', '智能水表', 'valve', '阀门状态', 'ENUM', null, null,
         '[{"label":"关闭","value":0},{"label":"开启","value":1}]'),
        ('huanan-water', '水质监测仪', 'ph', 'pH', 'DOUBLE', null, 2, null),
        ('huanan-water', '水质监测仪', 'turbidity', '浊度', 'DOUBLE', 'NTU', 2, null),
        ('huanan-water', '水质监测仪', 'chlorine', '余氯', 'DOUBLE', 'mg/L', 2, null),
        ('xibei-energy', '光伏逆变器', 'pac', '交流功率', 'DOUBLE', 'kW', 2, null),
        ('xibei-energy', '光伏逆变器', 'eday', '日发电量', 'DOUBLE', 'kWh', 1, null),
        ('xibei-energy', '光伏逆变器', 'invTemp', '机内温度', 'DOUBLE', '℃', 1, null),
        ('xibei-energy', '光伏逆变器', 'invState', '运行状态', 'ENUM', null, null,
         '[{"label":"待机","value":0},{"label":"发电","value":1},{"label":"故障","value":2}]'),
        ('xibei-energy', '气象站', 'irradiance', '辐照度', 'DOUBLE', 'W/m²', 0, null),
        ('xibei-energy', '气象站', 'windSpeed', '风速', 'DOUBLE', 'm/s', 1, null),
        ('xibei-energy', '气象站', 'ambientTemp', '环境温度', 'DOUBLE', '℃', 1, null)
)
insert into product_property
    (tenant_id, product_id, identifier, name, data_type, unit, decimals, enum_items)
select t.id, p.id, s.identifier, s.name, s.data_type, s.unit, s.decimals::smallint, s.enum_items::jsonb
from seed s
join tenant t on t.code = s.tenant_code
join product p on p.tenant_id = t.id and p.name = s.product_name
on conflict (product_id, identifier) do nothing;

with seed(tenant_code, product_name, device_key, name) as (
    select 'default', '三相电表（演示）',
           'demo-meter-' || lpad(n::text, 3, '0'),
           ((n - 1) / 10 + 1)::text || '号楼配电室 电表' || lpad(((n - 1) % 10 + 1)::text, 2, '0')
    from generate_series(1, 100) n
    union all
    select 'default', '环境传感器',
           'demo-env-' || lpad(n::text, 3, '0'),
           ((n - 1) / 5 + 1)::text || '号楼' || ((n - 1) % 5 + 1)::text
               || '层 环境监测' || lpad(n::text, 2, '0')
    from generate_series(1, 10) n
    union all
    select 'huadong-power', '三相电表（华东）',
           'hd-meter-' || lpad(n::text, 3, '0'),
           ((n - 1) / 5 + 1)::text || '号配电室 电表' || lpad(((n - 1) % 5 + 1)::text, 2, '0')
    from generate_series(1, 20) n
    union all
    select 'huadong-power', '变压器温控器',
           'hd-transformer-' || lpad(n::text, 2, '0'),
           n::text || '号变压器 温控器'
    from generate_series(1, 6) n
    union all
    select 'huanan-water', '智能水表',
           'hn-water-' || lpad(n::text, 3, '0'),
           (array['海岸小区', '碧桂小区', '翠湖小区'])[((n - 1) / 5 + 1)]
               || ' 水表' || lpad(((n - 1) % 5 + 1)::text, 2, '0')
    from generate_series(1, 15) n
    union all
    select 'huanan-water', '水质监测仪',
           'hn-quality-' || lpad(n::text, 2, '0'),
           n::text || '号加压站 水质监测'
    from generate_series(1, 5) n
    union all
    select 'xibei-energy', '光伏逆变器',
           'xb-pv-' || lpad(n::text, 3, '0'),
           ((n - 1) / 4 + 1)::text || '号光伏阵列 逆变器' || lpad(((n - 1) % 4 + 1)::text, 2, '0')
    from generate_series(1, 16) n
    union all
    select 'xibei-energy', '气象站', 'xb-weather-east', '东区 气象站'
    union all
    select 'xibei-energy', '气象站', 'xb-weather-west', '西区 气象站'
)
insert into device (tenant_id, product_id, device_key, name, secret_hash, secret_salt, status)
select t.id, p.id, s.device_key, s.name,
       repeat('0', 64), repeat('0', 32), 'ENABLED'
from seed s
join tenant t on t.code = s.tenant_code
join product p on p.tenant_id = t.id and p.name = s.product_name
where not exists (
    select 1
    from device d
    where d.product_id = p.id and (d.device_key = s.device_key or d.name = s.name)
)
on conflict (device_key) do nothing;
