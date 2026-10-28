// Quantum Signal relay. Run: node server.js   (Node 18+; PORT env var; data in data.json)
// Stores usernames, public keys, hashed tokens and ENCRYPTED messages/receipts only.
const http=require('http'),fs=require('fs'),crypto=require('crypto');
const PORT=process.env.PORT||3000,DB='data.json',TTL=7*864e5,BOX_MAX=500;
let db={users:{},box:{}};
try{db=JSON.parse(fs.readFileSync(DB))}catch(e){if(e.code!=='ENOENT')console.error('data.json unreadable, starting empty:',e.message)}
db.users=db.users||{};db.box=db.box||{};
const save=()=>{try{fs.writeFileSync(DB+'.tmp',JSON.stringify(db));fs.renameSync(DB+'.tmp',DB);return true}catch(e){console.error('save failed:',e.message);return false}};
setInterval(()=>{const n=Date.now();for(const u in db.box){db.box[u]=db.box[u].filter(m=>n-m.ts<TTL);if(!db.box[u].length)delete db.box[u]}save()},36e5);
process.on('uncaughtException',e=>console.error('uncaught:',e.message));
process.on('unhandledRejection',e=>console.error('unhandled:',e&&e.message));
for(const s of['SIGINT','SIGTERM'])process.on(s,()=>{save();process.exit(0)});
class HttpErr extends Error{constructor(c,m){super(m);this.code=c}}
const sha=s=>crypto.createHash('sha256').update(String(s)).digest('hex');
const has=u=>typeof u==='string'&&Object.hasOwn(db.users,u);
const auth=(u,t)=>has(u)&&typeof t==='string'&&t.length>0&&crypto.timingSafeEqual(Buffer.from(sha(t)),Buffer.from(db.users[u].th));
const str=(v,max)=>typeof v==='string'&&v.length>0&&v.length<=max;
const hits={},regs={};
const limited=(map,ip,max,win)=>{const n=Date.now(),h=(map[ip]||[]).filter(t=>n-t<win);h.push(n);map[ip]=h;return h.length>max};
setInterval(()=>{for(const m of[hits,regs])for(const k in m)if(!m[k].length||Date.now()-m[k][m[k].length-1]>36e5)delete m[k]},6e5);
const out=(r,c,o)=>{r.writeHead(c,{'content-type':'application/json','access-control-allow-origin':'*','access-control-allow-headers':'content-type,x-token','access-control-allow-methods':'GET,POST,OPTIONS'});r.end(JSON.stringify(o))};
const body=req=>new Promise((ok,no)=>{let b='',big=false;
 req.on('data',d=>{if(big)return;b+=d;if(b.length>65536)big=true});
 req.on('end',()=>{if(big)return no(new HttpErr(413,'too large'));try{ok(JSON.parse(b||'{}'))}catch(e){no(new HttpErr(400,'invalid json'))}});
 req.on('error',()=>no(new HttpErr(400,'bad request')))});
http.createServer(async(req,res)=>{try{
 const ip=req.socket.remoteAddress,url=new URL(req.url,'http://x'),p=url.pathname,t=req.headers['x-token'];
 if(req.method==='OPTIONS')return out(res,204,{});
 if(limited(hits,ip,240,6e4))throw new HttpErr(429,'slow down');
 if(p==='/health')return out(res,200,{ok:true,users:Object.keys(db.users).length});
 if(p==='/register'&&req.method==='POST'){
  if(limited(regs,ip,5,36e5))throw new HttpErr(429,'too many sign-ups');
  const{u,pub}=await body(req);
  if(typeof u!=='string'||!/^[a-z0-9_]{3,20}$/.test(u))throw new HttpErr(400,'bad username');
  if(!pub||pub.kty!=='EC'||pub.crv!=='P-256'||!str(pub.x,100)||!str(pub.y,100))throw new HttpErr(400,'bad key');
  if(has(u))throw new HttpErr(409,'taken');
  const tok=crypto.randomBytes(32).toString('hex');
  db.users[u]={pub:{kty:'EC',crv:'P-256',x:pub.x,y:pub.y},th:sha(tok),at:Date.now()};
  if(!save()){delete db.users[u];throw new HttpErr(500,'storage error')}
  return out(res,200,{token:tok})}
 if(p.startsWith('/user/')){let u;try{u=decodeURIComponent(p.slice(6))}catch(e){throw new HttpErr(400,'bad username')}
  if(!has(u))throw new HttpErr(404,'not found');return out(res,200,{pub:db.users[u].pub})}
 if(p==='/send'&&req.method==='POST'){
  const{from,to,ct,iv}=await body(req);
  if(!auth(from,t))throw new HttpErr(401,'auth');
  if(!has(to))throw new HttpErr(404,'not found');
  if(!str(ct,60000)||!str(iv,40))throw new HttpErr(400,'bad message');
  const box=db.box[to]=db.box[to]||[];
  if(box.length>=BOX_MAX)throw new HttpErr(429,'recipient inbox full');
  box.push({from,ct,iv,ts:Date.now()});
  if(!save()){box.pop();throw new HttpErr(500,'storage error')}
  return out(res,200,{ok:true})}
 if(p==='/inbox'){const u=url.searchParams.get('u');
  if(!auth(u,t))throw new HttpErr(401,'auth');
  const all=(db.box[u]||[]).filter(m=>Date.now()-m.ts<TTL),m=all.slice(0,200);
  db.box[u]=all.slice(200);save();return out(res,200,{m})}
 throw new HttpErr(404,'not found')
}catch(e){if(!(e instanceof HttpErr))console.error('error:',e.message);if(!res.headersSent)out(res,e.code||500,{e:e instanceof HttpErr?e.message:'server error'})}
}).listen(PORT,()=>console.log('Quantum Signal server on :'+PORT));
