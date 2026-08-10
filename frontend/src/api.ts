export type Env='DEV'|'PROD';
export type Application={id:string;name:string;description?:string;environment:Env;enabled:boolean;createdAt:string};
export type Provider={id:string;name:string;slug:string;baseUrl:string;environment:Env;enabled:boolean;createdAt:string};
export type Credential={id:string;name:string;providerId:string;providerName:string;environment:Env;authType:'BEARER'|'API_KEY_HEADER'|'BASIC';headerName?:string;secretRef:string;enabled:boolean;createdAt:string};
export type Policy={id?:string;method:string;pathPattern:string};
export type Grant={id:string;applicationId:string;applicationName:string;providerId:string;providerName:string;credentialId:string;credentialName:string;environment:Env;enabled:boolean;policies:Policy[];createdAt:string};
export type Audit={id:string;occurredAt:string;actorType:string;actorId?:string;action:string;outcome:string;requestMethod?:string;requestPath?:string;statusCode?:number;correlationId:string};
let auth='';
export const hasAuth=()=>!!auth;
export const setAuth=(user:string,password:string)=>{const bytes=new TextEncoder().encode(`${user}:${password}`);auth='Basic '+btoa(Array.from(bytes,b=>String.fromCharCode(b)).join(''))};
export const clearAuth=()=>{auth=''};
export async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const response=await fetch(`/api/admin${path}`,{...options,headers:{'Content-Type':'application/json',Authorization:auth,...options.headers}});
  if(response.status===401){clearAuth();throw new Error('AUTH_REQUIRED')}
  if(!response.ok){const p=await response.json().catch(()=>null);throw new Error(p?.detail||`Request failed (${response.status})`)}
  if(response.status===204||response.headers.get('content-length')==='0')return undefined as T;
  return response.json();
}
