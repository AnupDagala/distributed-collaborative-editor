import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
const content = `DATABASE_PASSWORD=${randomBytes(32).toString('hex')}\nREDIS_PASSWORD=${randomBytes(32).toString('hex')}\nJWT_SECRET=${randomBytes(32).toString('base64')}\nALLOWED_ORIGINS=http://localhost:5173\n`;
try { writeFileSync(new URL('../.env', import.meta.url), content, { flag: 'wx', mode: 0o600 }); console.log('Created ignored .env with random local secrets. Existing files are never overwritten.'); }
catch (error) { console.error(error.code === 'EEXIST' ? '.env already exists; kept existing secrets.' : 'Could not write .env'); process.exitCode = 1; }
