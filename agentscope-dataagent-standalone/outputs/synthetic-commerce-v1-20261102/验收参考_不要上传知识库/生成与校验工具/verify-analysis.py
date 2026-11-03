"""Run the project's unchanged fixed analysis scripts on independent SQL aggregates."""
import json
import subprocess
import sys
from pathlib import Path

out = Path('/results/分析脚本结果')
out.mkdir(exist_ok=True)
cases = {
    'attribution': {'metric':'净销售额','period':'期间','current':'当前期','base':'对比期','dimensions':['销售渠道','客户地区'],'depth':2,'title':'净销售额变化拆解'},
    'anomaly': {'date':'日期','metric':'订单数','granularity':'day','title':'十月每日订单异常'},
    'forecast': {'date':'日期','metric':'订单数','granularity':'day','horizon':7,'title':'订单量七天历史回测'},
}
results = {}
for name,params in cases.items():
    run = subprocess.run([sys.executable,f'/skills/{name}/{name}.py','--input',f'/work/{name}.csv','--params',json.dumps(params,ensure_ascii=False),'--outdir',str(out)],capture_output=True,text=True,timeout=180)
    if run.returncode:
        raise RuntimeError(name+': '+run.stderr[-2500:])
    result = json.loads(run.stdout)
    assert result.get('ok'), result
    (out/(name+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    results[name] = result
    print(name,'ok',flush=True)
a = results['attribution']
assert a['checks']['items_sum_to_total_delta']
assert abs(a['total']['base']-399558.20) < .005
assert abs(a['total']['current']-362259.10) < .005
assert any(x['time']=='2026-10-28' for x in results['anomaly']['anomalies'])
f = results['forecast']
assert f['history_points']==150
assert len(f['forecast'])==7
assert f['forecast'][0]['time']=='2026-09-28'
assert f['forecast'][-1]['time']=='2026-10-04'
assert all(p['lo95']<=p['lo80']<=p['value']<=p['hi80']<=p['hi95'] for p in f['forecast'])
summary = {
    'execution':'Unchanged project scripts in the existing analysis sandbox image, no network, no LLM or database connection.',
    'attribution':{'main_dimension':a['dimension'],'total':a['total'],'leader':a['items'][0],'drilldown':a['drilldown'],'contribution_reconciles':True},
    'anomaly':{'points':results['anomaly']['points_count'],'anomalies':results['anomaly']['anomalies']},
    'forecast':{'history_points':150,'forecast_points':7,'total':f['total'],'intervals_ordered':True},
}
(Path('/results')/'分析脚本校验.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False))
