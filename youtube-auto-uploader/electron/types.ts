export type Privacy='public'|'unlisted'|'private'
export interface Account {email:string;name:string;pictureUrl?:string;channelId?:string;channelName?:string}
export interface AuthState {authenticated:boolean;hasCredentials:boolean;account?:Account}
export interface Channel {id:number;youtube_channel_id:string;channel_name:string|null;channel_handle:string|null;thumbnail_url:string|null;interval_hours:number;auto_upload:number;privacy:Privacy;is_active:number;last_checked_at:string|null;last_error:string|null}
export interface UploadInput {url?:string;localPath?:string;sourceTitle?:string;title?:string;description?:string;tags?:string[];privacy?:Privacy;quality?:string;madeForKids?:boolean;categoryId?:string;thumbnailPath?:string;clipId?:number;uploadType?:'single'|'batch'|'auto_sync'|'clipper'}
export interface ClipInput {url?:string;localPath?:string;model:string;count:number;maxLength:number;category:string;aspect:string;captions:boolean;smartZoom:boolean;music:boolean;blurBackground:boolean}
export interface JobProgress {jobId:string;phase:string;percent:number;speed?:string;eta?:string;message?:string}
export interface JobHandle {jobId:string}
export interface VideoMetadata {id:string;title:string;description:string;thumbnail:string;channel:string;duration:number;viewCount:number;uploadDate?:string;url:string}
export interface AppSettings {language:'en'|'fa';theme:'dark'|'light';defaultPrivacy:Privacy;downloadConcurrency:number;uploadConcurrency:number;keepDownloads:boolean;autoStartMonitor:boolean;minimizeToTray:boolean;ollamaEndpoint:string;defaultModel:string;acceptedCopyright:boolean;onboardingComplete:boolean;devtools:boolean}
