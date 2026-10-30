// Quantum Signal relay for Cloudflare Workers (free plan).
// One SQLite-backed Durable Object ("Hub") stores usernames, public keys, hashed tokens and ENCRYPTED messages.
// Same API as the Node server, so the app works with either. Runs 24/7 on Cloudflare's network over HTTPS.
const NAME=/^[a-z0-9_]{3,20}$/,TTL=7*864e5,BOX_MAX=500;
const CORS={'access-control-allow-origin':'*','access-control-allow-headers':'content-type,x-token','access-control-allow-methods':'GET,POST,OPTIONS','access-control-max-age':'86400'};
const reply=(o,c=200)=>new Response(JSON.stringify(o),{status:c,headers:{'content-type':'application/json',...CORS}});
class HttpErr extends Error{constructor(c,m){super(m);this.code=c}}
const str=(v,m)=>typeof v==='string'&&v.length>0&&v.length<=m;
const hex=async s=>[...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(String(s))))].map(b=>b.toString(16).padStart(2,'0')).join('');
const same=(a,b)=>{if(a.length!==b.length)return false;let r=0;for(let i=0;i<a.length;i++)r|=a.charCodeAt(i)^b.charCodeAt(i);return r===0};
const parse=raw=>{try{return JSON.parse(raw||'{}')}catch(e){throw new HttpErr(400,'invalid json')}};

export default{
  async fetch(req,env){
    if(req.method==='OPTIONS')return new Response(null,{status:204,headers:CORS});
    try{return await env.HUB.get(env.HUB.idFromName('main')).fetch(req)}
    catch(e){return reply({e:'server busy, try again'},503)}
  }
};

export class Hub{
  constructor(state,env){
    this.sql=state.storage.sql;this.waiters=new Map();this.hits=new Map();this.regs=new Map();
    state.blockConcurrencyWhile(async()=>{
      this.run('CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY,pub TEXT NOT NULL,th TEXT NOT NULL,at INTEGER NOT NULL)');
      this.run('CREATE TABLE IF NOT EXISTS box(id TEXT PRIMARY KEY,to_user TEXT NOT NULL,from_user TEXT NOT NULL,ct TEXT NOT NULL,iv TEXT NOT NULL,ts INTEGER NOT NULL)');
      this.run('CREATE INDEX IF NOT EXISTS box_to ON box(to_user,ts)');
    });
  }
  run(q,...p){return this.sql.exec(q,...p).toArray()}
  user(u){return typeof u==='string'?this.run('SELECT name,pub,th,at FROM users WHERE name=?',u)[0]:undefined}
  limited(map,ip,max,win){const n=Date.now(),h=(map.get(ip)||[]).filter(t=>n-t<win);h.push(n);map.set(ip,h);if(map.size>5000)map.clear();return h.length>max}
  async auth(u,t){const r=this.user(u);if(!r||typeof t!=='string'||!t)throw new HttpErr(401,'auth');if(!same(await hex(t),r.th))throw new HttpErr(401,'auth')}
  notify(u){const s=this.waiters.get(u);if(s)for(const f of[...s])f()}

