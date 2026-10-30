// Quantum Signal relay v2: standalone or part of a cluster of interconnected servers.
// Settings come from environment variables or a .env file next to this script:
//   PORT=3000   HOST=0.0.0.0
//   SELF=http://this-server:3000        public address of THIS server
//   PEERS=http://b:3000,http://c:3000   the OTHER servers in the cluster
//   CLUSTER_KEY=long-random-secret      identical on every server (16+ characters)
// Servers hold only usernames, public keys, hashed tokens and ENCRYPTED messages.
const http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto');
try{for(const l of fs.readFileSync(path.join(__dirname,'.env'),'utf8').split('\n')){const m=l.match(/^\s*([A-Z_]+)\s*=\s*(.*?)\s*$/);if(m&&!(m[1] in process.env))process.env[m[1]]=m[2]}}catch(e){}
const PORT=+process.env.PORT||3000,HOST=process.env.HOST||'0.0.0.0';
const SELF=(process.env.SELF||'').replace(/\/$/,''),KEY=process.env.CLUSTER_KEY||'';
const PEERS=(process.env.PEERS||'').split(',').map(s=>s.trim().replace(/\/$/,'')).filter(s=>s&&s!==SELF);
if(PEERS.length&&KEY.length<16){console.error('CLUSTER_KEY (16+ characters) is required when PEERS is set');process.exit(1)}
const DB=process.env.DATA||'data.json',TTL=7*864e5,BOX_MAX=500,Q_MAX=20000;

let db={users:{},box:{},acked:{},outq:[]};
try{db=Object.assign(db,JSON.parse(fs.readFileSync(DB)))}catch(e){if(e.code!=='ENOENT')console.error('data file unreadable, starting empty:',e.message)}
const save=()=>{try{fs.writeFileSync(DB+'.tmp',JSON.stringify(db));fs.renameSync(DB+'.tmp',DB);return true}catch(e){console.error('save failed:',e.message);return false}};
let soon=null;const saveSoon=()=>{if(!soon)soon=setTimeout(()=>{soon=null;save()},300)};

class HttpErr extends Error{constructor(c,m){super(m);this.code=c}}
const sha=s=>crypto.createHash('sha256').update(String(s)).digest('hex');
const NAME=/^[a-z0-9_]{3,20}$/;
const has=u=>typeof u==='string'&&Object.hasOwn(db.users,u);
const str=(v,max)=>typeof v==='string'&&v.length>0&&v.length<=max;
const auth=(u,t)=>has(u)&&typeof t==='string'&&t.length>0&&crypto.timingSafeEqual(Buffer.from(sha(t)),Buffer.from(db.users[u].th));
const hits={},regs={};
const limited=(map,ip,max,win)=>{const n=Date.now(),h=(map[ip]||[]).filter(t=>n-t<win);h.push(n);map[ip]=h;return h.length>max};
const out=(r,c,o)=>{r.writeHead(c,{'content-type':'application/json','access-control-allow-origin':'*','access-control-allow-private-network':'true','access-control-allow-headers':'content-type,x-token','access-control-allow-methods':'GET,POST,OPTIONS'});r.end(JSON.stringify(o))};
const rawBody=(req,max)=>new Promise((ok,no)=>{let b='',big=false;
  req.on('data',d=>{if(big)return;b+=d;if(b.length>max)big=true});
  req.on('end',()=>big?no(new HttpErr(413,'too large')):ok(b));
  req.on('error',()=>no(new HttpErr(400,'bad request')))});
const waiters=new Map();
const notify=u=>{const s=waiters.get(u);if(s)for(const f of[...s])f()};
const parse=raw=>{try{return JSON.parse(raw||'{}')}catch(e){throw new HttpErr(400,'invalid json')}};

// ---- cluster plumbing ----
const up={};
const sign=(ts,b)=>crypto.createHmac('sha256',KEY).update(ts+'.'+b).digest('hex');
const peerOk=(req,raw)=>{const ts=req.headers['x-peer-ts'],sg=req.headers['x-peer-sig'];
  if(!KEY||!ts||!sg||Math.abs(Date.now()-Number(ts))>3e5)return false;
  const a=Buffer.from(sign(ts,raw)),b=Buffer.from(String(sg));return a.length===b.length&&crypto.timingSafeEqual(a,b)};
async function peerCall(peer,p,obj,ms=1500){const b=JSON.stringify(obj||{}),ts=String(Date.now()),ac=new AbortController(),t=setTimeout(()=>ac.abort(),ms);
  try{const r=await fetch(peer+p,{method:'POST',signal:ac.signal,headers:{'content-type':'application/json','x-peer-ts':ts,'x-peer-sig':sign(ts,b)},body:b});
    if(!r.ok)throw new Error('http '+r.status);return await r.json()}finally{clearTimeout(t)}}
