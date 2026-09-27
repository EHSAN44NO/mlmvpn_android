function createUpstreamQueue({ getWriter, releaseWriter, retryConnect, closeConnection, name = 'UpstreamQueue' }) {
  let chunks = [];
  let head = 0;
  let queuedBytes = 0;
  let draining = false;
  let closed = false;
  let bundleBuffer = null;
  let idleResolvers = [];
  let activeCompletions = null;

  const settleCompletions = (completions, err = null) => {
    if (!completions) return;
    for (const comp of completions) {
      if (comp) {
        if (err) comp.reject(err);
        else comp.resolve();
      }
    }
  };

  const rejectQueued = (err) => {
    for (let i = head; i < chunks.length; i++) {
      const item = chunks[i];
      if (item && item.completions) settleCompletions(item.completions, err);
    }
  };

  const compact = () => {
    if (head > 32 && head * 2 >= chunks.length) {
      chunks = chunks.slice(head);
      head = 0;
    }
  };

  const resolveIdle = () => {
    if (queuedBytes || draining || !idleResolvers.length) return;
    const resolvers = idleResolvers;
    idleResolvers = [];
    for (const resolve of resolvers) resolve();
  };

  const clear = (err = null) => {
    const closeErr = err || (closed ? new Error(`${name}: queue closed`) : null);
    if (closeErr) {
      rejectQueued(closeErr);
      settleCompletions(activeCompletions, closeErr);
      activeCompletions = null;
    }
    chunks = [];
    head = 0;
    queuedBytes = 0;
    resolveIdle();
  };

  const shift = () => {
    if (head >= chunks.length) return null;
    const item = chunks[head];
    chunks[head++] = undefined;
    queuedBytes -= item.chunk.byteLength;
    compact();
    return item;
  };

  const bundle = () => {
    const first = shift();
    if (!first) return null;
    if (head >= chunks.length || first.chunk.byteLength >= UPSTREAM_BUNDLE_TARGET_BYTES) return first;

    let byteLength = first.chunk.byteLength;
    let end = head;
    let allowRetry = first.allowRetry;
    let completions = first.completions || null;
    while (end < chunks.length) {
      const next = chunks[end];
      const nextLength = byteLength + next.chunk.byteLength;
      if (nextLength > UPSTREAM_BUNDLE_TARGET_BYTES) break;
      byteLength = nextLength;
      allowRetry = allowRetry && next.allowRetry;
      if (next.completions) completions = completions ? completions.concat(next.completions) : next.completions;
      end++;
    }
    if (end === head) return first;

    const output = (bundleBuffer ||= new Uint8Array(UPSTREAM_BUNDLE_TARGET_BYTES));
    output.set(first.chunk);
    let offset = first.chunk.byteLength;
    while (head < end) {
      const next = chunks[head];
      chunks[head++] = undefined;
      queuedBytes -= next.chunk.byteLength;
      output.set(next.chunk, offset);
      offset += next.chunk.byteLength;
    }
    compact();
    return { chunk: output.subarray(0, byteLength), allowRetry, completions };
  };

  const drain = async () => {
    if (draining || closed) return;
    draining = true;
    try {
      for (; ;) {
        if (closed) break;
        const item = bundle();
        if (!item) break;
        let writer = getWriter();
        if (!writer) throw new Error(`${name}: remote writer unavailable`);
        const completions = item.completions || null;
        activeCompletions = completions;
        try {
          try {
            await writer.write(item.chunk);
          } catch (err) {
            releaseWriter?.();
            if (!item.allowRetry || typeof retryConnect !== 'function') throw err;
            await retryConnect();
            writer = getWriter();
            if (!writer) throw err;
            await writer.write(item.chunk);
          }
          settleCompletions(completions);
        } catch (err) {
          settleCompletions(completions, err);
          throw err;
        } finally {
          if (activeCompletions === completions) activeCompletions = null;
        }
      }
    } catch (err) {
      closed = true;
      clear(err);
      try { closeConnection?.(err); } catch (_) { }
    } finally {
      draining = false;
      if (!closed && head < chunks.length) queueMicrotask(drain);
      else resolveIdle();
    }
  };

  const enqueue = (data, allowRetry = true, waitForFlush = false) => {
    if (closed) return false;
    if (!getWriter()) return false;
    const chunk = convertToUint8Array(data);
    if (!chunk.byteLength) return true;
    const nextBytes = queuedBytes + chunk.byteLength;
    const nextItems = chunks.length - head + 1;
    if (nextBytes > UPSTREAM_QUEUE_MAX_BYTES || nextItems > UPSTREAM_QUEUE_MAX_ITEMS) {
      closed = true;
      const err = Object.assign(new Error(`${name}: upload queue overflow (${nextBytes}B/${nextItems})`), { isQueueOverflow: true });
      clear(err);
      try { closeConnection?.(err); } catch (_) { }
      throw err;
    }
    let completionPromise = null;
    let completions = null;
    if (waitForFlush) {
      completions = [];
      completionPromise = new Promise((resolve, reject) => completions.push({ resolve, reject }));
    }
    chunks.push({ chunk, allowRetry, completions });
    queuedBytes = nextBytes;
    if (!draining) queueMicrotask(drain);
    return waitForFlush ? completionPromise.then(() => true) : true;
  };

  return {
    writeAndAwait(data, allowRetry = true) { return enqueue(data, allowRetry, true); },
    async awaitEmpty() {
      if (!queuedBytes && !draining) return;
      await new Promise(resolve => idleResolvers.push(resolve));
    },
    clear() { closed = true; clear(); }
  };
}

