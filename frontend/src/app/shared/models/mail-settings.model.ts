export type MailEncryption = 'NONE' | 'STARTTLS' | 'SSL_TLS';

export interface MailSettings {
  host: string;
  port: number;
  encryption: MailEncryption;
  username: string;
  fromAddress: string;
  fromName: string;
  enabled: boolean;
  passwordSet: boolean;
}

export interface UpdateMailSettingsRequest {
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

export interface MailTestResult {
  success: boolean;
  category: string;
  message: string;
}
