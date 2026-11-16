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
const HEX64=/^[0-9a-f]{64}$/;
const rand=()=>[...crypto.getRandomValues(new Uint8Array(32))].map(b=>b.toString(16).padStart(2,'0')).join('');
const vaultOk=v=>!!v&&typeof v==='object'&&str(v.iv,64)&&str(v.ct,4096);
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
    this.state=state;this.env=env;this.fcmTok=null;this.fcmTokExp=0;this.fcmProj=null;
    this.sql=state.storage.sql;this.waiters=new Map();this.hits=new Map();this.regs=new Map();this.logins=new Map();this.fails=new Map();this.backs=new Map();
    state.blockConcurrencyWhile(async()=>{
      this.run('CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY,pub TEXT NOT NULL,th TEXT NOT NULL,at INTEGER NOT NULL,ah TEXT,vault TEXT)');
      for(const c of['ah TEXT','vault TEXT']){try{this.run('ALTER TABLE users ADD COLUMN '+c)}catch(e){}}   // older databases
      this.run('CREATE TABLE IF NOT EXISTS tokens(th TEXT PRIMARY KEY,user TEXT NOT NULL,at INTEGER NOT NULL)');
      this.run('CREATE INDEX IF NOT EXISTS tokens_user ON tokens(user,at)');
      this.run('CREATE TABLE IF NOT EXISTS box(id TEXT PRIMARY KEY,to_user TEXT NOT NULL,from_user TEXT NOT NULL,ct TEXT NOT NULL,iv TEXT NOT NULL,ts INTEGER NOT NULL)');
      this.run('CREATE TABLE IF NOT EXISTS backups(user TEXT PRIMARY KEY,data TEXT NOT NULL,iv TEXT NOT NULL,ts INTEGER NOT NULL)');
      this.run('CREATE TABLE IF NOT EXISTS push_tokens(user TEXT NOT NULL,token TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(user,token))');
      this.run('CREATE TABLE IF NOT EXISTS security(user TEXT PRIMARY KEY,q1 TEXT NOT NULL,q2 TEXT NOT NULL,a1 TEXT NOT NULL,a2 TEXT NOT NULL,rec_iv TEXT NOT NULL,rec_ct TEXT NOT NULL)');
      this.run('CREATE TABLE IF NOT EXISTS resets(th TEXT PRIMARY KEY,user TEXT NOT NULL,exp INTEGER NOT NULL)');
      this.run('CREATE INDEX IF NOT EXISTS box_to ON box(to_user,ts)');
    });
  }
  run(q,...p){return this.sql.exec(q,...p).toArray()}
  user(u){return typeof u==='string'?this.run('SELECT name,pub,th,at,ah,vault FROM users WHERE name=?',u)[0]:undefined}
  limited(map,ip,max,win){const n=Date.now(),h=(map.get(ip)||[]).filter(t=>n-t<win);h.push(n);map.set(ip,h);if(map.size>5000)map.clear();return h.length>max}
  async auth(u,t){
    const r=this.user(u);if(!r||typeof t!=='string'||!t)throw new HttpErr(401,'auth');
    const h=await hex(t);
    if(!same(h,r.th)){const row=this.run('SELECT at FROM tokens WHERE user=? AND th=?',u,h)[0];if(!row||Date.now()-row.at>30*864e5)throw new HttpErr(401,'auth');}
    return h;
  }
  failed(u){const f=this.fails.get(u)||{n:0,t:0};if(Date.now()-f.t>9e5)f.n=0;return f}
  bad(u,f){f.n++;f.t=Date.now();if(this.fails.size>5000)this.fails.clear();this.fails.set(u,f)}
  notify(u){const s=this.waiters.get(u);if(s)for(const f of[...s])f()}

  // --- FCM push: notify a closed app. The server only sees ciphertext,
  // so the push carries just the sender's name, never message content.
  // Needs the Firebase service account key as the FCM_KEY secret (JSON).
  // If it is not configured, pushes are skipped silently. ---
  b64url(u){let s='';for(let i=0;i<u.length;i++)s+=String.fromCharCode(u[i]);return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')}
  async fcmAccess(){
    if(this.fcmTok&&Date.now()<this.fcmTokExp-60000)return this.fcmTok;
    const raw=this.env&&this.env.FCM_KEY;if(!raw)return null;
    let sa;try{sa=JSON.parse(raw)}catch(e){return null}
    if(!sa||!sa.project_id||!sa.client_email||!sa.private_key)return null;
    const now=Math.floor(Date.now()/1000);
    const head=this.b64url(new TextEncoder().encode(JSON.stringify({alg:'RS256',typ:'JWT'})));
    const body=this.b64url(new TextEncoder().encode(JSON.stringify({iss:sa.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',exp:now+3600,iat:now})));
    const pem=sa.private_key.replace(/-----BEGIN PRIVATE KEY-----/g,'').replace(/-----END PRIVATE KEY-----/g,'').replace(/\s+/g,'');
    let der;try{der=Uint8Array.from(atob(pem),c=>c.charCodeAt(0))}catch(e){return null}
    let key;try{key=await crypto.subtle.importKey('pkcs8',der,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign'])}catch(e){return null}
    const sig=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(head+'.'+body));
    const jwt=head+'.'+body+'.'+this.b64url(new Uint8Array(sig));
    let res;try{res=await fetch('https://oauth2.googleapis.com/token',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:'grant_type='+encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer')+'&assertion='+encodeURIComponent(jwt)})}catch(e){return null}
    if(!res.ok)return null;
    const d=await res.json().catch(()=>null);if(!d||!d.access_token)return null;
    this.fcmTok=d.access_token;this.fcmTokExp=Date.now()+(d.expires_in||3600)*1000;this.fcmProj=sa.project_id;
    return this.fcmTok;
  }
  async pushNotify(to,from){
    try{
      const toks=this.run('SELECT token FROM push_tokens WHERE user=?',to);
      if(!toks.length)return;
      const acc=await this.fcmAccess();if(!acc||!this.fcmProj)return;
      for(const r of toks){
        try{
          const res=await fetch('https://fcm.googleapis.com/v1/'+this.fcmProj+'/messages:send',{method:'POST',headers:{'content-type':'application/json','authorization':'Bearer '+acc},body:JSON.stringify({message:{token:r.token,notification:{title:'@'+from,body:'New message'},data:{chat:from},android:{priority:'high'}}})});
          if(!res.ok){const t=await res.text().catch(()=> '');if(/UNREGISTERED|NOT_FOUND|INVALID_ARGUMENT/i.test(t))this.run('DELETE FROM push_tokens WHERE token=?',r.token)}
        }catch(e){}
      }
    }catch(e){}
  }

  async fetch(req){
    try{
      const url=new URL(req.url),p=url.pathname,ip=req.headers.get('cf-connecting-ip')||'?',t=req.headers.get('x-token');
      if(this.limited(this.hits,ip,300,6e4))throw new HttpErr(429,'slow down');
      const raw=(req.method==='POST'||req.method==='DELETE')?await req.text():'';
      if(raw.length>(p==='/backup'?6*1024*1024:65536))throw new HttpErr(413,'too large');

      if(p==='/health')return reply({ok:true,users:this.run('SELECT COUNT(*) AS c FROM users')[0].c,id:url.origin,peers:0,peersUp:0});
      if(p==='/servers')return reply({all:[url.origin]});

      if(p==='/register'&&req.method==='POST'){
        if(this.limited(this.regs,ip,5,36e5))throw new HttpErr(429,'too many sign-ups');
        const{u,pub,auth,vault}=parse(raw);
        if(typeof u!=='string'||!NAME.test(u))throw new HttpErr(400,'bad username');
        const withPw=auth!==undefined||vault!==undefined;
        if(withPw&&(!HEX64.test(auth||'')||!vaultOk(vault)))throw new HttpErr(400,'bad password data');
        if(!pub||pub.kty!=='EC'||pub.crv!=='P-256'||!str(pub.x,100)||!str(pub.y,100))throw new HttpErr(400,'bad key');
        const tok=rand(),th=await hex(tok),ah=withPw?await hex(auth):null;
        if(this.user(u))throw new HttpErr(409,'taken');   // check and insert with no await in between
        this.run('INSERT INTO users(name,pub,th,at,ah,vault) VALUES(?,?,?,?,?,?)',u,JSON.stringify({kty:'EC',crv:'P-256',x:pub.x,y:pub.y}),th,Date.now(),ah,withPw?JSON.stringify(vault):null);
        return reply({token:tok});
      }

      if(p==='/login'&&req.method==='POST'){
        if(this.limited(this.logins,ip,30,9e5))throw new HttpErr(429,'too many attempts');
        const{u,auth}=parse(raw);
        if(typeof u!=='string'||!HEX64.test(auth||''))throw new HttpErr(400,'bad login');
        const f=this.failed(u);if(f.n>=5)throw new HttpErr(429,'too many attempts');
        const r=this.user(u),ah=await hex(auth);
        if(!r||!r.ah||!r.vault||!same(ah,r.ah)){this.bad(u,f);throw new HttpErr(401,'login')}
        this.fails.delete(u);
        const tok=rand();
        this.run('INSERT INTO tokens(th,user,at) VALUES(?,?,?)',await hex(tok),u,Date.now());
        this.run('DELETE FROM tokens WHERE user=? AND th NOT IN (SELECT th FROM tokens WHERE user=? ORDER BY at DESC LIMIT 10)',u,u);
        this.run('DELETE FROM tokens WHERE at<?',Date.now()-30*864e5);
        return reply({token:tok,pub:JSON.parse(r.pub),vault:JSON.parse(r.vault)});
      }

      if(p==='/password'&&req.method==='POST'){
        const{u,auth,old,vault}=parse(raw);
        await this.auth(u,t);
        if(!HEX64.test(auth||'')||!vaultOk(vault))throw new HttpErr(400,'bad password data');
        const r=this.user(u);
        if(r.ah){
          const f=this.failed(u);if(f.n>=5)throw new HttpErr(429,'too many attempts');
          if(!HEX64.test(old||'')||!same(await hex(old),r.ah)){this.bad(u,f);throw new HttpErr(401,'login')}
          this.fails.delete(u);
        }
        this.run('UPDATE users SET ah=?,vault=? WHERE name=?',await hex(auth),JSON.stringify(vault),u);
        return reply({ok:true});
      }

      if(p==='/logout'&&req.method==='POST'){
        const u=parse(raw).u,h=await this.auth(u,t),r=this.user(u);
        this.run('DELETE FROM tokens WHERE user=? AND th=?',u,h);
        if(same(h,r.th))this.run('UPDATE users SET th=? WHERE name=?',await hex(rand()),u);
        return reply({ok:true});
      }

      if(p==='/logout-all'&&req.method==='POST'){
        const u=parse(raw).u,h=await this.auth(u,t);
        const n=this.run('SELECT COUNT(*) AS c FROM tokens WHERE user=? AND th!=?',u,h)[0].c;
        this.run('DELETE FROM tokens WHERE user=? AND th!=?',u,h);
        return reply({ok:true,removed:n});
      }

      if(p.startsWith('/user/')){
        let u;try{u=decodeURIComponent(p.slice(6))}catch(e){throw new HttpErr(400,'bad username')}
        const r=this.user(u);if(!r)throw new HttpErr(404,'not found');
        return reply({pub:JSON.parse(r.pub)});
      }

      if(p==='/push-token'&&req.method==='POST'){
        const{u,token}=parse(raw);
        await this.auth(u,t);
        if(!str(token,512))throw new HttpErr(400,'bad token');
        this.run('INSERT OR REPLACE INTO push_tokens(user,token,updated) VALUES(?,?,?)',u,token,Date.now());
        this.run('DELETE FROM push_tokens WHERE user=? AND token NOT IN (SELECT token FROM push_tokens WHERE user=? ORDER BY updated DESC LIMIT 5)',u,u);
        return reply({ok:true});
      }

      if(p==='/push-token'&&req.method==='DELETE'){
        const{u,token}=parse(raw);
        await this.auth(u,t);
        if(token)this.run('DELETE FROM push_tokens WHERE user=? AND token=?',u,token);
        else this.run('DELETE FROM push_tokens WHERE user=?',u);
        return reply({ok:true});
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
        try{if(this.state&&this.state.waitUntil)this.state.waitUntil(this.pushNotify(to,from));else this.pushNotify(to,from).catch(()=>{})}catch(e){}
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

      if(p==='/backup'&&req.method==='POST'){
        const{u,iv,data}=parse(raw);
        await this.auth(u,t);
        if(!str(iv,64)||!str(data,5*1024*1024))throw new HttpErr(400,'bad backup');
        if(this.limited(this.backs,u,1,6e4))throw new HttpErr(429,'slow down');
        this.run('INSERT OR REPLACE INTO backups(user,data,iv,ts) VALUES(?,?,?,?)',u,data,iv,Date.now());
        return reply({ok:true});
      }

      if(p==='/backup'&&req.method==='GET'){
        const u=url.searchParams.get('u');
        await this.auth(u,t);
        const r=this.run('SELECT data,iv,ts FROM backups WHERE user=?',u)[0];
        if(!r)throw new HttpErr(404,'not found');
        return reply({data:r.data,iv:r.iv,ts:r.ts});
      }

      if(p==='/backup'&&req.method==='DELETE'){
        const u=parse(raw).u;
        await this.auth(u,t);
        this.run('DELETE FROM backups WHERE user=?',u);
        return reply({ok:true});
      }


      if(p==='/security-questions'&&req.method==='POST'){
        const{u,q1,q2,a1,a2,rec_iv,rec_ct}=parse(raw);
        await this.auth(u,t);
        if(!str(q1,200)||!str(q2,200)||!HEX64.test(a1||'')||!HEX64.test(a2||'')||!str(rec_iv,64)||!str(rec_ct,8192))throw new HttpErr(400,'bad security questions');
        this.run('INSERT OR REPLACE INTO security(user,q1,q2,a1,a2,rec_iv,rec_ct) VALUES(?,?,?,?,?,?,?)',u,q1,q2,a1,a2,rec_iv,rec_ct);
        return reply({ok:true});
      }

      if(p==='/security-questions'&&req.method==='GET'){
        const u=url.searchParams.get('u');
        if(typeof u!=='string'||!NAME.test(u))throw new HttpErr(400,'bad username');
        const r=this.run('SELECT q1,q2 FROM security WHERE user=?',u)[0];
        if(!r)throw new HttpErr(404,'not found');
        return reply({q1:r.q1,q2:r.q2});
      }

      if(p==='/forgot-password'&&req.method==='POST'){
        const{u,a1,a2}=parse(raw);
        if(typeof u!=='string'||!NAME.test(u)||!HEX64.test(a1||'')||!HEX64.test(a2||''))throw new HttpErr(400,'bad request');
        const f=this.failed(u);if(f.n>=5)throw new HttpErr(429,'too many attempts');
        if(this.limited(this.logins,ip,5,9e5))throw new HttpErr(429,'too many attempts');
        const r=this.run('SELECT a1,a2,rec_iv,rec_ct FROM security WHERE user=?',u)[0];
        if(!r||!this.user(u)){this.bad(u,f);throw new HttpErr(404,'not found')}
        if(!same(a1,r.a1)||!same(a2,r.a2)){this.bad(u,f);throw new HttpErr(401,'wrong answers')}
        this.fails.delete(u);
        const tok=rand();
        this.run('INSERT INTO resets(th,user,exp) VALUES(?,?,?)',await hex(tok),u,Date.now()+15*60*1000);
        this.run('DELETE FROM resets WHERE exp<?',Date.now());
        return reply({ok:true,rec_iv:r.rec_iv,rec_ct:r.rec_ct,reset_token:tok});
      }

      if(p==='/reset-password'&&req.method==='POST'){
        const{u,reset_token,auth,vault}=parse(raw);
        if(typeof u!=='string'||!HEX64.test(reset_token||'')||!HEX64.test(auth||'')||!vaultOk(vault))throw new HttpErr(400,'bad reset');
        const row=this.run('SELECT user,exp FROM resets WHERE th=?',await hex(reset_token))[0];
        if(!row||row.user!==u||Date.now()>row.exp)throw new HttpErr(401,'bad or expired reset token');
        this.run('DELETE FROM resets WHERE th=?',await hex(reset_token));
        const r=this.user(u);if(!r)throw new HttpErr(404,'not found');
        this.run('UPDATE users SET ah=?,vault=? WHERE name=?',await hex(auth),JSON.stringify(vault),u);
        // Log the user in with a fresh token (and clear any lockout).
        this.fails.delete(u);
        const tok=rand();
        this.run('INSERT INTO tokens(th,user,at) VALUES(?,?,?)',await hex(tok),u,Date.now());
        return reply({ok:true,token:tok,pub:JSON.parse(r.pub),vault:JSON.parse(JSON.stringify(vault))});
      }

      if(p==='/admin/prune'&&req.method==='POST'){
        const{keep,key}=parse(raw);
        const adminKey=this.env&&this.env.ADMIN_KEY;
        if(!adminKey||typeof key!=='string'||!same(key,adminKey))throw new HttpErr(403,'admin only');
        if(typeof keep!=='string'||!NAME.test(keep))throw new HttpErr(400,'bad keep user');
        if(!this.user(keep))throw new HttpErr(404,'keep user not found');
        const n=this.run('SELECT COUNT(*) AS c FROM users WHERE name!=?',keep)[0].c;
        this.run('DELETE FROM users WHERE name!=?',keep);
        this.run('DELETE FROM tokens WHERE user!=?',keep);
        this.run('DELETE FROM box WHERE to_user!=? AND from_user!=?',keep,keep);
        this.run('DELETE FROM backups WHERE user!=?',keep);
        this.run('DELETE FROM push_tokens WHERE user!=?',keep);
        this.run('DELETE FROM security WHERE user!=?',keep);
        this.run('DELETE FROM resets WHERE user!=?',keep);
        return reply({ok:true,removed:n,kept:keep});
      }

      throw new HttpErr(404,'not found');
    }catch(e){
      if(!(e instanceof HttpErr))console.error('error:',e&&e.message);
      return reply({e:e instanceof HttpErr?e.message:'server error'},e instanceof HttpErr?e.code:500);
    }
  }
}
