"""Generate deterministic business fixtures and an independent SQLite oracle."""

import csv
import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


TABLES = {
    "customer": (
        ["customer_id", "customer_name", "is_internal", "region"],
        [(1, "张三", 0, "华东"), (2, "李四", 0, "华北"),
         (3, "无订单客户", 0, "华南"), (999, "内部测试", 1, "华东")],
    ),
    "order": (
        ["order_id", "customer_id", "order_time", "amount_cents", "status", "is_deleted"],
        [(1001, 1, "2025-01-01 00:00:00", 10000, "PAID", 0),
         (1002, 1, "2025-01-31 23:59:59", 20000, "PAID", 0),
         (1003, 2, "2025-02-01 00:00:00", 30000, "PAID", 0),
         (1004, 2, "2025-02-15 12:00:00", 40000, "PAID", 0),
         (1005, 999, "2025-02-10 12:00:00", 99900, "PAID", 0),
         (1006, 1, "2025-02-10 12:00:00", 50000, "CANCELLED", 0),
         (1007, 2, "2025-02-10 12:00:00", 60000, "REFUNDED", 0),
         (1008, 1, "2025-02-10 12:00:00", 70000, "PAID", 1),
         (1009, None, "2025-02-10 12:00:00", 80000, "PAID", 0),
         (1010, 404, "2025-02-10 12:00:00", 90000, "PAID", 0),
         (1011, 1, "2025-04-01 00:00:00", 5000, "PAID", 0),
         (1012, 2, "2025-04-02 00:00:00", 0, "PAID", 0)],
    ),
    "refund": (
        ["refund_id", "order_id", "refund_time", "refund_cents", "refund_status"],
        [(1, 1002, "2025-02-02 12:00:00", 5000, "SUCCESS"),
         (2, 1002, "2025-02-03 12:00:00", 2000, "SUCCESS"),
         (3, 1003, "2025-02-04 12:00:00", 3000, "SUCCESS"),
         (4, 1004, "2025-02-05 12:00:00", 9999, "FAILED")],
    ),
    "order_item": (
        ["item_id", "order_id", "product_code", "line_cents"],
        [(1, 1001, "P1", 4000), (2, 1001, "P2", 6000),
         (3, 1002, "P1", 20000), (4, 1003, "P2", 30000),
         (5, 1004, "P1", 10000), (6, 1004, "P2", 10000),
         (7, 1004, "P3", 20000), (8, 1011, "P3", 5000),
         (9, 1012, "P3", 0)],
    ),
    "product": (
        ["product_row_id", "product_code", "product_name"],
        [(1, "P1", "产品一"), (2, "P2", "产品二"),
         (3, "P2", "重复产品二"), (4, "P3", "产品三")],
    ),
    "calendar_month": (
        ["month"], [("2025-01",), ("2025-02",), ("2025-03",), ("2025-04",)],
    ),
    "event": (
        ["event_id", "value_cents", "code", "note"],
        [(i, 1, "000123", "" if i == 1 else None) for i in range(1, 10006)],
    ),
}

VALID = '''FROM ds_order o JOIN ds_customer c ON o.customer_id=c.customer_id
WHERE o.status='PAID' AND o.is_deleted=0 AND c.is_internal=0'''
CASES = [
    ("Q01", "有效订单总额是多少（人民币元）？", f"SELECT SUM(o.amount_cents)/100.0 {VALID}", [[1050.0]]),
    ("Q02", "有效订单数和付费客户数是多少？", f"SELECT COUNT(*),COUNT(DISTINCT o.customer_id) {VALID}", [[6, 2]]),
    ("Q03", "按客户列出有效订单总额，降序排名。", f"SELECT c.customer_name,SUM(o.amount_cents)/100.0 {VALID} GROUP BY c.customer_id,c.customer_name ORDER BY 2 DESC,1", [["李四", 700.0], ["张三", 350.0]]),
    ("Q04", "列出1至4月营收，没订单的月份也显示0。", f"WITH m AS (SELECT substr(o.order_time,1,7) month,SUM(o.amount_cents)/100.0 amount {VALID} GROUP BY 1) SELECT c.month,COALESCE(m.amount,0) FROM ds_calendar_month c LEFT JOIN m USING(month) ORDER BY 1", [["2025-01",300.0],["2025-02",700.0],["2025-03",0],["2025-04",50.0]]),
    ("Q05", "2025年2月有效营收是多少？按月边界计算。", f"SELECT SUM(o.amount_cents)/100.0 {VALID} AND o.order_time>='2025-02-01' AND o.order_time<'2025-03-01'", [[700.0]]),
    ("Q06", "按订单归属统计扣除成功退款后的净营收。", f"SELECT SUM(o.amount_cents-COALESCE(r.amount,0))/100.0 FROM ds_order o JOIN ds_customer c ON o.customer_id=c.customer_id LEFT JOIN (SELECT order_id,SUM(refund_cents) amount FROM ds_refund WHERE refund_status='SUCCESS' GROUP BY order_id) r ON r.order_id=o.order_id WHERE o.status='PAID' AND o.is_deleted=0 AND c.is_internal=0", [[950.0]]),
    ("Q07", "谁没有产生任何有效订单？排除内部客户。", f"SELECT c.customer_name FROM ds_customer c WHERE c.is_internal=0 AND NOT EXISTS (SELECT 1 FROM ds_order o WHERE o.customer_id=c.customer_id AND o.status='PAID' AND o.is_deleted=0) ORDER BY 1", [["无订单客户"]]),
    ("Q08", "商品业务键是否唯一？列出重复键。", "SELECT product_code,COUNT(*) FROM ds_product GROUP BY 1 HAVING COUNT(*)>1 ORDER BY 1", [["P2",2]]),
    ("Q09", "查看订单明细时，总有效订单金额不能重复累加。", f"SELECT SUM(o.amount_cents)/100.0 {VALID} AND EXISTS(SELECT 1 FROM ds_order_item i WHERE i.order_id=o.order_id)", [[1050.0]]),
    ("Q10", "事件总数和总金额是多少？应覆盖全部数据。", "SELECT COUNT(*),SUM(value_cents)/100.0 FROM ds_event", [[10005,100.05]]),
    ("Q11", "有多少订单的客户为空或不存在？", "SELECT COUNT(*) FROM ds_order o LEFT JOIN ds_customer c ON c.customer_id=o.customer_id WHERE c.customer_id IS NULL", [[2]]),
    ("Q12", "有效订单平均金额是多少？", f"SELECT SUM(o.amount_cents)/100.0/COUNT(*) {VALID}", [[175.0]]),
]


