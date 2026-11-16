"""Drive the real local APIs; resume stages using an ignored runtime state file."""
import json
import sys
import time
import uuid
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parent
APP=ROOT.parents[2]
RUNTIME=APP/'.runtime/showroom-retail'
RUNTIME.mkdir(parents=True,exist_ok=True)
STATE=RUNTIME/'state.json'
state=json.loads(STATE.read_text(encoding='utf8')) if STATE.exists() else {}
session=requests.Session()
BASE='http://127.0.0.1:8080'
login=session.post(BASE+'/api/auth/login',json={'username':'bob','password':'bob'},timeout=20)
login.raise_for_status()
session.headers['Authorization']='Bearer '+login.json()['token']

def api(method,path,**kwargs):
    r=session.request(method,BASE+path,timeout=kwargs.pop('timeout',300),**kwargs)
    if not r.ok: raise RuntimeError(str(r.status_code)+' '+r.text[:1500])
    return r.json()

def save(): STATE.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf8')

def chat(agent,message,purpose=None):
    conversation=state.setdefault(agent+'_conversation',str(uuid.uuid4()))
    if agent=='dashboard-agent': conversation=state['dashboardId']
    save()
    body=dict(message=message,sessionKey=conversation,groupIds=[state['groupId']],webSearch=False)
    if purpose:body['purpose']=purpose
    start=time.monotonic();events=[]
    with session.post(BASE+'/api/agents/'+agent+'/chat/stream',json=body,stream=True,timeout=(20,600)) as r:
        r.raise_for_status()
        for line in r.iter_lines():
            if not line.startswith(b'data:'): continue
            try: event=json.loads(line[5:])
            except ValueError: continue
            events.append(event)
            if event.get('type') in ['tool_call','tool_result','done','error']:
                print(round(time.monotonic()-start,1),event.get('type'),event.get('toolName',''),event.get('error','')[:180],flush=True)
    (RUNTIME/(agent+'-'+str(int(time.time()))+'.json')).write_text(json.dumps(events,ensure_ascii=False),encoding='utf8')
    text=''.join(e.get('data','') for e in events if e.get('type')=='token')
    print(text[-1200:],flush=True)
    if any(e.get('type')=='error' for e in events): raise RuntimeError('Agent did not complete; see runtime events')

stage=sys.argv[1]
if stage=='upload':
    if 'groupId' not in state:
        group=api('POST','/api/dataset-groups',json={'name':'曜境零售-全渠道经营样板','description':'合成演示数据：收入、毛利、目标、区域、渠道和商品的旗舰经营看板；2025-04至2026-09。'})
        state['groupId']=group['id'];state['datasets']={};save()
    for name in ['retail_stores','retail_products','retail_orders','retail_targets']:
        if name in state['datasets']:continue
        with (ROOT/(name+'.csv')).open('rb') as f:
            data=api('POST','/api/datasets',params={'name':name,'groupId':state['groupId']},files={'file':(name+'.csv',f,'text/csv')})
        state['datasets'][name]=data['id'];save();print('uploaded',name,data.get('rowCount'),flush=True)
    with (ROOT/'business-rules.md').open('rb') as f:
        api('PUT','/api/dataset-groups/'+state['groupId']+'/modeling/documents',files={'file':('business-rules.md',f,'text/markdown')})
    descriptions={'order_id':'订单唯一编号，主键','order_date':'下单自然日','store_id':'门店编号，关联retail_stores.store_id','product_id':'商品编号，关联retail_products.product_id','net_sales':'净销售额，人民币元，已扣优惠和退款','net_cost':'退款调整后商品净成本，人民币元','net_profit':'商品毛利，净销售额减净成本，不是净利润','gross_sales':'原价商品金额，人民币元','discount_amount':'优惠金额，人民币元','refund_amount':'退款金额，人民币元','quantity':'下单商品件数','channel':'销售渠道','customer_segment':'新客或复购会员','target_month':'目标月份的月初自然日','sales_target':'该月该门店该渠道净销售额目标，人民币元','region':'所属业务区域','city':'门店城市','store_name':'门店名称','store_format':'旗舰店或城市店','product_name':'商品名称','category':'商品品类','list_price':'商品含税标价，人民币元'}
    for name,did in state['datasets'].items():
        fields=api('GET',f'/api/dataset-groups/{state["groupId"]}/fields/{did}')
        updates=[dict(name=f['name'],included=True,description=descriptions.get(f['name'],f.get('description') or '')) for f in fields['fields']]
        api('PUT',f'/api/dataset-groups/{state["groupId"]}/fields/{did}',json={'updates':updates,'confirm':True,'force':False})
    print('READY',state['groupId'],flush=True)
elif stage=='model':
    chat('modeling-agent','请依据已上传business-rules.md完成基础建模。四张表保持英文模型名retail_orders、retail_stores、retail_products、retail_targets，字段名不改。建立订单到门店、订单到商品、目标到门店的多对一关系；补充中文字段说明和文档明确的指标规则。不要查询数据，不创建问答对、View、加工表或Cube。核对并编译草稿，完成后说明结果。','document')
elif stage=='publish-model':
    result=api('POST',f'/api/dataset-groups/{state["groupId"]}/modeling/mdl/publish');print(json.dumps(result,ensure_ascii=False)[:1600]);state['modelPublished']=result.get('ok');save()
elif stage=='generate':
    if 'dashboardId' not in state:
        d=api('POST','/api/dashboards',json={'groupId':state['groupId'],'title':'曜境零售 · 全渠道经营驾驶舱'});state['dashboardId']=d['id'];save()
    chat('dashboard-agent',(ROOT/'dashboard-prompt.md').read_text(encoding='utf8'))
elif stage=='adjust':
    chat('dashboard-agent',sys.argv[2])
elif stage=='publish':
    d=api('GET','/api/dashboards/'+state['dashboardId'],params={'draft':'true'})
    result=api('POST','/api/dashboards/'+state['dashboardId']+'/publish',json={'revision':d['revision']});state['publishedRevision']=result['publishedRevision'];save();print('PUBLISHED',state['dashboardId'])
