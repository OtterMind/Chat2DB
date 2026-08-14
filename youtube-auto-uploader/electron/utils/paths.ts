import {app} from 'electron'; import {mkdirSync} from 'node:fs'; import path from 'node:path';
export interface AppPaths {root:string;temp:string;logs:string;cache:string;database:string}
export function createPaths(root=app.getPath('userData')):AppPaths {const p={root,temp:path.join(root,'tmp'),logs:path.join(root,'logs'),cache:path.join(root,'cache'),database:path.join(root,'app.db')}; for(const dir of [p.root,p.temp,p.logs,p.cache]) mkdirSync(dir,{recursive:true}); return p}
