"""Build original vector icon proposals and review boards; no product code changes."""
from pathlib import Path
from html import escape

ROOT = Path(__file__).resolve().parent
INK = '#303541'
VIOLET = '#6550df'
NAMES = [('knowledge', '知识库'), ('modeling', '语义建模'), ('terms', '业务术语'), ('scheduled', '例行任务'), ('more', '更多'), ('source', '数据源'), ('dataset', '数据集'), ('compose', '新建对话')]
OUTLINE = {
    'knowledge': '<rect x="3.5" y="4" width="6" height="16" rx="1.5"/><path d="M6.5 7.5v5"/><rect x="13" y="4" width="6" height="16" rx="1.5" transform="rotate(-12 16 12)"/>',
    'modeling': '<rect x="3.5" y="3.5" width="6" height="6" rx="1.5"/><rect x="14.5" y="14.5" width="6" height="6" rx="1.5"/><path d="M6.5 9.5v6a2 2 0 0 0 2 2h6M9.5 6.5h6a2 2 0 0 1 2 2v2"/>',
    'terms': '<path d="M3.5 6h11M9 6v13M6.5 19h5M16.5 11h4M16.5 15h4M16.5 19h4"/>',
    'scheduled': '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M7.5 3.5v3M16.5 3.5v3M3.5 10h17M8 14h2M14 14h2M8 17.5h2"/>',
    'more': '<circle cx="5" cy="12" r="1.35" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.35" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.35" fill="currentColor" stroke="none"/>',
    'source': '<ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5v12c0 4 16 4 16 0v-12M4 11.5c0 4 16 4 16 0"/>',
    'dataset': '<rect x="3.5" y="3.5" width="17" height="17" rx="2.5"/><path d="M3.5 9h17M9 9v11.5M9 14.5h11.5"/>',
    'compose': '<path d="M12 4.5H6a2.5 2.5 0 0 0-2.5 2.5v11A2.5 2.5 0 0 0 6 20.5h11a2.5 2.5 0 0 0 2.5-2.5v-6M11 10.5l6.5-6.5a1.8 1.8 0 0 1 2.5 2.5L13.5 13l-4 1.5Z"/>',
}
SOLID = {
    'knowledge': '<path fill-rule="evenodd" d="M4.8 3.5h3.4A1.8 1.8 0 0 1 10 5.3v13.4a1.8 1.8 0 0 1-1.8 1.8H4.8A1.8 1.8 0 0 1 3 18.7V5.3a1.8 1.8 0 0 1 1.8-1.8Zm1 3.5v5.5h1.4V7Z"/><path fill-rule="evenodd" d="M14.3 3.5h3.4a1.8 1.8 0 0 1 1.8 1.8v13.4a1.8 1.8 0 0 1-1.8 1.8h-3.4a1.8 1.8 0 0 1-1.8-1.8V5.3a1.8 1.8 0 0 1 1.8-1.8Zm1 3.5v5.5h1.4V7Z" transform="rotate(-12 16 12)"/>',
    'modeling': '<path d="M6.5 8v7.5a2 2 0 0 0 2 2H16M8 6.5h7.5a2 2 0 0 1 2 2v2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/><rect x="3" y="3" width="7" height="7" rx="1.8"/><rect x="14" y="14" width="7" height="7" rx="1.8"/>',
    'terms': '<path d="M3 5.5h12v2H10v12H8v-12H3Z"/><rect x="16.5" y="10" width="4.5" height="2" rx=".7"/><rect x="16.5" y="14" width="4.5" height="2" rx=".7"/><rect x="16.5" y="18" width="4.5" height="2" rx=".7"/>',
    'scheduled': '<path fill-rule="evenodd" d="M6 4h12a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3Zm-.5 5.5h13v-2h-13Zm2 4h2v2h-2Zm7 0h2v2h-2Zm-7 4h2V19h-2Z"/><path d="M7.5 3v3M16.5 3v3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    'more': '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>',
    'source': '<path fill-rule="evenodd" d="M12 2.5c5 0 9 1.5 9 3.5v12c0 2-4 3.5-9 3.5S3 20 3 18V6c0-2 4-3.5 9-3.5ZM5 6c0 1.1 3.2 2 7 2s7-.9 7-2-3.2-2-7-2-7 .9-7 2Zm0 5.2v1.6c4 2.3 10 2.3 14 0v-1.6c-4 2.3-10 2.3-14 0Z"/>',
    'dataset': '<path fill-rule="evenodd" d="M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Zm-.5 3v2H19V6ZM6 10v8h2v-8Zm4 0v3h8v-3Zm0 5v3h8v-3Z"/>',
    'compose': '<path d="M11.5 3H6a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3h12a3 3 0 0 0 3-3v-5.5h-2V18a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5.5Z"/><path d="m10.5 10.5 7-7a2.1 2.1 0 0 1 3 3l-7 7-4.5 1.5Z"/>',
}

