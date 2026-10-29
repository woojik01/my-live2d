export const MAX_BYTES=128*1024*1024;
export function validateSize(w,h){if(!Number.isInteger(w)||!Number.isInteger(h)||w<1||h<1||w>8192||h>8192||w*h>32000000)throw Error('invalid size')}
export function canvas(w,h){const c=document.createElement('canvas');c.width=w;c.height=h;return c}
export const id=()=>crypto.randomUUID();
export const layer=(name,w,h,parent=null)=>({id:id(),type:'paint',name,canvas:canvas(w,h),parent,visible:true,opacity:1,mask:null});
export const group=name=>({id:id(),type:'group',name,visible:true,opacity:1});
