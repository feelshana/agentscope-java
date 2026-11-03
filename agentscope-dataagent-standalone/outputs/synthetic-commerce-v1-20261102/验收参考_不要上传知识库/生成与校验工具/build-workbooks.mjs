import fs from 'node:fs/promises';
import path from 'node:path';
import { Workbook, SpreadsheetFile, FileBlob } from '@oai/artifact-tool';

const root = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1'));
const data = JSON.parse(await fs.readFile(path.join(root,'work/workbook-data.json'),'utf8'));
const output = path.join(root,'outputs/synthetic-commerce-v1');
for (const table of data.tables) {
  console.log('Authoring',table.name,table.rows.length,'rows');
  const layoutOnly = process.argv.includes('--layout-only');
  const wb = layoutOnly ? await SpreadsheetFile.importXlsx(await FileBlob.load(path.join(output,table.file))) : Workbook.create();
  const sheet = layoutOnly ? wb.worksheets.getItemAt(0) : wb.worksheets.add(table.name);
  const ncol = table.fields.length;
  // Numeric Excel serials keep dates typed while avoiding the ISO t="d" export unsupported by this POI schema.
  const rows = table.rows.map(row => row.map((v,i) => table.dates.includes(i) ? Date.parse(v.replace(' ','T')+'Z')/86400000+25569 : v));
  if (!layoutOnly) {
  sheet.getRangeByIndexes(0,0,1,ncol).values = [table.fields.map(([key,label])=>key+' '+label)];
  for(let at=0;at<rows.length;at+=2000){
    const batch = rows.slice(at,at+2000);
    sheet.getRangeByIndexes(at+1,0,batch.length,ncol).values = batch;
  }
  console.log('Values ready',table.name);
  const all = sheet.getRangeByIndexes(0,0,rows.length+1,ncol);
  all.format.font = { name:'Arial',size:10,color:'#283345' };
  all.format.rowHeight = 21;
  all.format.columnWidth = 20;
  all.format.verticalAlignment = 'center';
  const head = sheet.getRangeByIndexes(0,0,1,ncol);
  head.format = { fill:'#5744B5',font:{name:'Arial',size:10,bold:true,color:'#FFFFFF'},rowHeight:32,horizontalAlignment:'center',verticalAlignment:'center' };
  for (let i=0;i<ncol;i++) {
    const column = sheet.getRangeByIndexes(1,i,rows.length,1);
    const key = table.fields[i][0];
    if (table.dates.includes(i)) {
      column.setNumberFormat('yyyy-mm-dd hh:mm:ss');
      sheet.getRangeByIndexes(0,i,rows.length+1,1).format.columnWidth = 30;
    } else if (table.money.includes(i)) {
      column.setNumberFormat('#,##0.00');
      column.format.horizontalAlignment = 'right';
      sheet.getRangeByIndexes(0,i,rows.length+1,1).format.columnWidth = 25;
    } else if (key==='quantity' || key==='refunded_quantity') {
      column.setNumberFormat('#,##0');column.format.horizontalAlignment='right';
    } else {
      column.setNumberFormat('@');column.format.horizontalAlignment='left';
    }
  }
  sheet.showGridLines = false;
  sheet.freezePanes.freezeRows(1);
  const t = sheet.tables.add(all,true,table.name==='演示订单'?'DemoOrders':'DemoCustomers');
  t.style = 'TableStyleMedium2';
  t.showFilterButton = true;
  }
  // Fit bilingual source headers without adding a title row that the upload parser would read as data.
  for (let i=0;i<ncol;i++) {
    const [key,label] = table.fields[i];
    const width = Math.max(table.dates.includes(i)?30:20,Math.ceil((key.length*7+label.length*14+28)/7));
    sheet.getRangeByIndexes(0,i,1,1).format.columnWidth = width;
  }
  if (layoutOnly) for(const i of table.dates){
    const r=sheet.getRangeByIndexes(1,i,rows.length,1);
    r.values=rows.map(row=>[row[i]]);
    r.setNumberFormat('yyyy-mm-dd hh:mm:ss');
  }
  wb.recalculate();
  const check = await wb.inspect({kind:'table',range:`'${table.name}'!A1:D4`,include:'values,formulas',tableMaxRows:4,tableMaxCols:4,maxChars:1200});
  console.log(check.ndjson);
  const errors = await wb.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!',options:{useRegex:true,maxResults:10},maxChars:800});
  console.log('Error scan', errors.ndjson);
  const previews = table.name==='演示订单'?['A1:I12','J1:S12']:['A1:F12'];
  for(let i=0;i<previews.length;i++){
    const png = await wb.render({sheetName:table.name,range:previews[i],scale:1.3,format:'png'});
    await fs.writeFile(path.join(root,`work/${table.name}-${i}.png`),new Uint8Array(await png.arrayBuffer()));
  }
  const file = await SpreadsheetFile.exportXlsx(wb);
  await file.save(path.join(output,table.file));
  console.log('Saved',table.file);
}
