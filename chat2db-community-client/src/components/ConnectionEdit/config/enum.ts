export enum InputType {
  INPUT = 'input',
  PASSWORD = 'password',
  SELECT = 'select',
  FILE = 'file',
  COLOR = 'color',
  CHECKBOX = 'checkbox',
}

export enum AuthenticationType {
  USERANDPASSWORD = '1',
  NONE = '2',
  PASSWORD = '3',
}

export enum SSHAuthenticationType {
  PASSWORD = 1,
  KEYPAIR = 2,
  OPENSSH = 3
}