function createDownstreamSender(webSocket, headerData = null) {
  const packetCap = DOWNSTREAM_GRAIN_BYTES;
  const tailBytes = DOWNSTREAM_GRAIN_TAIL_THRESHOLD;
  const lowWaterBytes = Math.max(4096, tailBytes << 3);
  let header = headerData;
  let pendingBuffer = new Uint8Array(packetCap);
  let pendingBytes = 0;
  let flushTimer = null;
  let microtaskQueued = false;
  let generation = 0;
  let scheduledGeneration = 0;
  let waitRounds = 0;
  let flushPromise = null;

  const sendRawChunk = async (chunk) => {
    if (webSocket.readyState !== WebSocket.OPEN) throw new Error('ws.readyState is not open');
    webSocket.send(chunk);
  };

  const attachResponseHeader = (chunk) => {
    if (!header) return chunk;
    const merged = new Uint8Array(header.length + chunk.byteLength);
    merged.set(header, 0);
    merged.set(chunk, header.length);
    header = null;
    return merged;
  };

  const flush = async () => {
    while (flushPromise) await flushPromise;
    if (flushTimer) clearTimeout(flushTimer);
    flushTimer = null;
    microtaskQueued = false;
    if (!pendingBytes) return;
    const output = pendingBuffer.subarray(0, pendingBytes).slice();
    pendingBuffer = new Uint8Array(packetCap);
    pendingBytes = 0;
    waitRounds = 0;
    flushPromise = sendRawChunk(output).finally(() => { flushPromise = null; });
    return flushPromise;
  };

  const scheduleFlush = () => {
    if (flushTimer || microtaskQueued) return;
    microtaskQueued = true;
    scheduledGeneration = generation;
    queueMicrotask(() => {
      microtaskQueued = false;
      if (!pendingBytes || flushTimer) return;
      if (packetCap - pendingBytes < tailBytes) {
        flush().catch(() => closeSocketQuietly(webSocket));
        return;
      }
      flushTimer = setTimeout(() => {
        flushTimer = null;
        if (!pendingBytes) return;
        if (packetCap - pendingBytes < tailBytes) {
          flush().catch(() => closeSocketQuietly(webSocket));
          return;
        }
        if (waitRounds < 2 && (generation !== scheduledGeneration || pendingBytes < lowWaterBytes)) {
          waitRounds++;
          scheduledGeneration = generation;
          scheduleFlush();
          return;
        }
        flush().catch(() => closeSocketQuietly(webSocket));
      }, Math.max(DOWNSTREAM_GRAIN_SILENT_MS, 1));
    });
  };

  return {
    async sendDirect(data) {
      let chunk = convertToUint8Array(data);
      if (!chunk.byteLength) return;
      chunk = attachResponseHeader(chunk);
      await sendRawChunk(chunk);
    },
    async send(data) {
      let chunk = convertToUint8Array(data);
      if (!chunk.byteLength) return;
      chunk = attachResponseHeader(chunk);
      let offset = 0;
      const totalBytes = chunk.byteLength;
      while (offset < totalBytes) {
        if (!pendingBytes && totalBytes - offset >= packetCap) {
          const sendBytes = Math.min(packetCap, totalBytes - offset);
          const view = offset || sendBytes !== totalBytes ? chunk.subarray(offset, offset + sendBytes) : chunk;
          await sendRawChunk(view);
          offset += sendBytes;
          continue;
        }
        const copyBytes = Math.min(packetCap - pendingBytes, totalBytes - offset);
        pendingBuffer.set(chunk.subarray(offset, offset + copyBytes), pendingBytes);
        pendingBytes += copyBytes;
        offset += copyBytes;
        generation++;
        if (pendingBytes === packetCap || packetCap - pendingBytes < tailBytes) await flush();
        else scheduleFlush();
      }
    },
    flush
  };
}

