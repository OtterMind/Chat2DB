import type { AgentRuntimeOption, AgentRuntimeProvider } from '@/service/agent';

export const CHAT2DB_AGENT_AVATAR = './logo-transparent.webp';
export const LEGACY_CHAT2DB_AGENT_AVATAR = '/logo-transparent.webp';

const CODEX_PATH =
  'M14.949 6.547a3.94 3.94 0 0 0-.348-3.273 4.11 4.11 0 0 0-4.4-1.934A4.1 4.1 0 0 0 8.423.2 4.15 4.15 0 0 0 6.305.086a4.1 4.1 0 0 0-1.891.948 4.04 4.04 0 0 0-1.158 1.753 4.1 4.1 0 0 0-1.563.679A4 4 0 0 0 .554 4.72a3.99 3.99 0 0 0 .502 4.731 3.94 3.94 0 0 0 .346 3.274 4.11 4.11 0 0 0 4.402 1.933c.382.425.852.764 1.377.995.526.231 1.095.35 1.67.346 1.78.002 3.358-1.132 3.901-2.804a4.1 4.1 0 0 0 1.563-.68 4 4 0 0 0 1.14-1.253 3.99 3.99 0 0 0-.506-4.716m-6.097 8.406a3.05 3.05 0 0 1-1.945-.694l.096-.054 3.23-1.838a.53.53 0 0 0 .265-.455v-4.49l1.366.778q.02.011.025.035v3.722c-.003 1.653-1.361 2.992-3.037 2.996m-6.53-2.75a2.95 2.95 0 0 1-.36-2.01l.095.057L5.29 12.09a.53.53 0 0 0 .527 0l3.949-2.246v1.555a.05.05 0 0 1-.022.041L6.473 13.3c-1.454.826-3.311.335-4.15-1.098m-.85-6.94A3.02 3.02 0 0 1 3.07 3.949v3.785a.51.51 0 0 0 .262.451l3.93 2.237-1.366.779a.05.05 0 0 1-.048 0L2.585 9.342a2.98 2.98 0 0 1-1.113-4.094zm11.216 2.571L8.747 5.576l1.362-.776a.05.05 0 0 1 .048 0l3.265 1.86a3 3 0 0 1 1.173 1.207 2.96 2.96 0 0 1-.27 3.2 3.05 3.05 0 0 1-1.36.997V8.279a.52.52 0 0 0-.276-.445m1.36-2.015-.097-.057-3.226-1.855a.53.53 0 0 0-.53 0L6.249 6.153V4.598a.04.04 0 0 1 .019-.04L9.533 2.7a3.07 3.07 0 0 1 3.257.139c.474.325.843.778 1.066 1.303.223.526.289 1.103.191 1.664zM5.503 8.575 4.139 7.8a.05.05 0 0 1-.026-.037V4.049c0-.57.166-1.127.476-1.607s.752-.864 1.275-1.105a3.08 3.08 0 0 1 3.234.41l-.096.054-3.23 1.838a.53.53 0 0 0-.265.455zm.742-1.577 1.758-1 1.762 1v2l-1.755 1-1.762-1z';

// OpenAI mark from Bootstrap Icons, matching the Codex treatment used by Silieco.
function CodexLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" fill="currentColor" className={className} role="img" aria-label="Codex">
      <path d={CODEX_PATH} />
    </svg>
  );
}

// Official Anthropic mark, matching the provider artwork used by Silieco.
function ClaudeCodeLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" fill="#D97757" className={className} role="img" aria-label="Claude Code">
      <path d="m3.127 10.604 3.135-1.76.053-.153-.053-.085H6.11l-.525-.032-1.791-.048-1.554-.065-1.505-.08-.38-.081L0 7.832l.036-.234.32-.214.455.04 1.009.069 1.513.105 1.097.064 1.626.17h.259l.036-.105-.089-.065-.068-.064-1.566-1.062-1.695-1.121-.887-.646-.48-.327-.243-.306-.104-.67.435-.48.585.04.15.04.593.456 1.267.981 1.654 1.218.242.202.097-.068.012-.049-.109-.181-.9-1.626-.96-1.655-.428-.686-.113-.411a2 2 0 0 1-.068-.484l.496-.674L4.446 0l.662.089.279.242.411.94.666 1.48 1.033 2.014.302.597.162.553.06.17h.105v-.097l.085-1.134.157-1.392.154-1.792.052-.504.25-.605.497-.327.387.186.319.456-.045.294-.19 1.23-.37 1.93-.243 1.29h.142l.161-.16.654-.868 1.097-1.372.484-.545.565-.601.363-.287h.686l.505.751-.226.775-.707.895-.585.759-.839 1.13-.524.904.048.072.125-.012 1.897-.403 1.024-.186 1.223-.21.553.258.06.263-.218.536-1.307.323-1.533.307-2.284.54-.028.02.032.04 1.029.098.44.024h1.077l2.005.15.525.346.315.424-.053.323-.807.411-3.631-.863-.872-.218h-.12v.073l.726.71 1.331 1.202 1.667 1.55.084.383-.214.302-.226-.032-1.464-1.101-.565-.497-1.28-1.077h-.084v.113l.295.432 1.557 2.34.08.718-.112.234-.404.141-.444-.08-.911-1.28-.94-1.44-.759-1.291-.093.053-.448 4.821-.21.246-.484.186-.403-.307-.214-.496.214-.98.258-1.28.21-1.016.19-1.263.112-.42-.008-.028-.092.012-.953 1.307-1.448 1.957-1.146 1.227-.274.109-.477-.247.045-.44.266-.39 1.586-2.018.956-1.25.617-.723-.004-.105h-.036l-4.212 2.736-.75.096-.324-.302.04-.496.154-.162 1.267-.871z" />
    </svg>
  );
}

function OpenCodeLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} role="img" aria-label="OpenCode">
      <path d="M18 18H6V6H18V18Z" fill="#CFCECD" />
      <path d="M18 3H6V18H18V3ZM24 24H0V0H24V24Z" fill="#656363" />
    </svg>
  );
}

function PiLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 800 800" fill="none" className={className} role="img" aria-label="Pi">
      <rect width="800" height="800" rx="150" fill="#09090b" />
      <path fill="#fff" fillRule="evenodd" d="M165.29 165.29H517.36V400H400V517.36H282.65V634.72H165.29ZM282.65 282.65V400H400V282.65Z" />
      <path fill="#fff" d="M517.36 400H634.72V634.72H517.36Z" />
    </svg>
  );
}

// DeepSeek whale mark, kept inline for the offline Community desktop package.
function DshLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 27 23" className={className} role="img" aria-label="DeepSeek Harness">
      <path
        fill="currentColor"
        d="M26.5174 3.39471C26.235 3.2567 26.1137 3.52006 25.9487 3.65346C25.8923 3.69659 25.8446 3.75294 25.7969 3.80469C25.3846 4.24516 24.9027 4.53439 24.2737 4.49989C23.3536 4.44814 22.5682 4.73737 21.8735 5.44119C21.7258 4.57349 21.2353 4.0554 20.4889 3.72304C20.0985 3.55054 19.7034 3.37746 19.4297 3.00197C19.2388 2.73459 19.1865 2.43673 19.091 2.14289C19.0301 1.96579 18.9697 1.78466 18.7656 1.75418C18.5442 1.71968 18.4574 1.90541 18.3705 2.06067C18.0232 2.69549 17.8887 3.39471 17.9019 4.10313C17.9324 5.6965 18.6051 6.96556 19.9421 7.86834C20.0939 7.97184 20.133 8.07535 20.0852 8.22658C19.9938 8.53766 19.8857 8.83955 19.7903 9.15063C19.7293 9.34901 19.6384 9.39271 19.4257 9.30588C18.692 8.9994 18.0583 8.54571 17.4982 7.99772C16.5477 7.07827 15.6881 6.06336 14.6162 5.26869C14.3644 5.08296 14.1125 4.91045 13.8521 4.746C12.7584 3.68394 13.9952 2.81164 14.2816 2.70814C14.5812 2.60003 14.3857 2.22857 13.4179 2.23317C12.4502 2.2372 11.5646 2.56151 10.4359 2.99335C10.2708 3.05832 10.0972 3.10547 9.91951 3.14457C8.8954 2.95022 7.83162 2.90709 6.72069 3.03245C4.62877 3.26533 2.95777 4.25436 1.72954 5.94261C0.254043 7.97184 -0.0932678 10.2777 0.33167 12.6824C0.778458 15.2171 2.07225 17.3153 4.06008 18.9558C6.12152 20.6567 8.49577 21.4905 11.2047 21.3306C12.8498 21.2358 14.6812 21.0155 16.7473 19.2669C17.2682 19.5262 17.8151 19.6297 18.7219 19.7074C19.4205 19.7723 20.0933 19.6729 20.6143 19.5648C21.4302 19.3923 21.3739 18.6367 21.0789 18.4981C18.6874 17.3843 19.2124 17.8374 18.7351 17.4706C19.9501 16.033 21.8063 13.4776 22.379 9.99821C22.4353 9.61409 22.5072 9.073 22.4986 8.76192C22.494 8.57216 22.5377 8.49856 22.7545 8.47671C23.3536 8.40771 23.935 8.24383 24.4692 7.94999C26.0188 7.10357 26.6439 5.71318 26.7911 4.04678C26.8129 3.79204 26.7865 3.52869 26.5174 3.39471ZM13.0143 18.3946C10.6964 16.5724 9.5722 15.9726 9.10816 15.9985C8.67402 16.0244 8.75222 16.5212 8.84768 16.8449C8.94773 17.1646 9.07768 17.3849 9.25996 17.6655C9.38589 17.8512 9.47272 18.1272 9.13404 18.3348C8.38766 18.7965 7.08985 18.1796 7.0289 18.1491C5.51833 17.2595 4.25559 16.0853 3.36546 14.4793C2.50581 12.9337 2.0067 11.2753 1.92447 9.50542C1.90262 9.07818 2.02855 8.92695 2.45406 8.84932C3.01413 8.74582 3.59144 8.72397 4.15093 8.80619C6.51656 9.15178 8.53027 10.2092 10.2185 11.8848C11.1822 12.8388 11.9114 13.979 12.6623 15.0929C13.461 16.2757 14.3201 17.4027 15.4144 18.3268C15.8008 18.6505 16.109 18.8966 16.404 19.0783C15.5144 19.1778 14.0297 19.1991 13.0143 18.3958V18.3946ZM14.1252 11.2489C14.1252 11.0591 14.277 10.9079 14.4679 10.9079C14.511 10.9079 14.5501 10.9165 14.5852 10.9292C14.6329 10.9464 14.6766 10.9723 14.7111 11.0114C14.7721 11.0718 14.8066 11.158 14.8066 11.2489C14.8066 11.4386 14.6548 11.5899 14.4639 11.5899C14.273 11.5899 14.1252 11.4386 14.1252 11.2489ZM17.5759 13.0188C17.3545 13.1096 17.1331 13.1873 16.9203 13.1959C16.5903 13.2131 16.2303 13.0791 16.0348 12.9153C15.7312 12.6605 15.5139 12.5179 15.423 12.0734C15.3839 11.8837 15.4057 11.5899 15.4402 11.4214C15.5185 11.0585 15.4316 10.8257 15.1757 10.614C14.9676 10.4415 14.7025 10.3938 14.4115 10.3938C14.3029 10.3938 14.2034 10.3461 14.1292 10.3076C14.0079 10.2472 13.9078 10.096 14.0033 9.91023C14.0338 9.84985 14.1815 9.70322 14.216 9.67734C14.6111 9.45251 15.0665 9.52612 15.488 9.6946C15.8784 9.85445 16.174 10.1477 16.5989 10.5623C17.033 11.0631 17.1112 11.2011 17.3585 11.5772C17.554 11.871 17.7317 12.1729 17.8536 12.5185C17.9272 12.7341 17.8317 12.9107 17.5759 13.0188Z"
      />
    </svg>
  );
}

