"""Reproducible synthetic retail source files. Monetary arithmetic uses integer cents."""
import csv
import json
import random
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RNG = random.Random(20261116)


def write(name, rows):
    with (ROOT / (name + '.csv')).open('w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


regions = ['华东', '华南', '华北', '西南', '华中', '西北']
cities = [('上海', '杭州'), ('深圳', '广州'), ('北京', '天津'), ('成都', '重庆'), ('武汉', '长沙'), ('西安', '兰州')]
stores = []
for i, region in enumerate(regions):
    for j, city in enumerate(cities[i]):
        stores.append(dict(store_id=f'S{i * 2 + j + 1:02}', store_name=city + ('旗舰店' if j == 0 else '城市店'), region=region, city=city, store_format='旗舰店' if j == 0 else '城市店'))
products = []
for i, (category, names, prices) in enumerate([
    ('户外装备', ['轻量冲锋衣', '山行登山包', '防风软壳', '徒步登山杖'], [899, 699, 599, 299]),
    ('运动服饰', ['速干运动T恤', '轻弹训练裤', '晨跑防晒衣', '越野运动鞋'], [199, 329, 399, 799]),
    ('日常生活', ['便携保温杯', '露营折叠椅', '旅行收纳包', '极简通勤包'], [159, 369, 129, 459]),
    ('专业配件', ['全天候运动帽', '偏光太阳镜', '专业运动袜', '越野腰包'], [139, 299, 69, 199])]):
    for j, name in enumerate(names):
        products.append(dict(product_id=f'P{i * 4 + j + 1:02}', product_name=name, category=category, list_price=prices[j]))
channels = ['线下门店', '官方商城', '内容电商']
orders = []
monthly = defaultdict(int)
day = date(2025, 4, 1)
while day <= date(2026, 9, 30):
    growth = 1 + (day - date(2025, 4, 1)).days / 1400
    promo = 1.45 if day.month in [6, 9] and 12 <= day.day <= 20 else 1
    n = int((62 + RNG.randrange(25)) * growth * promo * (1.16 if day.weekday() >= 5 else 1))
    for _ in range(n):
        store = RNG.choices(stores, weights=[18, 14, 17, 12, 10, 7, 13, 9, 9, 7, 6, 4])[0]
        product = RNG.choices(products, weights=[20, 12, 8, 5, 14, 10, 7, 13, 15, 9, 6, 10, 8, 7, 12, 5])[0]
        channel = RNG.choices(channels, weights=[44, 37, 19 + (15 if day.year == 2026 else 0)])[0]
        qty = RNG.choices([1, 2, 3], weights=[75, 22, 3])[0]
        gross = product['list_price'] * qty * 100
        discount_rate = (0.12 if channel == '内容电商' else 0.06) + (0.08 if promo > 1 else 0) + (0.04 if store['region'] == '西南' else 0)
        discount = round(gross * discount_rate)
        refund = gross - discount if RNG.random() < (0.055 if channel == '内容电商' else 0.027) else 0
        net = gross - discount - refund
        cost = round(gross * (0.47 + (int(product['product_id'][1:]) % 4) * 0.035)) if not refund else 0
        row = dict(order_id=f'YJ{len(orders)+1:07}', order_date=day.isoformat(), store_id=store['store_id'], product_id=product['product_id'], channel=channel, customer_segment=RNG.choices(['新客', '复购会员'], [54, 46])[0], quantity=qty,
                   gross_sales=f'{gross/100:.2f}', discount_amount=f'{discount/100:.2f}', refund_amount=f'{refund/100:.2f}', net_sales=f'{net/100:.2f}', net_cost=f'{cost/100:.2f}', net_profit=f'{(net-cost)/100:.2f}')
        orders.append(row)
        monthly[(day.strftime('%Y-%m-01'), store['store_id'], channel)] += net
    day += timedelta(days=1)
targets = []
for (month, sid, channel), actual in sorted(monthly.items()):
    region = stores[int(sid[1:]) - 1]['region']
    attainment = {'华东': 1.12, '华南': 1.06, '华北': .94, '西南': 1.16, '华中': 1.02, '西北': .90}[region]
    target = round(actual / attainment / 10000) * 10000
    targets.append(dict(target_month=month, store_id=sid, channel=channel, sales_target=f'{max(target,10000)/100:.2f}'))
write('retail_orders', orders)
write('retail_stores', stores)
write('retail_products', products)
write('retail_targets', targets)
def summarize(start, end, region=None, channel=None):
    selected = [r for r in orders if start <= r['order_date'] < end and (channel is None or r['channel'] == channel) and (region is None or stores[int(r['store_id'][1:])-1]['region'] == region)]
    cents = lambda field: sum(round(float(r[field])*100) for r in selected)
    return dict(orders=len(selected), paid_orders=sum(float(r['net_sales']) > 0 for r in selected), net_sales_cents=cents('net_sales'), net_profit_cents=cents('net_profit'), refund_cents=cents('refund_amount'), gross_cents=cents('gross_sales'))
truth = dict(seed=20261116, counts={n:len(v) for n,v in [('orders',orders),('stores',stores),('products',products),('targets',targets)]}, q3_2026=summarize('2026-07-01','2026-10-01'), q3_2025=summarize('2025-07-01','2025-10-01'), east_q3=summarize('2026-07-01','2026-10-01','华东'), online_q3=summarize('2026-07-01','2026-10-01',channel='官方商城'))
(ROOT/'truth.json').write_text(json.dumps(truth,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(truth,ensure_ascii=False))
