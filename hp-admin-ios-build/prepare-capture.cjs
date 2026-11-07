'use strict';
// Only the GitHub masking command may contain the token; no ordinary log or file receives it.
const fs=require('node:fs'),path=require('node:path');
try{
 const session=JSON.parse(process.env.HP_ADMIN_CAPTURE_SESSION||'null');
 const now=Date.now()/1000;
 if(!session||typeof session.token!=='string'||!/^r1\.[\x21-\x7e]{1,197}$/.test(session.token)||typeof session.expires_at!=='number'||session.expires_at<=now+600||session.expires_at>now+13*3600)throw Error('Invalid temporary limited reviewer session');
 const config=JSON.parse(fs.readFileSync(path.join(process.env.BUILD_WORK,'app/AdminUITests/capture-config.json'),'utf8'));
 if(!config||typeof config!=='object'||Array.isArray(config)||config.origin!=='https://hptelecom.id.vn'||config.username!=='hp.review.admin')throw Error('Capture configuration must use the limited reviewer on the main server');
 process.stdout.write('::add-mask::'+session.token+'\n');
 fs.writeFileSync(path.join(process.env.BUILD_WORK,'capture-context.json'),JSON.stringify({temporarySession:true,origin:config.origin,expiresAt:new Date(session.expires_at*1000).toISOString(),configurationPresent:true,valuesLogged:false},null,2)+'\n');
}catch{console.error('Temporary screenshot session/configuration failed validation; no private values logged.');process.exitCode=1;}
