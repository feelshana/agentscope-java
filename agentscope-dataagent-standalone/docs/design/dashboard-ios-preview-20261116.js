/* Standalone UI prototype. No authentication, API, or persistent mutations. */
(() => {
'use strict';
const $ = id => document.getElementById(id);
const icons = {
 compose:'M14 4l6 6M4 20l4-1L21 6l-4-4L4 15zM12 4H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-7',
 book:'M4 3h5v18H4zM13 4l5-1 4 17-5 1z',chart:'M4 3v18h17M9 16V9m5 7V5m5 11v-5',
 network:'M3 3h6v6H3zM15 15h6v6h-6zM6 9v6h9M9 6h9v9',plus:'M12 5v14M5 12h14',
 arrow:'M12 20V4m-6 6 6-6 6 6',search:'M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15m5.5-2 5 5',
 external:'M14 3h7v7m-1-6L10 14M10 4H4v16h16v-6',edit:'M4 20l5-1L21 7l-4-4L5 15zM14 6l4 4',
 back:'M15 5l-7 7 7 7',upload:'M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5',check:'M5 12l4 4L19 6',
 panel:'M3 4h18v16H3zM9 4v16',refresh:'M20 7V2m0 5h-5M4 17v5m0-5h5M5 8a8 8 0 0 1 14-2M19 16A8 8 0 0 1 5 18',
 expand:'M4 9V4h5m6 0h5v5M4 15v5h5m6 0h5v-5',close:'M6 6l12 12M6 18 18 6'
};
const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${icons[name] || icons.chart}"/></svg>`;
function hydrate(root=document){root.querySelectorAll('[data-icon]').forEach(el=>{el.outerHTML=icon(el.dataset.icon);});}
hydrate();
const cards = [
 {title:'曜境零售 · 全渠道经营驾驶舱',status:'已发布',state:'published',mine:false,canEdit:true,color:'#0b9488',mini:'全渠道经营概览'},
 {title:'曜境零售 · 区域经营分析',status:'有未发布修改',state:'changed',mine:true,canEdit:true,color:'#4c72df',mini:'区域经营概览'},
 {title:'曜境零售 · 渠道增长洞察',status:'草稿',state:'draft',mine:true,canEdit:true,color:'#8c6be7',mini:'渠道增长概览'}
];
let filter='all',selected=0,isViewing=false,hasChanges=true,wasPublished=true,toastTimer;
const charts = new Map();
function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,3500);}
function chart(id,options){const el=$(id);if(!el||!el.clientWidth)return;let c=charts.get(id);if(!c){c=echarts.init(el,null,{renderer:'canvas'});charts.set(id,c);}c.setOption(options,true);return c;}
const compactAxis={type:'category',boundaryGap:false,axisLine:{show:false},axisTick:{show:false},axisLabel:{fontSize:7,color:'#9ba5b3'}};
const compactY={type:'value',splitNumber:3,axisLabel:{show:false},splitLine:{lineStyle:{color:'#f0f3f7'}}};
function renderCards(){
 $('cards').innerHTML=cards.map((c,i)=>`<article class="boardcard surface" data-card="${i}" ${!matches(c)?'hidden':''}>
 <button class="cover" data-open="${i}" data-view="${c.state==='draft'?'edit':'view'}" aria-label="${c.state==='draft'?'预览草稿':'查看'}：${c.title}">
 <div class="mini-dashboard ${i===1?'blue':i===2?'purple':''}"><div class="mini-title">${c.mini}<small>2026 Q3</small></div><div class="mini-trend" id="miniTrend${i}"></div><div class="mini-bottom"><div class="mini-chart" id="miniRing${i}"></div><div class="mini-chart" id="miniBar${i}"></div></div></div></button>
 <div class="cardbody"><h3>${c.title}</h3><span class="status ${c.state==='changed'?'changed':c.state==='draft'?'draft':''}">${c.status}</span><p class="source">${icon('book')}曜境零售-全渠道经营样板</p><p class="updated">${c.mine?'我创建的':'共享给我 · 可管理'} · 更新于今天</p></div>
 <footer class="cardfoot"><button data-open="${i}" data-view="${c.state==='draft'?'edit':'view'}">${icon(c.state==='draft'?'chart':'external')}${c.state==='draft'?'预览草稿':'查看看板'}</button><button data-open="${i}" data-view="edit">${icon('edit')}继续编辑</button></footer></article>`).join('');
 $('empty').hidden=cards.some(matches);
 requestAnimationFrame(drawMiniatures);
}
function matches(c){return(filter==='all'||(filter==='mine'&&c.mine)||(filter==='shared'&&!c.mine))&&(!currentSearch||c.title.toLowerCase().includes(currentSearch)||'曜境零售-全渠道经营样板'.includes(currentSearch));}
let currentSearch='';
function drawMiniatures(){cards.forEach((c,i)=>{
 if(document.querySelector(`[data-card="${i}"]`).hidden)return;
 chart(`miniTrend${i}`,{animation:false,grid:{left:5,right:5,top:11,bottom:18},xAxis:{...compactAxis,data:PROTOTYPE_DATA.month.labels,axisLabel:{fontSize:8,color:'#98a3b3',interval:0}},yAxis:compactY,series:[{type:i===1?'bar':'line',data:PROTOTYPE_DATA.month.values,smooth:true,symbol:'none',barWidth:30,itemStyle:{color:c.color,borderRadius:[3,3,0,0]},lineStyle:{width:1.8,color:c.color},areaStyle:{color:c.color,opacity:.09}}]});
 chart(`miniRing${i}`,{animation:false,color:[c.color,'#9fdcd5','#d9e6f0'],series:[{type:'pie',radius:['45%','69%'],center:['50%','51%'],label:{show:false},data:PROTOTYPE_DATA.channels.map(r=>({name:r.name,value:r.value}))}]});
 chart(`miniBar${i}`,{animation:false,grid:{left:6,right:7,top:10,bottom:8},xAxis:{type:'value',show:false},yAxis:{type:'category',show:false,data:PROTOTYPE_DATA.regions.map(r=>r.name)},series:[{type:'bar',barWidth:5,itemStyle:{color:c.color,opacity:.65,borderRadius:[0,2,2,0]},data:PROTOTYPE_DATA.regions.map(r=>r.value)}]});
 });}