const enqueue=(peer,p,obj)=>{if(db.outq.length<Q_MAX)db.outq.push({peer,p,obj,ts:Date.now()});saveSoon()};
async function fanout(p,obj,wait){const jobs=PEERS.map(async peer=>{try{await peerCall(peer,p,obj);up[peer]=true}catch(e){up[peer]=false;enqueue(peer,p,obj)}});if(wait)await Promise.all(jobs)}
let flushing=false;
async function flush(){if(flushing||!db.outq.length)return;flushing=true;
  try{const q=db.outq;db.outq=[];const keep=[];
    await Promise.all(q.map(async j=>{if(Date.now()-j.ts>TTL)return;try{await peerCall(j.peer,j.p,j.obj,3000);up[j.peer]=true}catch(e){up[j.peer]=false;keep.push(j)}}));
    db.outq=keep.concat(db.outq);saveSoon()}finally{flushing=false}}
function mergeUser(u,rec){if(!NAME.test(u||'')||!rec||!rec.pub||!str(rec.th,64)||typeof rec.at!=='number')return false;
  const cur=db.users[u];if(!cur||!Object.hasOwn(db.users,u)){db.users[u]={pub:rec.pub,th:rec.th,at:rec.at};return true}
  if(cur.th===rec.th)return false;
  const win=rec.at<cur.at||(rec.at===cur.at&&rec.th<cur.th);   // earliest registration wins
  if(win){db.users[u]={pub:rec.pub,th:rec.th,at:rec.at};return true}return false}
function addEnv(u,e){if(!NAME.test(u||'')||!e||!str(e.id,64)||db.acked[e.id]||!str(e.from,20)||!str(e.ct,60000)||!str(e.iv,40))return false;
  const b=db.box[u]=db.box[u]||[];if(b.some(x=>x.id===e.id)||b.length>=BOX_MAX)return false;
  b.push({id:e.id,from:e.from,ct:e.ct,iv:e.iv,ts:Number(e.ts)||Date.now()});b.sort((x,y)=>x.ts-y.ts);notify(u);return true}
function dropEnv(id){for(const u of Object.keys(db.box)){db.box[u]=db.box[u].filter(x=>x.id!==id);if(!db.box[u].length)delete db.box[u]}}
async function syncFrom(peer){try{const r=await peerCall(peer,'/peer/sync',{},8000);up[peer]=true;let ch=false;
  for(const u in r.users||{})ch=mergeUser(u,r.users[u])||ch;
  for(const id in r.acked||{})if(!db.acked[id]){db.acked[id]=Number(r.acked[id])||Date.now();dropEnv(id);ch=true}
  for(const u in r.box||{})for(const e of r.box[u])ch=addEnv(u,e)||ch;
  if(ch)saveSoon()}catch(e){up[peer]=false}}
if(PEERS.length){
  setInterval(flush,3000);
  setInterval(()=>PEERS.forEach(syncFrom),60000);
  setInterval(()=>PEERS.forEach(p=>peerCall(p,'/peer/has',{u:'x'},2000).then(()=>up[p]=true).catch(()=>up[p]=false)),15000);
  setTimeout(()=>PEERS.forEach(syncFrom),500);
}
setInterval(()=>{const n=Date.now();for(const u of Object.keys(db.box)){db.box[u]=db.box[u].filter(m=>n-m.ts<TTL);if(!db.box[u].length)delete db.box[u]}
  for(const id in db.acked)if(n-db.acked[id]>TTL)delete db.acked[id];save()},36e5);
process.on('uncaughtException',e=>console.error('uncaught:',e.message));
process.on('unhandledRejection',e=>console.error('unhandled:',e&&e.message));
for(const s of['SIGINT','SIGTERM'])process.on(s,()=>{save();process.exit(0)});

