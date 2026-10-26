'use strict';
// Synthetic, in-memory extension of the existing UI prototype.
const modelPreview = {
  active: 'company', activeRevision: 1, counter: 0,
  services: [
    {id:'company',revision:1,name:'公司大模型服务',provider:'OPENAI',baseUrl:'https://models.example.invalid/v1',modelName:'公司分析模型',stream:true,timeoutSeconds:60,temperature:0,disableThinking:false,external:false,tested:true},
    {id:'deepseek',revision:1,name:'DeepSeek 备用',provider:'DEEPSEEK',baseUrl:'https://api.deepseek.com',modelName:'deepseek-v4-flash',stream:true,timeoutSeconds:60,temperature:0,disableThinking:true,external:true,tested:true},
    {id:'qwen',revision:1,name:'千问备用',provider:'QWEN',baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1',modelName:'qwen-plus',stream:true,timeoutSeconds:60,temperature:0,disableThinking:true,external:true,tested:false}
  ], activeName:'公司大模型服务', activeModel:'公司分析模型'
};
const modelProviderNames = {OPENAI:'OpenAI 兼容',DEEPSEEK:'DeepSeek',QWEN:'千问（兼容接口）'};
function modelPreviewButton(label, callback, kind='', icon='') {
  const id='model-preview-'+(++modelPreview.counter); actions[id]=()=>{if(state.role==='admin')callback();};
  return B(label,id,kind,icon);
}
function modelServicesMoreEntry() {
  return state.role==='admin'?`<div class="docrow model-entry">${I('settings')}<div><strong>大模型服务</strong><p>管理连接并选择平台当前使用的服务</p></div><span class="spacer"></span><a class="btn quiet sm" href="#models">${I('chevron')}打开</a></div>`:'';
}
function modelServicesPage() {
  if(state.role!=='admin')return crumb([['对话','chat'],['更多','more'],'大模型服务'])+`<div class="content"><div class="blankstate">仅全局管理员可管理大模型服务。</div></div>`;
  return crumb([['对话','chat'],['更多','more'],'大模型服务'])+`<div class="content model-services-content">${head('大模型服务','管理平台使用的大模型连接，按需切换当前服务。',modelPreviewButton('添加模型服务',()=>modelServiceForm(),'primary','plus'))}
    <section class="ms-current"><div><span class="small muted">当前使用</span><h2>${E(modelPreview.activeName)}</h2><p>${E(modelPreview.activeModel)} · 版本 ${modelPreview.activeRevision}</p></div><span class="status green">使用中</span></section>
    ${N('切换对新一轮问数和建模生效，进行中的轮次保持原服务。')}
    <div class="panel model-services-table"><table><thead><tr><th>连接名称</th><th>服务类型</th><th>模型名称</th><th>最近测试</th><th>操作</th></tr></thead><tbody>${modelPreview.services.map(p=>`<tr><td><strong>${E(p.name)}</strong><div class="small muted">${p.external?'公网服务':'内部服务'}${p.id===modelPreview.active?' · 当前使用':''}${p.id===modelPreview.active&&p.revision!==modelPreview.activeRevision?' · 有待应用修改':''}</div></td><td>${modelProviderNames[p.provider]}</td><td>${E(p.modelName)}</td><td>${p.tested?S('检查通过','green'):S('未测试')}</td><td><div class="model-row-actions">${modelPreviewButton('测试',()=>{p.tested=true;render();toast('演示：连接与工具调用检查通过');},'quiet sm','refresh')}${modelPreviewButton('编辑',()=>modelServiceForm(p),'quiet sm','edit')}${p.id===modelPreview.active&&p.revision===modelPreview.activeRevision?'<span class="small muted">当前使用</span>':modelPreviewButton('设为当前',()=>modelServiceApply(p),'sm')}${p.id!==modelPreview.active?modelPreviewButton('删除',()=>{modal('删除模型服务',p.name,'<p>确认删除此连接？</p>',B('取消','close')+modelPreviewButton('确认删除',()=>{modelPreview.services=modelPreview.services.filter(x=>x.id!==p.id);close();render();toast('演示连接已删除');},'danger'));},'quiet sm'):''}</div></td></tr>`).join('')}</tbody></table></div>
    <p class="small muted mt16">共 ${modelPreview.services.length} 个服务 · API Key 加密保存，列表不展示明文</p></div>`;
}
function modelServiceApply(p) {
  if(!p.tested){toast('请先测试当前保存版本');return;}
  modal('切换大模型服务',p.name,`<p>下一轮问数和建模将使用此服务，正在执行的轮次保持原服务。</p>${p.external?'<p class="mt16">这是公网服务，后续请求将发送到该服务。</p>':''}`,B('取消','close')+modelPreviewButton('确认切换',()=>{modelPreview.active=p.id;modelPreview.activeRevision=p.revision;modelPreview.activeName=p.name;modelPreview.activeModel=p.modelName;close();render();toast('演示：当前服务已切换');},'primary'));
}
function modelServiceForm(profile) {
  let p=profile||{name:'',provider:'OPENAI',baseUrl:'',modelName:'',stream:true,timeoutSeconds:60,temperature:0,disableThinking:false,external:false};
  modal(profile?'编辑模型服务':'添加模型服务','保存连接后测试，再设为当前。',`<div class="field"><label for="mp-name">连接名称</label><input id="mp-name" maxlength="64" value="${E(p.name)}" placeholder="例如：公司大模型服务"></div>
    <div class="split2"><div class="field"><label for="mp-provider">服务类型</label><select id="mp-provider">${Object.entries(modelProviderNames).map(([v,n])=>`<option value="${v}" ${v===p.provider?'selected':''}>${n}</option>`).join('')}</select></div><div class="field"><label for="mp-model">模型名称</label><input id="mp-model" value="${E(p.modelName)}" placeholder="填写已开通的模型名称"></div></div>
    <div class="field"><label for="mp-url">Base URL</label><input id="mp-url" value="${E(p.baseUrl)}" placeholder="https://模型服务地址"></div>
    <div class="field"><label for="mp-key">API Key</label><input id="mp-key" type="password" autocomplete="new-password" placeholder="${profile?'已配置，留空保留原Key':'请输入API Key'}"></div>
    <details class="model-advanced"><summary>高级设置</summary><div class="split2 mt16"><div class="field"><label for="mp-timeout">超时（秒）</label><input id="mp-timeout" type="number" min="5" max="300" value="${p.timeoutSeconds}"></div><div class="field"><label for="mp-temperature">Temperature</label><input id="mp-temperature" type="number" min="0" max="2" step="0.1" value="${p.temperature}"></div></div><div class="field"><label for="mp-path">Chat Completions 路径</label><input id="mp-path" value="/v1/chat/completions"></div><div class="model-checks"><label><input id="mp-stream" type="checkbox" ${p.stream?'checked':''}> 流式输出</label><label><input id="mp-thinking" type="checkbox" ${p.disableThinking?'checked':''}> 关闭思考（支持的服务）</label><label><input id="mp-external" type="checkbox" ${p.external?'checked':''}> 公网服务</label></div></details><p id="mp-error" class="small" role="alert"></p>`,B('取消','close')+modelPreviewButton('保存连接',()=>{
      const get=id=>document.getElementById(id);let name=get('mp-name').value.trim(),url=get('mp-url').value.trim(),model=get('mp-model').value.trim();
      if(!name||!url||!model||(!profile&&!get('mp-key').value)){get('mp-error').textContent='请填写连接名称、地址、模型名称和API Key。';return;}
      if(modelPreview.services.some(x=>x.id!==p.id&&x.name===name)){get('mp-error').textContent='连接名称已存在。';return;}
      const saved={...p,id:p.id||'demo-'+Date.now(),revision:(p.revision||0)+1,name,baseUrl:url,modelName:model,provider:get('mp-provider').value,stream:get('mp-stream').checked,timeoutSeconds:Number(get('mp-timeout').value),temperature:Number(get('mp-temperature').value),disableThinking:get('mp-thinking').checked,external:get('mp-external').checked,tested:false};
      modelPreview.services=modelPreview.services.filter(x=>x.id!==saved.id);modelPreview.services.push(saved);close();render();toast('演示：连接已保存，请测试后应用');
    },'primary'));
  document.getElementById('mp-provider').addEventListener('change',event=>{
    const preset={OPENAI:['',false,false],DEEPSEEK:['https://api.deepseek.com',true,true],QWEN:['https://dashscope.aliyuncs.com/compatible-mode/v1',true,true]}[event.target.value];
    document.getElementById('mp-url').value=preset[0];document.getElementById('mp-thinking').checked=preset[1];document.getElementById('mp-external').checked=preset[2];
  });
}
const requestedModelPreviewRole=new URLSearchParams(location.search).get('role');
if(['admin','manage','query'].includes(requestedModelPreviewRole))state.role=requestedModelPreviewRole;
render();
