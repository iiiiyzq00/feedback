-- 多租户隔离的地基（跨域 + 权限边界）。
--
-- ## 为什么需要这个迁移
--
-- 迁移之前全库只有两处 tenant_key，且都不是隔离：t_feedback_episode.tenant_key 是真列真索引，
-- 但生产链路唯一写它的是 RcaIntakeSignalAssembler 里一个常量 'DEFAULT'；t_auth_external_identity.tenant_key
-- 存了飞书 OAuth 的事实，却没有任何查询按它过滤。也就是说「多租户」此前只是占位列，不是能力。
--
-- 本迁移做三件互相独立的事：
--   1. 给租户一个注册表，让 tenant_key 是**可枚举的封闭值**而不是自由字符串；
--   2. 把租户落到两处**无法从别处推导**的权威落点上（账号 / 应用身份）；
--   3. 把 TENANT 扩进 t_auth_data_permission 的范围维度，使租户隔离**复用已有的行级数据权限机制**
--      （ALLOW/DENY 合并、失败关闭语义），而不是另造一套。
--
-- ## 设计边界（与约束一一对应，不要放宽）
--
-- * 租户**不是**旧的「渠道」。渠道（CHANNEL）是同一租户内的业务分类，租户是数据归属方；
--   两者是独立维度，因此 TENANT 是新增 scope_type，不复用 CHANNEL 的取值。
-- * 默认值 'DEFAULT' 是迁移期兼容值，不是「无租户」：所有存量数据与未接入租户的写路径都落在这里，
--   于是新增隔离**不会**让任何现存数据突然不可见。
-- * `t_feedback_source` **故意不加租户列**（理由见第 4 节）：一条反馈属于哪个租户是**派生事实**
--   （它经由哪个应用身份进来），不是独立事实。派生事实冗余成列就是缓存，而缓存会漂移。
-- * 本迁移只加列与约束，不改任何已应用的迁移；`chk_data_perm_scope` 的收窄发生在 V164，这里只做前向放宽。

-- 1. 租户注册表：让 tenant_key 成为可枚举的封闭值
--
-- 没有这张表时，tenant_key 只能靠代码里的常量约定，`*` 通配也没有「全集」可言。
-- lifecycle_status 只区分 ACTIVE / SUSPENDED：SUSPENDED 是运维动作，
-- **不**在本层实现任何拒绝——拒绝发生在每次读取时按租户范围判定，不靠一张表的状态缓存。
CREATE TABLE t_auth_tenant (
  tenant_key VARCHAR(64) NOT NULL COMMENT '租户稳定键，与各处 tenant_key 列同值同义',
  display_name VARCHAR(200) NOT NULL COMMENT '展示名，不作为身份合并依据',
  lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_key),
  CONSTRAINT chk_auth_tenant_lifecycle CHECK (lifecycle_status IN ('ACTIVE','SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='租户注册表';

-- 必须先于下面所有带默认值的新列插入：存量行回填到 'DEFAULT' 时外键才解析得开。
INSERT INTO t_auth_tenant (tenant_key, display_name) VALUES ('DEFAULT', '默认租户');

-- 2. 账号：把「读侧租户」落库
--
-- 这个值就是会话主体的租户来源（WebPrincipal.tenantKey）。加外键是因为账号写入是人工治理动作、
-- 量小，且一个指向不存在租户的账号是治理缺陷而不是吞吐问题——让它在这里就失败。
ALTER TABLE t_auth_user
  ADD COLUMN tenant_key VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '所属租户；会话主体的租户来源' AFTER id,
  ADD KEY idx_auth_user_tenant (tenant_key),
  ADD CONSTRAINT fk_auth_user_tenant FOREIGN KEY (tenant_key) REFERENCES t_auth_tenant (tenant_key);

-- 3. 应用身份：把「写侧租户」落到归属锚点上
--
-- 反馈数据属于哪个租户，权威来源是它经由哪个应用身份进来——不是哪个人提交的。
-- 因此这张表是租户的**归属锚点**。同样加外键：应用身份是人工/AUTO 建档的治理对象，量可控。
ALTER TABLE t_application_identity
  ADD COLUMN tenant_key VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '所属租户；该身份下反馈数据的归属锚点' AFTER display_name,
  ADD KEY idx_application_identity_tenant (tenant_key),
  ADD CONSTRAINT fk_application_identity_tenant FOREIGN KEY (tenant_key) REFERENCES t_auth_tenant (tenant_key);

-- 4. 为什么 t_feedback_source 上**不加** tenant_key
--
-- 一条反馈属于哪个租户，权威答案只有一个：它经由哪个应用身份进来。也就是说它是**派生事实**，
-- 派生链是 t_feedback_source.id → t_feedback_primary_problem_fact.application_identity_id
-- → t_application_identity.tenant_key，两跳都是主键/唯一键查找。
--
-- 曾经考虑过把结果冗余到 t_feedback_source.tenant_key 上（读取侧就不必 join）。放弃它，是因为
-- 那样得到的是一份**缓存**，而这份缓存没有可靠的写入方：接入时应用身份往往尚未解析
-- （t_feedback_primary_problem_fact 是影子批处理写入的），唯一能补写它的是影子提交事务。
-- 于是缓存必然滞后于权威值，并且当某个应用身份被改判租户时，存量反馈会永远留在旧租户下——
-- 隔离判定拿到的正是这份旧值，且没有任何机制会自愈。
--
-- 两跳主键查找的代价在反馈详情读取（已是 by-id 查询、且本就 join t_feedback_content）上可以忽略。
-- 因此这里选择**每次现算**：判定用的租户永远与权威值一致，也不需要第二处写入方。
--
-- 未解析 / 歧义的来源（application_identity_id 为 NULL）经左侧外连接得到 NULL，
-- 读取侧用 COALESCE 退到 'DEFAULT'，与第 2、3 节的默认值同义：**不是「无租户」，而是迁移期兼容租户**。

-- 5. 把 TENANT 扩进行级数据权限的范围维度
--
-- V164 把 chk_data_perm_scope 定成了 CHANNEL/REGION/MODULE/FIELD，这里只做前向放宽。
-- 为什么复用这张表而不是新建租户授权表：ALLOW/DENY 合并、失败关闭、有界读取（findReadPermissions）
-- 这一整套语义已经存在且被门禁测试守着；租户要的正是同一套语义，只是多一个维度。
-- 唯一键 uk_auth_data_scope 含 scope_type，所以同一主体的 TENANT 行与 CHANNEL 行天然是两行、互不覆盖。
ALTER TABLE t_auth_data_permission DROP CHECK chk_data_perm_scope;
ALTER TABLE t_auth_data_permission
  ADD CONSTRAINT chk_data_perm_scope CHECK (scope_type IN ('CHANNEL','REGION','MODULE','FIELD','TENANT'));

-- 6. 存量回填说明
--
-- 第 2、3 节的新列都是 NOT NULL DEFAULT 'DEFAULT'，MySQL 在建列时即把存量行写成该默认值，
-- 因此**不需要**额外的 UPDATE。此处不写「UPDATE ... SET tenant_key='DEFAULT' WHERE tenant_key IS NULL」：
-- 那种语句在 NOT NULL 列上恒为空操作，写出来只会让人以为回填逻辑存在。
