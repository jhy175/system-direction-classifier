(() => {
'use strict';
const app=document.getElementById('app'),dialog=document.getElementById('editor');
const state={me:null,csrf:null,tab:'profile',page:1,q:'',status:'',pageSize:20,teams:[],selectedTeam:null};
const e=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const titles={profile:'个人资料',users:'用户管理',roles:'角色管理',permissions:'功能权限',menus:'导航菜单',teams:'团队管理'};
const hints={profile:'维护个人资料与使用偏好。',users:'管理账户状态、资料和系统角色。',roles:'通过系统角色分配功能权限和可见菜单。',permissions:'维护后端操作使用的权限编码。',menus:'管理页面导航及其层级关系。',teams:'组织成员，为方法、知识库与模型连接共享提供团队身份。'};
const capabilities={users:'user:manage',roles:'role:manage',permissions:'permission:manage',menus:'menu:manage'};
const has=code=>state.me?.permissions?.some(p=>p.code===code);
let toastTimer;
function notify(message){const n=document.getElementById('notice');n.textContent=message;n.style.display='block';clearTimeout(toastTimer);toastTimer=setTimeout(()=>n.style.display='none',4500);}
async function api(path,method='GET',body){
 const headers={'Accept':'application/json'};
 if(body!==undefined)headers['Content-Type']='application/json';
 if(!['GET','HEAD'].includes(method))headers['X-CSRF-Token']=state.csrf||'';
 const response=await fetch('/api'+path,{method,headers,credentials:'same-origin',body:body===undefined?undefined:JSON.stringify(body)});
 let data;try{data=await response.json();}catch(_){throw new Error('服务响应无法解析');}
 if(!response.ok||!data.success){if(response.status===401&&!path.includes('/auth/login')){state.me=null;await showLogin();}throw new Error(data.message||'操作未完成');}
 return data.data;
}
async function csrf(){state.csrf=(await api('/auth/csrf')).csrfToken;}
async function showLogin(){
 await csrf();
 app.innerHTML='<main class="login"><a href="/">← 返回识别工作台</a><h1>用户与团队</h1><p class="subtle">学科交叉识别平台 · 账户服务</p><form id="login-form"><label>登录账号<input name="login" autocomplete="username" required maxlength="64"></label><label>密码<input name="password" type="password" autocomplete="current-password" required maxlength="128"></label><div class="error" id="login-error" role="alert"></div><button class="primary" type="submit">登录</button></form><p class="subtle">首次管理员凭据由本地初始化脚本生成；普通账户由管理员创建。</p></main>';
 document.getElementById('login-form').onsubmit=async event=>{
  event.preventDefault();const form=event.currentTarget,button=form.querySelector('button');button.disabled=true;
  try{const result=await api('/auth/login','POST',Object.fromEntries(new FormData(form)));state.csrf=result.csrfToken;state.me=await api('/auth/me');state.tab='profile';await shell();}
  catch(error){document.getElementById('login-error').textContent=error.message;button.disabled=false;}
 };
}
async function shell(){
 const tabs=['profile',...Object.keys(capabilities).filter(t=>has(capabilities[t])),'teams'];
 app.innerHTML='<div class="shell"><aside class="sidebar"><div class="brand">学科交叉识别平台</div><div class="subtle">账户与协作</div>'+tabs.map(t=>'<button class="nav '+(state.tab===t?'selected':'')+'" data-tab="'+t+'">'+titles[t]+'</button>').join('')+'<footer><a href="/">← 返回识别工作台</a><p class="subtle">用户数据存储于 MySQL</p></footer></aside><main class="content"><div class="topline"><div><h1>'+titles[state.tab]+'</h1><div class="subtle">'+hints[state.tab]+'</div></div><div class="identity"><span>'+e(state.me.displayName||state.me.login)+'</span><button id="logout">退出登录</button></div></div><section id="body"></section></main></div>';
 app.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>{state.tab=button.dataset.tab;state.page=1;state.q='';state.status='';state.selectedTeam=null;shell().catch(err=>notify(err.message));});
 document.getElementById('logout').onclick=async()=>{try{await api('/auth/logout','POST');state.me=null;await showLogin();}catch(err){notify(err.message);}};
 if(state.tab==='profile')profile();else await listing();
}
function input(key,label,value,type='text',required=false){
 return '<label>'+e(label)+(required?' *':'')+'<input name="'+key+'" type="'+type+'" value="'+e(value)+'"'+(required?' required':'')+(type==='password'?' autocomplete="new-password"':'')+'></label>';
}
function profile(){
 const u=state.me;
 document.getElementById('body').innerHTML='<div class="card"><div class="summary"><span>用户 ID：'+u.id+'</span><span>账号：'+e(u.login)+'</span><span>角色：'+e((u.roles||[]).map(r=>r.name).join('、')||'未分配')+'</span></div><form id="profile-form"><div class="form-grid">'+input('displayName','显示名称',u.displayName)+input('email','邮箱',u.email,'email')+input('phone','联系电话',u.phone)+input('organization','所属单位',u.organization)+input('department','所属部门',u.department)+input('langKey','语言偏好',u.langKey)+input('avatarUrl','头像地址',u.avatarUrl)+'<label>备注<textarea name="remark">'+e(u.remark)+'</textarea></label><label class="checkbox wide"><input type="checkbox" name="advanced" '+(u.preferences?.advancedConfig?'checked':'')+'>默认展开高级配置</label></div><div class="actions"><button class="primary">保存资料</button><button type="button" id="password">修改密码</button></div></form></div><div class="note">资料中的单位与部门用于展示。团队成员身份在团队管理中维护。</div>';
 document.getElementById('profile-form').onsubmit=async event=>{
  event.preventDefault();const f=new FormData(event.currentTarget),data={};for(const[k,v]of f)if(k!=='advanced')data[k]=v.trim()||null;
  data.preferences={...(u.preferences||{}),advancedConfig:f.has('advanced')};
  try{state.me=await api('/auth/me','PATCH',data);notify('个人资料已保存');await shell();}catch(err){notify(err.message);}
 };
 document.getElementById('password').onclick=()=>openForm('修改密码',[input('oldPassword','当前密码','','password',true),input('newPassword','新密码（10至128个字符）','','password',true)].join(''),async data=>{await api('/auth/password','PUT',data);dialog.close();notify('密码已修改，请重新登录');state.me=null;await showLogin();});
}
function badge(status){return '<span class="badge '+(status===1?'':status===2?'lock':'off')+'">'+(status===1?'启用':status===2?'锁定':status===0?'停用':'未设置')+'</span>';}
async function listing(){
 const resource=state.tab,params=new URLSearchParams({pageIndex:state.page,pageSize:state.pageSize,q:state.q});if(state.status!=='')params.set('status',state.status);
 const page=await api('/'+resource+'?'+params),rows=page.items;
 const canCreate=resource!=='teams'||has('team:create');
 const writable=r=>resource!=='teams'||r.ownerUserId===state.me.id||has('team:manage');
 document.getElementById('body').innerHTML='<div class="card"><form class="toolbar" id="search"><input name="q" placeholder="搜索'+(resource==='users'?'登录账号':'名称')+'" value="'+e(state.q)+'"><select name="status" style="width:140px"><option value="">全部状态</option><option value="1">启用</option><option value="0">停用</option>'+(resource==='users'?'<option value="2">锁定</option>':'')+'</select><button>搜索</button>'+(canCreate?'<button type="button" class="primary push" id="create">新建'+(resource==='users'?'用户':resource==='roles'?'角色':resource==='teams'?'团队':resource==='menus'?'菜单':'权限')+'</button>':'')+'</form><div class="table-wrap"><table><thead><tr><th>ID</th><th>'+ (resource==='users'?'账号 / 显示名称':'名称 / 编码')+'</th><th>状态</th><th>'+(resource==='users'?'单位':resource==='teams'?'负责人 ID':'说明 / 路径')+'</th><th>操作</th></tr></thead><tbody>'+rows.map(r=>'<tr><td>'+r.id+'</td><td><strong>'+e(resource==='users'?r.login:r.name)+'</strong><div class="subtle">'+e(resource==='users'?r.displayName:r.code)+'</div></td><td>'+badge(r.status)+'</td><td>'+e(resource==='users'?r.organization:resource==='teams'?r.ownerUserId:r.description||r.path)+'</td><td>'+(writable(r)?'<button data-edit="'+r.id+'">编辑</button>':'')+(resource==='users'?'<button data-link="'+r.id+'" data-relation="roles">分配角色</button>':resource==='roles'?'<button data-link="'+r.id+'" data-relation="permissions">分配权限</button><button data-link="'+r.id+'" data-relation="menus">分配菜单</button>':resource==='teams'?'<button data-members="'+r.id+'">成员</button>':'')+(writable(r)?'<button class="danger" data-remove="'+r.id+'">'+(resource==='users'||resource==='teams'?'停用':'删除')+'</button>':'')+'</td></tr>').join('')+(rows.length?'':'<tr><td colspan="5" class="empty">暂无记录</td></tr>')+'</tbody></table></div><div class="pager"><span>共 '+page.total+' 条 · 第 '+state.page+' 页</span><div><button id="previous" '+(state.page===1?'disabled':'')+'>上一页</button> <button id="next" '+(state.page*state.pageSize>=page.total?'disabled':'')+'>下一页</button></div></div></div><div id="members"></div>';
 const search=document.getElementById('search');search.elements.status.value=state.status;
 search.onsubmit=event=>{event.preventDefault();state.q=search.elements.q.value;state.status=search.elements.status.value;state.page=1;listing().catch(err=>notify(err.message));};
 if(canCreate)document.getElementById('create').onclick=()=>edit(resource);
 document.getElementById('previous').onclick=()=>{state.page--;listing().catch(err=>notify(err.message));};
 document.getElementById('next').onclick=()=>{state.page++;listing().catch(err=>notify(err.message));};
 document.querySelectorAll('[data-edit]').forEach(button=>button.onclick=()=>edit(resource,rows.find(r=>r.id===Number(button.dataset.edit))));
 document.querySelectorAll('[data-remove]').forEach(button=>button.onclick=()=>confirmAction(resource==='users'||resource==='teams'?'停用后将保留账户或团队及其历史引用。':'确认删除此记录？',async()=>{await api('/'+resource+'/'+button.dataset.remove,'DELETE');notify('操作已完成');await refresh();}));
 document.querySelectorAll('[data-link]').forEach(button=>button.onclick=()=>assign(resource,Number(button.dataset.link),button.dataset.relation).catch(err=>notify(err.message)));
 document.querySelectorAll('[data-members]').forEach(button=>button.onclick=()=>{state.selectedTeam=rows.find(r=>r.id===Number(button.dataset.members));members().catch(err=>notify(err.message));});
 if(resource==='teams'&&state.selectedTeam)await members();
}
async function refresh(){state.me=await api('/auth/me');await shell();}
function openForm(title,fields,save){
 dialog.innerHTML='<h2>'+e(title)+'</h2><form><div class="form-grid">'+fields+'</div><div class="error" role="alert"></div><div class="actions"><button type="button" data-close>取消</button><button type="submit" class="primary">保存</button></div></form>';dialog.showModal();
 dialog.querySelector('[data-close]').onclick=()=>dialog.close();
 dialog.querySelector('form').onsubmit=async event=>{event.preventDefault();const button=event.currentTarget.querySelector('[type=submit]');button.disabled=true;try{await save(Object.fromEntries(new FormData(event.currentTarget)));}catch(err){dialog.querySelector('.error').textContent=err.message;button.disabled=false;}};
}
function stateField(value,users=false){return '<label>状态<select name="status"><option value="__NULL__" '+(value===null?'selected':'')+'>未设置</option><option value="1" '+(value===1?'selected':'')+'>启用</option><option value="0" '+(value===0?'selected':'')+'>停用</option>'+(users?'<option value="2" '+(value===2?'selected':'')+'>锁定</option>':'')+'</select></label>';}
function edit(resource,row){
 const r=row||{},creating=!row;let html='';
 if(resource==='users')html=input('login','登录账号',r.login,'text',true)+input('displayName','显示名称',r.displayName)+input('email','邮箱',r.email,'email')+input('phone','联系电话',r.phone)+input('organization','所属单位',r.organization)+input('department','所属部门',r.department)+input('password',creating?'登录密码（可稍后设置）':'重设密码（留空保留原密码）','','password')+stateField(creating?0:r.status,true)+input('remark','备注',r.remark);
 else {
  html=input('name','名称',r.name,'text',true);
  if(['roles','permissions'].includes(resource))html+=input('code','稳定编码',r.code,'text',true);
  if(['roles','permissions','teams'].includes(resource))html+='<label>说明<textarea name="description">'+e(r.description)+'</textarea></label>';
  if(resource==='menus')html+=input('path','路由路径（目录可空）',r.path)+input('icon','图标',r.icon)+input('component','组件标识',r.component);
  if(['menus','permissions'].includes(resource))html+=input('parentId','父节点 ID（根节点留空）',r.parentId,'number');
  if(resource==='permissions')html+='<label>分类<select name="type"><option value="__NULL__">未设置</option><option value="1" '+(r.type===1?'selected':'')+'>页面</option><option value="2" '+(r.type===2?'selected':'')+'>操作</option></select></label>';
  if(resource!=='teams')html+=input('sort','排序',r.sort,'number');
  html+=stateField(creating?1:r.status);
 }
 openForm((creating?'新建':'编辑')+titles[resource].replace('管理',''),html,async values=>{
  const data={};for(const[k,v]of Object.entries(values)){
   if(k==='password'&&!v)continue;
   data[k]=v==='__NULL__'||v===''?null:['status','sort','parentId','type'].includes(k)?Number(v):v;
  }
  await api('/'+resource+(row?'/'+row.id:''),row?'PATCH':'POST',data);dialog.close();notify('已保存');await refresh();
 });
}
function flatten(rows){return rows.flatMap(r=>[r,...flatten(r.children||[])]);}
async function assign(resource,id,relation){
 const existing=flatten(await api('/'+resource+'/'+id+'/'+relation));
 let choices=[];let page=1;
 while(true){const result=await api('/'+relation+'?pageSize=100&pageIndex='+page);choices.push(...result.items);if(choices.length>=result.total)break;page++;}
 const selected=new Set(existing.map(r=>r.id));
 dialog.innerHTML='<h2>分配'+({roles:'角色',permissions:'权限',menus:'菜单'}[relation])+'</h2><form><div class="check-list">'+choices.map(r=>'<label><input type="checkbox" name="ids" value="'+r.id+'" '+(selected.has(r.id)?'checked':'')+'>'+e(r.name)+'<small>'+e(r.code||r.path)+' · '+(r.status===1?'启用':'未启用')+'</small></label>').join('')+'</div><div class="error" role="alert"></div><div class="actions"><button type="button" data-close>取消</button><button class="primary">保存授权</button></div></form>';dialog.showModal();
 dialog.querySelector('[data-close]').onclick=()=>dialog.close();
 dialog.querySelector('form').onsubmit=async event=>{event.preventDefault();try{await api('/'+resource+'/'+id+'/'+relation,'PUT',new FormData(event.currentTarget).getAll('ids').map(Number));dialog.close();notify('授权已更新');await refresh();}catch(err){dialog.querySelector('.error').textContent=err.message;}};
}
async function members(){
 const team=state.selectedTeam;if(!team)return;const rows=await api('/teams/'+team.id+'/members');
 const owner=team.ownerUserId===state.me.id||has('team:manage'),membership=rows.find(r=>r.userId===state.me.id&&r.status===1),manage=owner||membership?.memberRole==='admin';
 document.getElementById('members').innerHTML='<div class="card"><div class="section-title"><h2>'+e(team.name)+' · 成员</h2><div>'+(manage?'<button id="add-member">添加成员</button> ':'')+(owner?'<button id="transfer">转交负责人</button>':'')+'</div></div><p class="subtle">负责人用户 ID：'+team.ownerUserId+'。成员身份与系统角色分别管理。</p><div class="table-wrap"><table><thead><tr><th>用户 ID</th><th>账号 / 名称</th><th>团队身份</th><th>状态</th><th>操作</th></tr></thead><tbody>'+rows.map(r=>'<tr><td>'+r.userId+'</td><td>'+e(r.login)+'<div class="subtle">'+e(r.displayName)+'</div></td><td>'+(r.userId===team.ownerUserId?'负责人':r.memberRole==='admin'?'管理员':r.memberRole==='member'?'成员':'未设置')+'</td><td>'+badge(r.status)+'</td><td>'+(manage?'<button data-member-edit="'+r.userId+'">编辑</button>':'')+((manage||r.userId===state.me.id)&&r.userId!==team.ownerUserId?'<button class="danger" data-member-remove="'+r.userId+'">'+(r.userId===state.me.id?'退出':'移除')+'</button>':'')+'</td></tr>').join('')+'</tbody></table></div></div>';
 const editMember=row=>openForm(row?'编辑成员':'添加成员',(row?'<p>用户 ID：'+row.userId+'</p>':input('userId','用户 ID（可由对方在个人资料中查看）','','number',true))+'<label>团队角色<select name="memberRole"><option value="member">普通成员</option>'+(owner?'<option value="admin" '+(row?.memberRole==='admin'?'selected':'')+'>团队管理员</option>':'')+'<option value="__NULL__" '+(row&&row.memberRole===null?'selected':'')+'>未设置</option></select></label>'+stateField(row?row.status:1),async data=>{
  const id=row?row.userId:Number(data.userId);delete data.userId;
  data.memberRole=data.memberRole==='__NULL__'?null:data.memberRole;data.status=data.status==='__NULL__'?null:Number(data.status);
  await api('/teams/'+team.id+'/members/'+id,'PUT',data);dialog.close();notify('成员已保存');await members();
 });
 if(manage)document.getElementById('add-member').onclick=()=>editMember();
 document.querySelectorAll('[data-member-edit]').forEach(button=>button.onclick=()=>editMember(rows.find(r=>r.userId===Number(button.dataset.memberEdit))));
 document.querySelectorAll('[data-member-remove]').forEach(button=>button.onclick=()=>confirmAction('确认退出或移除此团队成员？',async()=>{await api('/teams/'+team.id+'/members/'+button.dataset.memberRemove,'DELETE');notify('成员关系已更新');state.selectedTeam=null;await listing();}));
 if(owner)document.getElementById('transfer').onclick=()=>openForm('转交团队负责人',input('userId','新负责人用户 ID（必须为有效成员）','','number',true),async data=>{await api('/teams/'+team.id+'/owner','PUT',{userId:Number(data.userId)});dialog.close();notify('负责人已转交');state.selectedTeam=null;await listing();});
}
function confirmAction(message,action){
 openForm('确认操作','<p class="wide">'+e(message)+'</p>',async()=>{await action();dialog.close();});
}
(async()=>{try{await csrf();const response=await fetch('/api/auth/me',{credentials:'same-origin'});if(response.ok){const result=await response.json();state.me=result.data;await shell();}else await showLogin();}catch(err){app.innerHTML='<main class="login"><h1>暂时无法连接账户服务</h1><p>'+e(err.message)+'</p><button onclick="location.reload()">重新连接</button></main>';}})();
})();
