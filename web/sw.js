const BUILD_ID='__BUILD_ID__';
const CACHE_PREFIX='my-live2d-web-';
const CACHE=CACHE_PREFIX+BUILD_ID;
const FILES=['./','./index.html','./style.css','./app.mjs','./rig-core.mjs','./manifest.webmanifest','./icon-192.png','./icon-512.png','./demo.png'];

self.addEventListener('install',event=>event.waitUntil((async()=>{
 const cache=await caches.open(CACHE);
 await cache.addAll(FILES.map(path=>new Request(path,{cache:'reload'})));
 await self.skipWaiting();
})()));

self.addEventListener('activate',event=>event.waitUntil((async()=>{
 const keys=await caches.keys();
 await Promise.all(keys.filter(key=>key.startsWith(CACHE_PREFIX)&&key!==CACHE).map(key=>caches.delete(key)));
 await self.clients.claim();
})()));

self.addEventListener('fetch',event=>{
 const request=event.request;
 if(request.method!=='GET'||new URL(request.url).origin!==location.origin)return;
 event.respondWith((async()=>{
  try{
   const response=await fetch(request,{cache:'no-cache'});
   if(response.ok){
    try{await(await caches.open(CACHE)).put(request,response.clone())}catch{}
    return response;
   }
   const cached=await caches.match(request);
   return cached||response;
  }catch{
   const cached=await caches.match(request);
   if(cached)return cached;
   return request.mode==='navigate'?(await caches.match('./index.html'))||Response.error():Response.error();
  }
 })());
});
