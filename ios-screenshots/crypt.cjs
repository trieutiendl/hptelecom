const fs = require('node:fs');
const crypto = require('node:crypto');
const MAGIC = Buffer.from('HP-IOS-GCM1\0');

function keyBytes(hex) {
  if (!/^[a-f0-9]{64}$/i.test(hex || '')) throw new Error('Missing or invalid build key');
  return Buffer.from(hex, 'hex');
}
function encrypt(bytes, key) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', keyBytes(key), iv);
  cipher.setAAD(MAGIC);
  const ciphertext = Buffer.concat([cipher.update(bytes), cipher.final()]);
  return Buffer.concat([MAGIC, iv, cipher.getAuthTag(), ciphertext]);
}
function decrypt(bytes, key) {
  if (bytes.length < MAGIC.length + 28 || !bytes.subarray(0, MAGIC.length).equals(MAGIC)) throw new Error('Invalid encrypted artifact');
  const offset = MAGIC.length;
  const decipher = crypto.createDecipheriv('aes-256-gcm', keyBytes(key), bytes.subarray(offset, offset + 12));
  decipher.setAAD(MAGIC);
  decipher.setAuthTag(bytes.subarray(offset + 12, offset + 28));
  return Buffer.concat([decipher.update(bytes.subarray(offset + 28)), decipher.final()]);
}
module.exports = {encrypt, decrypt};
if (require.main === module) {
  try {
    const [mode, source, target] = process.argv.slice(2);
    if (!['encrypt', 'decrypt'].includes(mode) || !source || !target) throw new Error('Use encrypt/decrypt input output');
    fs.writeFileSync(target, module.exports[mode](fs.readFileSync(source), process.env.HP_IOS_BUILD_KEY), {mode:0o600});
  } catch (e) { console.error(e.message); process.exit(1); }
}
