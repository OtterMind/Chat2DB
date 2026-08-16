import createRequest from '../base';
import { IUserVO, CountryItem } from '@/typings/enterprise/user';

/** User query */
// const getUser = createRequest<{ id: number }, Partial<IUserVO>>(`/api/user`, {method: 'get' });

/** Password reset */
const userResetPassword = createRequest<{ email: string; passcode: string; newPassword: string }, Partial<IUserVO>>(
  `/api/user/reset_password`,
  {
    method: 'post',
    errorLevel: 'toast',
  },
);

/** Update user information */
const updateUser = createRequest<Partial<IUserVO>, Partial<IUserVO>>(`/api/user/update`, {
  method: 'post',
  errorLevel: 'toast',
});

/** Send mobile phone verification code */
const sendPhoneSMS = createRequest<{ phoneNumber: string }, number>(`/api/user/send_sms`, {
  errorLevel: 'toast',
});

/** Send mobile phone verification code */
const sendEmailSMS = createRequest<{ email: string }, number>(`/api/user/send_sms_email`, {
  errorLevel: 'toast',
});

/** Obtain WeChat QR code */
const getWechatQRCode = createRequest<null, { img: string }>(`/api/user/wechat_qr`, {
  errorLevel: 'toast',
});

/** Generate invitation link */
export const generateInviteLink =
  __RUNTIME_ENV__ === 'community'
    ? async () => ({ url: '' })
    : createRequest<{ organizationId: number }, { url: string }>(`/api/user/invite`, {
        method: 'post',
        errorLevel: 'toast',
      });

/** The front end passes the Token to the server */
// Desktop obtains the third-party login token from the gateway and forwards it to the server.
const setAuthorizationToken = createRequest<{ token: string }, void>('/api/oauth/login_token', {
  method: 'post',
  errorLevel: 'toast',
});

// Get list of countries
const getCountries = createRequest<void, CountryItem[]>('/api/oauth/get_countries', {
  method: 'get',
  errorLevel: 'toast',
});

// setCountry
const setCountry = createRequest<{ country: string }, void>('/api/oauth/set_country', {
  method: 'post',
  errorLevel: 'toast',
});

export default {
  userResetPassword,
  updateUser,
  sendPhoneSMS,
  sendEmailSMS,
  getWechatQRCode,
  generateInviteLink,
  setAuthorizationToken,
  getCountries,
  setCountry,
};
