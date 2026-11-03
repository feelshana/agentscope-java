"""Package verified artifacts; retain authoring/debug files outside delivered data."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent
SOURCE=ROOT/'outputs/synthetic-commerce-v1'
MODULE=ROOT.parent.parent
DEST=MODULE/'outputs/synthetic-commerce-v1-20261102'
ADMIN=SOURCE/'验收参考_不要上传知识库'
assert DEST.resolve().is_relative_to(MODULE.resolve())
assert not DEST.exists(), 'Existing delivery must be reviewed instead of overwritten'
parser=json.loads((ADMIN/'实际Excel解析校验.json').read_text(encoding='utf-8'))
assert parser['passed']
checks=json.loads((ADMIN/'数据校验.json').read_text(encoding='utf-8'))
analysis=json.loads((ADMIN/'分析脚本校验.json').read_text(encoding='utf-8'))
answers=json.loads((ADMIN/'标准答案.json').read_text(encoding='utf-8'))

NS={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
export_checks=[]
for name,nrow,ncol in [('演示订单.xlsx',48000,19),('演示客户.xlsx',2000,6)]:
    with zipfile.ZipFile(SOURCE/name) as z:
        wb=ET.fromstring(z.read('xl/workbook.xml'))
        assert len(wb.findall('m:sheets/m:sheet',NS))==1
        rows=formulas=iso_dates=0
        with z.open('xl/worksheets/sheet1.xml') as stream:
            for _,elem in ET.iterparse(stream,events=['end']):
                if elem.tag.endswith('}row'):
                    rows+=1
                    cells=list(elem)
                    assert len(cells)==ncol
                    formulas+=sum(c.find('m:f',NS) is not None for c in cells)
                    iso_dates+=sum(c.attrib.get('t')=='d' for c in cells)
                    elem.clear()
        assert rows==nrow+1 and formulas==iso_dates==0
        sheet=ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
        assert sheet.find('m:mergeCells',NS) is None
        assert sheet.find('m:sheetViews/m:sheetView/m:pane',NS) is not None
        assert any(x.startswith('xl/tables/') and x.endswith('.xml') for x in z.namelist())
        export_checks.append({'file':name,'data_rows':nrow,'columns':ncol,'sheets':1,'formulas':0,'iso_date_cells':0,'native_excel_table':True,'frozen_header':True})
(ADMIN/'导出文件校验.json').write_text(json.dumps(export_checks,ensure_ascii=False,indent=2),encoding='utf-8')

def fmt(v):
    if v is None:return '不可计算'
    if isinstance(v,float):return f'{v:,.4f}'.rstrip('0').rstrip('.')
    return str(v)

report=['# 管理员验收参考','','不要把本文件或本文件夹上传给建模助手。标准答案来自独立SQLite查询，不来自模型回答。参考SQL中的金额是整数分；最终Excel为元，不能直接把这些SQL当作Wren语义SQL执行。','','## 必查结果','',
    '- 两表合计50000条业务记录，订单48000、客户2000，184个连续日期，1800名有订单客户和200名未下单客户。',
    '- 全量净销售额9,640,810.45元，毛利额4,099,288.45元。',
    '- 10月净销售额1,918,953.65元，毛利额789,614.65元。',
    '- 归因对比：399,558.20元降至362,259.10元，下降37,299.10元（9.34%）。短视频商城下降42,921.35元，自营App增长5,622.25元。短视频下降来自华东。',
    '- 短视频贡献115.07%，大于100%是因为其他渠道增长形成抵消，不能强行改成100%。',
    '- 促销对比：406,866.20元增至439,940.45元；毛利173,255.20元降至159,054.45元，毛利率42.58%降至36.15%。',
    '- 9月15日至21日，短视频商城数码配件退款金额率35.68%，在至少10笔成交单的组合中最高。',
    '- 异常脚本只识别10月28日，订单1048笔，预期251.25笔，27.6σ偏高。',
    '- 预测150天历史产生随后7天预测合计1890.80笔，提供80%/95%区间；这是模型预测值，不是实际订单数，历史回测答案不用于保证预测一定准确。','',
    '## 数据与文件验证','',
    '固定随机种子生成，不读取真实业务资料；使用整数分做独立核算；关联主键唯一、无孤儿键、联表不扩行；最终Excel由当前项目实际ExcelParser完整读取，日期推断DATETIME、金额精确到分、总额与源数据一致。实际脚本使用现有沙箱镜像执行，网络关闭，无数据库、LLM或生产请求。','',
    '首轮导出的ISO日期编码不能被当前POI schema读取；已重新导出为Excel数值日期并通过实际解析。一次临时校验把类型名误写成TIMESTAMP，已按项目真实DATETIME类型修正并重跑；未修改项目解析器。','','## 分题参考','']
for item in answers:
    report+=['### '+item['id']+' '+item['question'],'']
    if item['note']:report +=[item['note'],'']
    rows=item['expected']
    if rows is None:
        report+=['应明确说明缺少数据，不能给出数字。',''];continue
    if not rows:
        report+=['没有匹配记录。',''];continue
    keys=list(rows[0])
    report+=['| '+' | '.join(keys)+' |','| '+' | '.join('---' for _ in keys)+' |']
    report+=['| '+' | '.join(fmt(row[k]) for k in keys)+' |' for row in rows[:10]]
    if len(rows)>10:report+=['',f'共{len(rows)}行，此处展示前10行；完整记录见标准答案.json。']
    report+=['']
(ADMIN/'验收参考.md').write_text('\n'.join(report),encoding='utf-8')
guide=SOURCE/'使用说明.md'
guide.write_text(guide.read_text(encoding='utf-8')+'\n## 已完成的文件兼容检查\n\n已使用当前项目ExcelParser完整解析两份最终Excel，订单48000行、客户2000行、日期列识别为DATETIME，所有关联键和关键金额总数通过。日期采用Excel数值日期及显式时间格式，兼容当前POI，避免ISO日期单元格编码问题。\n',encoding='utf-8')

shutil.copytree(SOURCE,DEST,ignore=shutil.ignore_patterns('*.inspect.ndjson'))
# A reproducible generator is kept with the delivery, outside the two upload files.
repro=DEST/'验收参考_不要上传知识库/生成与校验工具'
repro.mkdir()
for src in [ROOT/'generate.py',ROOT/'build-workbooks.mjs',ROOT/'finalize.py',ROOT/'work/VerifyDemoExcel.java',ROOT/'work/verify-analysis.py']:
    shutil.copy2(src,repro/src.name)
(repro/'说明.md').write_text('生成器固定种子20261102。generate.py仅依赖Python标准库，生成原始JSON/CSV、独立SQLite参考和业务说明；build-workbooks.mjs使用Codex bundled @oai/artifact-tool导出Excel，不是产品运行依赖。VerifyDemoExcel.java通过项目编译类和依赖调用实际解析器。verify-analysis.py在现有分析沙箱镜像、无网络环境运行原项目脚本。工具来源与当前工作目录不同，重跑前应选择独立目录并按脚本ROOT路径调整；不要覆盖旧线上示例。finalize.py为本次打包脚本，包含已验证控制值与输出目录保护。\n',encoding='utf-8')
manifest=[]
for f in sorted(DEST.rglob('*')):
    if f.is_file():manifest.append(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.relative_to(DEST).as_posix())
(DEST/'SHA256SUMS').write_text('\n'.join(manifest)+'\n',encoding='utf-8')
zip_path=DEST.parent/'DataAgent-通用虚构示例-v1-20261102.zip'
assert not zip_path.exists()
with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for f in DEST.rglob('*'):
        if f.is_file():z.write(f,Path(DEST.name)/f.relative_to(DEST))
with zipfile.ZipFile(zip_path) as z:assert z.testzip() is None
desktop=Path('C:/Users/96309/Desktop')/zip_path.name
assert not desktop.exists(), 'Do not overwrite an existing desktop file'
shutil.copy2(zip_path,desktop)
print(json.dumps({'directory':str(DEST),'zip':str(zip_path),'desktop_zip':str(desktop),'zip_bytes':zip_path.stat().st_size,'files':len(manifest)+1,'workbooks':export_checks},ensure_ascii=False,indent=2))
