const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/hpmap/app.js'), 'utf8');
function implementation(name) {
  const start = source.indexOf('  function ' + name + '(');
  assert(start >= 0, 'Missing implementation: ' + name);
  const end = source.indexOf('\n  function ', start + 1);
  return source.slice(start, end === -1 ? undefined : end);
}
const clone = value => JSON.parse(JSON.stringify(value));
const fixture = () => ({
  folders: [{id:'a',name:'Nguồn',parent:'',visible:true}, {id:'root',name:'Cha',parent:'',visible:false}, {id:'b',name:'Đích',parent:'root',visible:false}],
  features: [
    {id:'p',kind:'point',folder:'a',name:'MX',coords:[12.6,108.1],note:'Giữ ghi chú',properties:{pole:'TR-1'}},
    {id:'l',kind:'line',folder:'a',name:'Cáp',coords:[[12.6,108.1],[12.7,108.2]],color:'#aabbcc',sourceRoute:'R1'},
    {id:'keep',kind:'point',folder:'a',name:'DP',coords:[12.8,108.3]},
    {id:'existing',kind:'point',folder:'b',name:'MX',coords:[13,109]}
  ], activeFolder:'a', selected:[], sharedSelected:[]
});
function harness(shared = false) {
  const nodes = {}, writes = [], calls = [], messages = [];
  let serial = 0;
  const context = {
    state:fixture(), featureSelection:{source:shared?'shared':'local',ids:new Set(['p','l']),...(shared?{team:'DLK',revision:4}:{})},
    sharedLayers:[
      {key:'DLK:a',folderId:'a',team:'DLK',revision:4,name:'Nguồn',parent:'',markers:[{feature_id:'p',name:'MX',coords:[12.6,108.1]}],lineFeatures:[{feature_id:'l',name:'Cáp',coords:[[12.6,108.1],[12.7,108.2]]}]},
      {key:'DLK:b',folderId:'b',team:'DLK',revision:4,name:'Đích',parent:'DLK:a',markers:[],lineFeatures:[]}
    ],
    selected:new Set(),sharedSelected:new Set(),openedFolders:new Set(),transferBusy:false,sharedTransferNeedsRefresh:false,sessionEpoch:2,authExpired:false,refreshing:false,admin:true,
    requireAdmin:()=>context.admin,isAdmin:()=>context.admin,
    uid:prefix=>prefix+'-'+(++serial), esc:value=>String(value),
    $:id=>nodes[id] ||= {value:id==='transfer-target'?'b':'',hidden:true,disabled:false,isConnected:true,textContent:''},
    modal:(title,html,setup)=>{context.dialog={title,html};setup();},closeModal:()=>{context.dialog=null;},
    localStorage:{setItem:(key,value)=>writes.push([key,value])},
    renderMap:()=>{},renderShared:()=>{},renderFolders:()=>{},renderSelectionBar:()=>{},openSheet:()=>{},cancelDrawing:()=>{},persist:()=>true,
    applyPermissions:()=>{},toast:message=>messages.push(message),
    receiveSession:result=>calls.push(['session',result]),native:async(action,args)=>{calls.push([action,args]);return {transfer:{saved:true,count:2}};}
  };
  vm.createContext(context);
  vm.runInContext(['folderOptions','sharedFeatures','selectedFeatureItems','sharedFolderPath','localTransferSnapshot','beginFeatureSelection','openTransfer'].map(implementation).join('\n'),context);
  return {context,nodes,writes,calls,messages};
}
async function run() {
  let h=harness(), c=h.context, original=clone(c.state);
  const moved=c.localTransferSnapshot(c.state,['p','l'],'b','move',c.uid,'now');
  assert.equal(moved.count,2);assert.deepEqual(c.state,original,'Planning must not mutate live data');
  assert.equal(moved.state.features.length,4);assert.equal(moved.state.features.find(f=>f.id==='p').folder,'b');
  assert.deepEqual(clone(moved.state.features.find(f=>f.id==='l').coords),original.features[1].coords);
  assert.equal(moved.state.features.find(f=>f.id==='keep').folder,'a');
  assert(moved.state.folders.filter(f=>['b','root'].includes(f.id)).every(f=>f.visible),'Target ancestors must be visible');
  assert.equal(c.localTransferSnapshot(moved.state,['p','l'],'b','move',c.uid,'later').count,0,'Same-folder move is a no-op');
  const copied=c.localTransferSnapshot(c.state,['p','l'],'b','copy',c.uid,'now');
  assert.equal(copied.state.features.length,6);assert.equal(copied.count,2);
  assert.equal(copied.state.features[4].name,'MX · Bản sao');assert.notEqual(copied.state.features[4].id,'p');
  assert.deepEqual(clone(copied.state.features[4].properties),{pole:'TR-1'});
  copied.state.features[4].coords[0]=0;assert.equal(c.state.features[0].coords[0],12.6,'Copy geometry must be independent');
  assert.throws(()=>c.localTransferSnapshot(c.state,['missing'],'b','move',c.uid,'now'),/chọn lại/);
  assert.throws(()=>c.localTransferSnapshot(c.state,['p'],'missing','move',c.uid,'now'),/không còn/);
  assert.throws(()=>c.localTransferSnapshot(c.state,['p'],'b','copy',()=> 'p','now'),/mã bản sao/);
  const full=fixture();full.features=Array.from({length:10000},(_,i)=>({...full.features[0],id:String(i)}));
  assert.throws(()=>c.localTransferSnapshot(full,['0'],'b','copy',c.uid,'now'),/10.000/);

  c.openTransfer('move');await h.nodes['transfer-confirm'].onclick();
  assert.equal(h.writes.length,1);assert.equal(c.featureSelection,null);assert.equal(c.state.features[0].folder,'b');
  assert.deepEqual(JSON.parse(h.writes[0][1]),clone(c.state),'Persisted transaction equals committed state');
  h=harness();c=h.context;const before=c.state;
  c.localStorage.setItem=()=>{const error=new Error('full');error.name='QuotaExceededError';throw error;};
  c.openTransfer('move');await h.nodes['transfer-confirm'].onclick();
  assert.equal(c.state,before,'Storage failure must preserve live source objects');assert.equal(c.state.features[0].folder,'a');
  assert.equal(c.featureSelection.ids.size,2);assert.equal(h.nodes['transfer-error'].hidden,false);assert.match(h.nodes['transfer-error'].textContent,/gốc vẫn được giữ/);
  h=harness();c=h.context;c.openTransfer('copy');c.state.features[0].note='Edited while dialog was open';await h.nodes['transfer-confirm'].onclick();
  assert.equal(h.writes.length,0);assert.match(h.nodes['transfer-error'].textContent,/thay đổi/);
  h=harness();c=h.context;c.openTransfer('copy');c.admin=false;await h.nodes['transfer-confirm'].onclick();assert.equal(h.writes.length,0,'Expired role cannot commit');

  h=harness(true);c=h.context;let finish;
  c.native=(action,args)=>{h.calls.push([action,args]);return new Promise(resolve=>finish=resolve);};
  c.openTransfer('copy');const pending=h.nodes['transfer-confirm'].onclick();await h.nodes['transfer-confirm'].onclick();
  assert.equal(h.calls.length,1,'Double tap must submit once');assert.equal(c.transferBusy,true);
  assert.deepEqual(clone(h.calls[0]),['transferSharedFeatures',{team:'DLK',featureIds:['p','l'],targetFolderId:'b',mode:'copy',revision:4}]);
  finish({transfer:{saved:true,count:2}});await pending;assert.equal(c.featureSelection,null);assert.equal(c.transferBusy,false);
  assert(c.sharedSelected.has('DLK:b'));

  h=harness(true);c=h.context;c.native=async(action,args)=>{h.calls.push([action,args]);if(action==='refresh')throw new Error('offline');return {refreshRequired:true,transfer:{saved:null,uncertain:true},warning:'Chưa xác nhận'};};
  c.openTransfer('copy');await h.nodes['transfer-confirm'].onclick();
  assert.deepEqual(h.calls.map(x=>x[0]),['transferSharedFeatures','refresh'],'Uncertain save must refresh and never replay copy');
  assert.equal(c.featureSelection,null);assert.equal(c.sharedTransferNeedsRefresh,true);
  c.beginFeatureSelection('shared','DLK:a');assert.equal(c.featureSelection,null,'Cannot repeat a shared operation until refresh succeeds');

  h=harness(true);c=h.context;c.native=async(action,args)=>{h.calls.push([action,args]);if(action==='transferSharedFeatures'){const e=new Error('timeout');e.transferUncertain=true;throw e;}return {user:{role:'admin'}};};
  c.openTransfer('move');await h.nodes['transfer-confirm'].onclick();assert.deepEqual(h.calls.map(x=>x[0]),['transferSharedFeatures','refresh','session']);assert.equal(c.featureSelection,null);assert.equal(c.sharedTransferNeedsRefresh,false);

  h=harness(true);c=h.context;c.native=async()=>{const e=new Error('changed');e.status=403;throw e;};
  c.openTransfer('copy');await h.nodes['transfer-confirm'].onclick();assert.equal(c.authExpired,true);assert.equal(c.featureSelection,null);

  h=harness(true);c=h.context;c.native=()=>new Promise(resolve=>finish=resolve);
  c.openTransfer('copy');const switched=h.nodes['transfer-confirm'].onclick();c.sessionEpoch++;finish({user:{username:'old',role:'admin'},transfer:{saved:true}});await switched;
  assert.equal(h.calls.length,0,'A pending save must not restore an old signed-in session');assert.equal(c.sharedTransferNeedsRefresh,true);
  console.log('PASS: subset copy/move, IDs and metadata, same-folder no-op, hierarchy, capacity, storage rollback, stale edits, roles, double-submit, raw shared API IDs, ambiguous save/timeout, refresh gate, session switch.');
}
run().catch(error=>{console.error(error);process.exitCode=1;});