  async fetch(req){
    try{
      const url=new URL(req.url),p=url.pathname,ip=req.headers.get('cf-connecting-ip')||'?',t=req.headers.get('x-token');
      if(this.limited(this.hits,ip,300,6e4))throw new HttpErr(429,'slow down');
      const raw=req.method==='POST'?await req.text():'';
      if(raw.length>65536)throw new HttpErr(413,'too large');

      if(p==='/health')return reply({ok:true,users:this.run('SELECT COUNT(*) AS c FROM users')[0].c,id:url.origin,peers:0,peersUp:0});
      if(p==='/servers')return reply({all:[url.origin]});

      if(p==='/register'&&req.method==='POST'){
        if(this.limited(this.regs,ip,5,36e5))throw new HttpErr(429,'too many sign-ups');
        const{u,pub}=parse(raw);
        if(typeof u!=='string'||!NAME.test(u))throw new HttpErr(400,'bad username');
        if(!pub||pub.kty!=='EC'||pub.crv!=='P-256'||!str(pub.x,100)||!str(pub.y,100))throw new HttpErr(400,'bad key');
        const tok=[...crypto.getRandomValues(new Uint8Array(32))].map(b=>b.toString(16).padStart(2,'0')).join(''),th=await hex(tok);
        if(this.user(u))throw new HttpErr(409,'taken');   // check and insert with no await in between
        this.run('INSERT INTO users(name,pub,th,at) VALUES(?,?,?,?)',u,JSON.stringify({kty:'EC',crv:'P-256',x:pub.x,y:pub.y}),th,Date.now());
        return reply({token:tok});
      }

      if(p.startsWith('/user/')){
        let u;try{u=decodeURIComponent(p.slice(6))}catch(e){throw new HttpErr(400,'bad username')}
        const r=this.user(u);if(!r)throw new HttpErr(404,'not found');
        return reply({pub:JSON.parse(r.pub)});
      }

      if(p==='/send'&&req.method==='POST'){
        const{from,to,ct,iv}=parse(raw);
        await this.auth(from,t);
        if(!this.user(to))throw new HttpErr(404,'not found');
        if(!str(ct,60000)||!str(iv,40))throw new HttpErr(400,'bad message');
        if(this.run('SELECT COUNT(*) AS c FROM box WHERE to_user=?',to)[0].c>=BOX_MAX)throw new HttpErr(429,'recipient inbox full');
        const id=crypto.randomUUID();
        this.run('INSERT INTO box(id,to_user,from_user,ct,iv,ts) VALUES(?,?,?,?,?,?)',id,to,from,ct,iv,Date.now());
        if(Math.random()<0.02)this.run('DELETE FROM box WHERE ts<?',Date.now()-TTL);
        this.notify(to);
        return reply({ok:true,id});
      }

      if(p==='/inbox'){
        // POST {u,wait,ack:[ids]}: messages stay until acknowledged. GET ?u=&wait=: older apps, removed on delivery.
        const post=req.method==='POST';let u,wait,ack=[];
        if(post){const o=parse(raw);u=o.u;wait=o.wait;ack=Array.isArray(o.ack)?o.ack.filter(x=>str(x,64)).slice(0,500):[]}
        else{u=url.searchParams.get('u');wait=url.searchParams.get('wait')}
        await this.auth(u,t);
        if(ack.length)this.run(`DELETE FROM box WHERE to_user=? AND id IN (${ack.map(()=>'?').join(',')})`,u,...ack);
        const take=()=>{
          const m=this.run('SELECT id,from_user,ct,iv,ts FROM box WHERE to_user=? AND ts>? ORDER BY ts LIMIT 200',u,Date.now()-TTL).map(r=>({id:r.id,from:r.from_user,ct:r.ct,iv:r.iv,ts:r.ts}));
          if(!post&&m.length)this.run(`DELETE FROM box WHERE id IN (${m.map(()=>'?').join(',')})`,...m.map(x=>x.id));
          return m;
        };
        let m=take();
        const w=Math.min(50,Math.max(0,Number(wait)||0));
        if(!m.length&&w){
          await new Promise(done=>{
            const s=this.waiters.get(u)||new Set(),f=()=>{clearTimeout(tm);s.delete(f);if(!s.size&&this.waiters.get(u)===s)this.waiters.delete(u);done()},tm=setTimeout(f,w*1000);
            this.waiters.set(u,s);s.add(f);
          });
          m=take();
        }
        return reply({m});
      }

      throw new HttpErr(404,'not found');
    }catch(e){
      if(!(e instanceof HttpErr))console.error('error:',e&&e.message);
      return reply({e:e instanceof HttpErr?e.message:'server error'},e instanceof HttpErr?e.code:500);
    }
  }
}
