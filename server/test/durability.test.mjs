import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createECDH, randomUUID, randomBytes } from 'node:crypto';
import { createRelay } from '../src/server.mjs';

test('queue and credentials survive restart; expired payloads are pruned',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'watchbridge-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 let now=Date.now(),s,base;
 async function start(){s=createRelay({path:join(dir,'db.sqlite'),now:()=>now});await new Promise(r=>s.listen(0,'127.0.0.1',r));base=`http://127.0.0.1:${s.address().port}`;}
 const close=()=>new Promise(r=>s.close(r));
 const call=async(path,body,token)=>{const r=await fetch(base+path,{method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,headers:token?{Authorization:`Bearer ${token}`}:{}});assert.equal(r.status,200);return r.json();};
 const peer=()=>{const k=createECDH('prime256v1');k.generateKeys();return {deviceId:randomUUID(),publicKey:k.getPublicKey().toString('base64')};};
 await start();const a=peer(),w=peer();const ac=await call('/v1/pair/create',a);const wc=await call('/v1/pair/join',{...w,code:ac.code});
 await call('/v1/pair/approve',{},ac.token);await call('/v1/pair/approve',{},wc.token);
 const e={v:1,id:randomUUID(),sender:a.deviceId,recipient:w.deviceId,type:'ping',created:now,expires:now+60000,nonce:randomBytes(12).toString('base64'),ciphertext:randomBytes(32).toString('base64')};
 await call('/v1/events',e,ac.token);await close();await start();
 assert.equal((await call('/v1/pair',null,wc.token)).active,true);assert.deepEqual((await call('/v1/events',null,wc.token)).events,[e]);
 now+=60001;assert.equal((await call('/v1/events',null,wc.token)).events.length,0);await close();
});
