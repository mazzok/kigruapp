/** Inhalt der Startseite, wie ihn das Backend liefert (Roh-HTML mit Tokens). */
export interface LandingPage {
  bodyHtml: string;
  updatedAt: string | null;
}

/** Eine im Editor einfügbare Platzhalter-Kachel. */
export interface LandingPlaceholder {
  token: string;
  label: string;
  group: string;
}

/** Token → Wert für den angemeldeten Nutzer. */
export type LandingContext = Record<string, string>;

/** Antwort des Bild-Upload-Endpoints. */
export interface LandingPageImageUpload {
  id: string;
  url: string;
}
