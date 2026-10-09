"""Sequential precision and Python follow-ups after baseline questions complete."""
import json,uuid
from live_validation import Client,load_state,record
client=Client('bob');state=load_state()
cases=[('Q05_explicit','2025年2月有效订单销售额（不扣退款）是多少人民币元？按2025-02-01含至2025-03-01不含的边界计算，请实际查询。'),('python_sequential','请使用Wren查询2025年1至4月有效订单销售额（不扣退款，金额由分换算元），补齐无订单的3月为0。月份连接请将订单月份转换为yyyy-MM，与calendar_month.month按字符串对齐。然后必须调用run_python，从Wren返回的数据CSV文件读取，生成中文月度趋势图和CSV，计算总额及环比；3月基数为0时4月环比应为不可计算。不得抄写结果行为Python字面量。')]
for label,message in cases:
    try:
        events=client.stream('/api/agents/data-agent/chat/stream',{'message':message,'sessionKey':'validation-'+label+'-'+uuid.uuid4().hex,'groupIds':[state['group_id']]},label)
        record({'stage':'followup','id':label,'done':any(e.get('type')=='done' for e in events),'errors':[e.get('error') for e in events if e.get('type')=='error'],'tools':[e.get('toolName') for e in events if e.get('type')=='tool_call']})
    except Exception as error:record({'stage':'followup','id':label,'error':str(error)})