// NousResearch Hermes mascot used by Silieco. Keeping it embedded preserves Community offline behavior.
const HERMES_ICON =
  'data:image/webp;base64,UklGRuYDAABXRUJQVlA4INoDAADQEwCdASowADAAPm0uk0ckIiGhKrqpWIANiWkAEyQea/fD8IewvGL5V7Nb3H8u/MA8G9rT+4flL+UfIe42/23GB8zPqF/o3E0UAP5h/h/+F6ZX+x5evnX/r+4T/K/67/u/zg7znogfrusMANZLkn1gvlY/vNsKubtj/9xLSzxTsLr7K9GLdFNs5rwtISRcPXvH4z57n2fg0XR3aQ2D+pPpycwyl7TwAAD+/2DbjivnePzfyHCsdOgJXKlUR/OgAkofD7K4AdmsPKyP5Ml4/4HBYmIm5/efn/H+X3IZtngyaUOvwbFuRS/1yODFYO3vf3qeXGgPdfgIROXd/EPT7K2jysfvY9N71+w6g2gBPs+P6lxYkPf6S9QfpvH/7Pp7i8xRh0nVDBTEQyczSz7V9hoqo4nDJuii+SfibZRR/d5zB+9jkcb1DNN7YnC5Y7+WfGrE3eseXt3hSm+NS5++m1MHbjsrd9z/Q4HPRP/C85Po41XObalGyIUcFUL2j2n3uI/Yh6U8r6trCUJFB4kT3fsv6+8ylX/d96y2hq869FCXLjq4YqEO8vs5BtT52sf7KyDxPAWkH/b06YbfVXf4/7y5THL6Sr/4mOrrY9P2LW81f05HHFN8n0jcyqKOH7AluMm0AHPgFyz8RVrfBdmnPiC2FLMQfNDte5yGFzGC3fMlDed/tS/PO3Q/hjsNLvAXUUjqHyCo3JeN69jyNgWjjf8iUqoBsXT+lJyp2r8p60ad1jxhNyTblyJwda8aWEw1hFDeGjpMGguDF66RL4c+ZO+PhculC6WxvCsZ7IPAsdD7/ywx3w3AowJ66hAAK7k+m6X2QV06OVOCwyIGERex/AUyuBbLUK93X58+M+Si7YfYjVYGpoJ7JvSgD8ExaA21z9OY+si+1wreacDanKnFDmhwBQC3t6MLeXCOGp3VURDKl10K7tdKHQcb4hr48ba+1x/MrMRwHfq3IQrDIXPYCg4b0OLnVN9JyXttKGM63B5imIdKuU0r6hhSslT10lGLjnIJuwO5WKR0RHs+BX5vs6H63y3K7IuuZ1eRN+Aczvbs4QuDs6ZRuzjJ/1DJ5R/3ZrFPrtxvMwT06vAXIgcbhLGNLhOQUYRPdUN5MgyCtL5NH71ArTPLRRkIjhGwoCYXKKqlqIKKT9NX3vwp/nlh4SX71dlYg/mPXbJ9bMeVugyjqFahjFTJ/rT3HtBCWG8h+OvvbOFDFKurCG9BOhO9B719OS7zsP0KPqoymnv7hVvoJyZp0iziCbBvaJpmF9Cvfs8/vWqWr7TUo616WfMW+X9nkgpuqtnfAAAAAA==';

