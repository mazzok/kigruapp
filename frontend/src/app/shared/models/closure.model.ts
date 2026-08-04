/** Art eines Schliesstags. Generell und immer gueltig, ohne Datumsbezug. */
export interface ClosureDefinition {
  id: string;
  label: string;
  color: string;
  active: boolean;
  createdAt: string;
}

export interface ClosureDefinitionRequest {
  label: string;
  color: string;
  active?: boolean;
}

/** Ein zusammenhaengender Schliesszeitraum, beide Grenzen inklusive. */
export interface ClosurePeriod {
  id: string;
  /** ISO yyyy-MM-dd */
  from: string;
  /** ISO yyyy-MM-dd */
  to: string;
  definitionId: string;
}

export interface ApplyPeriodsRequest {
  /** ISO yyyy-MM-dd, roh wie im Kalender markiert — das Backend normalisiert. */
  days: string[];
  definitionId: string;
  mode: 'assign' | 'remove';
}

export interface Holiday {
  /** ISO yyyy-MM-dd */
  date: string;
  name: string;
}
