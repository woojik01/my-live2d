import test from 'node:test';
import assert from 'node:assert/strict';
import * as R from '../web/rig-core.mjs';
const fixture=()=>{let p=R.emptyProject(),a={id:R.uid(),src:'data:image/png;base64,iVBORw0KGgo=',width:100,height:80};let part=R.makePart('눈',a);p.assets.push(a);p.parts.push(part);return[p,part]};
test('two independent parameters interpolate without baking each other',()=>{let [p,part]=fixture(),x=R.makeParameter('X',0,0,1),y=R.makeParameter('Y',0,0,1);p.parameters.push(x,y);x.value=1;y.value=1;let base=part.mesh.vertices;R.record(p,part.id,x.id,{vertices:base.map(v=>R.add(v,R.V(10,0)))});R.record(p,part.id,y.id,{vertices:base.map(v=>R.add(v,R.V(0,6)))});assert.deepEqual(R.evaluate(p,part).vertices[0],R.add(base[0],R.V(10,6)));x.value=.5;y.value=.5;assert.deepEqual(R.evaluate(p,part).vertices[0],R.add(base[0],R.V(5,3)))});
test('warp and rotation hierarchy round-trips coordinates',()=>{let [p,part]=fixture(),node=R.makeDeformer('warp',null,{x:0,y:0,w:100,h:80});node.controls=node.controls.map(v=>R.add(v,R.V(v.y*.1,v.x*.05)));node.transform.rotation=17;node.transform.position=R.V(4,8);p.deformers.push(node);part.parent=node.id;let pt=R.V(23,12),world=R.parentToWorld(p,node.id,pt),back=R.worldToParent(p,node.id,world);assert.ok(Math.abs(back.x-pt.x)<.01);assert.ok(Math.abs(back.y-pt.y)<.01)});
test('invalid hierarchy and key vertex counts are rejected',()=>{let [p,part]=fixture(),a=R.makeDeformer('rotation',null),b=R.makeDeformer('rotation',a.id);a.parent=b.id;p.deformers.push(a,b);assert.throws(()=>R.validate(p));a.parent=null;let par=R.makeParameter('x');p.parameters.push(par);par.bindings[part.id]={keys:[{value:0,pose:{vertices:[R.V()]}}]};assert.throws(()=>R.validate(p))});
test('two-dimensional correction is additive and bilinear',()=>{let [p,part]=fixture(),x=R.makeParameter('x',0,0,1),y=R.makeParameter('y',0,0,1);p.parameters.push(x,y);x.value=1;y.value=1;R.record(p,part.id,x.id,{vertices:part.mesh.vertices.map(v=>R.add(v,R.V(10,0)))});let before=R.evaluate(p,part).vertices[0],c=R.createCombination(p,part.id,x.id,y.id),pose=R.combinationDraft(p,c,part);pose.vertices=pose.vertices.map(v=>R.add(v,R.V(8,4)));R.recordCombination(p,c,part,pose);assert.deepEqual(R.evaluate(p,part).vertices[0],R.add(before,R.V(8,4)));x.value=.5;y.value=.5;let out=R.sampleCombination(p,c);assert.equal(out.points[0].x,2);assert.equal(out.points[0].y,1);assert.throws(()=>R.recordCombination(p,c,part,pose))});
test('import rejects unsafe external images',()=>{let [p]=fixture();p.assets[0].src='https://example.com/a.png';assert.throws(()=>R.validate(p))});

test('clipped and multiply parts remain valid across project serialization',()=>{
 const [p,base]=fixture(),part=R.makePart('클리핑',p.assets[0]);part.clipBase=base.id;part.blend='multiply';p.parts.push(part);
 assert.doesNotThrow(()=>R.validate(JSON.parse(JSON.stringify(p))));
 part.clipBase=part.id;assert.throws(()=>R.validate(p));
});
