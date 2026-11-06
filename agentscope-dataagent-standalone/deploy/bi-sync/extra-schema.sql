-- Generated review DDL; production uses the same code-owned schema via explicit init.
-- 6 business tables, 77 business columns and 2 generated technical primary keys.

CREATE TABLE IF NOT EXISTS `bi_page_click_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '生成的技术行号，非源事件ID；重刷后可能变化',
  `operator` varchar(255) NULL COMMENT '源用户账号原值，不裁剪空格',
  `event_time` datetime NOT NULL COMMENT '操作时间，东八区',
  `module_name` varchar(255) NULL COMMENT '模块名称',
  `url` mediumtext NULL COMMENT 'URL_DECODE解码的页面URL；保留长URL',
  PRIMARY KEY (`id`),
  KEY `idx_0` (`event_time`),
  KEY `idx_1` (`operator`,`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='原始点击，一源行一记录，保留完全相同的重复行';

CREATE TABLE IF NOT EXISTS `bi_iam_user` (
  `id` bigint NOT NULL COMMENT 'IAM用户ID，不假定等于BI人员ID',
  `username` varchar(50) NULL COMMENT '用户姓名',
  `account_name` varchar(50) NULL COMMENT '登录账号；wx结尾为外协',
  `spms_account` varchar(200) NULL COMMENT 'SPMS账号',
  `email` varchar(200) NULL COMMENT '邮箱',
  `phonenumber` varchar(32) NULL COMMENT '手机号；源bigint转文本，不恢复丢失的前导零',
  `coop_flag` int NULL COMMENT '源人员属性编码，不猜测含义',
  `undertaking_flag` int NULL COMMENT '承诺书：1已签署，0未签署',
  `order_type` varchar(20) NULL COMMENT '开户工单类型原值',
  `created_time` datetime NULL COMMENT '账号开通时间，东八区',
  `updated_time` datetime NULL COMMENT '更新时间，东八区',
  `jg_name` varchar(512) NULL COMMENT '所属公司；外协取责任人公司',
  `bm_name` varchar(512) NULL COMMENT '所属部门；外协取责任人部门',
  `post` varchar(1000) NULL COMMENT '责任人账号等于本人时的职位，否则空',
  `duty_person` varchar(64) NULL COMMENT '责任人姓名；自有人员为本人',
  `duty_person_account` varchar(64) NULL COMMENT '责任人账号',
  `wx_company_name` varchar(512) NULL COMMENT '外协公司名称',
  `oa_status` int NULL COMMENT 'OA：10启用，20停用；无账号为空',
  PRIMARY KEY (`id`),
  KEY `idx_0` (`account_name`),
  KEY `idx_1` (`spms_account`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='IAM当前全量用户，非历史组织快照';

CREATE TABLE IF NOT EXISTS `bi_tenant_optimization_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '生成的技术行号，非源事件ID；重刷后可能变化',
  `dayid` date NOT NULL COMMENT '扫描日期，东八区',
  `cluster_name` varchar(200) NULL COMMENT '集群',
  `project_name` varchar(200) NULL COMMENT '所属项目名称',
  `db_name` varchar(200) NULL COMMENT '库名',
  `table_name` varchar(200) NULL COMMENT '表名',
  `dir_name` varchar(400) NULL COMMENT '表路径',
  `owner` varchar(200) NULL COMMENT '建表账号',
  `update_user` varchar(200) NULL COMMENT '数据更新账号',
  `is_no_visited` varchar(200) NULL COMMENT '三个月未访问：yes是，no否',
  `is_no_compressed` varchar(200) NULL COMMENT '未压缩：yes是，no否',
  `is_small_file` varchar(200) NULL COMMENT '小文件：yes是，no否',
  `is_expire` varchar(200) NULL COMMENT '过期数据：yes是，no否',
  `total_size` double NULL COMMENT '总容量；TRUNCATE(SUM原值/1072741824,2)',
  `total_files` bigint NULL COMMENT '总文件数，个',
  `total_dirs` bigint NULL COMMENT '总目录数，个',
  `no_compress_size` double NULL COMMENT '未压缩容量；TRUNCATE(SUM原值/1072741824,2)',
  `no_compress_files` bigint NULL COMMENT '未压缩文件数，个',
  `small_file_size` double NULL COMMENT '小文件容量；TRUNCATE(SUM原值/1072741824,2)',
  `small_file_count` bigint NULL COMMENT '小文件数，个',
  `expire_size` double NULL COMMENT '过期容量；TRUNCATE(SUM原值/1072741824,2)',
  `expire_count` bigint NULL COMMENT '过期文件数，个',
  PRIMARY KEY (`id`),
  KEY `idx_0` (`dayid`),
  KEY `idx_1` (`owner`,`dayid`),
  KEY `idx_2` (`update_user`,`dayid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='租户扫描日快照，原报表聚合，无快照不代表零';

CREATE TABLE IF NOT EXISTS `bi_project_capacity_daily` (
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `project_name` varchar(30) NULL COMMENT '项目当前名称',
  `leader_name` varchar(50) NULL COMMENT '项目当前负责人',
  `record_date` date NOT NULL COMMENT '源yyyyMMdd转DATE，排除空日期',
  `total_storage_tb` decimal(38,12) NULL COMMENT '总容量；SUM配额字节/1099511627776',
  `used_storage_tb` decimal(38,4) NULL COMMENT '已用容量；ROUND(SUM字节/1099511627776,4)',
  `storage_usage_pct` decimal(38,4) NULL COMMENT '容量百分比数值；SUM(已用/配额)*100',
  `total_files_10k` decimal(38,4) NULL COMMENT '总文件数量；SUM配额/10000，万',
  `used_files_10k` decimal(38,4) NULL COMMENT '已用文件数量；ROUND(SUM数量/10000,4)，万',
  `file_usage_pct` decimal(38,4) NULL COMMENT '文件百分比数值；SUM(已用/配额)*100',
  PRIMARY KEY (`project_id`,`record_date`),
  KEY `idx_0` (`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目容量日数据；当前配额及项目资料，非历史配额快照';

CREATE TABLE IF NOT EXISTS `bi_screen` (
  `id` varchar(255) NOT NULL COMMENT '大屏源主键',
  `name` varchar(50) NOT NULL COMMENT '大屏名称',
  `title` varchar(255) NULL COMMENT '大屏标题',
  `description` varchar(255) NULL COMMENT '大屏描述',
  `last_heartbeat_time` datetime NULL COMMENT '配置页心跳，不是访问时间',
  `editing_session` varchar(2000) NULL COMMENT '编辑状态原值，不作认证凭据',
  `state` int NOT NULL COMMENT '0待发布，1已发布；不默认过滤',
  `org_id` varchar(255) NOT NULL COMMENT '当前所属项目ID',
  `org_name` varchar(50) NOT NULL COMMENT '当前所属项目名称',
  `create_time` datetime NOT NULL COMMENT '创建时间，东八区',
  `update_time` datetime NULL COMMENT '更新时间，东八区',
  `create_user_id` varchar(255) NOT NULL COMMENT '创建人ID',
  `update_user_id` varchar(255) NOT NULL COMMENT '更新人ID',
  `temp` varchar(10) NULL COMMENT '临时保存标记原值',
  PRIMARY KEY (`id`),
  KEY `idx_0` (`org_id`,`create_time`),
  KEY `idx_1` (`org_id`,`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='大屏当前全量，仅排除两个global配置字段';

CREATE TABLE IF NOT EXISTS `bi_screen_visit_log` (
  `id` bigint NOT NULL COMMENT '源访问记录主键，保留原ID',
  `screen_id` varchar(255) NULL COMMENT '大屏ID',
  `screen_name` varchar(255) NULL COMMENT '访问时大屏名称',
  `operation` varchar(255) NULL COMMENT '行为原值，不筛选',
  `operator_id` varchar(255) NULL COMMENT '访问用户ID',
  `operator_user_name` varchar(255) NULL COMMENT '访问用户账号',
  `operator_name` varchar(255) NULL COMMENT '访问用户姓名',
  `project_id` varchar(32) NULL COMMENT '访问用户所在项目ID，非大屏所属项目',
  `project_name` varchar(255) NULL COMMENT '访问用户所在项目名称',
  `operate_time` datetime NOT NULL COMMENT '日志时间，东八区',
  PRIMARY KEY (`id`),
  KEY `idx_0` (`operate_time`),
  KEY `idx_1` (`screen_id`,`operate_time`),
  KEY `idx_2` (`operator_user_name`,`operate_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='大屏原始访问行为，全部类型，排除nginx IP';
