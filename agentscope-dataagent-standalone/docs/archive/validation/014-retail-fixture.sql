
-- specs/014 local MySQL fixture. Safe to re-run: rows are upserted by stable IDs.
CREATE DATABASE IF NOT EXISTS data_agent
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE data_agent;

CREATE TABLE IF NOT EXISTS ds_demo_customer (
  customer_id VARCHAR(16) NOT NULL COMMENT '客户唯一编号',
  customer_name VARCHAR(100) NOT NULL COMMENT '客户名称',
  customer_tier VARCHAR(16) NOT NULL COMMENT '客户当前等级，VIP 或 NORMAL',
  region_code VARCHAR(16) NOT NULL COMMENT '所属区域编码，EAST、SOUTH 或 NORTH',
  registered_at DATETIME NOT NULL COMMENT '注册时间',
  PRIMARY KEY (customer_id),
  KEY ix_demo_customer_region (region_code)
) ENGINE=InnoDB COMMENT='文档语义增强验证：客户主数据';

CREATE TABLE IF NOT EXISTS ds_demo_product (
  product_id VARCHAR(16) NOT NULL COMMENT '商品唯一编号',
  product_name VARCHAR(100) NOT NULL COMMENT '商品名称',
  category_code VARCHAR(16) NOT NULL COMMENT '品类编码，DIGITAL、HOME 或 FOOD',
  is_active TINYINT(1) NOT NULL COMMENT '是否在售，1 是，0 否',
  PRIMARY KEY (product_id),
  KEY ix_demo_product_category (category_code)
) ENGINE=InnoDB COMMENT='文档语义增强验证：商品主数据';

CREATE TABLE IF NOT EXISTS ds_demo_order (
  order_id VARCHAR(16) NOT NULL COMMENT '订单唯一编号',
  customer_id VARCHAR(16) NOT NULL COMMENT '下单客户编号',
  order_status VARCHAR(16) NOT NULL COMMENT '订单状态：PAID、REFUNDED 或 CANCELLED',
  gross_amount DECIMAL(12,2) NOT NULL COMMENT '优惠前订单金额',
  discount_amount DECIMAL(12,2) NOT NULL COMMENT '优惠金额',
  paid_amount DECIMAL(12,2) NOT NULL COMMENT '客户实付金额',
  currency_code CHAR(3) NOT NULL COMMENT '币种编码',
  paid_at DATETIME NULL COMMENT '支付完成时间',
  deleted_at DATETIME NULL COMMENT '逻辑删除时间，空值表示未删除',
  PRIMARY KEY (order_id),
  KEY ix_demo_order_customer (customer_id),
  KEY ix_demo_order_paid_at (paid_at),
  KEY ix_demo_order_status (order_status)
) ENGINE=InnoDB COMMENT='文档语义增强验证：订单事实表';

CREATE TABLE IF NOT EXISTS ds_demo_order_item (
  order_id VARCHAR(16) NOT NULL COMMENT '订单编号',
  line_no INT NOT NULL COMMENT '订单内行号',
  product_id VARCHAR(16) NOT NULL COMMENT '商品编号',
  quantity INT NOT NULL COMMENT '购买数量',
  unit_price DECIMAL(12,2) NOT NULL COMMENT '商品成交单价',
  PRIMARY KEY (order_id, line_no),
  KEY ix_demo_item_product (product_id)
) ENGINE=InnoDB COMMENT='文档语义增强验证：订单明细表，无物理外键';

CREATE TABLE IF NOT EXISTS ds_demo_refund (
  refund_id VARCHAR(16) NOT NULL COMMENT '退款单唯一编号',
  order_id VARCHAR(16) NOT NULL COMMENT '原订单编号',
  refund_amount DECIMAL(12,2) NOT NULL COMMENT '退款金额',
  refund_status VARCHAR(16) NOT NULL COMMENT '退款状态：SUCCESS 或 REJECTED',
  refunded_at DATETIME NOT NULL COMMENT '退款完成时间',
  PRIMARY KEY (refund_id),
  KEY ix_demo_refund_order (order_id)
) ENGINE=InnoDB COMMENT='文档语义增强验证：退款事实表，无物理外键';

INSERT INTO ds_demo_customer
  (customer_id, customer_name, customer_tier, region_code, registered_at)
VALUES
  ('C001', '星河科技', 'VIP',    'EAST',  '2025-10-01 09:00:00'),
  ('C002', '南方商贸', 'NORMAL', 'SOUTH', '2025-11-12 10:00:00'),
  ('C003', '北辰制造', 'VIP',    'NORTH', '2025-12-03 11:00:00'),
  ('C004', '海岸设计', 'NORMAL', 'EAST',  '2026-01-05 12:00:00'),
  ('C005', '青禾餐饮', 'NORMAL', 'SOUTH', '2026-01-08 13:00:00'),
  ('C006', '远山工作室', 'NORMAL', 'NORTH', '2026-02-01 14:00:00')
