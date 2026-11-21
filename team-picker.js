(function (root) {
  'use strict';
  const text = value => value == null ? '' : String(value);
  const normalize = value => text(value).toLocaleLowerCase('vi').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/\s+/g, ' ').trim();
  function myTeams(teams, user) {
    if (!user || !text(user.id) || !Array.isArray(teams)) throw new Error('Không đọc được danh sách nhóm.');
    const seen = new Set();
    return teams.filter(team => {
      if (!team || !text(team.id) || typeof team.name !== 'string' || !team.name.trim() || seen.has(text(team.id))) return false;
      const member = text(team.ownerId) === text(user.id) || (Array.isArray(team.members) && team.members.some(member => text(member.id) === text(user.id))) || ['owner', 'coordinator', 'technician'].includes(team.membershipRole);
      if (member) seen.add(text(team.id));
      return member;
    }).map(team => ({id:text(team.id), name:team.name, slug:text(team.slug), owned:text(team.ownerId)===text(user.id)}))
      .sort((a,b) => a.name.localeCompare(b.name,'vi') || a.slug.localeCompare(b.slug));
  }
  const matches = (teams, query) => teams.filter(team => normalize(team.name).includes(normalize(query)) || normalize(team.slug).includes(normalize(query)));
  if (typeof module === 'object' && module.exports) {module.exports={normalize,myTeams,matches};return;}
  const input = document.querySelector('#team-filter');
  if (!input || input.tagName !== 'INPUT' || !location.pathname.startsWith('/staging/')) return;
  const panel = document.createElement('div'); panel.className='hp-team-picker'; panel.hidden=true;
  const list=document.createElement('div');list.id='team-suggestions';list.setAttribute('role','listbox');list.setAttribute('aria-label','Nhóm đã tạo hoặc tham gia');
  const status=document.createElement('p');status.className='hp-team-picker-status';status.setAttribute('role','status');
  const retry=document.createElement('button');retry.type='button';retry.className='hp-team-retry';retry.textContent='Thử tải lại';retry.hidden=true;
  panel.append(list,status,retry);document.body.append(panel);
  input.autocomplete='off';input.setAttribute('role','combobox');input.setAttribute('aria-autocomplete','list');input.setAttribute('aria-haspopup','listbox');input.setAttribute('aria-controls',list.id);input.setAttribute('aria-expanded','false');
  input.placeholder='Tìm nhóm đã tạo hoặc tham gia';
  let teams=[], state='idle', message='', loadedAt=0, active=-1, choices=[], currentRequest;
  function position() {
    if(panel.hidden)return;
    const rect=input.getBoundingClientRect();
    if(rect.bottom<0||rect.top>innerHeight){close();return;}
    panel.style.width=Math.min(Math.max(rect.width,280),innerWidth-16)+'px';
    panel.style.left=Math.max(8,Math.min(rect.left,innerWidth-panel.offsetWidth-8))+'px';
    const below=innerHeight-rect.bottom-8, above=rect.top-8;
    const down=below>=180||below>=above;
    panel.style.maxHeight=Math.max(80,Math.min(320,down?below:above))+'px';
    panel.style.top=(down?rect.bottom+4:Math.max(8,rect.top-panel.offsetHeight-4))+'px';
  }
  function close(){panel.hidden=true;input.setAttribute('aria-expanded','false');input.removeAttribute('aria-activedescendant');active=-1;}
  function mark(index) {
    active=index;[...list.children].forEach((option,i)=>option.setAttribute('aria-selected',String(i===index)));
    const selected=list.children[index];
    if(selected){input.setAttribute('aria-activedescendant',selected.id);selected.scrollIntoView({block:'nearest'});}else input.removeAttribute('aria-activedescendant');
  }
  function choose(index) {
    const team=choices[index];if(!team)return;
    input.value=team.name;
    input.dispatchEvent(new Event('input',{bubbles:true}));input.dataset.teamId=team.id;input.dispatchEvent(new Event('change',{bubbles:true}));close();
  }
  function render() {
    if(panel.hidden)return;
    choices=[{id:'',name:'',label:'Tất cả Team/Đội'},...(state==='ready'?matches(teams,input.value):[])];
    list.replaceChildren();active=-1;input.removeAttribute('aria-activedescendant');
    choices.forEach((team,index)=>{
      const option=document.createElement('div');option.id='team-option-'+index;option.className='hp-team-option';option.setAttribute('role','option');option.setAttribute('aria-selected','false');
      const name=document.createElement('strong');name.textContent=team.label||team.name;option.append(name);
      if(team.id){const detail=document.createElement('small');detail.textContent=(team.owned?'Bạn đã tạo':'Bạn đã tham gia')+(team.slug?' · nhom/'+team.slug:'');option.append(detail);}
      option.addEventListener('pointerdown',event=>event.preventDefault());option.addEventListener('click',()=>choose(index));list.append(option);
    });
    status.textContent=state==='loading'?'Đang tải nhóm…':state==='error'?message:state==='ready'?(teams.length?(choices.length===1?'Không tìm thấy nhóm phù hợp.':(choices.length-1)+' nhóm phù hợp · Gõ tên để tìm'):'Bạn chưa tạo hoặc tham gia nhóm nào.'):'Bấm để tải danh sách nhóm.';
    retry.hidden=state!=='error';position();
  }
  async function load(force=false) {
    if(state==='loading'||(!force&&state==='ready'&&Date.now()-loadedAt<60000))return;
    state='loading';teams=[];render();const request=new AbortController();currentRequest=request;const timer=setTimeout(()=>request.abort(),15000);
    try {
      const read=async url=>{const response=await fetch(url,{credentials:'same-origin',cache:'no-store',signal:request.signal});if(!response.ok)throw new Error(response.status===401?'Phiên đăng nhập hết hạn. Hãy đăng nhập lại.':'Không tải được nhóm. Bấm Thử tải lại.');return response.json();};
      const [account,result]=await Promise.all([read('/staging/api/auth/me'),read('/staging/api/teams')]);
      if(currentRequest!==request)return;
      teams=myTeams(result.teams,account.user);state='ready';loadedAt=Date.now();
    } catch(error) {if(currentRequest!==request)return;teams=[];state='error';message=request.signal.aborted?'Kết nối quá lâu. Bấm Thử tải lại.':error.message;}
    finally {clearTimeout(timer);if(currentRequest===request)render();}
  }
  function open(){panel.hidden=false;input.setAttribute('aria-expanded','true');render();load();}
  input.addEventListener('focus',open);input.addEventListener('click',open);
  input.addEventListener('input',()=>{delete input.dataset.teamId;open();});
  input.addEventListener('keydown',event=>{
    if(event.isComposing)return;
    if(event.key==='Escape'){event.stopPropagation();close();return;}
    if(event.key==='Tab'){close();return;}
    if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();if(panel.hidden)open();mark(event.key==='ArrowDown'?(active+1)%choices.length:(active<0?choices.length-1:(active-1+choices.length)%choices.length));}
    if(event.key==='Enter'&&!panel.hidden&&active>=0){event.preventDefault();choose(active);}
  });
  input.addEventListener('blur',event=>{if(!panel.contains(event.relatedTarget))close();});
  document.addEventListener('pointerdown',event=>{if(event.target!==input&&!panel.contains(event.target))close();});
  retry.addEventListener('click',()=>{input.focus();load(true);});
  window.addEventListener('resize',position);window.addEventListener('scroll',position,true);
  window.addEventListener('focus',()=>{loadedAt=0;if(!panel.hidden)load(true);});
  input.form?.addEventListener('reset',close);
})(typeof window==='undefined'?globalThis:window);
