const CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const SHAPE = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/;

/** The check character of a GSTIN: the GSTN's base-36 weighted sum over its first 14 characters. */
export function gstinCheckCharacter(first14: string): string {
  let sum = 0;
  for (let i = 0; i < first14.length; i++) {
    const product = CHARACTERS.indexOf(first14[i]) * (i % 2 === 0 ? 1 : 2);
    sum += Math.floor(product / 36) + (product % 36);
  }
  return CHARACTERS[(36 - (sum % 36)) % 36];
}

/** A well-formed GSTIN whose last character checks out. Uppercase, as the forms normalise it. */
export function isValidGstin(gstin: string): boolean {
  return SHAPE.test(gstin) && gstin[14] === gstinCheckCharacter(gstin.slice(0, 14));
}
