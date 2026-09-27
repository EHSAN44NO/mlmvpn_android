async function dohQuery(domain, recordType) {
  const cacheKey = `${domain}:${recordType}`;
  if (DNS_CACHE.has(cacheKey)) {
    const cached = DNS_CACHE.get(cacheKey);
    if (Date.now() < cached.expires) return cached.data;
    DNS_CACHE.delete(cacheKey);
  }
  try {
    const typeMap = { 'A': 1, 'AAAA': 28 };
    const qtype = typeMap[recordType.toUpperCase()] || 1;

    const encodeDomain = (name) => {
      const parts = name.endsWith('.') ? name.slice(0, -1).split('.') : name.split('.');
      const bufs = [];
      for (const label of parts) {
        const enc = new TextEncoder().encode(label);
        bufs.push(new Uint8Array([enc.length]), enc);
      }
      bufs.push(new Uint8Array([0]));
      return concatBytes(...bufs);
    };

    const qname = encodeDomain(domain);
    const query = new Uint8Array(12 + qname.length + 4);
    const qview = new DataView(query.buffer);
    qview.setUint16(0, crypto.getRandomValues(new Uint16Array(1))[0]);
    qview.setUint16(2, 0x0100);
    qview.setUint16(4, 1);
    query.set(qname, 12);
    qview.setUint16(12 + qname.length, qtype);
    qview.setUint16(12 + qname.length + 2, 1);

    const response = await fetch(DOH_RESOLVER, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/dns-message',
        'Accept': 'application/dns-message',
      },
      body: query,
    });

    if (!response.ok) return [];

    const buf = new Uint8Array(await response.arrayBuffer());
    const dv = new DataView(buf.buffer);
    const qdcount = dv.getUint16(4);
    const ancount = dv.getUint16(6);

    const parseName = (pos) => {
      const labels = [];
      let p = pos, jumped = false, endPos = -1, safe = 128;
      while (p < buf.length && safe-- > 0) {
        const len = buf[p];
        if (len === 0) { if (!jumped) endPos = p + 1; break; }
        if ((len & 0xC0) === 0xC0) {
          if (!jumped) endPos = p + 2;
          p = ((len & 0x3F) << 8) | buf[p + 1];
          jumped = true;
          continue;
        }
        labels.push(new TextDecoder().decode(buf.slice(p + 1, p + 1 + len)));
        p += len + 1;
      }
      if (endPos === -1) endPos = p + 1;
      return [labels.join('.'), endPos];
    };

    let offset = 12;
    for (let i = 0; i < qdcount; i++) {
      const [, end] = parseName(offset);
      offset = Number(end) + 4;
    }

    const answers = [];
    for (let i = 0; i < ancount && offset < buf.length; i++) {
      const [name, nameEnd] = parseName(offset);
      offset = Number(nameEnd);
      const type = dv.getUint16(offset); offset += 2;
      offset += 2;
      const ttl = dv.getUint32(offset); offset += 4;
      const rdlen = dv.getUint16(offset); offset += 2;
      const rdata = buf.slice(offset, offset + rdlen);
      offset += rdlen;

      let data;
      if (type === 1 && rdlen === 4) {
        data = `${rdata[0]}.${rdata[1]}.${rdata[2]}.${rdata[3]}`;
      } else if (type === 28 && rdlen === 16) {
        const segs = [];
        for (let j = 0; j < 16; j += 2) segs.push(((rdata[j] << 8) | rdata[j + 1]).toString(16));
        data = segs.join(':');
      } else {
        data = Array.from(rdata).map(b => b.toString(16).padStart(2, '0')).join('');
      }
      answers.push({ name, type, TTL: ttl, data });
    }
    DNS_CACHE.set(cacheKey, { data: answers, expires: Date.now() + DNS_CACHE_TTL });
    return answers;
  } catch (e) {
    return [];
  }
}

