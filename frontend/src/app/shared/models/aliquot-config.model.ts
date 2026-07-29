export type AliquotMode = 'NONE' | 'WHOLE_MONTH' | 'PER_DAY';

export interface AliquotConfig {
  semesterId: string;
  mode: AliquotMode;
}
