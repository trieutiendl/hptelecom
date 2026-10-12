(function (root) {
  'use strict';
  const scalar = value => typeof value === 'string' || typeof value === 'number' ? String(value).trim() : '';
  const normalize = value => scalar(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[đĐ]/g, 'd').toLowerCase().replace(/[_-]+/g, ' ').replace(/\s+/g, ' ').trim();
  const validMonth = month => /^\d{4}-(0[1-9]|1[0-2])$/.test(month);
  const statusOf = record => scalar(record.data?.status) || scalar(record.data?.trangThai);
  const completed = record => ['completed', 'complete', 'done', 'resolved', 'closed', 'hoan tat', 'da hoan tat', 'hoan thanh', 'da hoan thanh', 'da xu ly', 'da khac phuc'].includes(normalize(statusOf(record)));
  function groupFor(record, teams) {
    const id = scalar(record.data?.teamId);
    if (id) {
      const team = teams.find(team => scalar(team.id) === id);
      return {key:'id:' + id, name:team?.name || scalar(record.team) || 'Nhóm không còn trong danh sách', id, ambiguous:false};
    }
    const name = scalar(record.team);
    const matches = teams.filter(team => team.name === name);
    if (name && matches.length === 1) return {key:'id:' + scalar(matches[0].id), name, id:scalar(matches[0].id), ambiguous:false};
    return {key:'name:' + name, name:name || 'Chưa gán Team/Đội', id:'', ambiguous:matches.length > 1};
  }
  function summarize({incidents, relocations, teams, month, selectedId = ''}) {
    if (!validMonth(month)) throw new Error('Tháng báo cáo không hợp lệ.');
    if (selectedId && !teams.some(team => scalar(team.id) === selectedId)) throw new Error('Nhóm đã chọn không còn trong danh sách.');
    const source = [...incidents.map(record => ({...record, kind:'incident'})), ...relocations.map(record => ({...record, kind:'relocation'}))].filter(record => record.month === month);
    const scoped = source.map(record => ({...record, group:groupFor(record, teams)})).filter(record => !selectedId || record.group.id === selectedId);
    const groups = new Map();
    for (const record of scoped) {
      if (!groups.has(record.group.key)) groups.set(record.group.key, {...record.group, incidents:0, relocations:0, completed:0});
      const group = groups.get(record.group.key);
      group[record.kind === 'incident' ? 'incidents' : 'relocations']++;
      if (completed(record)) group.completed++;
    }
    const timestamp = record => Date.parse(record.updatedAt || record.createdAt) || 0;
    return {records:scoped.slice().sort((a,b) => timestamp(b)-timestamp(a) || scalar(a.id).localeCompare(scalar(b.id))),
      incidents:scoped.filter(record => record.kind === 'incident').length,
      relocations:scoped.filter(record => record.kind === 'relocation').length,
      completed:scoped.filter(completed).length,
      unknownStatus:scoped.filter(record => !statusOf(record)).length,
      teams:selectedId ? 1 : teams.length,
      ambiguous:source.filter(record => groupFor(record, teams).ambiguous).length,
      groups:[...groups.values()].sort((a,b) => a.name.localeCompare(b.name,'vi'))};
  }
  async function fetchJson(fetcher, url, signal) {
    const response = await fetcher(url, {credentials:'same-origin', cache:'no-store', signal});
    if (!response.ok) {
      const error = new Error(response.status === 401 ? 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.' : 'Không tải được dữ liệu staging (HTTP ' + response.status + ').');
      error.status = response.status; throw error;
    }
    const result = await response.json();
    if (!result || typeof result !== 'object') throw new Error('Dữ liệu máy chủ không đúng định dạng.');
    return result;
  }
  async function fetchRecords(fetcher, resource, month, signal) {
    if (!['incidents','relocations'].includes(resource) || !validMonth(month)) throw new Error('Yêu cầu báo cáo không hợp lệ.');
    const records = [], seen = new Set();
    for (let offset=0; ; offset+=500) {
      const result = await fetchJson(fetcher, '/staging/api/' + resource + '?month=' + encodeURIComponent(month) + '&limit=500&offset=' + offset, signal);
      if (!Array.isArray(result.records) || result.limit !== 500 || result.offset !== offset) throw new Error('Không đọc được đầy đủ danh sách báo cáo. Hãy tải lại.');
      for (const record of result.records) {
        if (!record || !scalar(record.id) || record.month !== month || !record.data || typeof record.data !== 'object' || Array.isArray(record.data)) throw new Error('Báo cáo trả về không đúng định dạng.');
        if (seen.has(record.id)) throw new Error('Danh sách báo cáo đã thay đổi khi đang tải. Hãy tải lại để tính đúng số liệu.');
        seen.add(record.id); records.push(record);
      }
      if (result.records.length < 500) return records;
      if (signal?.aborted) throw new Error('Đã hủy tải dữ liệu.');
    }
  }
  const model = {scalar, normalize, validMonth, statusOf, completed, groupFor, summarize, fetchJson, fetchRecords};
  if (typeof module === 'object' && module.exports) {module.exports = model; return;}
  root.HPOverviewData = model;
  const $ = selector => document.querySelector(selector);
  const period = $('#period'), teamFilter = $('#region-filter'), refreshButton = $('#overview-refresh');
  const badge = $('#overview-state'), note = $('#region-filter-note'), main = document.querySelector('main');
  const regions = ['#overview-teams-body','#overview-incidents-body','#overview-relocations','#overview-activity'];
  const parts = new Intl.DateTimeFormat('en',{timeZone:'Asia/Ho_Chi_Minh',year:'numeric',month:'2-digit'}).formatToParts(new Date());
  const currentMonth = parts.find(part=>part.type==='year').value + '-' + parts.find(part=>part.type==='month').value;
  period.value = currentMonth;
  let requestId = 0, controller;
  const element = (tag, text, className) => {const el=document.createElement(tag);if(text!==undefined)el.textContent=String(text);if(className)el.className=className;return el;};
  function empty(selector, text) {
    const target = $(selector); target.replaceChildren();
    if (target.tagName === 'TBODY') {const tr=element('tr');const td=element('td',text,'overview-empty');td.colSpan=target.id==='overview-teams-body'?4:5;tr.append(td);target.append(tr);}
    else target.append(element('p',text,'overview-empty'));
  }
  function clear(text) {document.querySelectorAll('[data-live-metric]').forEach(el=>el.textContent='—');regions.forEach(selector=>empty(selector,text));$('#overview-completed-note').textContent='Chỉ tính báo cáo có trạng thái hoàn tất.';}
  function formatTime(value) {const date=new Date(value);return Number.isFinite(date.getTime())?new Intl.DateTimeFormat('vi-VN',{timeZone:'Asia/Ho_Chi_Minh',dateStyle:'short',timeStyle:'short'}).format(date):'Chưa ghi thời gian';}
  function showUser(user) {
    root.HPOverviewUser = user;
    const name=scalar(user.fullName)||scalar(user.username)||'Tài khoản';
    $('.admin-name').textContent=name;
    $('.admin-role').textContent=({admin:'Quản trị viên',coordinator:'Điều phối viên',operator:'Kỹ thuật viên'})[user.role]||'Thành viên';
    $('.avatar').textContent=name.split(/\s+/).slice(-2).map(part=>part[0]).join('').toUpperCase();
    $('#account-trigger').setAttribute('aria-label','Tài khoản '+name);
    $('.breadcrumb').firstChild.textContent=$('.admin-role').textContent+' ';
  }
  function render(summary, teams, selectedId, month) {
    for (const key of ['teams','incidents','relocations','completed']) $('[data-live-metric="'+key+'"]').textContent=new Intl.NumberFormat('vi-VN').format(summary[key]);
    $('#overview-completed-note').textContent=summary.unknownStatus ? summary.unknownStatus+' báo cáo chưa có trạng thái.' : 'Theo trạng thái đã lưu trong báo cáo.';
    const tbody=$('#overview-teams-body');tbody.replaceChildren();
    for(const group of summary.groups){const row=element('tr');for(const value of [group.name+(group.ambiguous?' · Chưa có mã nhóm':''),group.incidents,group.relocations,group.completed])row.append(element('td',value));tbody.append(row);}
    if(!summary.groups.length)empty('#overview-teams-body','Chưa có báo cáo trong nhóm/tháng đã chọn.');
    const incidents=summary.records.filter(record=>record.kind==='incident').slice(0,8);
    const incidentBody=$('#overview-incidents-body');incidentBody.replaceChildren();
    for(const record of incidents){const row=element('tr');for(const value of [scalar(record.code)||'Chưa có mã',scalar(record.data.location)||scalar(record.data.area)||'Chưa ghi khu vực',scalar(record.data.priority)||'Chưa cập nhật',statusOf(record)||'Chưa cập nhật',record.group.name])row.append(element('td',value));incidentBody.append(row);}
    if(!incidents.length)empty('#overview-incidents-body','Chưa có báo cáo sự cố trong nhóm/tháng đã chọn.');
    const relocations=summary.records.filter(record=>record.kind==='relocation').slice(0,5);
    const dispatch=$('#overview-relocations');dispatch.replaceChildren();
    for(const record of relocations){const row=element('div',undefined,'dispatch-row');const center=element('span');center.append(element('span',scalar(record.code)||'Báo cáo di dời','team-title'),element('span',record.group.name+' · '+(statusOf(record)||'Chưa cập nhật trạng thái'),'team-sub'));const time=element('span',undefined,'dispatch-count');time.append(element('small',formatTime(record.updatedAt||record.createdAt)));row.append(element('span','DD','team-avatar'),center,time);dispatch.append(row);}
    if(!relocations.length)empty('#overview-relocations','Chưa có báo cáo di dời trong nhóm/tháng đã chọn.');
    const activities=$('#overview-activity');activities.replaceChildren();
    for(const record of summary.records.slice(0,5)){const row=element('div',undefined,'activity');const text=element('p',undefined,'activity-text');text.append(element('strong',(record.kind==='incident'?'Sự cố ':'Di dời ')+(scalar(record.code)||'chưa có mã')),document.createTextNode(' · '+record.group.name),element('span','Cập nhật: '+formatTime(record.updatedAt||record.createdAt),'activity-time'));row.append(element('span',undefined,'activity-dot'),text);activities.append(row);}
    if(!summary.records.length)empty('#overview-activity','Chưa có cập nhật báo cáo trong nhóm/tháng đã chọn.');
    const selected=teams.find(team=>scalar(team.id)===selectedId);
    const [year,number]=month.split('-');
    note.textContent=(selected?.name||'Tất cả Team/Đội')+' · Tháng '+number+'/'+year+'. Tổng hợp '+summary.records.length+' báo cáo đã lưu trong staging; không tính dữ liệu mẫu hoặc bản sửa chỉ lưu trên trình duyệt.'+(summary.ambiguous?' Có '+summary.ambiguous+' báo cáo dùng tên nhóm bị trùng, chưa đủ mã nhóm để lọc riêng.':'');
    badge.textContent='Dữ liệu staging · '+new Intl.DateTimeFormat('vi-VN',{timeZone:'Asia/Ho_Chi_Minh',hour:'2-digit',minute:'2-digit'}).format(new Date());
  }
  async function load() {
    const id=++requestId; controller?.abort();controller=new AbortController();const signal=controller.signal;
    const timeout=setTimeout(()=>controller?.signal===signal&&controller.abort(),45000);
    const month=validMonth(period.value)?period.value:currentMonth;period.value=month;
    const selectedId=teamFilter.value;
    clear('Đang tải dữ liệu staging…');badge.textContent='Đang tải dữ liệu…';note.textContent='Đang lấy dữ liệu đã lưu trong staging.';main.setAttribute('aria-busy','true');refreshButton.disabled=true;
    try {
      const [me,teamResult,incidents,relocations]=await Promise.all([
        fetchJson(root.fetch.bind(root),'/staging/api/auth/me',signal),fetchJson(root.fetch.bind(root),'/staging/api/teams',signal),
        fetchRecords(root.fetch.bind(root),'incidents',month,signal),fetchRecords(root.fetch.bind(root),'relocations',month,signal)]);
      if(id!==requestId)return;
      if(!me.user||!Array.isArray(teamResult.teams))throw new Error('Không tải được tài khoản và danh sách nhóm.');
      const teams=teamResult.teams.filter(team=>team&&scalar(team.id)&&typeof team.name==='string').sort((a,b)=>a.name.localeCompare(b.name,'vi'));
      showUser(me.user);
      teamFilter.replaceChildren(new Option('Tất cả Team/Đội',''));
      teams.forEach(team=>teamFilter.add(new Option(team.name,scalar(team.id))));
      teamFilter.disabled=!teams.length;
      if(selectedId&&!teams.some(team=>scalar(team.id)===selectedId))throw new Error('Nhóm đã chọn không còn khả dụng. Hãy chọn lại nhóm.');
      teamFilter.value=selectedId;
      render(summarize({incidents,relocations,teams,month,selectedId}),teams,selectedId,month);
    } catch(error) {
      if(id!==requestId)return;
      const text=signal.aborted?'Tải dữ liệu quá lâu. Bấm Cập nhật để thử lại.':error.message||'Không tải được dữ liệu staging.';
      clear('Chưa tải được dữ liệu.');badge.textContent='Chưa tải được dữ liệu';note.textContent=text;
      if(error.status===401){const login=element('a',' Đăng nhập lại');login.href='/staging/dangnhap/';note.append(login);}
    } finally {clearTimeout(timeout);if(id===requestId){main.setAttribute('aria-busy','false');refreshButton.disabled=false;}}
  }
  period.addEventListener('change',load);teamFilter.addEventListener('change',load);refreshButton.addEventListener('click',load);
  window.addEventListener('focus',()=>{if(document.visibilityState==='visible')load();});
  load();
})(typeof window === 'undefined' ? globalThis : window);
