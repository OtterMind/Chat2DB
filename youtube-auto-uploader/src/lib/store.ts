import {create} from 'zustand';import type {AppSettings,AuthState,JobProgress} from '../../electron/types';
interface State{auth:AuthState|null;settings:AppSettings|null;progress:Record<string,JobProgress>;setAuth:(auth:AuthState)=>void;setSettings:(settings:AppSettings)=>void;setProgress:(progress:JobProgress)=>void}
export const useStore=create<State>(set=>({auth:null,settings:null,progress:{},setAuth:auth=>set({auth}),setSettings:settings=>set({settings}),setProgress:value=>set(state=>({progress:{...state.progress,[value.jobId]:value}}))}));
