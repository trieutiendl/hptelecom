!function(f){typeof module!='undefined'&&typeof exports=='object'?module.exports=f():typeof define!='undefined'&&define.amd?define(f):(typeof self!='undefined'?self:this).fflate=f()}(function(){var _e={};"use strict";_e.deflate=zt,_e.deflateSync=kt,_e.inflate=At,_e.inflateSync=Tt,_e.gzip=It,_e.compress=It,_e.gzipSync=Ut,_e.compressSync=Ut,_e.gunzip=Zt,_e.gunzipSync=qt,_e.zlib=Lt,_e.zlibSync=Bt,_e.unzlib=Nt,_e.unzlibSync=Pt,_e.gzip=It,_e.compress=It,_e.decompress=Jt,_e.decompressSync=Kt,_e.strToU8=nn,_e.strFromU8=rn,_e.zip=dn,_e.zipSync=gn,_e.unzip=zn,_e.unzipSync=kn;var t=(typeof module!='undefined'&&typeof exports=='object'?function(_f){"use strict";var e,r,t,n=";var __w=require('worker_threads');__w.parentPort.on('message',function(m){onmessage({data:m})}),postMessage=function(m,t){__w.parentPort.postMessage(m,t)},close=process.exit;self=global";try{e=require("worker_threads"),r=e.Worker,t=e.isMarkedAsUntransferable}catch(e){}exports.default=r?function(e,o,a,s,u){var i=!1,l=new r(e+n,{eval:!0}).on("error",function(e){return u(e,null)}).on("message",function(e){return u(null,e)}).on("exit",function(e){e&&!i&&u(Error("exited with code "+e),null)});return t&&(s=s.filter(function(e){return!t(e)})),l.postMessage(a,s),l.terminate=function(){return i=!0,r.prototype.terminate.call(l)},l}:function(e,r,t,n,o){setImmediate(function(){return o(Error("async operations unsupported - update to Node 12+ (or Node 10-11 with the --experimental-worker CLI flag)"),null)});var a=function(){};return{terminate:a,postMessage:a}};return _f}:function(_f){"use strict";var e={};_f.default=function(r,t,s,a,n){var o=new Worker(e[t]||(e[t]=URL.createObjectURL(new Blob([r+';addEventListener("error",function(e){e=e.error;postMessage({$e$:[e.message,e.code,e.stack]})})'],{type:"text/javascript"}))));return o.onmessage=function(e){var r=e.data,t=r.$e$;if(t){var s=Error(t[0]);s.code=t[1],s.stack=t[2],n(s,null)}else n(null,r)},o.postMessage(s,a),o};return _f})({}),n=Uint8Array,r=Uint16Array,i=Int32Array,e=new n([0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0,0,0,0]),o=new n([0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13,0,0]),s=new n([16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15]),a=function(t,n){for(var e=new r(31),o=0;o<31;++o)e[o]=n+=1<<t[o-1];var s=new i(e[30]);for(o=1;o<30;++o)for(var a=e[o];a<e[o+1];++a)s[a]=a-e[o]<<5|o;return{b:e,r:s}},u=a(e,2),h=u.b,f=u.r;h[28]=258,f[258]=28;for(var c=a(o,0),l=c.b,p=c.r,v=new r(32768),d=0;d<32768;++d){var g=(43690&d)>>1|(21845&d)<<1;v[d]=((65280&(g=(61680&(g=(52428&g)>>2|(13107&g)<<2))>>4|(3855&g)<<4))>>8|(255&g)<<8)>>1}var y=function(t,n,i){for(var e=t.length,o=0,s=new r(n);o<e;++o)t[o]&&++s[t[o]-1];var a,u=new r(n);for(o=1;o<n;++o)u[o]=u[o-1]+s[o-1]<<1;if(i){a=new r(1<<n);var h=15-n;for(o=0;o<e;++o)if(t[o])for(var f=o<<4|t[o],c=n-t[o],l=u[t[o]-1]++<<c,p=l|(1<<c)-1;l<=p;++l)a[v[l]>>h]=f}else for(a=new r(e),o=0;o<e;++o)t[o]&&(a[o]=v[u[t[o]-1]++]>>15-t[o]);return a},m=new n(288);for(d=0;d<144;++d)m[d]=8;for(d=144;d<256;++d)m[d]=9;for(d=256;d<280;++d)m[d]=7;for(d=280;d<288;++d)m[d]=8;var b=new n(32);for(d=0;d<32;++d)b[d]=5;var w=y(m,9,0),x=y(m,9,1),z=y(b,5,0),k=y(b,5,1),M=function(t){for(var n=t[0],r=1;r<t.length;++r)t[r]>n&&(n=t[r]);return n},S=function(t,n,r){var i=n/8|0;return(t[i]|t[i+1]<<8)>>(7&n)&r},A=function(t,n){var r=n/8|0;return(t[r]|t[r+1]<<8|t[r+2]<<16)>>(7&n)},T=function(t){return(t+7)/8|0},D=function(t,r,i){return(null==r||r<0)&&(r=0),(null==i||i>t.length)&&(i=t.length),new n(t.subarray(r,i))};_e.FlateErrorCode={UnexpectedEOF:0,InvalidBlockType:1,InvalidLengthLiteral:2,InvalidDistance:3,StreamFinished:4,NoStreamHandler:5,InvalidHeader:6,NoCallback:7,InvalidUTF8:8,ExtraFieldTooLong:9,InvalidDate:10,FilenameTooLong:11,StreamFinishing:12,InvalidZipData:13,UnknownCompressionMethod:14};var C=["unexpected EOF","invalid block type","invalid length/literal","invalid distance","stream finished","no stream handler",,"no callback","invalid UTF-8 data","extra field too long","date not in range 1980-2099","filename too long","stream finishing","invalid zip data"],I=function(t,n,r){var i=Error(n||C[t]);if(i.code=t,Error.captureStackTrace&&Error.captureStackTrace(i,I),!r)throw i;return i},U=function(t,r,i,a){var u=t.length,f=a?a.length:0;if(!u||r.f&&!r.l)return i||new n(0);var c=!i,p=c||2!=r.i,v=r.i;c&&(i=new n(3*u));var d=function(t){var r=i.length;if(t>r){var e=new n(Math.max(2*r,t));e.set(i),i=e}},g=r.f||0,m=r.p||0,b=r.b||0,w=r.l,z=r.d,C=r.m,U=r.n,F=8*u;do{if(!w){g=S(t,m,1);var E=S(t,m+1,3);if(m+=3,!E){var Z=t[(Y=T(m)+4)-4]|t[Y-3]<<8,q=Y+Z;if(q>u){v&&I(0);break}p&&d(b+Z),i.set(t.subarray(Y,q),b),r.b=b+=Z,r.p=m=8*q,r.f=g;continue}if(1==E)w=x,z=k,C=9,U=5;else if(2==E){var O=S(t,m,31)+257,G=S(t,m+10,15)+4,L=O+S(t,m+5,31)+1;m+=14;for(var B=new n(L),H=new n(19),j=0;j<G;++j)H[s[j]]=S(t,m+3*j,7);m+=3*G;var N=M(H),P=(1<<N)-1,V=y(H,N,1);for(j=0;j<L;){var Y,J=V[S(t,m,P)];if(m+=15&J,(Y=J>>4)<16)B[j++]=Y;else{var K=0,Q=0;for(16==Y?(Q=3+S(t,m,3),m+=2,K=B[j-1]):17==Y?(Q=3+S(t,m,7),m+=3):18==Y&&(Q=11+S(t,m,127),m+=7);Q--;)B[j++]=K}}var R=B.subarray(0,O),W=B.subarray(O);C=M(R),U=M(W),w=y(R,C,1),z=y(W,U,1)}else I(1);if(m>F){v&&I(0);break}}p&&d(b+131072);for(var X=(1<<C)-1,$=(1<<U)-1,_=m;;_=m){var tt=(K=w[A(t,m)&X])>>4;if((m+=15&K)>F){v&&I(0);break}if(K||I(2),tt<256)i[b++]=tt;else{if(256==tt){_=m,w=null;break}var nt=tt-254;tt>264&&(nt=S(t,m,(1<<(et=e[j=tt-257]))-1)+h[j],m+=et);var rt=z[A(t,m)&$],it=rt>>4;if(rt||I(3),m+=15&rt,W=l[it],it>3){var et=o[it];W+=A(t,m)&(1<<et)-1,m+=et}if(m>F){v&&I(0);break}p&&d(b+131072);var ot=b+nt;if(b<W){var st=f-W,at=Math.min(W,ot);for(st+b<0&&I(3);b<at;++b)i[b]=a[st+b]}for(;b<ot;++b)i[b]=i[b-W]}}r.l=w,r.p=_,r.b=b,r.f=g,w&&(g=1,r.m=C,r.d=z,r.n=U)}while(!g);return b!=i.length&&c?D(i,0,b):i.subarray(0,b)},F=function(t,n,r){var i=n/8|0;t[i]|=r<<=7&n,t[i+1]|=r>>8},E=function(t,n,r){var i=n/8|0;t[i]|=r<<=7&n,t[i+1]|=r>>8,t[i+2]|=r>>16},Z=function(t,i){for(var e=[],o=0;o<t.length;++o)t[o]&&e.push({s:o,f:t[o]});var s=e.length,a=e.slice();if(!s)return{t:j,l:0};if(1==s){var u=new n(e[0].s+1);return u[e[0].s]=1,{t:u,l:1}}e.sort(function(t,n){return t.f-n.f}),e.push({s:-1,f:25001});var h=e[0],f=e[1],c=0,l=1,p=2;for(e[0]={s:-1,f:h.f+f.f,l:h,r:f};l!=s-1;)h=e[e[c].f<e[p].f?c++:p++],f=e[c!=l&&e[c].f<e[p].f?c++:p++],e[l++]={s:-1,f:h.f+f.f,l:h,r:f};var v=a[0].s;for(o=1;o<s;++o)a[o].s>v&&(v=a[o].s);var d=new r(v+1),g=q(e[l-1],d,0);if(g>i){o=0;var y=0,m=g-i,b=1<<m;for(a.sort(function(t,n){return d[n.s]-d[t.s]||t.f-n.f});o<s;++o){var w=a[o].s;if(!(d[w]>i))break;y+=b-(1<<g-d[w]),d[w]=i}for(y>>=m;y>0;){var x=a[o].s;d[x]<i?y-=1<<i-d[x]++-1:++o}for(;o>=0&&y;--o){var z=a[o].s;d[z]==i&&(--d[z],++y)}g=i}return{t:new n(d),l:g}},q=function(t,n,r){return-1==t.s?Math.max(q(t.l,n,r+1),q(t.r,n,r+1)):n[t.s]=r},O=function(t){for(var n=t.length;n&&!t[--n];);for(var i=new r(++n),e=0,o=t[0],s=1,a=function(t){i[e++]=t},u=1;u<=n;++u)if(t[u]==o&&u!=n)++s;else{if(!o&&s>2){for(;s>138;s-=138)a(32754);s>2&&(a(s>10?s-11<<5|28690:s-3<<5|12305),s=0)}else if(s>3){for(a(o),--s;s>6;s-=6)a(8304);s>2&&(a(s-3<<5|8208),s=0)}for(;s--;)a(o);s=1,o=t[u]}return{c:i.subarray(0,e),n:n}},G=function(t,n){for(var r=0,i=0;i<n.length;++i)r+=t[i]*n[i];return r},L=function(t,n,r){var i=r.length,e=T(n+2);t[e]=255&i,t[e+1]=i>>8,t[e+2]=255^t[e],t[e+3]=255^t[e+1];for(var o=0;o<i;++o)t[e+o+4]=r[o];return 8*(e+4+i)},B=function(t,n,i,a,u,h,f,c,l,p,v){F(n,v++,i),++u[256];for(var d=Z(u,15),g=d.t,x=d.l,k=Z(h,15),M=k.t,S=k.l,A=O(g),T=A.c,D=A.n,C=O(M),I=C.c,U=C.n,q=new r(19),B=0;B<T.length;++B)++q[31&T[B]];for(B=0;B<I.length;++B)++q[31&I[B]];for(var H=Z(q,7),j=H.t,N=H.l,P=19;P>4&&!j[s[P-1]];--P);var V,Y,J,K,Q=p+5<<3,R=G(u,m)+G(h,b)+f,W=G(u,g)+G(h,M)+f+14+3*P+G(q,j)+2*q[16]+3*q[17]+7*q[18];if(l>=0&&Q<=R&&Q<=W)return L(n,v,t.subarray(l,l+p));if(F(n,v,1+(W<R)),v+=2,W<R){V=y(g,x,0),Y=g,J=y(M,S,0),K=M;var X=y(j,N,0);for(F(n,v,D-257),F(n,v+5,U-1),F(n,v+10,P-4),v+=14,B=0;B<P;++B)F(n,v+3*B,j[s[B]]);v+=3*P;for(var $=[T,I],_=0;_<2;++_){var tt=$[_];for(B=0;B<tt.length;++B)F(n,v,X[rt=31&tt[B]]),v+=j[rt],rt>15&&(F(n,v,tt[B]>>5&127),v+=tt[B]>>12)}}else V=w,Y=m,J=z,K=b;for(B=0;B<c;++B){var nt=a[B];if(nt>255){var rt;E(n,v,V[257+(rt=nt>>18&31)]),v+=Y[rt+257],rt>7&&(F(n,v,nt>>23&31),v+=e[rt]);var it=31&nt;E(n,v,J[it]),v+=K[it],it>3&&(E(n,v,nt>>5&8191),v+=o[it])}else E(n,v,V[nt]),v+=Y[nt]}return E(n,v,V[256]),v+Y[256]},H=new i([65540,131080,131088,131104,262176,1048704,1048832,2114560,2117632]),j=new n(0),N=function(t,s,a,u,h,c){var l=c.z||t.length,v=new n(u+l+5*(1+Math.ceil(l/7e3))+h),d=v.subarray(u,v.length-h),g=c.l,y=7&(c.r||0);if(s){y&&(d[0]=c.r>>3);for(var m=H[s-1],b=m>>13,w=8191&m,x=(1<<a)-1,z=c.p||new r(32768),k=c.h||new r(x+1),M=Math.ceil(a/3),S=2*M,A=function(n){return(t[n]^t[n+1]<<M^t[n+2]<<S)&x},C=new i(25e3),I=new r(288),U=new r(32),F=0,E=0,Z=c.i||0,q=0,O=c.w||0,G=0;Z+2<l;++Z){var j=A(Z),N=32767&Z,P=k[j];if(z[N]=P,k[j]=N,O<=Z){var V=l-Z;if((F>7e3||q>24576)&&(V>423||!g)){y=B(t,d,0,C,I,U,E,q,G,Z-G,y),q=F=E=0,G=Z;for(var Y=0;Y<286;++Y)I[Y]=0;for(Y=0;Y<30;++Y)U[Y]=0}var J=2,K=0,Q=w,R=N-P&32767;if(V>2&&j==A(Z-R))for(var W=Math.min(b,V)-1,X=Math.min(32767,Z),$=Math.min(258,V);R<=X&&--Q&&N!=P;){if(t[Z+J]==t[Z+J-R]){for(var _=0;_<$&&t[Z+_]==t[Z+_-R];++_);if(_>J){if(J=_,K=R,_>W)break;var tt=Math.min(R,_-2),nt=0;for(Y=0;Y<tt;++Y){var rt=Z-R+Y&32767,it=rt-z[rt]&32767;it>nt&&(nt=it,P=rt)}}}R+=(N=P)-(P=z[N])&32767}if(K){C[q++]=268435456|f[J]<<18|p[K];var et=31&f[J],ot=31&p[K];E+=e[et]+o[ot],++I[257+et],++U[ot],O=Z+J,++F}else C[q++]=t[Z],++I[t[Z]]}}for(Z=Math.max(Z,O);Z<l;++Z)C[q++]=t[Z],++I[t[Z]];y=B(t,d,g,C,I,U,E,q,G,Z-G,y),g||(c.r=7&y|d[y/8|0]<<3,y-=7,c.h=k,c.p=z,c.i=Z,c.w=O)}else{for(Z=c.w||0;Z<l+g;Z+=65535){var st=Z+65535;st>=l&&(d[y/8|0]=g,st=l),y=L(d,y+1,t.subarray(Z,st))}c.i=l}return D(v,0,u+T(y)+h)},P=function(){for(var t=new Int32Array(256),n=0;n<256;++n){for(var r=n,i=9;--i;)r=(1&r&&-306674912)^r>>>1;t[n]=r}return t}(),V=function(){var t=-1;return{p:function(n){for(var r=t,i=0;i<n.length;++i)r=P[255&r^n[i]]^r>>>8;t=r},d:function(){return~t}}},Y=function(){var t=1,n=0;return{p:function(r){for(var i=t,e=n,o=0|r.length,s=0;s!=o;){for(var a=Math.min(s+2655,o);s<a;++s)e+=i+=r[s];i=(65535&i)+15*(i>>16),e=(65535&e)+15*(e>>16)}t=i,n=e},d:function(){return(255&(t%=65521))<<24|(65280&t)<<8|(255&(n%=65521))<<8|n>>8}}},J=function(t,r,i,e,o){if(!o&&(o={l:1},r.dictionary)){var s=r.dictionary.subarray(-32768),a=new n(s.length+t.length);a.set(s),a.set(t,s.length),t=a,o.w=s.length}return N(t,null==r.level?6:r.level,null==r.mem?o.l?Math.ceil(1.5*Math.max(8,Math.min(13,Math.log(t.length)))):20:12+r.mem,i,e,o)},K=function(t,n){var r={};for(var i in t)r[i]=t[i];for(var i in n)r[i]=n[i];return r},Q=function(t,n,r){for(var i=t(),e=""+t,o=e.slice(e.indexOf("[")+1,e.lastIndexOf("]")).replace(/\s+/g,"").split(","),s=0;s<i.length;++s){var a=i[s],u=o[s];if("function"==typeof a){n+=";"+u+"=";var h=""+a;if(a.prototype)if(-1!=h.indexOf("[native code]")){var f=h.indexOf(" ",8)+1;n+=h.slice(f,h.indexOf("(",f))}else for(var c in n+=h,a.prototype)n+=";"+u+".prototype."+c+"="+a.prototype[c];else n+=h}else r[u]=a}return n},R=[],W=function(t){var n=[];for(var r in t)t[r].buffer&&n.push((t[r]=new t[r].constructor(t[r])).buffer);return n},X=function(n,r,i,e){if(!R[i]){for(var o="",s={},a=n.length-1,u=0;u<a;++u)o=Q(n[u],o,s);R[i]={c:Q(n[a],o,s),e:s}}var h=K({},R[i].e);return(0,t.default)(R[i].c+";onmessage=function(e){for(var k in e.data)self[k]=e.data[k];onmessage="+r+"}",i,h,W(h),e)},$=function(){return[n,r,i,e,o,s,h,l,x,k,v,C,y,M,S,A,T,D,I,U,Tt,et,ot]},_=function(){return[n,r,i,e,o,s,f,p,w,m,z,b,v,H,j,y,F,E,Z,q,O,G,L,B,T,D,N,J,kt,et]},tt=function(){return[pt,gt,lt,V,P]},nt=function(){return[vt,dt]},rt=function(){return[yt,lt,Y]},it=function(){return[mt]},et=function(t){return postMessage(t,[t.buffer])},ot=function(t){return t&&{out:t.size&&new n(t.size),dictionary:t.dictionary}},st=function(t,n,r,i,e,o){var s=X(r,i,e,function(t,n){s.terminate(),o(t,n)});return s.postMessage([t,n],n.consume?[t.buffer]:[]),function(){s.terminate()}},at=function(t){return t.ondata=function(t,n){return postMessage([t,n],[t.buffer])},function(n){n.data[0]?(t.push(n.data[0],n.data[1]),postMessage([n.data[0].length])):t.flush(n.data[1])}},ut=function(t,n,r,i,e,o,s){var a,u=X(t,i,e,function(t,r){t?(u.terminate(),n.ondata.call(n,t)):Array.isArray(r)?1==r.length?(n.queuedSize-=r[0],n.ondrain&&n.ondrain(r[0])):(r[1]&&u.terminate(),n.ondata.call(n,t,r[0],r[1])):s(r)});u.postMessage(r),n.queuedSize=0,n.push=function(t,r){n.ondata||I(5),a&&n.ondata(I(4,0,1),null,!!r),n.queuedSize+=t.length,u.postMessage([t,a=r],t.buffer instanceof ArrayBuffer?[t.buffer]:[])},n.terminate=function(){u.terminate()},o&&(n.flush=function(t){u.postMessage([0,t])})},ht=function(t,n){return t[n]|t[n+1]<<8},ft=function(t,n){return(t[n]|t[n+1]<<8|t[n+2]<<16|t[n+3]<<24)>>>0},ct=function(t,n){return ft(t,n)+4294967296*ft(t,n+4)},lt=function(t,n,r){for(;r;++n)t[n]=r,r>>>=8},pt=function(t,n){var r=n.filename;if(t[0]=31,t[1]=139,t[2]=8,t[8]=n.level<2?4:9==n.level?2:0,t[9]=3,0!=n.mtime&&lt(t,4,Math.floor(new Date(n.mtime||Date.now())/1e3)),r){t[3]=8;for(var i=0;i<=r.length;++i)t[i+10]=r.charCodeAt(i)}},vt=function(t){31==t[0]&&139==t[1]&&8==t[2]||I(6,"invalid gzip data");var n=t[3],r=10;4&n&&(r+=2+(t[10]|t[11]<<8));for(var i=(n>>3&1)+(n>>4&1);i>0;i-=!t[r++]);return r+(2&n)},dt=function(t){var n=t.length;return(t[n-4]|t[n-3]<<8|t[n-2]<<16|t[n-1]<<24)>>>0},gt=function(t){return 10+(t.filename?t.filename.length+1:0)},yt=function(t,n){var r=n.level,i=0==r?0:r<6?1:9==r?3:2;if(t[0]=120,t[1]=i<<6|(n.dictionary&&32),t[1]|=31-(t[0]<<8|t[1])%31,n.dictionary){var e=Y();e.p(n.dictionary),lt(t,2,e.d())}},mt=function(t,n){return(8!=(15&t[0])||t[0]>>4>7||(t[0]<<8|t[1])%31)&&I(6,"invalid zlib data"),(t[1]>>5&1)==+!n&&I(6,"invalid zlib data: "+(32&t[1]?"need":"unexpected")+" dictionary"),2+(t[1]>>3&4)};function bt(t,n){return"function"==typeof t&&(n=t,t={}),this.ondata=n,t}var wt=function(){function t(t,r){if("function"==typeof t&&(r=t,t={}),this.ondata=r,this.o=t||{},this.s={l:0,i:32768,w:32768,z:32768},this.b=new n(98304),this.o.dictionary){var i=this.o.dictionary.subarray(-32768);this.b.set(i,32768-i.length),this.s.i=32768-i.length}}return t.prototype.p=function(t,n){this.ondata(J(t,this.o,0,0,this.s),n)},t.prototype.push=function(t,r){this.ondata||I(5),this.s.l&&I(4);var i=t.length+this.s.z;if(i>this.b.length){if(i>2*this.b.length-32768){var e=new n(-32768&i);e.set(this.b.subarray(0,this.s.z)),this.b=e}var o=this.b.length-this.s.z;this.b.set(t.subarray(0,o),this.s.z),this.s.z=this.b.length,this.p(this.b,!1),this.b.set(this.b.subarray(-32768)),this.b.set(t.subarray(o),32768),this.s.z=t.length-o+32768,this.s.i=32766,this.s.w=32768}else this.b.set(t,this.s.z),this.s.z+=t.length;this.s.l=1&r,(this.s.z>this.s.w+8191||r)&&(this.p(this.b,r||!1),this.s.w=this.s.i,this.s.i-=2),r&&(this.s=this.o={},this.b=j)},t.prototype.flush=function(t){if(this.ondata||I(5),this.s.l&&I(4),this.p(this.b,!1),this.s.w=this.s.i,this.s.i-=2,t){var r=new n(6);r[0]=this.s.r>>3;var i=L(r,this.s.r,j);this.s.r=0,this.ondata(r.subarray(0,i>>3),!1)}},t}();_e.Deflate=wt;var xt=function(){return function(t,n){ut([_,function(){return[at,wt]}],this,bt.call(this,t,n),function(t){var n=new wt(t.data);onmessage=at(n)},6,1)}}();function zt(t,n,r){return r||(r=n,n={}),"function"!=typeof r&&I(7),st(t,n,[_],function(t){return et(kt(t.data[0],t.data[1]))},0,r)}function kt(t,n){return J(t,n||{},0,0)}_e.AsyncDeflate=xt;var Mt=function(){function t(t,r){"function"==typeof t&&(r=t,t={}),this.ondata=r;var i=t&&t.dictionary&&t.dictionary.subarray(-32768);this.s={i:0,b:i?i.length:0},this.o=new n(32768),this.p=new n(0),i&&this.o.set(i)}return t.prototype.e=function(t){if(this.ondata||I(5),this.d&&I(4),this.p.length){if(t.length){var r=new n(this.p.length+t.length);r.set(this.p),r.set(t,this.p.length),this.p=r}}else this.p=t},t.prototype.c=function(t){this.s.i=+(this.d=t||!1);var n=this.s.b,r=U(this.p,this.s,this.o);this.ondata(D(r,n,this.s.b),this.d),this.o=D(r,this.s.b-32768),this.s.b=this.o.length,this.p=D(this.p,this.s.p/8|0),this.s.p&=7},t.prototype.push=function(t,n){this.e(t),this.c(n)},t}();_e.Inflate=Mt;var St=function(){return function(t,n){ut([$,function(){return[at,Mt]}],this,bt.call(this,t,n),function(t){var n=new Mt(t.data);onmessage=at(n)},7,0)}}();function At(t,n,r){return r||(r=n,n={}),"function"!=typeof r&&I(7),st(t,n,[$],function(t){return et(Tt(t.data[0],ot(t.data[1])))},1,r)}function Tt(t,n){return U(t,{i:2},n&&n.out,n&&n.dictionary)}_e.AsyncInflate=St;var Dt=function(){function t(t,n){this.c=V(),this.l=0,this.v=1,wt.call(this,t,n)}return t.prototype.push=function(t,n){this.c.p(t),this.l+=t.length,wt.prototype.push.call(this,t,n)},t.prototype.p=function(t,n){var r=J(t,this.o,this.v&&gt(this.o),n&&8,this.s);this.v&&(pt(r,this.o),this.v=0),n&&(lt(r,r.length-8,this.c.d()),lt(r,r.length-4,this.l)),this.ondata(r,n)},t.prototype.flush=function(t){wt.prototype.flush.call(this,t)},t}();_e.Gzip=Dt,_e.Compress=Dt;var Ct=function(){return function(t,n){ut([_,tt,function(){return[at,wt,Dt]}],this,bt.call(this,t,n),function(t){var n=new Dt(t.data);onmessage=at(n)},8,1)}}();function It(t,n,r){return r||(r=n,n={}),"function"!=typeof r&&I(7),st(t,n,[_,tt,function(){return[Ut]}],function(t){return et(Ut(t.data[0],t.data[1]))},2,r)}function Ut(t,n){n||(n={});var r=V(),i=t.length;r.p(t);var e=J(t,n,gt(n),8),o=e.length;return pt(e,n),lt(e,o-8,r.d()),lt(e,o-4,i),e}_e.AsyncGzip=Ct,_e.AsyncCompress=Ct;var Ft=function(){function t(t,n){this.v=1,this.r=0,Mt.call(this,t,n)}return t.prototype.push=function(t,r){if(Mt.prototype.e.call(this,t),this.r+=t.length,this.v){var i=this.p.subarray(this.v-1),e=i.length>3?vt(i):4;if(e>i.length){if(!r)return}else this.v>1&&this.onmember&&this.onmember(this.r-i.length);this.p=i.subarray(e),this.v=0}Mt.prototype.c.call(this,0),this.s.f&&!this.s.l?(this.v=T(this.s.p)+9,this.s={i:0},this.o=new n(0),this.push(new n(0),r)):r&&Mt.prototype.c.call(this,r)},t}();_e.Gunzip=Ft;var Et=function(){return function(t,n){var r=this;ut([$,nt,function(){return[at,Mt,Ft]}],this,bt.call(this,t,n),function(t){var n=new Ft(t.data);n.onmember=function(t){return postMessage(t)},onmessage=at(n)},9,0,function(t){return r.onmember&&r.onmember(t)})}}();function Zt(t,n,r){return r||(r=n,n={}),"function"!=typeof r&&I(7),st(t,n,[$,nt,function(){return[qt]}],function(t){return et(qt(t.data[0],t.data[1]))},3,r)}function qt(t,r){var i=vt(t);return i+8>t.length&&I(6,"invalid gzip data"),U(t.subarray(i,-8),{i:2},r&&r.out||new n(dt(t)),r&&r.dictionary)}_e.AsyncGunzip=Et;var Ot=function(){function t(t,n){this.c=Y(),this.v=1,wt.call(this,t,n)}return t.prototype.push=function(t,n){this.c.p(t),wt.prototype.push.call(this,t,n)},t.prototype.p=function(t,n){var r=J(t,this.o,this.v&&(this.o.dictionary?6:2),n&&4,this.s);this.v&&(yt(r,this.o),this.v=0),n&&lt(r,r.length-4,this.c.d()),this.ondata(r,n)},t.prototype.flush=function(t){wt.prototype.flush.call(this,t)},t}();_e.Zlib=Ot;var Gt=function(){return function(t,n){ut([_,rt,function(){return[at,wt,Ot]}],this,bt.call(this,t,n),function(t){var n=new Ot(t.data);onmessage=at(n)},10,1)}}();function Lt(t,n,r){return r||(r=n,n={}),"function"!=typeof r&&I(7),st(t,n,[_,rt,function(){return[Bt]}],function(t){return et(Bt(t.data[0],t.data[1]))},4,r)}function Bt(t,n){n||(n={});var r=Y();r.p(t);var i=J(t,n,n.dictionary?6:2,4);return yt(i,n),lt(i,i.length-4,r.d()),i}_e.AsyncZlib=Gt;var Ht=function(){function t(t,n){Mt.call(this,t,n),this.v=t&&t.dictionary?2:1}return t.prototype.push=function(t,n){if(Mt.prototype.e.call(this,t),this.v){if(this.p.length<6&&!n)return;this.p=this.p.subarray(mt(this.p,this.v-1)),this.v=0}n&&(this.p.length<4&&I(6,"invalid zlib data"),this.p=this.p.subarray(0,-4)),Mt.prototype.c.call(this,n)},t}();_e.Unzlib=Ht;var jt=function(){return function(t,n){ut([$,it,function(){return[at,Mt,Ht]}],this,bt.call(this,t,n),function(t){var n=new Ht(t.data);onmessage=at(n)},11,0)}}();function Nt(t,n,r){return r||(r=n,n={}),"function"!=typeof r&&I(7),st(t,n,[$,it,function(){return[Pt]}],function(t){return et(Pt(t.data[0],ot(t.data[1])))},5,r)}function Pt(t,n){return U(t.subarray(mt(t,n&&n.dictionary),-4),{i:2},n&&n.out,n&&n.dictionary)}_e.AsyncUnzlib=jt;var Vt=function(){function t(t,n){this.o=bt.call(this,t,n)||{},this.G=Ft,this.I=Mt,this.Z=Ht}return t.prototype.i=function(){var t=this;this.s.ondata=function(n,r){t.ondata(n,r)}},t.prototype.push=function(t,r){if(this.ondata||I(5),this.s)this.s.push(t,r);else{if(this.p&&this.p.length){var i=new n(this.p.length+t.length);i.set(this.p),i.set(t,this.p.length)}else this.p=t;this.p.length>2&&(this.s=31==this.p[0]&&139==this.p[1]&&8==this.p[2]?new this.G(this.o):8!=(15&this.p[0])||this.p[0]>>4>7||(this.p[0]<<8|this.p[1])%31?new this.I(this.o):new this.Z(this.o),this.i(),this.s.push(this.p,r),this.p=null)}},t}();_e.Decompress=Vt;var Yt=function(){function t(t,n){Vt.call(this,t,n),this.queuedSize=0,this.G=Et,this.I=St,this.Z=jt}return t.prototype.i=function(){var t=this;this.s.ondata=function(n,r,i){t.ondata(n,r,i)},this.s.ondrain=function(n){t.queuedSize-=n,t.ondrain&&t.ondrain(n)}},t.prototype.push=function(t,n){this.queuedSize+=t.length,Vt.prototype.push.call(this,t,n)},t}();function Jt(t,n,r){return r||(r=n,n={}),"function"!=typeof r&&I(7),31==t[0]&&139==t[1]&&8==t[2]?Zt(t,n,r):8!=(15&t[0])||t[0]>>4>7||(t[0]<<8|t[1])%31?At(t,n,r):Nt(t,n,r)}function Kt(t,n){return 31==t[0]&&139==t[1]&&8==t[2]?qt(t,n):8!=(15&t[0])||t[0]>>4>7||(t[0]<<8|t[1])%31?Tt(t,n):Pt(t,n)}_e.AsyncDecompress=Yt;var Qt=function(t,r,i,e){for(var o in t){var s=t[o],a=r+o,u=e;Array.isArray(s)&&(u=K(e,s[1]),s=s[0]),ArrayBuffer.isView(s)?i[a]=[s,u]:(i[a+="/"]=[new n(0),u],Qt(s,a,i,e))}},Rt="undefined"!=typeof TextEncoder&&new TextEncoder,Wt="undefined"!=typeof TextDecoder&&new TextDecoder,Xt=0;try{Wt.decode(j,{stream:!0}),Xt=1}catch(t){}var $t=function(t){for(var n="",r=0;;){var i=t[r++],e=(i>127)+(i>223)+(i>239);if(r+e>t.length)return{s:n,r:D(t,r-1)};e?3==e?(i=((15&i)<<18|(63&t[r++])<<12|(63&t[r++])<<6|63&t[r++])-65536,n+=String.fromCharCode(55296|i>>10,56320|1023&i)):n+=String.fromCharCode(1&e?(31&i)<<6|63&t[r++]:(15&i)<<12|(63&t[r++])<<6|63&t[r++]):n+=String.fromCharCode(i)}},_t=function(){function t(t){this.ondata=t,Xt?this.t=new TextDecoder:this.p=j}return t.prototype.push=function(t,r){if(this.ondata||I(5),r=!!r,this.t)return this.ondata(this.t.decode(t,{stream:!0}),r),void(r&&(this.t.decode().length&&I(8),this.t=null));this.p||I(4);var i=new n(this.p.length+t.length);i.set(this.p),i.set(t,this.p.length);var e=$t(i),o=e.s,s=e.r;r?(s.length&&I(8),this.p=null):this.p=s,this.ondata(o,r)},t}();_e.DecodeUTF8=_t;var tn=function(){function t(t){this.ondata=t}return t.prototype.push=function(t,n){this.ondata||I(5),this.d&&I(4),this.ondata(nn(t),this.d=n||!1)},t}();function nn(t,r){if(r){for(var i=new n(t.length),e=0;e<t.length;++e)i[e]=t.charCodeAt(e);return i}if(Rt)return Rt.encode(t);var o=t.length,s=new n(t.length+(t.length>>1)),a=0,u=function(t){s[a++]=t};for(e=0;e<o;++e){if(a+5>s.length){var h=new n(a+8+(o-e<<1));h.set(s),s=h}var f=t.charCodeAt(e);f<128||r?u(f):f<2048?(u(192|f>>6),u(128|63&f)):f>55295&&f<57344?(u(240|(f=65536+(1047552&f)|1023&t.charCodeAt(++e))>>18),u(128|f>>12&63),u(128|f>>6&63),u(128|63&f)):(u(224|f>>12),u(128|f>>6&63),u(128|63&f))}return D(s,0,a)}function rn(t,n){if(n){for(var r="",i=0;i<t.length;i+=16384)r+=String.fromCharCode.apply(null,t.subarray(i,i+16384));return r}if(Wt)return Wt.decode(t);var e=$t(t),o=e.s;return(r=e.r).length&&I(8),o}_e.EncodeUTF8=tn;var en=function(t){return 1==t?3:t<6?2:9==t?1:0},on=function(t,n){return n+30+ht(t,n+26)+ht(t,n+28)},sn=function(t,n,r){var i=ht(t,n+28),e=ht(t,n+30),o=rn(t.subarray(n+46,n+46+i),!(2048&ht(t,n+8))),s=n+46+i,a=an(t,s,e,r,ft(t,n+20),ft(t,n+24),ft(t,n+42)),u=a[0],h=a[1],f=a[2];return[ht(t,n+10),u,h,o,s+e+ht(t,n+32),f]},an=function(t,n,r,i,e,o,s){var a=4294967295==e,u=4294967295==o,h=4294967295==s,f=n+r;if(i&&a+u+h){for(;n+4<f;n+=4+ht(t,n+2))if(1==ht(t,n))return[a?ct(t,n+4+8*u):e,u?ct(t,n+4):o,h?ct(t,n+4+8*(u+a)):s,1];i<2&&I(13)}return[e,o,s,0]},un=function(t){var n=0;if(t)for(var r in t){var i=t[r].length;i>65535&&I(9),n+=i+4}return n},hn=function(t,n,r,i,e,o,s,a){var u=i.length,h=r.extra,f=a&&a.length,c=un(h);lt(t,n,null!=s?33639248:67324752),n+=4,null!=s&&(t[n++]=20,t[n++]=r.os),t[n]=20,n+=2,t[n++]=r.flag<<1|(o<0&&8),t[n++]=e&&8,t[n++]=255&r.compression,t[n++]=r.compression>>8;var l=new Date(null==r.mtime?Date.now():r.mtime),p=l.getFullYear()-1980;if((p<0||p>119)&&I(10),lt(t,n,p<<25|l.getMonth()+1<<21|l.getDate()<<16|l.getHours()<<11|l.getMinutes()<<5|l.getSeconds()>>1),n+=4,-1!=o&&(lt(t,n,r.crc),lt(t,n+4,o<0?-o-2:o),lt(t,n+8,r.size)),lt(t,n+12,u),lt(t,n+14,c),n+=16,null!=s&&(lt(t,n,f),lt(t,n+6,r.attrs),lt(t,n+10,s),n+=14),t.set(i,n),n+=u,c)for(var v in h){var d=h[v],g=d.length;lt(t,n,+v),lt(t,n+2,g),t.set(d,n+4),n+=4+g}return f&&(t.set(a,n),n+=f),n},fn=function(t,n,r,i,e){lt(t,n,101010256),lt(t,n+8,r),lt(t,n+10,r),lt(t,n+12,i),lt(t,n+16,e)},cn=function(){function t(t){this.filename=t,this.c=V(),this.size=0,this.compression=0}return t.prototype.process=function(t,n){this.ondata(null,t,n)},t.prototype.push=function(t,n){this.ondata||I(5),this.c.p(t),this.size+=t.length,n&&(this.crc=this.c.d()),this.process(t,n||!1)},t}();_e.ZipPassThrough=cn;var ln=function(){function t(t,n){var r=this;n||(n={}),cn.call(this,t),this.d=new wt(n,function(t,n){r.ondata(null,t,n)}),this.compression=8,this.flag=en(n.level)}return t.prototype.process=function(t,n){try{this.d.push(t,n)}catch(t){this.ondata(t,null,n)}},t.prototype.push=function(t,n){cn.prototype.push.call(this,t,n)},t}();_e.ZipDeflate=ln;var pn=function(){function t(t,n){var r=this;n||(n={}),cn.call(this,t),this.d=new xt(n,function(t,n,i){r.ondata(t,n,i)}),this.compression=8,this.flag=en(n.level),this.terminate=this.d.terminate}return t.prototype.process=function(t,n){this.d.push(t,n)},t.prototype.push=function(t,n){cn.prototype.push.call(this,t,n)},t}();_e.AsyncZipDeflate=pn;var vn=function(){function t(t){this.ondata=t,this.u=[],this.d=1}return t.prototype.add=function(t){var r=this;if(this.ondata||I(5),2&this.d)this.ondata(I(4+8*(1&this.d),0,1),null,!1);else{var i=nn(t.filename),e=i.length,o=t.comment,s=o&&nn(o),a=e!=t.filename.length||s&&o.length!=s.length,u=e+un(t.extra)+30;e>65535&&this.ondata(I(11,0,1),null,!1);var h=new n(u);hn(h,0,t,i,a,-1);var f=[h],c=function(){for(var t=0,n=f;t<n.length;t++)r.ondata(null,n[t],!1);f=[]},l=this.d;this.d=0;var p=this.u.length,v=K(t,{f:i,u:a,o:s,t:function(){t.terminate&&t.terminate()},r:function(){if(c(),l){var t=r.u[p+1];t?t.r():r.d=1}l=1}}),d=0;t.ondata=function(i,e,o){if(i)r.ondata(i,e,o),r.terminate();else if(d+=e.length,f.push(e),o){var s=new n(16);lt(s,0,134695760),lt(s,4,t.crc),lt(s,8,d),lt(s,12,t.size),f.push(s),v.c=d,v.b=u+d+16,v.crc=t.crc,v.size=t.size,l&&v.r(),l=1}else l&&c()},this.u.push(v)}},t.prototype.end=function(){var t=this;2&this.d?this.ondata(I(4+8*(1&this.d),0,1),null,!0):(this.d?this.e():this.u.push({r:function(){1&t.d&&(t.u.splice(-1,1),t.e())},t:function(){}}),this.d=3)},t.prototype.e=function(){for(var t=0,r=0,i=0,e=0,o=this.u;e<o.length;e++)i+=46+(h=o[e]).f.length+un(h.extra)+(h.o?h.o.length:0);for(var s=new n(i+22),a=0,u=this.u;a<u.length;a++){var h;hn(s,t,h=u[a],h.f,h.u,-h.c-2,r,h.o),t+=46+h.f.length+un(h.extra)+(h.o?h.o.length:0),r+=h.b}fn(s,t,this.u.length,i,r),this.ondata(null,s,!0),this.d=2},t.prototype.terminate=function(){for(var t=0,n=this.u;t<n.length;t++)n[t].t();this.d=2},t}();function dn(t,r,i){i||(i=r,r={}),"function"!=typeof i&&I(7);var e={};Qt(t,"",e,r);var o=Object.keys(e),s=o.length,a=0,u=0,h=s,f=Array(s),c=[],l=function(){for(var t=0;t<c.length;++t)c[t]()},p=function(t,n){xn(function(){i(t,n)})};xn(function(){p=i});var v=function(){var t=new n(u+22),r=a,i=u-a;u=0;for(var e=0;e<h;++e){var o=f[e];try{var s=o.c.length;hn(t,u,o,o.f,o.u,s);var c=30+o.f.length+un(o.extra),l=u+c;t.set(o.c,l),hn(t,a,o,o.f,o.u,s,u,o.m),a+=16+c+(o.m?o.m.length:0),u=l+s}catch(t){return p(t,null)}}fn(t,a,f.length,i,r),p(null,t)};s||v();for(var d=function(t){var n=o[t],r=e[n],i=r[0],h=r[1],d=V(),g=i.length;d.p(i);var y=nn(n),m=y.length,b=h.comment,w=b&&nn(b),x=w&&w.length,z=un(h.extra),k=0==h.level?0:8,M=function(r,i){if(r)l(),p(r,null);else{var e=i.length;f[t]=K(h,{size:g,crc:d.d(),c:i,f:y,m:w,u:m!=n.length||w&&b.length!=x,compression:k}),a+=30+m+z+e,u+=76+2*(m+z)+(x||0)+e,--s||v()}};if(m>65535&&M(I(11,0,1),null),k)if(g<16e4)try{M(null,kt(i,h))}catch(t){M(t,null)}else c.push(zt(i,h,M));else M(null,i)},g=0;g<h;++g)d(g);return l}function gn(t,r){r||(r={});var i={},e=[];Qt(t,"",i,r);var o=0,s=0;for(var a in i){var u=i[a],h=u[0],f=u[1],c=0==f.level?0:8,l=(M=nn(a)).length,p=f.comment,v=p&&nn(p),d=v&&v.length,g=un(f.extra);l>65535&&I(11);var y=c?kt(h,f):h,m=y.length,b=V();b.p(h),e.push(K(f,{size:h.length,crc:b.d(),c:y,f:M,m:v,u:l!=a.length||v&&p.length!=d,o:o,compression:c})),o+=30+l+g+m,s+=76+2*(l+g)+(d||0)+m}for(var w=new n(s+22),x=o,z=s-o,k=0;k<e.length;++k){var M;hn(w,(M=e[k]).o,M,M.f,M.u,M.c.length);var S=30+M.f.length+un(M.extra);w.set(M.c,M.o+S),hn(w,o,M,M.f,M.u,M.c.length,M.o,M.m),o+=16+S+(M.m?M.m.length:0)}return fn(w,o,e.length,z,x),w}_e.Zip=vn;var yn=function(){function t(){}return t.prototype.push=function(t,n){this.ondata(null,t,n)},t.compression=0,t}();_e.UnzipPassThrough=yn;var mn=function(){function t(){var t=this;this.i=new Mt(function(n,r){t.ondata(null,n,r)})}return t.prototype.push=function(t,n){try{this.i.push(t,n)}catch(t){this.ondata(t,null,n)}},t.compression=8,t}();_e.UnzipInflate=mn;var bn=function(){function t(t,n){var r=this;n<32e4?this.i=new Mt(function(t,n){r.ondata(null,t,n)}):(this.i=new St(function(t,n,i){r.ondata(t,n,i)}),this.terminate=this.i.terminate)}return t.prototype.push=function(t,n){this.i.terminate&&(t=D(t,0)),this.i.push(t,n)},t.compression=8,t}();_e.AsyncUnzipInflate=bn;var wn=function(){function t(t){this.onfile=t,this.k=[],this.o={0:yn},this.p=j}return t.prototype.push=function(t,r){var i=this;if(this.onfile||I(5),this.p||I(4),this.c>0){var e=Math.min(this.c,t.length),o=t.subarray(0,e);if(this.c-=e,this.d?this.d.push(o,!this.c):this.k[0].push(o),(t=t.subarray(e)).length)return this.push(t,r)}else{var s=0,a=0,u=void 0,h=void 0;this.p.length?t.length?((h=new n(this.p.length+t.length)).set(this.p),h.set(t,this.p.length)):h=this.p:h=t;for(var f=h.length,c=this.c,l=c&&this.d,p=function(){var t=ft(h,a);if(67324752==t){s=1,u=a,v.d=null,v.c=0;var n=ht(h,a+6),r=ht(h,a+8),e=2048&n,o=8&n,l=ht(h,a+26),p=ht(h,a+28);if(f>a+30+l+p){var d=[];v.k.unshift(d),s=2;var g,y=ft(h,a+18),m=ft(h,a+22),b=rn(h.subarray(a+30,a+=30+l),!e),w=an(h,a,p,2,y,m,0),x=w[0],z=w[1];o&&(x=-1-w[3]),a+=p,v.c=x;var k={name:b,compression:r,start:function(){if(k.ondata||I(5),x){var t=i.o[r];t||k.ondata(I(14,"unknown compression type "+r,1),null,!1),(g=x<0?new t(b):new t(b,x,z)).ondata=function(t,n,r){k.ondata(t,n,r)};for(var n=0,e=d;n<e.length;n++)g.push(e[n],!1);i.k[0]==d&&i.c?i.d=g:g.push(j,!0)}else k.ondata(null,j,!0)},terminate:function(){g&&g.terminate&&g.terminate()}};x>=0&&(k.size=x,k.originalSize=z),v.onfile(k)}return"break"}if(c){if(134695760==t)return u=a+=12+(-2==c&&8),s=3,v.c=0,"break";if(33639248==t)return u=a-=4,s=3,v.c=0,"break"}},v=this;a<f-4&&"break"!==p();++a);if(this.p=j,c<0){var d=h.subarray(0,s?u-12-(-2==c&&8)-(134695760==ft(h,u-16)&&4):a);l?l.push(d,!!s):this.k[+(2==s)].push(d)}if(2&s)return this.push(h.subarray(a),r);this.p=h.subarray(a)}r&&(this.c&&I(13),this.p=null)},t.prototype.register=function(t){this.o[t.compression]=t},t}();_e.Unzip=wn;var xn="function"==typeof queueMicrotask?queueMicrotask:"function"==typeof setTimeout?setTimeout:function(t){t()};function zn(t,r,i){i||(i=r,r={}),"function"!=typeof i&&I(7);var e=[],o=function(){for(var t=0;t<e.length;++t)e[t]()},s={},a=function(t,n){xn(function(){i(t,n)})};xn(function(){a=i});for(var u=t.length-22;101010256!=ft(t,u);--u)if(!u||t.length-u>65558)return a(I(13,0,1),null),o;var h=ht(t,u+8);if(h){var f=h,c=ft(t,u+16),l=117853008==ft(t,u-20);if(l){var p=ft(t,u-12);(l=101075792==ft(t,p))&&(f=h=ft(t,p+32),c=ft(t,p+48))}for(var v=r&&r.filter,d=function(r){var i=sn(t,c,l),u=i[0],f=i[1],p=i[2],d=i[3],g=i[4],y=on(t,i[5]);c=g;var m=function(t,n){t?(o(),a(t,null)):(n&&(s[d]=n),--h||a(null,s))};if(!v||v({name:d,size:f,originalSize:p,compression:u}))if(u)if(8==u){var b=t.subarray(y,y+f);if(p<524288||f>.8*p)try{m(null,Tt(b,{out:new n(p)}))}catch(t){m(t,null)}else e.push(At(b,{size:p},m))}else m(I(14,"unknown compression type "+u,1),null);else m(null,D(t,y,y+f));else m(null,null)},g=0;g<f;++g)d()}else a(null,{});return o}function kn(t,r){for(var i={},e=t.length-22;101010256!=ft(t,e);--e)(!e||t.length-e>65558)&&I(13);var o=ht(t,e+8);if(!o)return{};var s=ft(t,e+16),a=117853008==ft(t,e-20);if(a){var u=ft(t,e-12);(a=101075792==ft(t,u))&&(o=ft(t,u+32),s=ft(t,u+48))}for(var h=r&&r.filter,f=0;f<o;++f){var c=sn(t,s,a),l=c[0],p=c[1],v=c[2],d=c[3],g=c[4],y=on(t,c[5]);s=g,h&&!h({name:d,size:p,originalSize:v,compression:l})||(l?8==l?i[d]=Tt(t.subarray(y,y+p),{out:new n(v)}):I(14,"unknown compression type "+l):i[d]=D(t,y,y+p))}return i}return _e});
;(function(){const factories={},cache={"fflate":globalThis.fflate,"./serverSession":{session:{user:null}}};function require(n){if(cache[n])return cache[n];const m={exports:{}};factories[n](m,m.exports,require);return cache[n]=m.exports;}
factories["./reports.ts"]=function(module,exports,require){
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.isNok = exports.filename = exports.xml = void 0;
exports.sheetName = sheetName;
exports.kmz = kmz;
exports.xlsx = xlsx;
exports.cableImageFiles = cableImageFiles;
const fflate_1 = require("fflate");
function sheetName(name) {
    const clean = name.replace(/[\\/:*?\[\]\u0000-\u001F]/g, '_').trim().replace(/^'+|'+$/g, '');
    const short = clean.slice(0, 31).replace(/[\uD800-\uDBFF]$/, '').replace(/'+$/, '');
    return !short ? 'Báo cáo' : short.toLowerCase() === 'history' ? 'History_' : short;
}
const xml = (v) => String(v).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
exports.xml = xml;
const filename = (s) => s.replace(/[\\/:*?"<>|\u0000-\u001F]/g, '_').trim().replace(/[. ]+$/g, '').slice(0, 70) || 'HP-Telecom';
exports.filename = filename;
const order = (rows) => [...rows].sort((a, b) => a.seq - b.seq || a.id - b.id);
const photoDate = (p) => String(p?.imageDate ?? p?.displayDate ?? p?.date ?? '').trim();
function kmz(name, rows) {
    const sorted = order(rows);
    const points = sorted.filter((p, i) => i === 0 || i === sorted.length - 1 || /MX|DP/i.test(p.note));
    if (!sorted.length)
        throw new Error('Chưa có vị trí để xuất KMZ.');
    const line = sorted.length > 1 ? `<Placemark><name>${(0, exports.xml)(name)}</name><Style><LineStyle><color>ffffff00</color><width>3</width></LineStyle></Style><LineString><tessellate>1</tessellate><coordinates>${sorted.map(p => `${p.lon},${p.lat},0`).join(' ')}</coordinates></LineString></Placemark>` : '';
    const marks = points.map(p => `<Placemark><name>${(0, exports.xml)(p === sorted[0] || p === sorted[sorted.length - 1] ? p.name : p.seq + ' - ' + p.name)}</name><description>${(0, exports.xml)(p.note + ' • ' + photoDate(p))}</description><Point><coordinates>${p.lon},${p.lat},0</coordinates></Point></Placemark>`).join('');
    return (0, fflate_1.zipSync)({ 'doc.kml': (0, fflate_1.strToU8)(`<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>${(0, exports.xml)(name)}</name><Folder><name>${(0, exports.xml)(name)}</name><open>1</open>${line}${marks}</Folder></Document></kml>`) });
}
function xlsx(name, rows, images, info) {
    if (info?.cableWork && !info._partition) {
        const result = nokWorkbook(name, rows, images, info);
        if (result)
            return result;
    }
    const offset = info?.cableWork ? 3 : 0;
    const sorted = order(rows);
    const files = {};
    const put = (path, content) => files[path] = (0, fflate_1.strToU8)('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' + content);
    const ns = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
    const rel = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
    const pkg = 'http://schemas.openxmlformats.org/package/2006/relationships';
    put('[Content_Types].xml', `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`);
    put('_rels/.rels', `<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>`);
    put('xl/workbook.xml', `<workbook xmlns="${ns}" xmlns:r="${rel}"><sheets><sheet name="${(0, exports.xml)(sheetName(name))}" sheetId="1" r:id="rId1"/></sheets></workbook>`);
    put('xl/_rels/workbook.xml.rels', `<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="${rel}/styles" Target="styles.xml"/></Relationships>`);
    put('xl/styles.xml', `<styleSheet xmlns="${ns}"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="18"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FF000000"/></left><right style="thin"><color rgb="FF000000"/></right><top style="thin"><color rgb="FF000000"/></top><bottom style="thin"><color rgb="FF000000"/></bottom></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf fontId="1" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf fontId="0" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`);
    const cell = (col, row, value) => `<c r="${col}${row}" s="${offset ? (row === 1 ? 1 : 2) : 0}" t="inlineStr"><is><t xml:space="preserve">${(0, exports.xml)(value)}</t></is></c>`;
    const header = offset ? ['STT', 'Địa chỉ', 'Tên vị trí', 'Hình ảnh', 'Mô tả', 'Kiến nghị / Giải pháp'] : ['STT', 'Hình ảnh', 'Tên vị trí', 'Lat', 'Long', 'Ghi chú', 'Ngày chụp'];
    const routeName = info?.routeName || name;
    const days = [...new Set(sorted.map(p => photoDate(p).split(' ')[0]).filter(Boolean))].sort((a, b) => { const key = (d) => d.split('/').reverse().join('-'); return key(a).localeCompare(key(b)); })[0] || '';
    const title = 'Báo cáo ' + ({ maintenance: 'bảo dưỡng', inspection: 'kiểm tra' }[info?.workType] || 'tuyến cáp') + ' ' + routeName;
    const meta = offset ? [[title, '', '', '', '', ''], ['Team / Đội', '', info?.team || '', 'Người thực hiện', '', info?.reporter || ''], ['Tên tuyến', '', routeName, '', '', ''], ['Ngày tuần tuyến', '', info?.firstDate ?? days, 'Tổng chiều dài tuyến (km)', '', info?.lengthKm ?? '']].map((values, i) => `<row r="${i + 1}" ht="${[54, 28, 28, 32][i]}" customHeight="1">${values.map((v, j) => cell(String.fromCharCode(65 + j), i + 1, v)).join('')}</row>`).join('') : `<row r="1">${cell('A', 1, name)}</row>`;
    const merges = offset ? '<mergeCells count="7">' + ['A1:F1', 'A2:B2', 'D2:E2', 'A3:B3', 'D3:E3', 'A4:B4', 'D4:E4'].map(ref => `<mergeCell ref="${ref}"/>`).join('') + '</mergeCells>' : '';
    const legacyData = sorted.map((p, i) => `<row r="${i + 3 + offset}" ht="190" customHeight="1"><c r="A${i + 3 + offset}"><v>${p.seq}</v></c>${cell('C', i + 3 + offset, p.name)}<c r="D${i + 3 + offset}"><v>${p.lat}</v></c><c r="E${i + 3 + offset}"><v>${p.lon}</v></c>${cell('F', i + 3 + offset, p.note)}${cell('G', i + 3 + offset, photoDate(p))}</row>`).join('');
    const sizes = sorted.map(p => { const b = images[p.file]; let w = 320, h = 400; if (b) {
        if (b[0] === 137 && b[1] === 80) {
            const n = (i) => b[i] * 16777216 + b[i + 1] * 65536 + b[i + 2] * 256 + b[i + 3];
            w = n(16);
            h = n(20);
        }
        else {
            for (let i = 2; i + 9 < b.length;) {
                if (b[i] !== 255)
                    break;
                const marker = b[i + 1], len = b[i + 2] * 256 + b[i + 3];
                if ([192, 193, 194].includes(marker)) {
                    h = b[i + 5] * 256 + b[i + 6];
                    w = b[i + 7] * 256 + b[i + 8];
                    break;
                }
                if (len < 2)
                    break;
                i += len + 2;
            }
        }
    } const height = Math.min(378, 200 * h / Math.max(1, w)), width = Math.min(200, height * w / Math.max(1, h)); return { width, height }; });
    const data = offset ? sorted.map((p, i) => { const r = i + 6, q = info?.points?.[p.seq] || {}; return `<row r="${r}" ht="${sizes[i].height * 0.75}" customHeight="1">${[p.seq, q.address || '', p.name, '', q.description ?? p.note, [q.assessment, q.proposal].filter(Boolean).join(' • ')].map((v, j) => cell(String.fromCharCode(65 + j), r, v)).join('')}</row>`; }).join('') : legacyData;
    const cols = offset ? [8, 24, 25, 28, 28, 28].map((width, i) => `<col min="${i + 1}" max="${i + 1}" width="${width}" customWidth="1"/>`).join('') : '<col min="1" max="1" width="8" customWidth="1"/><col min="2" max="2" width="34" customWidth="1"/><col min="3" max="3" width="25" customWidth="1"/><col min="4" max="5" width="18" customWidth="1"/><col min="6" max="6" width="35" customWidth="1"/><col min="7" max="7" width="25" customWidth="1"/>';
    put('xl/worksheets/sheet1.xml', `<worksheet xmlns="${ns}" xmlns:r="${rel}"><sheetViews><sheetView workbookViewId="0" showGridLines="0"/></sheetViews><cols>${cols}</cols><sheetData>${meta}<row r="${2 + offset}" ht="42" customHeight="1">${header.map((h, i) => cell(String.fromCharCode(65 + i), 2 + offset, h)).join('')}</row>${data}</sheetData>${offset ? `<autoFilter ref="A5:F${sorted.length + 5}"/>` : ""}${merges}<drawing r:id="rId1"/></worksheet>`);
    put('xl/worksheets/_rels/sheet1.xml.rels', `<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/drawing" Target="../drawings/drawing1.xml"/></Relationships>`);
    // Fixed portrait canvas matches the stamped photo preview (width 320, height 400).
    put('xl/drawings/drawing1.xml', `<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${rel}">${sorted.map((p, i) => `<xdr:oneCellAnchor><xdr:from><xdr:col>${offset ? 3 : 1}</xdr:col><xdr:colOff>${offset ? Math.round((200 - sizes[i].width) / 2 * 9525) : 20000}</xdr:colOff><xdr:row>${i + 2 + offset}</xdr:row><xdr:rowOff>${offset ? 0 : 20000}</xdr:rowOff></xdr:from><xdr:ext cx="${offset ? Math.round(sizes[i].width * 9525) : 1905000}" cy="${offset ? Math.round(sizes[i].height * 9525) : 2381250}"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i + 1}" name="Ảnh ${p.seq}"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${offset ? Math.round(sizes[i].width * 9525) : 1905000}" cy="${offset ? Math.round(sizes[i].height * 9525) : 2381250}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>`).join('')}</xdr:wsDr>`);
    put('xl/drawings/_rels/drawing1.xml.rels', `<Relationships xmlns="${pkg}">${sorted.map((p, i) => `<Relationship Id="rId${i + 1}" Type="${rel}/image" Target="../media/image${i + 1}.jpg"/>`).join('')}</Relationships>`);
    sorted.forEach((p, i) => { if (!images[p.file])
        throw new Error('Không đọc được ảnh ' + p.seq); files[`xl/media/image${i + 1}.jpg`] = images[p.file]; });
    if (offset) {
        files['xl/styles.xml'] = (0, fflate_1.strToU8)((0, fflate_1.strFromU8)(files['xl/styles.xml']).replace('fills count="2"', 'fills count="3"').replace('</fills>', '<fill><patternFill patternType="solid"><fgColor rgb="FF255D91"/><bgColor indexed="64"/></patternFill></fill></fills>').replace('<xf fontId="1" borderId="1" applyBorder="1"', '<xf fillId="2" applyFill="1" fontId="1" borderId="1" applyBorder="1"').replace('cellXfs count="3"', 'cellXfs count="4"').replace('</cellXfs>', '<xf fontId="0" fillId="2" applyFill="1" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs>'));
        files['xl/worksheets/sheet1.xml'] = (0, fflate_1.strToU8)((0, fflate_1.strFromU8)(files['xl/worksheets/sheet1.xml']).replace(/<c r="([ABDE][234])" s="2"/g, '<c r="$1" s="3"'));
        files['xl/styles.xml'] = (0, fflate_1.strToU8)((0, fflate_1.strFromU8)(files['xl/styles.xml']).replace('fonts count="2"', 'fonts count="3"').replace('</fonts>', '<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>').replace('cellXfs count="4"', 'cellXfs count="6"').replace('</cellXfs>', [0, 2].map(fill => `<xf fontId="2" applyFont="1" fillId="${fill}" applyFill="1" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>`).join('') + '</cellXfs>'));
        files['xl/worksheets/sheet1.xml'] = (0, fflate_1.strToU8)((0, fflate_1.strFromU8)(files['xl/worksheets/sheet1.xml']).replace(/<c r="([A-F][2-5])" s="([23])"/g, (_, ref, style) => `<c r="${ref}" s="${style === '3' ? 5 : 4}"`));
    }
    return (0, fflate_1.zipSync)(files, { level: 1 });
}
const isNok = (q) => q?.assessment === 'NOK' || (!q?.assessment && /^NOK\b/i.test(q?.proposal || ''));
exports.isNok = isNok;
function cableImageFiles(info) { return Object.values(info?.points || {}).flatMap((q) => ['before', 'after'].map(k => q[k]?.file).filter(Boolean)); }
function nokWorkbook(name, rows, images, info) {
    const sorted = order(rows), nok = sorted.filter(p => (0, exports.isNok)(info.points?.[p.seq]));
    if (!nok.length)
        return null;
    const days = sorted.map(p => photoDate(p)).filter(Boolean).sort((a, b) => a.split('/').reverse().join('-').localeCompare(b.split('/').reverse().join('-')));
    const meta = { ...info, _partition: true, firstDate: days[0] || '' };
    const first = (0, fflate_1.unzipSync)(xlsx(name, sorted, images, meta));
    const expanded = [], points = {}, slots = [];
    for (const p of nok) {
        const q = info.points?.[p.seq] || {};
        for (const key of ['before', 'after']) {
            const pic = key === 'before' ? (q.before?.file ? q.before : p) : q.after;
            if (!pic?.file)
                continue;
            const seq = expanded.length + 1;
            slots.push({ row: nok.indexOf(p), col: key === 'before' ? 3 : 4 });
            expanded.push({ ...p, id: seq, seq, file: pic.file, name: p.name + (key === 'original' ? '' : key === 'before' ? ' • Trước sửa' : ' • Sau sửa'), date: photoDate(pic) || photoDate(p), imageDate: pic.imageDate });
            points[seq] = { ...q, description: key === 'original' ? (q.description ?? p.note) : key === 'before' ? 'Ảnh trước khi sửa' : 'Ảnh sau khi sửa', assessment: 'NOK', proposal: q.proposal || '' };
        }
    }
    const second = (0, fflate_1.unzipSync)(xlsx('Chưa đạt - Sửa chữa', expanded, images, { ...meta, points }));
    const rawSheet = (0, fflate_1.strFromU8)(second['xl/worksheets/sheet1.xml']);
    const heights = [...rawSheet.matchAll(/<row r="(\d+)" ht="([\d.]+)"/g)].filter(m => Number(m[1]) >= 6).map(m => Number(m[2]));
    const rowHeights = nok.map((p, i) => Math.max(54, ...slots.map((slot, j) => slot.row === i ? (heights[j] || 54) : 0)));
    const repairCell = (col, row, value) => `<c r="${col}${row}" s="2" t="inlineStr"><is><t>${(0, exports.xml)(value)}</t></is></c>`;
    const repairRows = nok.map((p, i) => { const q = info.points?.[p.seq] || {}, row = i + 6; return `<row r="${row}" ht="${rowHeights[i]}" customHeight="1">${[p.seq, q.address || '', p.name, '', '', q.proposal || ''].map((v, j) => repairCell(String.fromCharCode(65 + j), row, v)).join('')}</row>`; }).join('');
    second['xl/worksheets/sheet1.xml'] = (0, fflate_1.strToU8)(rawSheet.replace(/<row r="(?:[6-9]|[1-9]\d+)"[^>]*>.*?<\/row>/g, '').replace('</sheetData>', repairRows + '</sheetData>').replace('>Hình ảnh<', '>Hình ảnh trước<').replace('>Mô tả<', '>Hình ảnh sau<').replace(/<col min="5" max="5" width="24"/, '<col min="5" max="5" width="40"').replace(/<autoFilter ref="[^"]+"/, `<autoFilter ref="A5:F${nok.length + 5}"`));
    let slotIndex = 0;
    second['xl/drawings/drawing1.xml'] = (0, fflate_1.strToU8)((0, fflate_1.strFromU8)(second['xl/drawings/drawing1.xml']).replace(/<xdr:oneCellAnchor>.*?<\/xdr:oneCellAnchor>/g, anchor => { const slot = slots[slotIndex++]; return anchor.replace(/<xdr:col>\d+<\/xdr:col>/, `<xdr:col>${slot.col}</xdr:col>`).replace(/<xdr:row>\d+<\/xdr:row>/, `<xdr:row>${slot.row + 5}</xdr:row>`); }));
    const read = (p) => (0, fflate_1.strFromU8)(second[p]);
    first['xl/worksheets/sheet2.xml'] = second['xl/worksheets/sheet1.xml'];
    first['xl/worksheets/_rels/sheet2.xml.rels'] = (0, fflate_1.strToU8)(read('xl/worksheets/_rels/sheet1.xml.rels').replace('drawing1.xml', 'drawing2.xml'));
    first['xl/drawings/drawing2.xml'] = second['xl/drawings/drawing1.xml'];
    first['xl/drawings/_rels/drawing2.xml.rels'] = (0, fflate_1.strToU8)(read('xl/drawings/_rels/drawing1.xml.rels').replace(/media\/image/g, 'media/nok-image'));
    for (const [p, bytes] of Object.entries(second))
        if (p.startsWith('xl/media/'))
            first[p.replace('/image', '/nok-image')] = bytes;
    const edit = (p, fn) => first[p] = (0, fflate_1.strToU8)(fn((0, fflate_1.strFromU8)(first[p])));
    edit('xl/workbook.xml', s => s.replace('</sheets>', '<sheet name="Chưa đạt - Sửa chữa" sheetId="2" r:id="rId3"/></sheets>'));
    edit('xl/_rels/workbook.xml.rels', s => s.replace('</Relationships>', '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>'));
    edit('[Content_Types].xml', s => s.replace('</Types>', '<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/drawings/drawing2.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/></Types>'));
    return (0, fflate_1.zipSync)(first, { level: 1 });
}

};
factories["./incident.ts"]=function(module,exports,require){
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.isMaterialGroup = exports.relocationGroups = exports.groupNames = exports.materialNames = exports.cableTypes = void 0;
exports.orderedPhotoGroups = orderedPhotoGroups;
exports.orderedExcelPhotoGroups = orderedExcelPhotoGroups;
exports.imagePairs = imagePairs;
exports.appendReportPhoto = appendReportPhoto;
exports.emptyIncident = emptyIncident;
exports.emptyRelocation = emptyRelocation;
exports.separateRelocationCable = separateRelocationCable;
exports.commonRelocationCable = commonRelocationCable;
exports.numberValue = numberValue;
exports.cableLength = cableLength;
exports.recoveryText = recoveryText;
exports.solutionText = solutionText;
exports.materialSummary = materialSummary;
exports.validDate = validDate;
exports.handlingMinutes = handlingMinutes;
exports.reportWarnings = reportWarnings;
exports.incidentXlsx = incidentXlsx;
const fflate_1 = require("fflate");
// @ts-ignore Native Node tests need the extension; Metro supports it too.
const reports_ts_1 = require("./reports.ts");
exports.cableTypes = ['2FO', '4FO', '6FO', '8FO', '12FO', '24FO', '48FO', '96FO'];
exports.materialNames = ['Măng xông', 'Néo', 'Treo', 'Ty', 'Kẹp', 'Gông dự phòng', 'D12', 'Đai Inox', 'Biển báo', 'Vật tư khác'];
exports.groupNames = ['Hiện trường sự cố', 'Đầu số cáp', 'Chủng loại cáp', 'Măng xông / treo néo', 'Kết quả đo kiểm', 'Trong thi công'];
exports.relocationGroups = ['Trước di dời', 'Trong thi công', 'Sau hoàn thành'];
// Keep original indices for photo actions and pending captures; only change presentation.
function orderedPhotoGroups(groups) {
    const entries = groups.map((g, i) => ({ g, i }));
    const construction = entries.findIndex(({ g }) => g.name === 'Trong thi công');
    if (construction < 0 || !entries.some(({ g }) => g.name === 'Kết quả đo kiểm'))
        return entries;
    const [item] = entries.splice(construction, 1);
    entries.splice(entries.findIndex(({ g }) => g.name === 'Kết quả đo kiểm'), 0, item);
    return entries;
}
function orderedExcelPhotoGroups(groups) {
    const entries = orderedPhotoGroups(groups);
    const measurement = entries.filter(({ g }) => g.name === 'Kết quả đo kiểm');
    return [...entries.filter(({ g }) => g.name !== 'Kết quả đo kiểm'), ...measurement];
}
const isMaterialGroup = (g) => g.name === 'Măng xông / treo néo' || exports.relocationGroups.includes(g.name);
exports.isMaterialGroup = isMaterialGroup;
function imagePairs(items) { return Array.from({ length: Math.ceil(items.length / 2) }, (_, i) => items.slice(i * 2, i * 2 + 2)); }
function appendReportPhoto(group, photo, note = '') {
    const images = [...group.images, photo];
    if (!note.trim())
        return { ...group, images };
    const pairIndex = Math.floor(group.images.length / 2);
    const pairNotes = imagePairs(images).map((_, i) => group.pairNotes?.[i] ?? group.note);
    pairNotes[pairIndex] = [...new Set([pairNotes[pairIndex], note].flatMap(s => s.split('\n')).map(s => s.trim()).filter(Boolean))].join('\n');
    return { ...group, images, pairNotes };
}
function emptyIncident() { return { location: '', distance: '', reporter: '', date: new Date().toLocaleDateString('en-GB'), received: '', restored: '', cause: '', solution: '', cable: '', start: '', end: '', materials: [], groups: exports.groupNames.map(name => ({ name, note: '', images: [] })) }; }
function emptyRelocation() { return { ...emptyIncident(), relocation: true, relocationLength: '', recovery: { enabled: true, length: '', cable: '' }, groups: exports.relocationGroups.map(name => ({ name, note: '', images: [] })) }; }
function separateRelocationCable(r) {
    const legacy = r;
    const recovery = typeof legacy.recovery === 'boolean' ? { enabled: legacy.recovery, length: legacy.recoveryLength ?? '', cable: legacy.recoveryCable ?? '' } : legacy.recovery;
    const normalized = { ...r, recovery };
    if (r.splitRelocation)
        return normalized;
    const reuse = !!recovery?.enabled;
    if (!r.relocation)
        return { ...normalized, splitRelocation: true, newCableEnabled: !reuse,
            recovery: { enabled: reuse, length: recovery?.length ?? '', cable: recovery?.cable ?? '' } };
    return { ...r, splitRelocation: true, newCableEnabled: !reuse && !!(r.cable || r.start || r.end || r.relocationLength),
        recovery: { enabled: reuse, length: reuse ? (r.relocationLength ?? '') : (recovery?.length ?? ''), cable: reuse ? r.cable : (recovery?.cable ?? '') },
        cable: reuse ? '' : r.cable, start: reuse ? '' : r.start, end: reuse ? '' : r.end, relocationLength: reuse ? '' : r.relocationLength };
}
function commonRelocationCable(r) {
    const v = separateRelocationCable({ ...r, materials: r.materials.map(m => m.name === 'Thẻ tên' ? { ...m, name: 'Biển báo' } : m) });
    if (!v.relocation || v.commonRelocationCable !== undefined)
        return v;
    const fresh = v.cable.trim(), reuse = v.recovery?.cable.trim() ?? '';
    const cable = fresh && reuse && fresh !== reuse ? '' : fresh || reuse;
    return { ...v, commonRelocationCable: cable };
}
function numberValue(s) { return /^\d+(?:[.,]\d+)?$/.test(s.trim()) && Number.isFinite(Number(s.replace(',', '.'))) ? Number(s.replace(',', '.')) : null; }
function cableLength(r) { if (r.splitRelocation) {
    if (!r.newCableEnabled)
        return null;
    if (r.relocation) {
        const a = numberValue(r.start), b = numberValue(r.end);
        if (!r.start.trim() && !r.end.trim())
            return numberValue(r.relocationLength ?? '');
        return a === null || b === null ? null : Math.abs(a - b);
    }
}
else if (!r.relocation && r.recovery?.enabled)
    return null; if (r.relocation && (r.recovery?.enabled || !r.start.trim() && !r.end.trim()))
    return numberValue(r.relocationLength ?? ''); const a = numberValue(r.start), b = numberValue(r.end); return a === null || b === null ? null : Math.abs(a - b); }
function recoveryText(r) {
    if (!r.recovery?.enabled)
        return '';
    const n = numberValue(r.relocation && !r.splitRelocation ? (r.relocationLength ?? '') : r.recovery.length);
    const cable = (r.relocation ? (r.commonRelocationCable ?? (!r.splitRelocation ? r.cable : r.recovery.cable)) : r.recovery.cable).trim();
    return 'Thu hồi kéo lại' + (n !== null && n > 0 ? ' ' + String(n).replace('.', ',') + 'm' : '') + (cable ? ' ' + cable : '');
}
function solutionText(r) { return r.relocation ? [recoveryText(r), r.solution.trim()].filter(Boolean).join(', ') : r.solution; }
function materialSummary(r) {
    if (r.recovery?.enabled && !r.relocation && (!r.splitRelocation || !r.newCableEnabled))
        return 'Không sử dụng cáp mới và vật tư';
    const parts = [], length = cableLength(r);
    const num = (n) => String(Number(n.toFixed(6))).replace('.', ',');
    const cable = (r.relocation ? r.commonRelocationCable ?? r.cable : r.cable).trim();
    if (length !== null && cable)
        parts.push(`${num(length)}m cáp ${cable}${r.relocation && !r.splitRelocation && r.recovery?.enabled ? ' (thu hồi, kéo lại)' : ''}`);
    for (const m of r.materials) {
        const quantity = numberValue(m.quantity);
        if (quantity === null || quantity <= 0 || !m.name.trim())
            continue;
        const name = m.name === 'Vật tư khác' ? m.type.trim() : (m.name === 'Thẻ tên' ? 'Biển báo' : m.name).toLocaleLowerCase('vi-VN') + (m.type.trim() ? ' ' + m.type.trim() : '');
        if (!name)
            continue;
        const unit = m.unit.trim();
        parts.push(`${num(quantity)}${unit && !['cái', 'bộ'].includes(unit.toLocaleLowerCase('vi-VN')) ? ' ' + unit : ''} ${name}`);
    }
    return parts.join(', ');
}
function validDate(value, time = false) { const m = value.trim().match(time ? /^(\d{1,2})\/(\d{1,2})\/(\d{4}) (\d{1,2}):(\d{2})$/ : /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/); if (!m)
    return false; const d = new Date(+m[3], +m[2] - 1, +m[1]); return d.getFullYear() === +m[3] && d.getMonth() === +m[2] - 1 && d.getDate() === +m[1] && (!time || (+m[4] < 24 && +m[5] < 60)); }
function handlingMinutes(received, restored) {
    if (!validDate(received, true) || !validDate(restored, true))
        return null;
    const stamp = (value) => { const [d, m, y, h, min] = value.trim().split(/[ /:]/).map(Number); return Date.UTC(y, m - 1, d, h, min); };
    const minutes = (stamp(restored) - stamp(received)) / 60000;
    return minutes >= 0 ? minutes : null;
}
function reportWarnings(r) {
    const warnings = [];
    if (r.relocation) {
        for (const [key, label] of [['location', 'Địa điểm'], ['reporter', 'Người thực hiện'], ['date', 'Ngày thực hiện'], ['cause', 'Nguyên nhân di dời'], ['solution', 'Nội dung di dời']])
            if (!r[key].trim())
                warnings.push('Thiếu ' + label.toLowerCase());
        if (r.date && !validDate(r.date))
            warnings.push('Ngày thực hiện chưa hợp lệ');
        if (r.splitRelocation && r.recovery?.enabled) {
            if (!exports.cableTypes.includes(r.commonRelocationCable ?? r.recovery.cable))
                warnings.push('Chưa chọn loại cáp thu hồi');
            if ((numberValue(r.recovery.length) ?? 0) <= 0)
                warnings.push('Số mét thu hồi chưa hợp lệ');
        }
        if ((!r.splitRelocation || r.newCableEnabled) && (r.cable || r.relocationLength || r.start || r.end || r.newCableEnabled)) {
            if (!exports.cableTypes.includes(r.commonRelocationCable ?? r.cable))
                warnings.push('Chưa chọn loại cáp');
            if ((cableLength(r) ?? 0) <= 0)
                warnings.push('Chiều dài di dời chưa hợp lệ');
        }
        r.materials.forEach((m, i) => { if (!m.name.trim() || !m.unit.trim() || (numberValue(m.quantity) ?? 0) <= 0 || m.name === 'Măng xông' && !exports.cableTypes.includes(m.type) || m.name === 'Vật tư khác' && !m.type.trim())
            warnings.push(`Vật tư ${i + 1}: cần bổ sung thông tin`); });
        r.groups.forEach(g => { if (!g.images.length)
            warnings.push(g.name + ': chưa có ảnh'); });
        if (r.pendingCapture)
            warnings.push('Có ảnh đang chờ GPS');
        return warnings;
    }
    if (r.pendingCapture)
        warnings.push('Có ảnh đang chờ ghi GPS, chưa đưa vào báo cáo');
    for (const [k, label] of Object.entries({ location: 'Địa điểm', reporter: 'Người báo cáo', date: 'Ngày báo cáo', received: 'Thời gian nhận', restored: 'Thời gian khôi phục', cause: 'Nguyên nhân' }))
        if (!String(r[k]).trim())
            warnings.push('Thiếu ' + label.toLowerCase());
    const newCableEnabled = r.splitRelocation ? !!r.newCableEnabled : !r.recovery?.enabled;
    if (!r.recovery?.enabled && !r.solution.trim())
        warnings.push('Thiếu phương án xử lý');
    if (newCableEnabled && !r.cable.trim())
        warnings.push('Thiếu chủng loại cáp mới');
    if (newCableEnabled && cableLength(r) === null)
        warnings.push('Chưa có hai đầu số cáp hợp lệ');
    if (newCableEnabled && r.cable && !exports.cableTypes.includes(r.cable))
        warnings.push('Chủng loại cáp chưa hợp lệ');
    if (!newCableEnabled && !r.recovery?.enabled)
        warnings.push('Chọn thu hồi kéo lại hoặc kéo cáp mới');
    if (r.date && !validDate(r.date))
        warnings.push('Ngày báo cáo chưa đúng ngày/tháng/năm');
    for (const key of ['received', 'restored'])
        if (r[key] && !validDate(r[key], true))
            warnings.push((key === 'received' ? 'Thời gian nhận' : 'Thời gian khôi phục') + ': cần ngày/tháng/năm giờ:phút');
    if (r.distance && numberValue(r.distance) === null)
        warnings.push('Khoảng cách chưa hợp lệ');
    if (r.recovery?.enabled) {
        if (!exports.cableTypes.includes(r.recovery.cable))
            warnings.push('Chưa chọn chủng loại cáp thu hồi');
        const length = numberValue(r.recovery.length);
        if (length === null || length <= 0)
            warnings.push('Chưa có số mét cáp thu hồi hợp lệ');
    }
    if (newCableEnabled)
        r.materials.forEach((m, i) => { if (!m.name.trim() || !m.unit.trim() || numberValue(m.quantity) === null || numberValue(m.quantity) === 0 || (m.name === 'Măng xông' && !exports.cableTypes.includes(m.type)) || (m.name === 'Vật tư khác' && !m.type.trim()))
            warnings.push(`Vật tư ${i + 1}: thiếu tên, loại, đơn vị hoặc số lượng hợp lệ`); });
    r.groups.forEach(g => { if (!g.images.length)
        warnings.push(g.name + ': chưa có ảnh'); });
    return warnings;
}
// Compact report uses the same proven OOXML packaging as the existing photo export.
function incidentXlsx(name, r, images, warnings = reportWarnings(r)) {
    const files = (0, fflate_1.unzipSync)((0, reports_ts_1.xlsx)(name, [], {}));
    const ns = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main', rel = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
    const rows = [], merges = [], anchors = [], rels = [];
    let row = 0, imageIndex = 0;
    const cell = (col, n, v, style = 4) => typeof v === 'number' ? `<c r="${col}${n}" s="${style}" t="n"><v>${v}</v></c>` : `<c r="${col}${n}" s="${style}" t="inlineStr"><is><t xml:space="preserve">${(0, reports_ts_1.xml)(v)}</t></is></c>`;
    const add = (parts, height = 24, style = 4, wide = false) => { if (!wide)
        parts = parts.map(([a, b, v]) => [String.fromCharCode(65 + (a.charCodeAt(0) - 65) * 2), String.fromCharCode(66 + (b.charCodeAt(0) - 65) * 2), v]); row++; rows.push(`<row r="${row}" ht="${height}" customHeight="1">${parts.map(([a, b, v]) => { if (a !== b)
        merges.push(`${a}${row}:${b}${row}`); return cell(a, row, v, style); }).join('')}</row>`); return row; };
    const band = (s) => add([['A', 'L', s]], 27, 1);
    band((r.relocation ? 'BÁO CÁO DI DỜI • ' : 'BÁO CÁO SỰ CỐ • ') + name);
    const info = (values) => {
        row++;
        const height = Math.min(409, Math.max(54, ...values.filter((_, i) => i % 2 === 1).map(v => String(v).split('\n').reduce((h, line) => h + Math.max(1, Math.ceil(line.length / 16)) * 15, 0))));
        rows.push(`<row r="${row}" ht="${height}" customHeight="1">${values.map((v, i) => { const start = Math.floor(i / 2) * 6 + (i % 2 ? 2 : 0), end = start + (i % 2 ? 3 : 1), col = String.fromCharCode(65 + start); merges.push(`${col}${row}:${String.fromCharCode(65 + end)}${row}`); return cell(col, row, v, i % 2 ? 3 : 1); }).join('')}</row>`);
    };
    if (r.relocation) {
        info(['Địa điểm', r.location, 'Ngày thực hiện', r.date, 'Người thực hiện', r.reporter, '', '']);
        add([['A', 'C', 'Tuyến'], ['D', 'L', name]], 36);
        add([['A', 'B', 'Nguyên nhân di dời'], ['C', 'L', r.cause]], Math.max(36, Math.ceil(r.cause.length / 65) * 15));
    }
    else {
        info(['Địa điểm sự cố', r.location, 'Khoảng cách đến trạm', r.distance + (r.distance ? ' km' : ''), 'Ngày báo cáo', r.date, 'Người báo cáo', r.reporter]);
        info(['Tuyến', name, 'Thời gian nhận thông tin', r.received, 'Thời gian khôi phục dịch vụ', r.restored, 'Tổng phút xử lý', handlingMinutes(r.received, r.restored) ?? '']);
        add([['A', 'B', 'Nguyên nhân'], ['C', 'L', r.cause]], Math.max(36, Math.ceil(r.cause.length / 65) * 15));
    }
    const solution = solutionText(r);
    add([['A', 'B', r.relocation ? 'Nội dung di dời' : 'Phương án xử lý'], ['C', 'L', solution]], Math.max(36, solution.split('\n').reduce((h, line) => h + Math.max(1, Math.ceil(line.length / 46)) * 15, 0)));
    const summary = materialSummary(r);
    add([['A', 'B', 'Vật tư'], ['C', 'L', summary]], Math.max(30, Math.ceil(summary.length / 46) * 15));
    for (const { g } of orderedExcelPhotoGroups(r.groups)) {
        if (!g.images.length)
            continue;
        band(g.name === 'Trong thi công' ? 'Hình ảnh thi công' : g.name);
        for (const [pairIndex, pair] of imagePairs(g.images).entries()) {
            const note = g.pairNotes?.[pairIndex] || g.note;
            const anchorRow = row;
            const rowHeight = Math.min(409, Math.max(54, ...pair.map(p => Math.ceil((Math.min(378, 304 * Math.max(1, p.height) / Math.max(1, p.width)) + 2) * 3 / 4)), Math.ceil((note.length + pair.reduce((s, p) => s + p.caption.length, 0)) / 22) * 15));
            add([['A', 'I', ''], ['J', 'O', [note, ...pair.map(p => p.caption)].filter(Boolean).join('\n')], ['P', 'X', '']], rowHeight, 3, true);
            pair.forEach((p, i) => {
                if (!images[p.file])
                    throw new Error('Không đọc được ảnh nhóm ' + g.name);
                const id = ++imageIndex, w = Math.max(1, p.width), h = Math.max(1, p.height), boxWidth = 304, boxHeight = Math.min(378, rowHeight * 4 / 3 - 2);
                const scale = Math.min(boxWidth / w, boxHeight / h), width = w * scale, height = h * scale;
                const cx = Math.round(width * 9525), cy = Math.round(height * 9525), dx = Math.round((1 + (boxWidth - width) / 2) * 9525), dy = Math.round((1 + (boxHeight - height) / 2) * 9525);
                files[`xl/media/image${id}.jpg`] = images[p.file];
                rels.push(`<Relationship Id="rId${id}" Type="${rel}/image" Target="../media/image${id}.jpg"/>`);
                anchors.push(`<xdr:oneCellAnchor><xdr:from><xdr:col>${i === 0 ? 0 : 15}</xdr:col><xdr:colOff>${dx}</xdr:colOff><xdr:row>${anchorRow}</xdr:row><xdr:rowOff>${dy}</xdr:rowOff></xdr:from><xdr:ext cx="${cx}" cy="${cy}"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${id}" name="Ảnh ${id}"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${id}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>`);
            });
        }
    }
    rows.forEach((text, index) => {
        const n = Number(text.match(/r="(\d+)"/)[1]);
        const cells = new Map([...text.matchAll(/<c\b[^>]*>.*?<\/c>/gs)].map(m => [m[0].match(/r="([A-X])/)[1], m[0]]));
        for (const merge of merges) {
            const [a, b] = merge.split(':');
            if (Number(a.slice(1)) !== n)
                continue;
            const style = Number(cells.get(a[0])?.match(/s="(\d+)"/)?.[1] ?? 0);
            for (let code = a.charCodeAt(0); code <= b.charCodeAt(0); code++) {
                const col = String.fromCharCode(code);
                if (!cells.has(col))
                    cells.set(col, cell(col, n, '', style));
            }
        }
        rows[index] = text.slice(0, text.indexOf('>') + 1) + [...cells.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([, v]) => v).join('') + '</row>';
    });
    const put = (p, s) => files[p] = (0, fflate_1.strToU8)('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' + s);
    put('xl/worksheets/sheet1.xml', `<worksheet xmlns="${ns}" xmlns:r="${rel}"><sheetViews><sheetView showGridLines="0" workbookViewId="0"/></sheetViews><cols><col min="1" max="24" width="4.140625" customWidth="1"/></cols><sheetData>${rows.join('')}</sheetData><mergeCells count="${merges.length}">${merges.map(m => `<mergeCell ref="${m}"/>`).join('')}</mergeCells><pageMargins left="0.25" right="0.25" top="0.4" bottom="0.4" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="portrait" fitToWidth="1" fitToHeight="0"/><drawing r:id="rId1"/></worksheet>`);
    put('xl/styles.xml', `<styleSheet xmlns="${ns}"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF194B5C"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FF000000"/></left><right style="thin"><color rgb="FF000000"/></right><top style="thin"><color rgb="FF000000"/></top><bottom style="thin"><color rgb="FF000000"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5">${[0, 1, 2, 3, 4].map(i => `<xf numFmtId="0" fontId="${i === 1 ? 1 : 0}" fillId="${i === 1 ? 2 : i === 2 ? 3 : 0}" borderId="${i === 0 ? 0 : 1}" xfId="0" applyAlignment="1"><alignment ${i === 1 || i === 3 ? 'horizontal="center" ' : ''}vertical="center" wrapText="1"/></xf>`).join('')}</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`);
    put('xl/drawings/drawing1.xml', `<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${rel}">${anchors.join('')}</xdr:wsDr>`);
    put('xl/drawings/_rels/drawing1.xml.rels', `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${rels.join('')}</Relationships>`);
    return (0, fflate_1.zipSync)(files, { level: 1 });
}

};
globalThis.HPExports={...require("./reports.ts"),...require("./incident.ts")};})();
// Monthly summaries use only report records already authorized by the server.
globalThis.HPSummary=(()=>{
const E=globalThis.HPExports,esc=E.xml;
function date(value){const s=String(value||'').trim();let m=s.match(/^(\d{2})\/(\d{2})\/(\d{4})(?:[ T](\d{1,2}):(\d{2}))?$/);if(m){const y=+m[3],mo=+m[2],d=+m[1],h=+(m[4]||0),mi=+(m[5]||0),t=new Date(Date.UTC(y,mo-1,d,h,mi));return t.getUTCFullYear()===y&&t.getUTCMonth()===mo-1&&t.getUTCDate()===d&&h<24&&mi<60?t:null;}m=s.match(/^(\d{4})-(\d{2})-(\d{2})(?:T.*)?$/);if(m)return date(`${m[3]}/${m[2]}/${m[1]}`);return null;}
function when(x){if(x.payload.module==='incident'&&Object.hasOwn(x.summary?.cells||{},'11')){const v=x.summary.cells['11'];return typeof v==='number'?new Date((v-25569)*86400000):null;}const r=x.payload.report||{};return date(x.payload.module==='relocation'?r.date:r.received)||date(r.date)||date(r.reportDate);}
function month(x){const d=when(x);return d?d.toISOString().slice(0,7):'';}
function province(r){if(r.province?.trim())return r.province.trim();const f=r.locationFix;if(!f)return '';if(f.region||f.province)return String(f.region||f.province).trim();const bits=String(r.location||'').split('\n')[0].split(',').map(s=>s.trim()).filter(s=>s&&!/^(việt nam|vietnam|vn)$/i.test(s));const last=bits.at(-1)||'';return bits.length>1&&!/\d|lat|long/i.test(last)?last.replace(/^(tỉnh|thành phố|tp\.)\s+/i,''):'';}
function n(v){return v!==null&&v!==undefined&&String(v).trim()!==''&&/^\d+(?:[.,]\d+)?$/.test(String(v).trim())?Number(String(v).replace(',','.')):null;}
function material(r,name,type){const found=(r.materials||[]).filter(m=>String(m.name).toLocaleLowerCase('vi')===name.toLocaleLowerCase('vi')&&(!type||String(m.type).toUpperCase()===type)).map(m=>n(m.quantity)).filter(x=>x!==null);return found.length?found.reduce((a,b)=>a+b,0):null;}
// Cabinet/ODF welding entries share the same summary columns.
function odfWelding(r,type){
 const normal=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/đ/gi,'d').toLowerCase().replace(/\s+/g,' ').trim();
 const quantities=(r.materials||[]).filter(m=>{
  const label=normal(m.name==='Vật tư khác'?m.type:m.name);
  if(!/^han(?: noi)? (?:tu(?: odf)?|odf)(?:\s|$)/.test(label))return false;
  const sizes=[...normal(label+' '+m.type).matchAll(/\b(96|48|24|12|8)\s*fo\b/g)].map(x=>x[1]+'FO');
  return new Set(sizes).size===1&&sizes[0]===type;
 }).map(m=>n(m.quantity)).filter(v=>v!==null);
 if(quantities.length)return quantities.reduce((a,b)=>a+b,0);
 // Fall back to explicitly counted welding work in the plan; do not double-count materials.
 const counts=[];
 for(const clause of normal(r.solution).split(/[;,\n]+/)){
  if(/\b(?:khong|chua)\s+(?:can\s+)?han\b/.test(clause))continue;
  const pattern=/\bhan(?:\s+(?:lai|noi))?\s+(?:(\d+)\s+(?:tu(?:\s+cap|\s+odf)?|odf)|(?:tu(?:\s+cap|\s+odf)?|odf)\s*[:x]?\s*(\d+)(?!\d|\s*fo))/g;
  const matches=[...clause.matchAll(pattern)];
  if(!matches.length)continue;
  const sizes=[...clause.matchAll(/\b(96|48|24|12|8)\s*fo\b/g)].map(m=>m[1]+'FO');
  const selected=sizes.length?(new Set(sizes).size===1?sizes[0]:''):String(r.cable||'').replace(/\s+/g,'').toUpperCase();
  if(selected===type)counts.push(...matches.map(m=>Number(m[1]||m[2])));
 }
 return counts.length?counts.reduce((a,b)=>a+b,0):null;
}
function coords(r){const f=r.locationFix||(r.groups||[]).filter(g=>/trước di dời|hiện trường/i.test(g.title||g.name||'')).flatMap(g=>g.images||[]).find(p=>p.gps)?.gps||{};return [typeof f.lat==='number'?f.lat:null,typeof (f.lon??f.long)==='number'?(f.lon??f.long):null];}
function totalLength(...values){const nums=values.filter(v=>v!==null&&Number.isFinite(v));return nums.length?Number(nums.reduce((a,b)=>a+b,0).toFixed(6)):null;}
// Older reports may store the recovered length only in the work description.
function recoveryFromPlan(value){
 const text=plain(value),amounts=[];
 for(const clause of text.split(/[;\n]+/)){
  if(/\b(?:khong|chua)\s+thu hoi/.test(clause))continue;
  for(const match of clause.matchAll(/\bthu hoi\s+(?:cap\s+)?(?:keo lai\s*)?(\d+(?:[.,]\d+)?)\s*m(?:et)?\b/g)){
   if(/keo lai/.test(match[0])||/keo lai/.test(clause.slice(match.index+match[0].length)))amounts.push(n(match[1]));
  }
 }
 return amounts.length?totalLength(...amounts):null;
}
function model(x){const p=x.payload,r={...E.emptyIncident(),...p.report},relo=p.module==='relocation';r.relocation=relo;if(typeof r.recovery==='boolean')r.recovery={enabled:r.recovery,length:r.recoveryLength||'',cable:r.recoveryCable||''};r.materials=(r.materials||[]).map(m=>m.name==='Thẻ tên'?{...m,name:'Biển báo'}:m);const oldReuse=!!r.recovery?.enabled&&!r.splitRelocation;const cable=oldReuse?null:E.cableLength(r);const recordedRecovery=r.recovery?.enabled?n(oldReuse?r.relocationLength:r.recovery.length):null;const recovery=recordedRecovery??recoveryFromPlan(r.solution),pull=totalLength(cable,recovery);return {p,r,relo,cable,recovery,pull,solution:E.solutionText(r),province:province(r),author:x.author||r.reporter||'',team:x.team||r.team||''};}
const incidentHeaders=["STT","Tuyến","Tỉnh/Thành","Chiều dài (km)","Ưu tiên","Link kết nối","Đầu mối","Vị trí đứt","Nguyên nhân sự cố","Phương án khắc phục","Nghiệm thu","Thời gian bắt đầu sự cố","Thời gian kết thức sự cố","Số phút","Kéo cáp quang (m)","Hàn nối măng xông (core hàn)","Lắp đặt biển báo (cái)","Lắp đặt bộ treo (bộ)","Lắp đặt bộ néo (bộ)","Lắp đặt bộ gá (1 ốp D12, 2 đai , 2 khoá inox) ","Lắp đặt Kẹp cáp 2 rãnh 3 lỗ (bộ)","Lắp đặt Phụ kiện ốp cột - 1 đế U, 2 dây đai, 2 khóa đai  (bộ)","Lắp đặt bộ đai ốp ( Móc J, đai, khoá đai) treo MX, cáp dự phòng","Hàn nối ODF 96FO","Hàn nối ODF 48FO","Hàn nối ODF 24FO","Hàn nối ODF 12FO","Hàn nối ODF 8FO","Chủng loại cáp quang","Khoảng vượt cáp quang","Khối lượng cáp quang (m)","Chủng loại măng xông","Khối lượng măng xông (bộ)","Vị trí Măng xông 1","Vị trí Măng xông 2","Thuộc HĐ VHKT","Đơn vị VHKT","Note  nội dung chú ý"];
const relocationHeaders=['STT','Tuyến','Tỉnh/Thành','Ngày lập báo cáo','Ngày thực hiện','Người lập','Team / Đội','Nguyên nhân di dời','Phương án','Chủng loại cáp','Đầu số 1','Đầu số 2','Kéo cáp quang (m)','Thu hồi kéo lại (m)','Măng xông (bộ)','Néo (bộ)','Treo (bộ)','Ty','Kẹp','Gông dự phòng','D12','Đai inox','Biển báo','Vật tư khác','Lat','Long','Khoảng vượt'];
const serial=d=>d?d.getTime()/86400000+25569:null;
function originalValues(x,i){const {p,r,relo,cable,recovery,pull,solution,province,author,team}=model(x),gps=coords(r),others=(r.materials||[]).filter(m=>!(relo?['Măng xông','Néo','Treo','Ty','Kẹp','Gông dự phòng','D12','Đai Inox','Biển báo']:['Măng xông','Néo','Treo']).some(name=>name.toLocaleLowerCase('vi')===String(m.name).trim().toLocaleLowerCase('vi'))).map(m=>[m.quantity,m.unit,m.name,m.type,m.note].filter(Boolean).join(' ')).join('; ');if(relo)return [i,p.name,province,serial(date(r.reportDate)),serial(date(r.date)),author,team,r.cause,solution,r.commonRelocationCable||r.cable,n(r.start),n(r.end),pull,recovery,material(r,'Măng xông'),material(r,'Néo'),material(r,'Treo'),...['Ty','Kẹp','Gông dự phòng','D12','Đai Inox','Biển báo'].map(name=>material(r,name)),others,...gps,r.cableSpan||''];return [i,p.name,province,n(r.routeLength),r.priority||'',r.connectionLink||'',r.contact||'',String(r.location||'').trim(),r.cause,solution,r.acceptance||'',serial(date(r.received)),serial(date(r.restored)),null,pull,n(r.spliceCores),material(r,'Biển báo'),material(r,'Treo'),material(r,'Néo'),material(r,'Bộ gá'),material(r,'Kẹp'),material(r,'Phụ kiện ốp cột'),material(r,'Bộ đai ốp'),...['96FO','48FO','24FO','12FO','8FO'].map(t=>odfWelding(r,t)),r.cable,r.cableSpan||'',cable,[...new Set((r.materials||[]).filter(m=>m.name==='Măng xông').map(m=>m.type).filter(Boolean))].join(', '),material(r,'Măng xông'),r.closureLocation||'',r.closureLocation2||'',r.maintenanceContract||'',r.maintenanceUnit||'',r.summaryNote||''];}
let catalog=[];
const routeKey=name=>String(name||'').normalize('NFC').trim().toUpperCase().replace(/[–—−]/g,'-').replace(/\s+/g,'');
function setCatalog(rows){catalog=rows||[];}
function routeInfo(name){const key=routeKey(name);let rows=catalog.filter(r=>routeKey(r.name)===key);if(!rows.length&&/^[A-Z0-9.]+-[A-Z0-9.]+$/.test(key)){const reversed=key.split('-').reverse().join('-');rows=catalog.filter(r=>routeKey(r.name)===reversed);}const unique=k=>[...new Set(rows.map(r=>r[k]).filter(v=>v!==null&&v!==''))];const lengths=unique('length'),links=unique('link');return {length:lengths.length===1?lengths[0]:null,link:links.length===1?links[0]:'',ambiguous:lengths.length>1||links.length>1};}
function plain(v){return String(v??'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/đ/gi,'d').toLowerCase().replace(/\s+/g,' ').trim();}
function coordNumber(v,limit){const n=typeof v==='number'?v:typeof v==='string'&&/^[+-]?\d+(?:[.,]\d+)?$/.test(v.trim())?Number(v.replace(',','.')):NaN;return Number.isFinite(n)&&Math.abs(n)<=limit?n:null;}
function coordText(text){text=String(text||'').replace('−','-').replace('º','°').replace('˚','°').replace('′',"'").replace('’',"'").replace('″','"').replace('“','"').replace('”','"');let m=text.match(/Lat(?:itude)?\s*[:=]?\s*([+-]?\d{1,2}(?:[.,]\d+)?)\s*(?:[|;,/\s]+|\s+)Long?(?:itude)?\s*[:=]?\s*([+-]?\d{2,3}(?:[.,]\d+)?)/i)||text.match(/([+-]?\d{1,2}[.,]\d{3,})\s*[,;|/\s]+\s*([+-]?\d{2,3}[.,]\d{3,})/);if(!m)return null;const lat=coordNumber(m[1],90),lon=coordNumber(m[2],180);return lat===null||lon===null?null:[lat,lon];}
function photoCoord(photo){const gps=photo?.gps||{};let lat=coordNumber(gps.lat??photo?.lat,90),lon=coordNumber(gps.lon??gps.long??photo?.lon??photo?.long,180);if(lat!==null&&lon!==null)return `${lat.toFixed(6)}, ${lon.toFixed(6)}`;const parsed=coordText([photo?.caption,photo?.note,photo?.description].filter(Boolean).join('\n'));return parsed?`${parsed[0].toFixed(6)}, ${parsed[1].toFixed(6)}`:'';}
function photoEntries(report,scene=false){
 const entries=[];
 for(const g of report?.groups||[]){
  const name=plain(g.name||g.title),dedicated=/\b(mx|mang xong|mangxong)\b/.test(name)&&!/treo|neo|vat tu/.test(name);
  if(scene&&!/hien truong|su co/.test(name))continue;
  const photos=g.images||[];
  photos.forEach((photo,index)=>{
   const pair=g.pairNotes?.[Math.floor(index/2)]||'',own=plain([photo.caption,photo.note,photo.description,photo.name,photo.title].filter(Boolean).join(' '));
   const note=plain(pair||g.note||'');
   const mx=/\b(mx|mx\d+|mang xong|mangxong)\b/;
   const support=/\b(treo|neo)\b/;
   const isMx=dedicated||mx.test(own)||(!support.test(own)&&mx.test(note));
   if(!scene&&!isMx) return;
   let coordinate=photoCoord(photo);
   if(!coordinate){
    const candidates=String(pair||g.note||'').split(/\r?\n/).map(coordText).filter(Boolean);
    // A two-image row with one coordinate is ambiguous; never duplicate it into both MX columns.
    const count=Math.min(2,photos.length-Math.floor(index/2)*2);
    const point=candidates.length===count?candidates[index%2]:null;
    if(point)coordinate=`${point[0].toFixed(6)}, ${point[1].toFixed(6)}`;
   }
   entries.push({photo,coordinate});
  });
 }
 return entries;
}
function photoLocationsByKind(report,kind='mx',scene=false){return photoEntries(report,scene).map(e=>e.coordinate).filter(Boolean);}
function mxPhotoLocations(report,scene=false){return photoLocationsByKind(report,'mx',scene).slice(0,2);}function values(x,i){const v=originalValues(x,i);if(x.payload.module==='incident'){v[2]=String(x.team||x.payload.report?.team||'').trim();const mx=mxPhotoLocations(x.payload.report);if(!v[33])v[33]=mx[0]||'';if(!v[34])v[34]=mx[1]||'';const info=routeInfo(x.payload.name);if(v[3]===null)v[3]=info.length;if(!v[5])v[5]=info.link;}if(x.payload.module==='incident')for(const [k,n]of Object.entries(x.summary?.cells||{})){if(+k>=0&&+k<38&&!([7,33,34].includes(+k)&&(n===null||n==='')))v[+k]=n;}if(x.payload.module==='incident'&&!Object.hasOwn(x.summary?.cells||{},'15')&&n(x.payload.report?.spliceCores)===null){const type=String(v[28]||'').trim().match(/^(2|4|12|24|48|96)\s*FO$/i),quantity=n(v[32]);v[15]=type&&quantity!==null&&Number.isInteger(quantity)?Number(type[1])*quantity:null;}return v;}
const materialHeaders=['STT','Vật tư','Chủng loại','Đơn vị','Tổng số lượng'];
function materialRows(records){
 const groups=new Map(),clean=v=>String(v??'').normalize('NFC').trim().replace(/\s+/g,' '),type=v=>clean(v).replace(/(\d+)\s*fo\b/ig,'$1FO');
 function add(name,kind,unit,quantity){name=clean(name);kind=type(kind);unit=clean(unit);if(!name||quantity===null||!Number.isFinite(quantity)||quantity<=0)return;const key=JSON.stringify([name,kind,unit].map(v=>v.toLocaleLowerCase('vi')));const old=groups.get(key);if(old)old[3]=Number((old[3]+quantity).toFixed(6));else groups.set(key,[name,kind,unit,quantity]);}
 for(const x of records){const {r,relo,cable,recovery}=model(x);add('Cáp kéo mới',relo?(r.commonRelocationCable||r.cable):r.cable,'m',cable);for(const m of r.materials||[])add(m.name,m.type,m.unit,n(m.quantity));}
 return [...groups.values()].sort((a,b)=>a[0].localeCompare(b[0],'vi')||a[1].localeCompare(b[1],'vi',{numeric:true})||a[2].localeCompare(b[2],'vi')).map((r,i)=>[i+1,...r]);
}
function col(i){let s='';for(i++;i;i=Math.floor((i-1)/26))s=String.fromCharCode(65+(i-1)%26)+s;return s;}
function xlsx(records,ym){if(!/^\d{4}-\d{2}$/.test(ym))throw Error('Chọn tháng tổng hợp.');const ns='http://schemas.openxmlformats.org/spreadsheetml/2006/main',rel='http://schemas.openxmlformats.org/officeDocument/2006/relationships',pkg='http://schemas.openxmlformats.org/package/2006/relationships',files={},put=(p,s)=>files[p]=fflate.strToU8(s);const groups=['incident','relocation'].map(type=>({type,rows:records.filter(x=>x.payload.module===type&&month(x)===ym).sort((a,b)=>when(a)-when(b)||String(a.payload.name).localeCompare(String(b.payload.name),'vi'))})).filter(g=>g.rows.length);if(!groups.length)throw Error('Không có báo cáo trong tháng đã chọn.');
const cell=(c,r,v,style=0,formula='')=>`<c r="${c}${r}" s="${style}"${typeof v==='number'||formula?'':' t="inlineStr"'}>${formula?`<f>${esc(formula)}</f>`:typeof v==='number'?`<v>${v}</v>`:`<is><t xml:space="preserve">${esc(v??'')}</t></is>`}</c>`;
groups.forEach(({type,rows},index)=>{const re=type==='relocation',heads=re?relocationHeaders:incidentHeaders,end=col(heads.length-1),title=`Tổng hợp ${re?'Di dời':'Sự cố'} tháng ${+ym.slice(5)}/${ym.slice(0,4)}`,dates=re?[3,4]:[11,12];let data=`<row r="1" ht="32" customHeight="1">${cell('A',1,title,1)}</row><row r="2" ht="26" customHeight="1">${re?cell('A',2,'Thông tin chưa có để trống. Tỉnh/Thành lấy từ địa chỉ định vị đã lưu; chưa xác định thì bổ sung trong báo cáo.'):heads.map((_,j)=>cell(col(j),2,j===14?'Phụ kiện cáp quang':j===28?'Vật tư':'',1)).join('')}</row><row r="3" ht="96" customHeight="1">${heads.map((h,i)=>cell(col(i),3,h,1)).join('')}</row>`;
rows.forEach((x,i)=>{const r=i+4,v=values(x,i+1);data+=`<row r="${r}" ht="55" customHeight="1">`+v.map((v,j)=>cell(col(j),r,v,dates.includes(j)?(re?2:3):0,!re&&j===13&&!Object.hasOwn(x.summary?.cells||{},'13')?`IF(OR(L${r}="",M${r}="",M${r}<L${r}),"",ROUND((M${r}-L${r})*1440,0))`:'')).join('')+'</row>';});const total=rows.length+4,sum=re?[12,13,14,15,16,17,18,19,20,21,22]:[13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,30,32];data+=`<row r="${total}" ht="26" customHeight="1">`+heads.map((_,j)=>cell(col(j),total,j===0?'Tổng':'',4,sum.includes(j)?`IF(COUNT(${col(j)}4:${col(j)}${total-1})=0,"",SUM(${col(j)}4:${col(j)}${total-1}))`:'')).join('')+'</row>';
put(`xl/worksheets/sheet${index+1}.xml`,`<worksheet xmlns="${ns}"><sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="3" topLeftCell="A4" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${heads.map((_,i)=>`<col min="${i+1}" max="${i+1}" width="${i===0?7:i===1?25:dates.includes(i)?24:[8,9,10,32,34].includes(i)?30:20}" customWidth="1"/>`).join('')}</cols><sheetData>${data}</sheetData><autoFilter ref="A3:${end}${total-1}"/><mergeCells count="${re?2:3}"><mergeCell ref="A1:${end}1"/>${re?`<mergeCell ref="A2:${end}2"/>`:`<mergeCell ref="O2:W2"/><mergeCell ref="AC2:AG2"/>`}</mergeCells></worksheet>`);});
const sheetNames=groups.map(g=>`T${+ym.slice(5)} - ${g.type==='incident'?'Sự cố':'Di dời'}`);
groups.forEach((g,i)=>{const index=groups.length+i+1,data=materialRows(g.rows),name=groups.length===1?'Tổng hợp vật tư':`Vật tư - ${g.type==='incident'?'Sự cố':'Di dời'}`;sheetNames.push(name);const sheetRows=`<row r="1" ht="32" customHeight="1">${cell('A',1,`Tổng hợp vật tư ${g.type==='incident'?'Sự cố':'Di dời'} tháng ${+ym.slice(5)}/${ym.slice(0,4)}`,1)}</row><row r="2" ht="40" customHeight="1">${cell('A',2,'Sheet chính: Kéo cáp quang đã cộng cáp mới và thu hồi kéo lại. Sheet vật tư chỉ tính cáp kéo mới; không đưa cáp thu hồi kéo lại vào vật tư.')}</row><row r="3" ht="30" customHeight="1">${materialHeaders.map((h,j)=>cell(col(j),3,h,1)).join('')}</row>`+(data.length?data.map((r,j)=>`<row r="${j+4}" ht="28" customHeight="1">${r.map((v,k)=>cell(col(k),j+4,v)).join('')}</row>`).join(''):`<row r="4">${cell('B',4,'Chưa có vật tư được ghi nhận.')}</row>`);put(`xl/worksheets/sheet${index}.xml`,`<worksheet xmlns="${ns}"><sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="3" topLeftCell="A4" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${[8,32,24,16,22].map((width,j)=>`<col min="${j+1}" max="${j+1}" width="${width}" customWidth="1"/>`).join('')}</cols><sheetData>${sheetRows}</sheetData>${data.length?`<autoFilter ref="A3:E${data.length+3}"/>`:''}<mergeCells count="2"><mergeCell ref="A1:E1"/><mergeCell ref="A2:E2"/></mergeCells></worksheet>`);});
put('xl/styles.xml',`<styleSheet xmlns="${ns}"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF255D91"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border>${['left','right','top','bottom'].map(x=>`<${x} style="thin"><color rgb="FF000000"/></${x}>`).join('')}</border></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5">${[0,0,14,22,0].map((num,i)=>`<xf numFmtId="${num}" fontId="${i===1||i===4?1:0}" fillId="${i===1||i===4?2:0}" borderId="0" xfId="0" applyBorder="1" applyFont="1" applyFill="1" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>`).join('')}</cellXfs></styleSheet>`);
put('xl/workbook.xml',`<workbook xmlns="${ns}" xmlns:r="${rel}"><sheets>${sheetNames.map((name,i)=>`<sheet name="${name}" sheetId="${i+1}" r:id="rId${i+1}"/>`).join('')}</sheets><calcPr fullCalcOnLoad="1"/></workbook>`);put('_rels/.rels',`<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>`);put('xl/_rels/workbook.xml.rels',`<Relationships xmlns="${pkg}">${sheetNames.map((_,i)=>`<Relationship Id="rId${i+1}" Type="${rel}/worksheet" Target="worksheets/sheet${i+1}.xml"/>`).join('')}<Relationship Id="styles" Type="${rel}/styles" Target="styles.xml"/></Relationships>`);put('[Content_Types].xml',`<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheetNames.map((_,i)=>`<Override PartName="/xl/worksheets/sheet${i+1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`);return fflate.zipSync(files);}
return {mxPhotoLocations,setCatalog,routeInfo,date,month,when,province,model,values,xlsx,incidentHeaders,relocationHeaders,materialHeaders,materialRows};})();

