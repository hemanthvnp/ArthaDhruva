/** Mirrors PlatformController's rule: 3 to 39 characters, lower-case letters, digits and hyphens, starting with
 * a letter or digit, and not one of the reserved names. */
export const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{2,38}$/;
const RESERVED = new Set(['platform', 'legacy', 'admin', 'api', 'www', 'app', 'login', 'signup']);

/** A suggested organization slug from a company name: "Delta Credit Union, Inc." becomes "delta-credit-union". */
export function slugFrom(companyName: string): string {
  const words = companyName
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/\b(inc|llc|ltd|plc|corp|co)\b\.?/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return words.slice(0, 39).replace(/-+$/, '');
}

/** Why a slug would be refused, or null when the backend's own checks are the only ones left (uniqueness). */
export function slugProblem(slug: string): string | null {
  if (!SLUG_PATTERN.test(slug)) return '3 to 39 characters: lower-case letters, digits and hyphens, not starting with a hyphen.';
  if (RESERVED.has(slug)) return 'That name is reserved.';
  return null;
}

/** A suggested first admin username from a work email's local part. */
export function usernameFrom(workEmail: string): string {
  return workEmail.split('@')[0].toLowerCase().replace(/[^a-z0-9._-]/g, '').slice(0, 50);
}