def main():
    conn = sqlite3.connect(":memory:")
    mysql = ["-- Generated fixture: import only into a dedicated test database."]
    models = ROOT / "official-project" / "models"
    for name, (columns, rows) in TABLES.items():
        physical = "ds_" + name
        types = ["INTEGER" if any(isinstance(r[i], int) for r in rows) else "TEXT" for i in range(len(columns))]
        ddl = ",".join(f'"{c}" {t}' for c,t in zip(columns, types))
        conn.execute(f'CREATE TABLE "{physical}" ({ddl})')
        conn.executemany(f'INSERT INTO "{physical}" VALUES ({",".join("?" for _ in columns)})', rows)
        with (ROOT / f"{physical}.csv").open("w", newline="", encoding="utf-8") as file:
            writer = csv.writer(file)
            writer.writerow(columns)
            writer.writerows(rows)
        mysql_types = ["BIGINT" if t == "INTEGER" else "VARCHAR(255)" for t in types]
        mysql_ddl = ",".join(f"`{c}` {t}" for c, t in zip(columns, mysql_types))
        mysql.append(f"CREATE TABLE `{physical}` ({mysql_ddl});")
        for start in range(0,len(rows),500):
            values = []
            for row in rows[start:start+500]:
                values.append("(" + ",".join("NULL" if v is None else str(v) if isinstance(v,int) else "'"+v.replace("'","''")+"'" for v in row) + ")")
            mysql.append(f'INSERT INTO `{physical}` VALUES ' + ",\n".join(values) + ";")
        model = {"name":name, "properties":{"description":"测试业务模型：" + name}, "table_reference":{"catalog":"", "schema":"wren_gap_fixture", "table":physical}, "columns":[{"name":c,"type":"BIGINT" if t=="INTEGER" else "VARCHAR"} for c,t in zip(columns,types)]}
        if name == "order":
            model["columns"].extend([
                {"name":"customer", "type":"customer", "relationship":"order_customer"},
                {"name":"customer_internal", "type":"BIGINT", "is_calculated":True, "expression":"customer.is_internal"},
                {"name":"customer_name", "type":"VARCHAR", "is_calculated":True, "expression":"customer.customer_name"},
            ])
        save(models / name / "metadata.yml", model)
    (ROOT / "fixture.mysql.sql").write_text("\n\n".join(mysql), encoding="utf-8")
    results=[]
    benchmark=[]
    for identifier,question,sql,expected in CASES:
        actual=[list(r) for r in conn.execute(sql)]
        if actual != expected:
            raise AssertionError((identifier,actual,expected))
        results.append({"id":identifier,"actual":actual,"status":"PASS"})
        benchmark.append({"id":identifier,"question":question,"oracle_sql_sqlite":sql,"expected":expected,"platform_status":"NOT_RUN","official_agent_status":"NOT_RUN"})
    save(ROOT / "oracle-results.json", results)
    save(ROOT / "questions.json", benchmark)
    project=ROOT / "official-project"
    save(project / "wren_project.yml", {"schema_version":5,"name":"wren-gap-fixture","version":"1.0","catalog":"wren","schema":"public","data_source":"mysql"})
    save(project / "relationships.yml", {"relationships":[{"name":"order_customer","models":["order","customer"],"join_type":"MANY_TO_ONE","condition":"order.customer_id = customer.customer_id"}]})
    save(project / "cubes" / "revenue" / "metadata.yml", {
        "name":"revenue","base_object":"order",
        "measures":[{"name":"valid_revenue_cents","type":"BIGINT","expression":"SUM(CASE WHEN status = 'PAID' AND is_deleted = 0 AND customer_internal = 0 THEN amount_cents ELSE 0 END)"}],
        "dimensions":[{"name":"customer_name","type":"VARCHAR","expression":"customer_name"}],
        "time_dimensions":[{"name":"order_time","type":"TIMESTAMP","expression":"CAST(order_time AS TIMESTAMP)"}],
    })
    naive=conn.execute("SELECT SUM(o.amount_cents)/100.0 FROM ds_order o JOIN ds_order_item i ON i.order_id=o.order_id JOIN ds_customer c ON c.customer_id=o.customer_id WHERE o.status='PAID' AND o.is_deleted=0 AND c.is_internal=0").fetchone()[0]
    save(ROOT / "diagnostics.json", {"fanout_wrong_revenue":naive,"correct_revenue":1050.0,"default_1000_rows_event_sum":10.0,"maximum_10000_rows_event_sum":100.0,"full_event_sum":100.05,"note":"Reference results only; not a platform or Wren execution accuracy score."})
    print(f"Generated {len(TABLES)} CSV tables, {sum(len(r) for _,r in TABLES.values())} rows; {len(results)} independent oracle assertions passed.")


if __name__ == "__main__":
    main()