async function waitForBackpressure(ws) {
  if (typeof ws.bufferedAmount === 'number') {
    while (ws.bufferedAmount > 256 * 1024) {
      await new Promise(r => setTimeout(r, 10));
    }
  }
}

async function connectStreams(remoteSocket, webSocket, headerData, retryFunc, onBytes) {
  let header = headerData, hasData = false, reader, useBYOB = false;
  const BYOB_LIMIT = 64 * 1024;
  const downstreamSender = createDownstreamSender(webSocket, header);
  header = null;

  try {
    reader = remoteSocket.readable.getReader({ mode: 'byob' });
    useBYOB = true;
  } catch (e) {
    reader = remoteSocket.readable.getReader();
  }

  // FORCE SEND RESPONSE HEADER
  await downstreamSender.flush();

  try {
    if (!useBYOB) {
      while (true) {
        await waitForBackpressure(webSocket);
        const { done, value } = await reader.read();
        if (done) break;
        if (!value || value.byteLength === 0) continue;
        hasData = true;
        if (typeof onBytes === 'function') onBytes(value.byteLength);
        if (webSocket.isXHTTP) {
          await downstreamSender.sendDirect(value);
        } else {
          await downstreamSender.send(value);
        }
      }
    } else {
      let readBuffer = new ArrayBuffer(BYOB_LIMIT);
      while (true) {
        await waitForBackpressure(webSocket);
        const { done, value } = await reader.read(new Uint8Array(readBuffer, 0, BYOB_LIMIT));
        if (done) break;
        if (!value || value.byteLength === 0) continue;
        hasData = true;
        if (typeof onBytes === 'function') onBytes(value.byteLength);
        if (value.byteLength >= DOWNSTREAM_GRAIN_BYTES || webSocket.isXHTTP) {
          await downstreamSender.flush();
          await downstreamSender.sendDirect(value);
          readBuffer = new ArrayBuffer(BYOB_LIMIT);
        } else {
          await downstreamSender.send(value);
          readBuffer = value.buffer.byteLength >= BYOB_LIMIT ? value.buffer : new ArrayBuffer(BYOB_LIMIT);
        }
      }
    }
    await downstreamSender.flush();
  } catch (err) {
    closeSocketQuietly(webSocket);
  } finally {
    try { reader.cancel(); } catch (e) { }
    try { reader.releaseLock(); } catch (e) { }
  }
  if (!hasData && retryFunc) await retryFunc();
}

