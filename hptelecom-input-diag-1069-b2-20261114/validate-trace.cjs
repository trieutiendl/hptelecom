'use strict';
// Export boundary for a diagnostic-only runner. Reject; never echo rejected payloads.
const outcomes=new Set(['REG_CONFIRM','REG_LENGTH','REG_USERNAME','REG_DUPLICATE','REG_NATIVE_PASSWORD','REG_NATIVE_DERIVE','REG_NATIVE_RANDOM','REG_UNKNOWN']);
const phases=new Set(['REG_BEGIN','REG_BUSY_GUARD','REG_CONFIRM_PASS','REG_CONFIRM_FAIL','REG_ACCOUNT_CALL']);
const fields=new Set(['P','C']);
const shapes={focus:['seq','kind','field'],blur:['seq','kind','field'],native:['seq','kind','field','length','eventCount'],callback:['seq','kind','field','length','eventCount'],commit:['seq','kind','pLength','cLength','pEventCount','cEventCount'],phase:['seq','kind','code']};
function reject(){throw Error('Diagnostic trace schema rejected');}
function exact(value,keys){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==keys.length||keys.some(key=>!Object.hasOwn(value,key)))reject();}
function integer(value,max){if(typeof value!=='number'||!Number.isInteger(value)||value<0||value>max)reject();}
function count(value){if(value!==null)integer(value,1_000_000_000);}
function validateTrace(raw){
  if(typeof raw!=='string'||raw.length>32_768)reject();
  let value;try{value=JSON.parse(raw);}catch{reject();}
  exact(value,['schema','scope','records','summary','outcome','expired','dropped']);
  if(value.schema!==1||value.scope!=='SYNTHETIC_REGISTER'||!Array.isArray(value.records)||value.records.length>128||!outcomes.has(value.outcome)||typeof value.expired!=='boolean')reject();
  integer(value.dropped,1_000_000);
  for(let i=0;i<value.records.length;i++){
    const item=value.records[i];if(!item||typeof item.kind!=='string'||!Object.hasOwn(shapes,item.kind))reject();exact(item,shapes[item.kind]);
    if(item.seq!==i+1)reject();
    if(Object.hasOwn(item,'field')&&!fields.has(item.field))reject();
    if(Object.hasOwn(item,'length'))integer(item.length,128);
    if(Object.hasOwn(item,'eventCount'))count(item.eventCount);
    if(item.kind==='commit'){integer(item.pLength,128);integer(item.cLength,128);count(item.pEventCount);count(item.cEventCount);}
    if(item.kind==='phase'&&!phases.has(item.code))reject();
  }
  if(value.summary!==null){exact(value.summary,['pLength','cLength','equal']);integer(value.summary.pLength,128);integer(value.summary.cLength,128);if(typeof value.summary.equal!=='boolean')reject();}
  return value;
}
module.exports={validateTrace};
