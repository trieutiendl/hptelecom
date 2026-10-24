const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/hpmap/app.js'),'utf8');
function implementation(name){const pattern=new RegExp('^  (?:async )?function '+name+'\\(','m'),match=pattern.exec(source);assert(match,'Missing '+name);const after=source.slice(match.index+1),next=/\n  (?:async )?function /.exec(after);return source.slice(match.index,next?match.index+1+next.index:undefined);}
const clone=value=>JSON.parse(JSON.stringify(value));
function harness(){
  const nodes={},writes=[],calls=[],messages=[];
  const point={id:'p',kind:'point',name:'MX 1',label:'MX',folder:'a',coords:[12.6,108.1],note:'  Ghi chú giữ nguyên  ',color:'#abcdef',properties:{pole:'P1'},display_on_map:true};
  const state={folders:[{id:'a',name:'A',parent:'',visible:true},{id:'b',name:'B',parent:'',visible:false}],features:[point,{id:'q',kind:'point',name:'DP 2',label:'DP',folder:'a',coords:[12.8,108.3]},{id:'l',kind:'line',name:'Cáp',folder:'b',coords:[[12.6,108.1],[12.8,108.3]]}],activeFolder:'a'};
  const ctx={state,selected:new Set(),sharedSelected:new Set(),openedFolders:new Set(),featureSelection:null,sessionEpoch:2,transferBusy:false,refreshing:false,sharedTransferNeedsRefresh:false,authExpired:false,admin:true,drawing:null,
    sharedLayers:[{shared:true,key:'DLK:a',folderId:'a',team:'DLK',revision:4,name:'A',parent:'',markers:[{feature_id:'p',name:'MX 1',position:'MX 1',coords:[12.6,108.1],note:'Giữ nguyên',label:'MX'}],lineFeatures:[{feature_id:'l',name:'Cáp',coords:[[12.6,108.1],[12.8,108.3]]}]}],
    requireAdmin:()=>ctx.admin,isAdmin:()=>ctx.admin,esc:value=>String(value),
    $:id=>nodes[id] ||= {value:'',hidden:true,disabled:false,isConnected:true,textContent:'',classList:{remove(){},add(){}},add(){},querySelectorAll(){return Object.values(nodes);}},
    modal:(title,html,setup)=>{ctx.dialog={title,html};setup();},closeModal:()=>{ctx.dialog=null;},
    localStorage:{setItem:(key,value)=>writes.push([key,value])},toast:message=>messages.push(message),
    renderMap(){},renderShared(){},renderFolders(){},renderSelectionBar(){},collapseSheet(){},applyPermissions(){},
    folderOptions:()=>'',sharedFolderPath:()=> 'A',lineLength:()=>0,formatDistance:()=>'',markerIcon:()=>({}),
    map:{setView(){},getZoom:()=>15,distance:()=>100},draftLayers:{clearLayers(){}},
    L:{marker:(coords)=>{let location=coords.slice();const handlers={};const marker={handlers,addTo:()=>marker,on:(name,fn)=>{handlers[name]=fn;return marker;},setLatLng:next=>{location=next.slice();},getLatLng:()=>({lat:location[0],lng:location[1]})};return marker;}},
    Option:function(label,value){this.label=label;this.value=value;},
    receiveSession:result=>calls.push(['session',result]),native:async(action,args)=>{calls.push([action,args]);return {transfer:{saved:true,mode:action==='editSharedFeature'?'edit':'delete',count:1}};}
  };
  vm.createContext(ctx);vm.runInContext(['ll','sharedFeatures','selectedFeatureItems','commitLocalState','localEditSnapshot','localDeleteSnapshot','sharedMutationAllowed','runSharedMutation','confirmFeatureDelete','previewPointPosition','cancelDrawing','editSharedPoint','editFeature'].map(implementation).join('\n'),ctx);
  return {ctx,nodes,writes,calls,messages};
}
async function run(){
  let h=harness(),c=h.ctx,p=c.state.features[0],original=clone(c.state),expected=JSON.stringify(p);
  const edited=c.localEditSnapshot(c.state,{...p,coords:[12.7,108.2]},expected);
  assert.deepEqual(c.state,original,'Edit plan cannot mutate source');
  assert.deepEqual(clone(edited.features[0]),{...original.features[0],coords:[12.7,108.2]},'All other point fields must survive');
  assert.deepEqual(clone(edited.features.slice(1)),original.features.slice(1),'Other objects untouched');
  for(const coords of [['',108],[' ',108],[12,''],[NaN,108],[Infinity,108],[91,108],[-91,108],[12,181],[12,-181]])assert.throws(()=>c.localEditSnapshot(c.state,{...p,coords},expected),/Tọa độ/);
  assert.throws(()=>c.localEditSnapshot(c.state,{...p,coords:[12,108]},'old'),/thay đổi/);
  const deleted=c.localDeleteSnapshot(c.state,['p','l']);assert.deepEqual(clone(deleted.features.map(f=>f.id)),['q']);assert.deepEqual(c.state,original);
  assert.throws(()=>c.localDeleteSnapshot(c.state,['missing']),/chọn lại/);
  c.localStorage.setItem=()=>{const error=new Error('full');error.name='QuotaExceededError';throw error;};
  const reference=c.state;assert.throws(()=>c.commitLocalState(edited));assert.equal(c.state,reference,'Failed persistence must not commit live edits');

  h=harness();c=h.ctx;p=c.state.features[0];c.editFeature({...p});
  assert(h.ctx.dialog.html.includes('pick-point-position'),'Local editor must offer map picking');
  for(const [id,value] of Object.entries({'feature-name':p.name,'feature-folder':p.folder,'feature-label':p.label,'point-lat':'12.7','point-lon':'108.2','feature-note':p.note}))c.$(id).value=value;
  assert.deepEqual(c.state.features[0].coords,[12.6,108.1],'Typing does not save');
  c.$('save-feature').onclick();assert.equal(h.writes.length,1);assert.deepEqual(clone(c.state.features[0].coords),[12.7,108.2]);
  assert.equal(c.state.features[0].note,p.note);assert.equal(c.state.features[0].color,p.color);assert.equal(c.state.features[0].properties.pole,'P1');

  h=harness();c=h.ctx;p=c.state.features[0];const before=JSON.stringify(c.state);let chosen;
  c.previewPointPosition(p.coords,next=>chosen=next);c.drawing.marker.setLatLng([13,109]);c.drawing.marker.handlers.dragend();
  assert.equal(JSON.stringify(c.state),before,'Moving preview pin does not save');c.cancelDrawing();assert.equal(JSON.stringify(c.state),before);assert.equal(h.writes.length,0);assert.equal(chosen,undefined);
  c.previewPointPosition(p.coords,next=>chosen=next);c.drawing.marker.setLatLng([13,109]);c.drawing.marker.handlers.dragend();c.drawing.save();
  assert.deepEqual(clone(chosen),[13,109]);assert.equal(h.writes.length,0,'Using a preview must return draft, not persist');

  h=harness();c=h.ctx;c.confirmFeatureDelete({source:'local',ids:new Set(['p','l'])});
  assert.equal(h.writes.length,0,'Delete must wait for explicit confirmation');assert(h.ctx.dialog.title.includes('2'));assert(h.ctx.dialog.html.includes('MX 1'));assert(h.ctx.dialog.html.includes('Cáp'));
  c.$('delete-cancel').onclick();assert.equal(c.state.features.length,3);
  c.confirmFeatureDelete({source:'local',ids:new Set(['p','l'])});await c.$('delete-confirm').onclick();assert.deepEqual(clone(c.state.features.map(f=>f.id)),['q']);assert.equal(h.writes.length,1);
  h=harness();c=h.ctx;c.localStorage.setItem=()=>{const error=new Error('full');error.name='QuotaExceededError';throw error;};
  c.confirmFeatureDelete({source:'local',ids:new Set(['p'])});await c.$('delete-confirm').onclick();assert.equal(c.state.features.length,3);assert.equal(c.$('delete-error').hidden,false);
  h=harness();c=h.ctx;c.confirmFeatureDelete({source:'local',ids:new Set(['p'])});c.admin=false;await c.$('delete-confirm').onclick();assert.equal(h.writes.length,0);

  h=harness();c=h.ctx;let route=c.sharedLayers[0],point=route.markers[0];c.editSharedPoint(route,point);
  c.$('shared-point-name').value='MX sửa';
  for(const values of [['','108'],[' ','108'],['NaN','108'],['91','108'],['12','181']]){c.$('shared-point-lat').value=values[0];c.$('shared-point-lon').value=values[1];await c.$('shared-point-save').onclick();assert.equal(h.calls.length,0);assert.equal(c.$('shared-point-error').hidden,false);}
  c.$('shared-point-lat').value='12,7';c.$('shared-point-lon').value='108.2';await c.$('shared-point-save').onclick();
  assert.deepEqual(clone(h.calls[0]),['editSharedFeature',{team:'DLK',featureId:'p',revision:4,name:'MX sửa',lat:12.7,lon:108.2}]);assert.equal(h.calls[1][0],'session');
  h=harness();c=h.ctx;c.admin=false;c.editSharedPoint(c.sharedLayers[0],c.sharedLayers[0].markers[0]);assert.equal(c.dialog,undefined,'Staff cannot open shared editor');
  c.admin=true;c.editSharedPoint({...c.sharedLayers[0],shared:false},c.sharedLayers[0].markers[0]);assert.equal(c.dialog,undefined,'Patrol source cannot be edited');

  h=harness();c=h.ctx;c.confirmFeatureDelete({source:'shared',team:'DLK',revision:4,ids:new Set(['p','l'])});
  let finish;c.native=(action,args)=>{h.calls.push([action,args]);return new Promise(resolve=>finish=resolve);};
  const pending=c.$('delete-confirm').onclick();await c.$('delete-confirm').onclick();assert.equal(h.calls.length,1,'Duplicate delete tap submits once');
  assert.deepEqual(clone(h.calls[0]),['deleteSharedFeatures',{team:'DLK',featureIds:['p','l'],revision:4}]);finish({transfer:{saved:true,count:2,mode:'delete'}});await pending;
  h=harness();c=h.ctx;c.native=async(action,args)=>{h.calls.push([action,args]);if(action==='refresh')throw new Error('offline');return {transfer:{saved:null,uncertain:true},refreshRequired:true};};
  c.confirmFeatureDelete({source:'shared',team:'DLK',revision:4,ids:new Set(['p'])});await c.$('delete-confirm').onclick();assert.deepEqual(h.calls.map(call=>call[0]),['deleteSharedFeatures','refresh']);assert.equal(c.sharedTransferNeedsRefresh,true);assert.equal(c.featureSelection,null);
  h=harness();c=h.ctx;c.confirmFeatureDelete({source:'shared',team:'DLK',revision:4,ids:new Set(['p'])});c.sharedLayers[0].revision=5;await c.$('delete-confirm').onclick();assert.equal(h.calls[0][0],'refresh');assert(!h.calls.some(call=>call[0]==='deleteSharedFeatures'));
  console.log('PASS: local/shared point edits, blank/NaN/range validation, preview cancel/use without save, metadata, explicit subset deletion, storage rollback, admin/patrol permissions, double-submit, stale revision and uncertain recovery.');
}
run().catch(error=>{console.error(error);process.exitCode=1;});