/**
 * How long a direct connection may take to open.
 *
 * It was one second, and a destination that took longer -- a server far from the colo, a slow
 * handshake on a busy host -- was treated as unreachable and sent through the shared public relay,
 * which is slower still and changes the address the site sees mid-session. Three seconds is what a
 * real connection needs at worst; a Cloudflare-owned address, the case the fallback exists for,
 * fails at once rather than timing out, so it pays nothing for the wider window.
 */
const STUDIO_CONNECT_TIMEOUT_MS = 3000;

async function connectDirect(address, port, initialData = null, timeoutMs = STUDIO_CONNECT_TIMEOUT_MS) {
  const socket = connect({ hostname: stripIPv6Brackets(address), port });
  let timer;
  try {
    await Promise.race([
      socket.opened,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('timeout')), timeoutMs); }),
    ]);
  } catch (e) {
    // Closed, not abandoned: a socket that loses the race keeps its half-open connection -- and its
    // place in the Worker's limit on simultaneous connections -- until the runtime gets round to it.
    try { socket.close(); } catch (x) { }
    throw e;
  } finally {
    clearTimeout(timer);
  }
  if (initialData && initialData.byteLength > 0) {
    const w = socket.writable.getWriter();
    await w.write(convertToUint8Array(initialData));
    w.releaseLock();
  }
  return socket;
}

/**
 * DNS for one tunnel's UDP stream: ONE TCP connection to the resolver, pipelined.
 *
 * The client frames each query with a two-byte length, which is exactly DNS-over-TCP's framing, so a
 * query is written the moment it arrives and every answer is sent back the moment it comes -- in any
 * order, which DNS matches by id. The old shape opened a new connection for each query and waited
 * for the resolver to hang up before the next query was even read, so every lookup queued behind the
 * one before it; one slow answer stalled a whole page.
 *
 * Answered from the Worker for every config, location ones included. Sending DNS through a location's
 * exit is what made a public exit that refuses port 53 look like "connected, no internet". Sites see
 * the exit's address whatever resolved their name, which is what a location is about.
 *
 * [ws] needs only `readyState` and `send`, so the XHTTP bridge passes itself.
 */
function studioDnsStream(ws, respHeader, onBytes) {
  let sock = null;
  let writer = null;
  let opening = null;
  let closed = false;
  let head = respHeader && respHeader.byteLength ? respHeader : null;

  const open = async () => {
    const s = await connectDirect('8.8.4.4', 53, null);
    sock = s;
    writer = s.writable.getWriter();
    (async () => {
      const reader = s.readable.getReader();
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          if (!value || !value.byteLength) continue;
          if (ws.readyState !== 1) break;
          if (onBytes) onBytes(value.byteLength);
          if (head) {
            ws.send(concatBytes(head, value));
            head = null;
          } else {
            ws.send(convertToUint8Array(value));
          }
        }
      } catch (e) {
      } finally {
        try { reader.releaseLock(); } catch (e) { }
        // The resolver hangs up an idle connection. The next query opens a fresh one.
        if (sock === s) { sock = null; writer = null; }
      }
    })();
  };

  const ensure = async () => {
    if (writer) return;
    if (!opening) opening = open().finally(() => { opening = null; });
    await opening;
  };

  return {
    async write(chunk) {
      if (closed) return;
      const data = convertToUint8Array(chunk);
      if (!data.byteLength) return;
      try {
        await ensure();
        await writer.write(data);
      } catch (e) {
        // One reconnect: an idle connection the resolver closed must not take the next query with it.
        try {
          sock = null;
          writer = null;
          await ensure();
          await writer.write(data);
        } catch (e2) { }
      }
    },
    close() {
      closed = true;
      try { if (writer) writer.releaseLock(); } catch (e) { }
      try { if (sock) sock.close(); } catch (e) { }
      sock = null;
      writer = null;
    },
  };
}

function extractSessionId(data) {
  if (data.byteLength < 17) return null;
  const hex = [...data.slice(1, 17)].map(b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}`;
}

// ==========================================================
// ۹. پوسته ها و کدهای رابط کاربری (HTML TEMPLATES)
// ==========================================================