def svg_icon(key, family='outline', size=24, color=INK):
    attrs = 'fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round"' if family == 'outline' else 'fill="currentColor"'
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="{size}" height="{size}" color="{color}" {attrs}>{(OUTLINE if family == "outline" else SOLID)[key]}</svg>'

def txt(x, y, content, size=14, fill=INK, weight=400, anchor='start'):
    return f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}" font-weight="{weight}" text-anchor="{anchor}">{escape(content)}</text>'

def rect(x,y,w,h,fill,rx=0,stroke='none'):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}" rx="{rx}" stroke="{stroke}"/>'

def icon(key, family, x,y,size=24,color=INK):
    return f'<g transform="translate({x} {y})">{svg_icon(key,family,size,color)}</g>'

def board(body,w,h):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" font-family="Microsoft YaHei,Segoe UI,sans-serif">{rect(0,0,w,h,"white")}{body}</svg>'

for family in ['outline','solid']:
    d=ROOT/family
    d.mkdir(parents=True,exist_ok=True)
    for key,_ in NAMES:
        (d/f'{key}.svg').write_text(svg_icon(key,family),encoding='utf-8')

s=txt(64,63,'红海 DataAgent  /  系统图标提案',15,'#747b88')
s+=txt(64,126,'让功能一眼可辨。',36,weight=650)
s+=txt(64,164,'统一 24px 网格 · 导航使用 20px · 以清楚的轮廓、留白与语义建立识别度',15,'#747b88')
for index,(family,title,sub) in enumerate([('outline','A   精工线性','轻、清晰，适合长时间使用的工作界面'),('solid','B   轻实心','识别更直接，小尺寸下视觉重量更稳定')]):
    y=212+index*238
    s+=rect(40,y,1240,214,'#f8f9fb',18)
    s+=txt(64,y+40,title,21,weight=600)+txt(310,y+39,sub,14,'#747b88')
    for j,(key,name) in enumerate(NAMES):
        x=126+j*151
        s+=icon(key,family,x-22,y+76,44)
        s+=txt(x,y+151,name,14,anchor='middle')
        s+=icon(key,family,x-10,y+171,20,'#606775')
s+=txt(64,747,'从图形到界面',23,weight=600)
s+=txt(64,780,'知识库用“并列书册”，术语用“文字与定义”，建模用“相连模块”。功能语义各有区分。',15,'#747b88')
s+=rect(64,813,568,112,'white',12,'#e4e7ed')+txt(88,842,'尺寸校准',12,'#747b88')
for i,size in enumerate([16,20,24,32]):
    x=110+i*126
    s+=icon('knowledge','outline',x,858,size)+txt(x+36,879,f'{size}px',12,'#747b88')
s+=rect(656,813,600,112,'white',12,'#e4e7ed')+txt(680,842,'默认与选中',12,'#747b88')
s+=icon('knowledge','outline',685,864,20,'#606775')+txt(718,881,'知识库',14)
s+=rect(880,852,164,46,'#f1edfc',8)+icon('knowledge','solid',896,865,20,VIOLET)+txt(931,881,'知识库',14,VIOLET,600)
s+=txt(64,974,'设计提案 · 原创 SVG · 尚未应用到产品',12,'#9097a3')
(ROOT/'01-icon-families.svg').write_text(board(s,1320,1010),encoding='utf-8')

