"""Isolated Wren 0.15 CLI/MCP contract and business-result checks; no LLM calls."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
from decimal import Decimal

def json_rows(output):
    return [json.loads(line) for line in output.splitlines() if line.strip().startswith('{')]

def same_rows(actual, expected):
    if len(actual) != len(expected): return False
    for left, right in zip(actual, expected):
        if set(left) != set(right): return False
        for key, value in right.items():
            if isinstance(value, (int, float, Decimal)):
                if left[key] is None or Decimal(str(left[key])) != Decimal(str(value)): return False
            elif left[key] != value: return False
    return True

ROWS = [
    (1, 'A', '2024-12-31', 100, 'paid', 0),
    (2, 'A', '2025-01-01', 99, 'paid', 0),
    (3, 'A', '2025-02-01', 200, 'paid', 0),
    (4, 'B', '2025-02-15', 2790, 'paid', 0),
    (5, 'TEST', '2025-01-10', 9999, 'paid', 1),
    (6, 'C', '2025-01-20', 500, 'cancelled', 0),
    (7, 'B', '2025-12-31', 10, 'paid', 0),
    (8, 'D', '2026-01-01', 40, 'paid', 0),
    (9, 'E', '2025-03-01', 0, 'paid', 0),
    (10, 'F', '2025-03-02', 60, 'refunded', 0),
]

def write(root, relative, content):
    p = root / relative
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding='utf-8')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wren', required=True)
    parser.add_argument('--output', default='target/wren-native-fixture')
    parser.add_argument('--reuse-queries', action='store_true')
    args = parser.parse_args()
    root = Path(args.output).resolve()
    marker = root / '.wren-native-fixture'
    if root.exists() and any(root.iterdir()) and not marker.is_file():
        parser.error('--output must be empty or an existing directory created by this fixture')
    if args.reuse_queries and not (root / 'questions.json').is_file():
        parser.error('--reuse-queries requires a previous successful questions.json')
    root.mkdir(parents=True, exist_ok=True)
    marker.write_text('Isolated test data; never use a production project here.\n', encoding='utf-8')
    env = {**os.environ, 'PYTHONUTF8': '1', 'PYTHONIOENCODING': 'utf-8', 'WREN_HOME': str(root / 'home'), 'WREN_PROJECT_HOME': str(root), 'NO_PROXY': '', 'no_proxy': ''}
    reports = []
    def run(*argv, timeout=240):
        result = subprocess.run([args.wren, *argv], cwd=root, env=env, capture_output=True, text=True, encoding='utf-8', timeout=timeout)
        reports.append({'args': list(argv), 'exit': result.returncode, 'output': result.stdout + result.stderr})
        (root / 'report.json').write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding='utf-8')
        if result.returncode:
            raise RuntimeError(f'{argv}: {result.stdout}\n{result.stderr}')
        return result.stdout
    run('context', 'init', '--empty', '--force')
    write(root, 'home/profiles.yml', f"active: fixture\nprofiles:\n  fixture:\n    datasource: duckdb\n    url: '{root.as_posix()}'\n    format: csv\n")
    run('context', 'set-profile', 'fixture')
    values = ',\n'.join(f"({i},'{c}',DATE '{d}',{a},'{s}',{t})" for i,c,d,a,s,t in ROWS)
    ref_sql = f'SELECT * FROM (VALUES {values}) AS t(id,customer_id,order_date,amount,status,is_internal)'
    columns = [('id','BIGINT'),('customer_id','VARCHAR'),('order_date','DATE'),('amount','BIGINT'),('status','VARCHAR'),('is_internal','BIGINT')]
    write(root, 'models/orders/metadata.yml', 'name: orders\nproperties:\n  description: 测试订单含重复客户与跨年边界\ncolumns:\n' + ''.join(f'- name: {n}\n  type: {t}\n' for n,t in columns))
    write(root, 'models/orders/ref_sql.sql', ref_sql)
    write(root, 'views/valid_orders/metadata.yml', 'name: valid_orders\nproperties:\n  description: 外部客户的已付费正额订单\n')
    write(root, 'views/valid_orders/sql.yml', "statement: SELECT * FROM orders WHERE status = 'paid' AND is_internal = 0 AND amount > 0\n")
    write(root, 'cubes/sales/metadata.yml', '''name: sales
base_object: valid_orders
measures:
  - name: revenue
    expression: SUM(amount)
    type: BIGINT
  - name: paying_customers
    expression: COUNT(DISTINCT customer_id)
    type: BIGINT
dimensions:
  - name: customer
    expression: customer_id
    type: VARCHAR
time_dimensions:
  - name: order_date
    expression: order_date
    type: DATE
properties:
  description: 外部付费客户指标，在查询时间范围和粒度上重算去重客户数
''')
    write(root, 'knowledge/metrics/sales.md', '营收和付费客户均排除内部、取消、退款和零金额订单；年度客户数须在年度范围去重。')
    run('context', 'validate', '--strict')
    run('context', 'build')
    import duckdb
    db = duckdb.connect()
    db.execute('CREATE VIEW orders AS ' + ref_sql)
    db.execute("CREATE VIEW valid_orders AS SELECT * FROM orders WHERE status='paid' AND is_internal=0 AND amount>0")
    cases = []
    for year in [2024,2025,2026]:
        for label, expr in [('营收','SUM(amount)'),('付费客户数','COUNT(DISTINCT customer_id)'),('付费订单数','COUNT(*)'),('平均订单金额','AVG(amount)'),('最大订单金额','MAX(amount)')]:
            cases.append({'question':f'{year}年{label}是多少', 'sql':f'SELECT {expr} AS value FROM valid_orders WHERE order_date >= DATE \'{year}-01-01\' AND order_date < DATE \'{year+1}-01-01\''})
    for month in [1,2,3,12]:
        for label, expr in [('营收','SUM(amount)'),('付费客户数','COUNT(DISTINCT customer_id)'),('订单数','COUNT(*)')]:
            cases.append({'question':f'2025年{month}月{label}是多少','sql':f'SELECT {expr} AS value FROM valid_orders WHERE YEAR(order_date)=2025 AND MONTH(order_date)={month}'})
    cases.extend([
        {'question':'2025年客户营收排名','sql':"SELECT customer_id, SUM(amount) AS revenue FROM valid_orders WHERE YEAR(order_date)=2025 GROUP BY customer_id ORDER BY revenue DESC"},
        {'question':'内部客户有多少付费订单（诊断，不保存）','sql':"SELECT COUNT(*) AS value FROM orders WHERE is_internal=1 AND status='paid'"},
        {'question':'2025年外部付费订单明细','sql':"SELECT id,customer_id,amount FROM valid_orders WHERE YEAR(order_date)=2025 ORDER BY id"},
    ])
    if args.reuse_queries and (root / 'questions.json').exists():
        cases = json.loads((root / 'questions.json').read_text(encoding='utf-8'))
    for case in ([] if args.reuse_queries else cases):
        cursor = db.execute(case['sql'])
        names = [c[0] for c in cursor.description]
        case['expected'] = [dict(zip(names, row)) for row in cursor.fetchall()]
        case['actual'] = json_rows(run('query','--sql',case['sql'],'--output','json'))
        case['passed'] = same_rows(case['actual'], case['expected'])
    write(root, 'questions.json', json.dumps(cases,ensure_ascii=False,indent=2,default=str))
    assert all(c['passed'] for c in cases), 'CLI business results differ from DuckDB reference'
    run('cube','list')
    run('cube','describe','sales')
    annual = run('cube','query','--cube','sales','--measures','paying_customers,revenue','--filter','order_date:gte:2025-01-01','--filter','order_date:lt:2026-01-01','--output','json')
    assert same_rows(json_rows(annual), [{'paying_customers':2,'revenue':3099}]), annual
    write(root, 'cube-annual.json', annual)
    run('cube','query','--cube','sales','--measures','paying_customers,revenue','--time-dimension','order_date:month','--sql-only')
    run('memory','store','--nl','2025年付费客户数有多少','--sql',cases[6]['sql'])
    run('memory','index','--no-seed',timeout=600)
    run('memory','status')
    recall = json.loads(run('memory','recall','-q','去年购买过产品的客户一共有几位','--output','json'))
    assert recall, 'Chinese paraphrase did not retrieve a pair'
    asyncio.run(check_mcp(args.wren, root, env))
    write(root, 'summary.json', json.dumps({'version':'0.15.0','questions':len(cases),'passed':sum(c['passed'] for c in cases),'chinese_recall':True,'mcp':True},ensure_ascii=False,indent=2))
    print('Official CLI/MCP fixture checks completed:', root)

async def check_mcp(executable, root, env):
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    params = StdioServerParameters(command=executable,args=['serve','mcp','--project',str(root),'--profile','fixture','--quiet'],env=env)
    async with stdio_client(params) as (read, write_stream):
        async with ClientSession(read, write_stream) as session:
            await session.initialize()
            schemas = await session.list_tools()
            write(root,'mcp-tools.json',schemas.model_dump_json(indent=2))
            for name, arguments in [('get_instructions',{}),('run_sql',{'sql':"SELECT COUNT(DISTINCT customer_id) AS value FROM valid_orders WHERE YEAR(order_date)=2025"}),('query_cube',{'cube':'sales','measures':['paying_customers'],'filters':['order_date:gte:2025-01-01', 'order_date:lt:2026-01-01']}),('recall_queries',{'question':'去年有几位购买客户'})]:
                result = await session.call_tool(name,arguments)
                write(root, f'mcp-{name}.json',result.model_dump_json(indent=2))
                assert not result.isError, f'MCP {name} failed: {result}'

if __name__ == '__main__':
    main()