function disposeMiniatures(){for(const[id,c]of charts){if(id.startsWith('mini')){c.dispose();charts.delete(id);}}}
$('search').addEventListener('input',e=>{currentSearch=e.target.value.trim().toLowerCase();cards.forEach((c,i)=>document.querySelector(`[data-card="${i}"]`).hidden=!matches(c));$('empty').hidden=cards.some(matches);requestAnimationFrame(()=>{drawMiniatures();resizeCharts();});});
document.querySelectorAll('[data-filter]').forEach(button=>button.addEventListener('click',()=>{filter=button.dataset.filter;document.querySelectorAll('[data-filter]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));$('search').dispatchEvent(new Event('input'));}));
function resizeCharts(){for(const c of charts.values()){if(c.getDom().clientWidth)c.resize();}}
new ResizeObserver(()=>requestAnimationFrame(resizeCharts)).observe($('studio'));
window.addEventListener('resize',()=>requestAnimationFrame(resizeCharts));
function openBoard(index,view){
 selected=index;wasPublished=cards[index].state!=='draft';hasChanges=cards[index].state!=='published';isViewing=view==='view';
 $('boardTitle').textContent=cards[index].title;$('confirmName').textContent=cards[index].title;$('boundKnowledge').textContent='曜境零售-全渠道经营样板';
 $('library').hidden=true;$('studio').hidden=false;$('studio').classList.remove('mobile-chat','collapsed');
 $('toggleChat').setAttribute('aria-expanded','true');$('toggleChat').setAttribute('aria-label','收起对话');
 $('mobileChat').setAttribute('aria-pressed','false');$('mobilePreview').setAttribute('aria-pressed','true');
 updateStudio();requestAnimationFrame(()=>{drawDashboard();resizeCharts();});location.hash=isViewing?'published':'edit';
}
function updateStudio(){
 $('studio').classList.toggle('viewing',isViewing);$('boardState').className='status '+(isViewing?'':hasChanges?(wasPublished?'changed':'draft'):'');
 $('boardState').textContent=isViewing?'已发布':hasChanges?(wasPublished?'有未发布修改':'草稿'):'已发布';
 $('savehint').textContent=isViewing?'成员查看页面 · 沿用知识库访问权限':hasChanges?'草稿已保存 · 修改不会影响已发布版':'草稿与已发布版一致';
 $('viewPublished').hidden=!wasPublished;$('viewPublished').innerHTML=isViewing?`${icon('edit')}返回编辑`:`查看已发布版${icon('external')}`;
 $('publish').hidden=isViewing;$('publish').disabled=!hasChanges;$('publish').title=hasChanges?'发布当前草稿':'与已发布版本一致，没有需要发布的修改';
 $('publish').innerHTML=`${icon('upload')}${wasPublished?'发布更新':'发布看板'}`;
 $('expand').innerHTML=icon('expand');$('expand').title='展开看板预览';
}
document.addEventListener('click',e=>{
 const open=e.target.closest('[data-open]');if(open)openBoard(Number(open.dataset.open),open.dataset.view);
 const close=e.target.closest('[data-close]');if(close)$(close.dataset.close).close();
 if(e.target.closest('[data-action="new"]')){$('newForm').reset();$('newDialog').showModal();}
 const suggestion=e.target.closest('[data-prompt]');if(suggestion){$('prompt').value=suggestion.dataset.prompt;$('prompt').dispatchEvent(new Event('input'));$('prompt').focus();}
});
function backToLibrary(){if(!$('studio').hidden){$('studio').hidden=true;$('library').hidden=false;location.hash='library';requestAnimationFrame(()=>{drawMiniatures();resizeCharts();});}}
$('back').onclick=backToLibrary;$('railLibrary').onclick=e=>{e.preventDefault();backToLibrary();};
$('viewPublished').onclick=()=>{isViewing=!isViewing;updateStudio();requestAnimationFrame(resizeCharts);};
$('publish').onclick=()=>{$('confirmName').textContent=$('boardTitle').textContent;$('publishDialog').showModal();};
$('confirmPublish').onclick=()=>{$('publishDialog').close();hasChanges=false;wasPublished=true;cards[selected].state='published';cards[selected].status='已发布';updateStudio();disposeMiniatures();renderCards();toast('演示：发布完成。正式看板未发生更改。');};
$('toggleChat').onclick=()=>{const collapsed=$('studio').classList.toggle('collapsed');$('toggleChat').setAttribute('aria-expanded',String(!collapsed));$('toggleChat').setAttribute('aria-label',collapsed?'展开对话':'收起对话');requestAnimationFrame(resizeCharts);};
$('expand').onclick=()=>{isViewing=!isViewing;updateStudio();requestAnimationFrame(resizeCharts);};
$('refresh').onclick=()=>{drawDashboard();toast('示例图表已重新载入');};
$('granularity').onchange=()=>{drawDashboard();};
$('mobileChat').onclick=()=>{$('studio').classList.add('mobile-chat');$('mobileChat').setAttribute('aria-pressed','true');$('mobilePreview').setAttribute('aria-pressed','false');};
$('mobilePreview').onclick=()=>{$('studio').classList.remove('mobile-chat');$('mobileChat').setAttribute('aria-pressed','false');$('mobilePreview').setAttribute('aria-pressed','true');requestAnimationFrame(resizeCharts);};
function drawDashboard(){
 const data=PROTOTYPE_DATA[$('granularity').value];
 chart('trend',{animation:false,tooltip:{trigger:'axis',valueFormatter:v=>`${Number(v).toFixed(2)} 万元`},grid:{left:43,right:12,top:24,bottom:29},xAxis:{...compactAxis,data:data.labels,axisLabel:{fontSize:9,color:'#8c99aa',interval:$('granularity').value==='week'?3:0}},yAxis:{...compactY,axisLabel:{fontSize:9,color:'#8c99aa'},splitNumber:3},series:[{name:'本期净销售',type:'line',data:data.values,symbol:'none',smooth:.25,lineStyle:{color:'#0b9488',width:2.6},areaStyle:{color:new echarts.graphic.LinearGradient(0,0,0,1,[{offset:0,color:'#0b948820'},{offset:1,color:'#0b948800'}])}},{name:'去年同期',type:'line',data:data.previous,symbol:'none',smooth:.25,lineStyle:{color:'#b6c3de',width:1.5,type:'dashed'}}]});
 chart('channels',{animation:false,color:['#0b9488','#4c72df','#b0bdd3'],tooltip:{trigger:'item',valueFormatter:v=>`${Number(v).toFixed(2)} 万元`},legend:{bottom:0,textStyle:{color:'#8390a2',fontSize:9},itemWidth:7,itemHeight:7,icon:'circle'},graphic:[{type:'text',left:'center',top:'35%',style:{text:'474.71',fontSize:22,fontWeight:650,fill:'#243b54'}},{type:'text',left:'center',top:'49%',style:{text:'净销售 / 万元',fontSize:9,fill:'#8a96a8'}}],series:[{type:'pie',radius:['53%','70%'],center:['50%','43%'],label:{show:false},itemStyle:{borderColor:'#fff',borderWidth:3},data:PROTOTYPE_DATA.channels.map(r=>({name:r.name,value:r.value}))}]});
 chart('regions',{animation:false,tooltip:{trigger:'axis',valueFormatter:v=>`${Number(v).toFixed(2)} 万元`},grid:{left:36,right:18,top:22,bottom:26},xAxis:{type:'value',axisLabel:{fontSize:9,color:'#8c99aa'},splitNumber:3,splitLine:{lineStyle:{color:'#f0f3f7'}}},yAxis:{type:'category',inverse:true,data:PROTOTYPE_DATA.regions.map(r=>r.name),axisLine:{show:false},axisTick:{show:false},axisLabel:{fontSize:10,color:'#7f8b9e'}},series:[{type:'bar',barWidth:11,itemStyle:{color:'#0b9488',borderRadius:[0,4,4,0]},data:PROTOTYPE_DATA.regions.map((r,i)=>({value:r.value,itemStyle:{color:i===0?'#0b9488':'#a5d4ce'}}))}]});
 $('storeRows').innerHTML=PROTOTYPE_DATA.stores.slice(0,4).map(r=>`<tr><td><b>${r.name}</b></td><td>${r.region}</td><td><em>${r.value.toFixed(2)}</em></td></tr>`).join('');
}
$('prompt').oninput=()=>{$('send').disabled=!$('prompt').value.trim();};
let busy=false;
function addMessage(kind,text){const el=document.createElement('div');el.className=kind==='user'?'user-message':'assistant-message';el.textContent=text;$('messages').append(el);$('messages').scrollTop=$('messages').scrollHeight;}
$('composer').onsubmit=e=>{
 e.preventDefault();const prompt=$('prompt').value.trim();if(!prompt||busy)return;busy=true;addMessage('user',prompt);$('prompt').value='';$('send').disabled=true;
 const progress=document.createElement('p');progress.className='busy';progress.textContent='演示：正在调整看板…';$('messages').append(progress);$('messages').scrollTop=$('messages').scrollHeight;
 setTimeout(()=>{progress.remove();busy=false;if(/月/.test(prompt)){$('granularity').value='month';drawDashboard();addMessage('assistant','已切换成按月趋势，便于比较季度内三个月的变化。这是本地交互演示，修改尚未发布。');}else if(/周/.test(prompt)){$('granularity').value='week';drawDashboard();addMessage('assistant','已切换成按周趋势。可以在右侧检查展示效果。');}else if(/华东/.test(prompt)){$('boardscroll').scrollTo({top:440,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});addMessage('assistant','华东本期净销售 126.20 万元，为区域第一。下方区域条形图已突出华东。这是静态样例的交互演示。');}else{addMessage('assistant','这条需求已记录在设计稿对话中。本页只演示操作，未连接 Agent；正式产品中会继续生成修改。');toast('设计预览仅支持“按月 / 按周 / 华东”三种演示');$('send').disabled=!$('prompt').value.trim();return;}hasChanges=true;updateStudio();$('send').disabled=!$('prompt').value.trim();},700);
};
$('prompt').addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey))$('composer').requestSubmit();});
$('fillExample').onclick=()=>{$('newPrompt').value='做一张全渠道经营看板，重点展示净销售、毛利、目标达成，以及趋势、渠道和区域差异。';$('newPrompt').focus();};
$('newForm').onsubmit=e=>{
 e.preventDefault();const name=$('newName').value.trim();$('newDialog').close();openBoard(0,'edit');wasPublished=false;hasChanges=true;if(name)$('boardTitle').textContent=name;
 $('boundKnowledge').textContent=$('newKnowledge').value;updateStudio();toast('已打开示例草稿；没有创建真实看板');
};
document.querySelectorAll('dialog').forEach(d=>d.addEventListener('click',e=>{if(e.target===d){const r=d.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)d.close();}}));
renderCards();if(location.hash==='#edit')openBoard(0,'edit');else if(location.hash==='#published')openBoard(0,'view');
})();
