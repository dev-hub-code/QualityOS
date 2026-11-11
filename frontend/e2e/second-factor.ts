import { createCipheriv, createHmac, randomBytes } from "node:crypto";

/**
 * What an operator's authenticator app does, for the browser tests. The sealing key is the local profile's
 * (backend/src/main/resources/application-local.yml), which the stack under test runs with.
 */
const SEALING_KEY = Buffer.from("bG9jYWwtZGV2LXNlY29uZC1mYWN0b3Ita2V5LTAwMDE=", "base64");
const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

/** Every operator the tests enrol by hand shares this secret. */
export const TEST_SECRET = Buffer.from("browser-test-seed!!!", "ascii");

export function fromBase32(text: string): Buffer {
  const clean = text.replace(/[\s=]/g, "").toUpperCase();
  const out: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of clean) {
    const value = BASE32.indexOf(char);
    if (value < 0) throw new Error(`Not base32: ${text}`);
    buffer = (buffer << 5) | value;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      out.push((buffer >> bits) & 0xff);
    }
  }
  return Buffer.from(out);
}

/** The six-digit code the app shows now: RFC 6238 with SHA-1 and thirty-second steps. */
export function totp(secret: Buffer, at = Date.now()): string {
  const step = Buffer.alloc(8);
  step.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const hash = createHmac("sha1", secret).update(step).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const binary =
    ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
  return String(binary % 1_000_000).padStart(6, "0");
}

/** The secret as the backend stores it: AES-256-GCM, nonce first, tag last, as a SQL bytea literal. */
export function sealedForSql(secret: Buffer): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", SEALING_KEY, iv);
  const sealed = Buffer.concat([iv, cipher.update(secret), cipher.final(), cipher.getAuthTag()]);
  return `'\\x${sealed.toString("hex")}'::bytea`;
}
