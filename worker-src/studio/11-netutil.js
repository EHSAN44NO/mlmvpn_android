function isIPv4(value) {
  const parts = String(value || '').split('.');
  return parts.length === 4 && parts.every(part => /^\d{1,3}$/.test(part) && Number(part) >= 0 && Number(part) <= 255);
}

function stripIPv6Brackets(hostname = '') {
  const host = String(hostname || '').trim();
  return host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
}

function isIPHostname(hostname = '') {
  const host = stripIPv6Brackets(hostname);
  if (isIPv4(host)) return true;
  if (!host.includes(':')) return false;
  try {
    new URL(`http://[${host}]/`);
    return true;
  } catch (e) {
    return false;
  }
}

/** Sixteen bytes at [offset] as an IPv6 address, the form `connect()` takes. */
function studioIpv6At(buf, offset) {
  const seg = [];
  for (let i = 0; i < 8; i++) seg.push(((buf[offset + i * 2] << 8) | buf[offset + i * 2 + 1]).toString(16));
  return seg.join(':');
}

function convertToUint8Array(data) {
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  return new Uint8Array(data || 0);
}

function concatBytes(...chunkList) {
  const chunks = chunkList.map(convertToUint8Array);
  const total = chunks.reduce((sum, c) => sum + c.byteLength, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    result.set(c, offset);
    offset += c.byteLength;
  }
  return result;
}

function closeSocketQuietly(socket) {
  try {
    if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CLOSING) {
      socket.close();
    }
  } catch (e) { }
}