// ---- HTTP API ----
http.createServer(async(req,res)=>{try{
  const ip=req.socket.remoteAddress,url=new URL(req.url,'http://x'),p=url.pathname,t=req.headers['x-token'];
  if(req.method==='OPTIONS')return out(res,204,{});
  const isPeer=p.startsWith('/peer/');
  if(!isPeer&&limited(hits,ip,240,6e4))throw new HttpErr(429,'slow down');
  const raw=req.method==='POST'?await rawBody(req,isPeer&&req.headers['x-peer-sig']?5e6:65536):'';

  if(isPeer){
    if(req.method!=='POST'||!peerOk(req,raw))throw new HttpErr(401,'peer auth');
    const o=parse(raw);
    if(p==='/peer/has')return out(res,200,{has:has(o.u)});
    if(p==='/peer/user'){if(mergeUser(o.u,o.rec))saveSoon();return out(res,200,{ok:true})}
    if(p==='/peer/msg'){if(addEnv(o.to,o.env))saveSoon();return out(res,200,{ok:true})}
    if(p==='/peer/ack'){for(const id of Array.isArray(o.ids)?o.ids.slice(0,500):[])if(str(id,64)){db.acked[id]=Date.now();dropEnv(id)}saveSoon();return out(res,200,{ok:true})}
    if(p==='/peer/sync')return out(res,200,{users:db.users,box:db.box,acked:db.acked});
    throw new HttpErr(404,'not found')}

  if(p==='/health')return out(res,200,{ok:true,users:Object.keys(db.users).length,id:SELF||'standalone',peers:PEERS.length,peersUp:PEERS.filter(x=>up[x]).length});
  if(p==='/servers')return out(res,200,{all:[SELF,...PEERS].filter(Boolean)});

  if(p==='/register'&&req.method==='POST'){
    if(limited(regs,ip,5,36e5))throw new HttpErr(429,'too many sign-ups');
    const{u,pub}=parse(raw);
    if(typeof u!=='string'||!NAME.test(u))throw new HttpErr(400,'bad username');
    if(!pub||pub.kty!=='EC'||pub.crv!=='P-256'||!str(pub.x,100)||!str(pub.y,100))throw new HttpErr(400,'bad key');
    if(has(u))throw new HttpErr(409,'taken');
    const taken=(await Promise.all(PEERS.map(x=>peerCall(x,'/peer/has',{u},1500).then(r=>!!r.has).catch(()=>false)))).some(Boolean);
    if(taken)throw new HttpErr(409,'taken');
    if(has(u))throw new HttpErr(409,'taken');
    const tok=crypto.randomBytes(32).toString('hex'),rec={pub:{kty:'EC',crv:'P-256',x:pub.x,y:pub.y},th:sha(tok),at:Date.now()};
    db.users[u]=rec;if(!save()){delete db.users[u];throw new HttpErr(500,'storage error')}
    fanout('/peer/user',{u,rec});
    return out(res,200,{token:tok})}

  if(p.startsWith('/user/')){let u;try{u=decodeURIComponent(p.slice(6))}catch(e){throw new HttpErr(400,'bad username')}
    if(!has(u))throw new HttpErr(404,'not found');return out(res,200,{pub:db.users[u].pub})}

  if(p==='/send'&&req.method==='POST'){
    const{from,to,ct,iv}=parse(raw);
    if(!auth(from,t))throw new HttpErr(401,'auth');
    if(!has(to))throw new HttpErr(404,'not found');
    if(!str(ct,60000)||!str(iv,40))throw new HttpErr(400,'bad message');
    const box=db.box[to]=db.box[to]||[];
    if(box.length>=BOX_MAX)throw new HttpErr(429,'recipient inbox full');
    const env={id:crypto.randomUUID(),from,ct,iv,ts:Date.now()};
    box.push(env);
    if(!save()){box.pop();throw new HttpErr(500,'storage error')}
    notify(to);
    await fanout('/peer/msg',{to,env},true);
    return out(res,200,{ok:true,id:env.id})}

  if(p==='/inbox'){const u=url.searchParams.get('u');
    if(!auth(u,t))throw new HttpErr(401,'auth');
    const take=()=>{const all=(db.box[u]||[]).filter(m=>Date.now()-m.ts<TTL),m=all.slice(0,200);
      if(!m.length)return m;
      db.box[u]=all.slice(200);if(!db.box[u].length)delete db.box[u];
      m.forEach(x=>{db.acked[x.id]=Date.now()});save();fanout('/peer/ack',{ids:m.map(x=>x.id)});return m};
    let m=take();
    const wait=Math.min(25,Math.max(0,Number(url.searchParams.get('wait'))||0));   // long polling: hold the request open
    if(!m.length&&wait){let closed=false;res.on('close',()=>{if(!res.writableEnded)closed=true});
      await new Promise(done=>{const s=waiters.get(u)||new Set(),f=()=>{clearTimeout(tm);s.delete(f);if(!s.size&&waiters.get(u)===s)waiters.delete(u);done()},tm=setTimeout(f,wait*1000);waiters.set(u,s);s.add(f);res.on('close',f)});
      if(closed)return;m=take()}
    return out(res,200,{m})}

  if(p==='/quantum-signal.apk'&&req.method==='GET'){const f=path.join(__dirname,'quantum-signal.apk');
    if(!fs.existsSync(f))throw new HttpErr(404,'not found');
    res.writeHead(200,{'content-type':'application/vnd.android.package-archive','content-length':fs.statSync(f).size,'content-disposition':'attachment; filename="quantum-signal.apk"'});
    return fs.createReadStream(f).pipe(res)}
  throw new HttpErr(404,'not found')
}catch(e){if(!(e instanceof HttpErr))console.error('error:',e.message);if(!res.headersSent)out(res,e.code||500,{e:e instanceof HttpErr?e.message:'server error'})}
}).listen(PORT,HOST,()=>console.log('Quantum Signal server on '+HOST+':'+PORT+(PEERS.length?' · cluster of '+(PEERS.length+1):' · standalone')));
