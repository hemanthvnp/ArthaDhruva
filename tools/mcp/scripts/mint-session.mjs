// Mints a session token for a LOCAL stack whose JWT secret you set yourself (as CI's loadtest job does).
// It is useless against any deployment you did not configure, by design: the secret is the credential.
//   JWT_SECRET=... node scripts/mint-session.mjs <orgId> [username=admin] [role=ADMIN] [sessionVersion=0]
// Prints the token on stdout, for RISK_ENGINE_SESSION.
import { createHmac } from 'node:crypto';

const secret = process.env.JWT_SECRET;
const [org, username = 'admin', role = 'ADMIN', sv = '0'] = process.argv.slice(2);
if (!secret || !org) {
  process.stderr.write('usage: JWT_SECRET=... node scripts/mint-session.mjs <orgId> [username] [role] [sessionVersion]\n');
  process.exit(1);
}
const b64 = (s) => Buffer.from(s).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const header = b64(JSON.stringify({ alg: 'HS256' }));
const payload = b64(JSON.stringify({ sub: username, role, org: Number(org), sv: Number(sv), auth_time: now, iat: now, exp: now + 3600 }));
const signature = createHmac('sha256', Buffer.from(secret, 'utf8')).update(`${header}.${payload}`).digest('base64url');
process.stdout.write(`${header}.${payload}.${signature}\n`);