s=txt(56,55,'红海 DataAgent  /  放回真实界面',15,'#747b88')+txt(56,107,'图标小，差别要在使用时看得见。',29,weight=600)
for index,(family,title) in enumerate([('outline','A  精工线性'),('solid','B  轻实心')]):
    ox=56+index*648
    s+=txt(ox,162,title,20,weight=600)
    s+=rect(ox,190,608,526,'white',14,'#e3e6ed')
    s+=rect(ox+1,191,207,524,'#f7f8fa',13)
    s+=txt(ox+20,230,'红海 DataAgent',18,weight=650)
    s+=rect(ox+16,253,176,41,'#282c36',8)+txt(ox+30,279,'新建对话',13,'white',500)+icon('compose',family,ox+160,264,18,'white')
    for j,(key,label) in enumerate(NAMES[:5]):
        y=316+j*47
        if j==0:s+=rect(ox+12,y-8,184,42,'#eeeaf9',8)
        s+=icon(key,family,ox+27,y+3,20,VIOLET if j==0 else '#555d6c')+txt(ox+62,y+19,label,14,VIOLET if j==0 else INK,550 if j==0 else 400)
    s+=txt(ox+24,592,'历史会话',12,'#858c99')+txt(ox+24,629,'每天的报表访问次数',12,'#697180')
    x=ox+232
    s+=txt(x,232,'知识库',23,weight=650)+txt(x,263,'组织你的业务数据',12,'#747b88')
    s+=rect(x,291,350,195,'white',11,'#e1e5eb')
    s+=icon('knowledge',family,x+20,313,28)+txt(x+64,335,'报表运营',17,weight=600)
    s+=txt(x+20,375,'报表访问记录与组织信息，',13,'#747b88')+txt(x+20,397,'用于分析平台使用情况。',13,'#747b88')
    s+=txt(x+20,448,'2 个数据集',12,'#747b88')+txt(x+238,448,'查看数据  →',12,VIOLET)
    s+=rect(x,508,350,66,'#fafbfc',8)
    s+=icon('source',family,x+18,530,22,'#606775')+txt(x+54,539,'数据源管理',14)+txt(x+54,559,'连接与管理外部业务数据库',11,'#858c99')
    s+=rect(x,591,350,66,'#fafbfc',8)
    s+=icon('terms',family,x+18,613,22,'#606775')+txt(x+54,622,'业务术语',14)+txt(x+54,642,'统一业务定义与统计口径',11,'#858c99')
s+=txt(56,764,'建议：默认用 A 的清晰轮廓，选中时用 B 的同语义实心形态；选中行的文字与背景同步变化。',15,'#606775')
s+=txt(56,800,'品牌标识本轮沿用现有方案；这里重点比较功能图标、字号比例与界面中的视觉重量。',13,'#858c99')
(ROOT/'02-in-context.svg').write_text(board(s,1320,840),encoding='utf-8')

html='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红海 DataAgent · 图标设计提案</title><style>body{margin:0;background:#f2f3f6;color:#303541;font:14px/1.8 'Microsoft YaHei',sans-serif}main{max-width:1320px;margin:24px auto}img{width:100%;height:auto;display:block;margin-bottom:24px;background:white}nav{padding:16px;background:white;display:flex;gap:20px;flex-wrap:wrap}a{color:#6550df}</style><main><nav><a href="01-icon-families.svg">查看矢量图稿</a><a href="02-in-context.svg">查看页面效果</a><span>设计提案，供选择；未应用到业务页面。</span></nav><img src="01-icon-families.svg" alt="两套图标的放大图、20像素实际尺寸和选中状态"><img src="02-in-context.svg" alt="两套图标在导航和知识库卡片中的对比"></main></html>'''
(ROOT/'index.html').write_text(html,encoding='utf-8')
print('Created 16 SVG assets, 2 vector boards and HTML review.')
