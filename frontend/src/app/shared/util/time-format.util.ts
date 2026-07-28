/** Parst "HH:MM" (Minuten 00–59, Dauer > 0) in Gesamtminuten; sonst null. */
export function parseHhmm(text: string): number | null {
  const m = /^(\d{1,2}):([0-5]\d)$/.exec((text ?? '').trim());
  if (!m) {
    return null;
  }
  const total = parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
  return total > 0 ? total : null;
}

/** Formatiert Gesamtminuten als "HH:MM". */
export function formatMinutes(total: number): string {
  const h = Math.floor(total / 60);
  const min = total % 60;
  return `${String(h).padStart(2, '0')}:${String(min).padStart(2, '0')}`;
}

/** Date -> "YYYY-MM-DD" in lokaler Zeit (kein UTC-Versatz). */
export function toIsoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** "YYYY-MM-DD" -> lokales Date; null bei ungültigem Format. */
export function parseIsoDate(iso: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? '');
  if (!m) {
    return null;
  }
  return new Date(parseInt(m[1], 10), parseInt(m[2], 10) - 1, parseInt(m[3], 10));
}

/** "YYYY-MM-DD" -> "DD.MM.YYYY"; gibt die Eingabe unverändert zurück, wenn sie nicht passt. */
export function formatIsoDateDe(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? '');
  return m ? `${m[3]}.${m[2]}.${m[1]}` : iso;
}
