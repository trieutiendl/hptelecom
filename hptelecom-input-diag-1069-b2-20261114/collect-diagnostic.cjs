'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {validateTrace} = require('./validate-trace.cjs');
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const isHash = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
function reject(){ throw Error('Diagnostic export validation failed'); }
function exact(value, names){if(!value || Array.isArray(value) || typeof value !== 'object' || Object.keys(value).length !== names.length || names.some(k=>!Object.hasOwn(value,k))) reject();}
function status(raw){
  let item; try{item=JSON.parse(raw);}catch{reject();}
  exact(item,['schema','outcome','keyboardCounts']);
  const early=['SETUP_FAILED','BUILD_FAILED','TEST_NO_TRACE'].includes(item.outcome);
  if(item.schema!==1 || (!early&&!['REGISTER_SUCCEEDED','REGISTER_FAILED','TRACE_MISSING'].includes(item.outcome)) || !Array.isArray(item.keyboardCounts) || item.keyboardCounts.length!==3 || item.keyboardCounts.some(n=>early?n!==null:!Number.isInteger(n)||n<0||n>4))reject();
  return item;
}
function collect(log,failureStage=null){
  if(typeof log!=='string'||log.length>64*1024*1024)reject();
  const records={status:[],trace:[]};
  for(const line of log.split(/\r?\n/)){
    if(line.startsWith('[HP_INPUT_DIAG_STATUS]'))records.status.push(status(line.slice(22)));
    if(line.startsWith('[HP_INPUT_DIAG_TRACE]'))records.trace.push(validateTrace(line.slice(21)));
  }
  if(records.status.length===0&&records.trace.length===0&&['SETUP_FAILED','BUILD_FAILED','TEST_NO_TRACE'].includes(failureStage))return {schema:1,purpose:'DIAGNOSTIC_ONLY_NOT_FOR_STORE',status:{schema:1,outcome:failureStage,keyboardCounts:[null,null,null]},trace:null};
  if(records.status.length!==1 || records.trace.length>1)reject();
  const result=records.status[0];
  if((result.outcome==='REGISTER_FAILED') !== (records.trace.length===1))reject();
  return {schema:1,purpose:'DIAGNOSTIC_ONLY_NOT_FOR_STORE',status:result,trace:records.trace[0]||null};
}
function provenance(release, product, result, env){
  if(release.purpose!=='DIAGNOSTIC_ONLY_NOT_FOR_STORE'||release.namespace!=='hptelecom-input-diag-1069-b2-20261114'||release.baseSourceSha256!=='de8f6eaea8ea62b5e6d24e346707605ca887bdbbc55db861d30d983da159fa1b')reject();
  exact(release.overlays,['src/PersonalAccount.tsx','src/InputProbe.ts']);
  const helperNames=['crypt.cjs','verify-provenance.py','verify-simulator.py','product-evidence.py','configure-tests.rb','InputDiagnostics.swift','run-diagnostic.sh','validate-trace.cjs','collect-diagnostic.cjs','workflow.yml'];
  exact(release.helperFiles,helperNames);
  if(Object.values(release.overlays).some(n=>!isHash(n))||Object.values(release.helperFiles).some(n=>!isHash(n)))reject();
  if(!isHash(release.sourceSha256))reject();
  if(product===null){
    if(!['SETUP_FAILED','BUILD_FAILED'].includes(JSON.parse(result).status.outcome))reject();
  }else{
    exact(product,['schema','purpose','bundleIdentifier','version','build','sourceSha256','jsBundleSha256','simulatorSignatureVerified','deviceFamily']);
    if(product.schema!==1||product.purpose!==release.purpose||product.bundleIdentifier!=='vn.hptelecom.fieldwork'||product.version!=='1.0.69'||product.build!=='2'||product.sourceSha256!==release.sourceSha256||!isHash(product.sourceSha256)||!isHash(product.jsBundleSha256)||product.simulatorSignatureVerified!==true||JSON.stringify(product.deviceFamily)!=='[1]')reject();
  }
  if(!/^[0-9a-f]{40}$/.test(env.GITHUB_SHA||'')||!/^\d+$/.test(env.GITHUB_RUN_ID||'')||!/^\d+$/.test(env.GITHUB_RUN_ATTEMPT||'')||env.GITHUB_REPOSITORY!=='trieutiendl/hptelecom')reject();
  return {schema:1,purpose:release.purpose,namespace:release.namespace,baseSourceSha256:release.baseSourceSha256,sourceSha256:release.sourceSha256,jsBundleSha256:product?.jsBundleSha256||null,diagnosticSha256:sha(result),overlays:release.overlays,helperFiles:release.helperFiles,commit:env.GITHUB_SHA,runId:env.GITHUB_RUN_ID,runAttempt:env.GITHUB_RUN_ATTEMPT,repository:env.GITHUB_REPOSITORY};
}
module.exports={collect,provenance,status};
if(require.main===module){
  try{
    const [logFile,productFile,destination,failureStage]=process.argv.slice(2);
    if(!logFile||!productFile||!destination||fs.existsSync(destination))reject();
    const logStat=fs.statSync(logFile); if(logStat.size>64*1024*1024)reject();
    const result=JSON.stringify(collect(fs.readFileSync(logFile,'utf8'),failureStage),null,2)+'\n';
    const receipt=provenance(JSON.parse(fs.readFileSync(path.join(__dirname,'release.json'))),productFile==='-'?null:JSON.parse(fs.readFileSync(productFile)),result,process.env);
    fs.mkdirSync(destination,{recursive:true,mode:0o700});
    fs.writeFileSync(path.join(destination,'diagnostic.json'),result,{mode:0o600});
    fs.writeFileSync(path.join(destination,'provenance.json'),JSON.stringify(receipt,null,2)+'\n',{mode:0o600});
    console.log('Validated numeric-only input diagnostic and source provenance.');
  }catch{console.error('Diagnostic export rejected; no raw logs were exported.');process.exitCode=1;}
}
