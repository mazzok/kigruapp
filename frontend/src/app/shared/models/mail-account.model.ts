export type MailEncryption = 'NONE' | 'STARTTLS' | 'SSL_TLS';

export interface MailAccount {
  id: string;
  name: string;
  host: string;
  port: number;
  encryption: MailEncryption;
  username: string;
  fromAddress: string;
  fromName: string;
  enabled: boolean;
  passwordSet: boolean;
}

export interface SaveMailAccountRequest {
  name: string;
  host: string;
  port: number;
  encryption: MailEncryption;
  username: string;
  password?: string;
  clearPassword?: boolean;
  fromAddress: string;
  fromName: string;
  enabled: boolean;
}
