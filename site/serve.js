// Tiny static web server for the Quantum Signal download page (no dependencies).
// Usage: node serve.js <folder>     (env: PORT=8080 HOST=0.0.0.0)
const http=require('http'),fs=require('fs'),path=require('path');
const DIR=path.resolve(process.argv[2]||'.'),PORT=+process.env.PORT||8080,HOST=process.env.HOST||'0.0.0.0';
const TYPES={'.html':'text/html; charset=utf-8','.apk':'application/vnd.android.package-archive','.json':'application/json','.txt':'text/plain; charset=utf-8'};
http.createServer((req,res)=>{
  let p;try{p=decodeURIComponent(new URL(req.url,'http://x').pathname)}catch(e){res.writeHead(400);return res.end('bad request')}
  if(p==='/')p='/index.html';
  const f=path.join(DIR,p);
  if(!f.startsWith(DIR+path.sep)||!fs.existsSync(f)||!fs.statSync(f).isFile()){res.writeHead(404,{'content-type':'text/plain'});return res.end('not found')}
  const ext=path.extname(f).toLowerCase(),h={'content-type':TYPES[ext]||'application/octet-stream','content-length':fs.statSync(f).size,'cache-control':'no-store'};
  if(ext==='.apk')h['content-disposition']='attachment; filename="quantum-signal.apk"';
  res.writeHead(200,h);fs.createReadStream(f).pipe(res);
}).listen(PORT,HOST,()=>console.log('Download page on '+HOST+':'+PORT));