function unsupportedProvider(provider: never): never {
  throw new Error(`Unsupported Agent Runtime provider: ${provider}`);
}

export function runtimeProviderAvatar(provider: AgentRuntimeProvider) {
  return `agent-runtime://${provider}`;
}

export function avatarRuntimeProvider(avatar?: string): AgentRuntimeProvider | undefined {
  const provider = avatar?.match(/^agent-runtime:\/\/(CLAUDE_CODE|CODEX|OPENCODE|PI|HERMES|DSH)$/)?.[1];
  return provider as AgentRuntimeProvider | undefined;
}

export function agentAvatarSource(avatar?: string) {
  return avatar === LEGACY_CHAT2DB_AGENT_AVATAR ? CHAT2DB_AGENT_AVATAR : avatar;
}

export function isDefaultAgentAvatar(avatar?: string) {
  if (!avatar || avatar === CHAT2DB_AGENT_AVATAR || avatar === LEGACY_CHAT2DB_AGENT_AVATAR) return true;
  return (['CLAUDE_CODE', 'CODEX', 'OPENCODE', 'PI', 'HERMES', 'DSH'] as AgentRuntimeProvider[])
    .some((provider) => avatar === runtimeProviderAvatar(provider));
}

export function agentRuntimeAvatar(
  runtimeType: 'EMBEDDED_SPRING_AI' | 'EXTERNAL_AGENT' | undefined,
  runtimeProfileId: string | undefined,
  options: AgentRuntimeOption[],
) {
  if (runtimeType !== 'EXTERNAL_AGENT') return CHAT2DB_AGENT_AVATAR;
  const provider = options.find((option) => option.profileId === runtimeProfileId)?.provider;
  return provider ? runtimeProviderAvatar(provider) : undefined;
}

export function RuntimeProviderLogo({
  provider,
  className,
}: {
  provider: AgentRuntimeProvider;
  className?: string;
}) {
  switch (provider) {
    case 'CLAUDE_CODE':
      return <ClaudeCodeLogo className={className} />;
    case 'CODEX':
      return <CodexLogo className={className} />;
    case 'OPENCODE':
      return <OpenCodeLogo className={className} />;
    case 'PI':
      return <PiLogo className={className} />;
    case 'HERMES':
      return <img src={HERMES_ICON} alt="Hermes" className={className} />;
    case 'DSH':
      return <DshLogo className={className} />;
    default:
      return unsupportedProvider(provider);
  }
}

export function runtimeProviderName(provider: AgentRuntimeProvider) {
  switch (provider) {
    case 'CLAUDE_CODE': return 'Claude Code';
    case 'CODEX': return 'Codex';
    case 'OPENCODE': return 'OpenCode';
    case 'PI': return 'Pi';
    case 'HERMES': return 'Hermes';
    case 'DSH': return 'DeepSeek Harness';
    default: return unsupportedProvider(provider);
  }
}