ON DUPLICATE KEY UPDATE
  customer_name = VALUES(customer_name),
  customer_tier = VALUES(customer_tier),
  region_code = VALUES(region_code),
  registered_at = VALUES(registered_at);

INSERT INTO ds_demo_product
  (product_id, product_name, category_code, is_active)
VALUES
  ('P001', '轻薄笔记本', 'DIGITAL', 1),
  ('P002', '旗舰手机', 'DIGITAL', 1),
  ('P003', '人体工学椅', 'HOME', 1),
  ('P004', '精品咖啡礼盒', 'FOOD', 1),
  ('P005', '有线耳机', 'DIGITAL', 0)
ON DUPLICATE KEY UPDATE
  product_name = VALUES(product_name),
  category_code = VALUES(category_code),
  is_active = VALUES(is_active);

INSERT INTO ds_demo_order
  (order_id, customer_id, order_status, gross_amount, discount_amount,
   paid_amount, currency_code, paid_at, deleted_at)
VALUES
  ('O1001', 'C001', 'PAID',      12000.00, 1000.00, 11000.00, 'CNY', '2026-01-10 10:00:00', NULL),
  ('O1002', 'C002', 'PAID',       3200.00,  200.00,  3000.00, 'CNY', '2026-01-15 11:00:00', NULL),
  ('O1003', 'C001', 'REFUNDED',   5000.00,    0.00,  5000.00, 'CNY', '2026-01-20 12:00:00', NULL),
  ('O1004', 'C003', 'PAID',       8000.00,  500.00,  7500.00, 'CNY', '2026-02-03 13:00:00', NULL),
  ('O1005', 'C004', 'CANCELLED',  2000.00, 2000.00,     0.00, 'CNY', NULL,                  NULL),
  ('O1006', 'C002', 'PAID',       4500.00,    0.00,  4500.00, 'CNY', '2026-02-12 14:00:00', '2026-02-13 09:00:00'),
  ('O1007', 'C005', 'PAID',       9000.00, 1000.00,  8000.00, 'CNY', '2026-03-01 15:00:00', NULL),
  ('O1008', 'C006', 'PAID',       1000.00,    0.00,  1000.00, 'USD', '2026-03-05 16:00:00', NULL),
  ('O1009', 'C003', 'PAID',       6000.00,    0.00,  6000.00, 'CNY', '2026-03-15 17:00:00', NULL),
  ('O1010', 'C001', 'PAID',       4000.00,  500.00,  3500.00, 'CNY', '2026-03-20 18:00:00', NULL)
ON DUPLICATE KEY UPDATE
  customer_id = VALUES(customer_id),
  order_status = VALUES(order_status),
  gross_amount = VALUES(gross_amount),
  discount_amount = VALUES(discount_amount),
  paid_amount = VALUES(paid_amount),
  currency_code = VALUES(currency_code),
  paid_at = VALUES(paid_at),
  deleted_at = VALUES(deleted_at);

INSERT INTO ds_demo_order_item
  (order_id, line_no, product_id, quantity, unit_price)
VALUES
  ('O1001', 1, 'P001', 1, 11000.00),
  ('O1002', 1, 'P003', 2,  1500.00),
  ('O1003', 1, 'P002', 1,  5000.00),
  ('O1004', 1, 'P001', 1,  7500.00),
  ('O1005', 1, 'P005', 2,  1000.00),
  ('O1006', 1, 'P003', 3,  1500.00),
  ('O1007', 1, 'P004', 8,  1000.00),
  ('O1008', 1, 'P005', 1,  1000.00),
  ('O1009', 1, 'P002', 1,  6000.00),
  ('O1010', 1, 'P003', 1,  3000.00),
  ('O1010', 2, 'P004', 1,   500.00)
ON DUPLICATE KEY UPDATE
  product_id = VALUES(product_id),
  quantity = VALUES(quantity),
  unit_price = VALUES(unit_price);

INSERT INTO ds_demo_refund
  (refund_id, order_id, refund_amount, refund_status, refunded_at)
VALUES
  ('R001', 'O1003', 5000.00, 'SUCCESS',  '2026-01-22 09:00:00'),
  ('R002', 'O1001', 1000.00, 'SUCCESS',  '2026-02-01 10:00:00'),
  ('R003', 'O1007',  500.00, 'SUCCESS',  '2026-03-10 11:00:00'),
  ('R004', 'O1009', 2000.00, 'REJECTED', '2026-03-18 12:00:00')
ON DUPLICATE KEY UPDATE
  order_id = VALUES(order_id),
  refund_amount = VALUES(refund_amount),
  refund_status = VALUES(refund_status),
  refunded_at = VALUES(refunded_at);
