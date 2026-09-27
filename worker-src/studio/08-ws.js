/**
 * WebSocket early data: the first bytes a client sends, carried in `Sec-WebSocket-Protocol`.
 *
 * A client configured with `?ed=2560` in its path puts its VLESS or Trojan header and the start of
 * the payload into the upgrade request itself, base64url-encoded, instead of waiting for the 101 --
 * one round trip less on every connection, which on a mobile line in Iran is most of the time it
 * takes a page to start. The subscription asks for it from build 18 (04c-studio-sub.js).
 *
 * Accepted only when it decodes to something shaped like a VLESS header (version byte 0) or a Trojan
 * one: anything else in that header is a real subprotocol offer and is none of this worker's
 * business. Capped, so a hostile header cannot make the worker allocate for it.
 */
function studioEarlyData(request) {
  const raw = request && request.headers.get('Sec-WebSocket-Protocol');
  if (!raw || raw.length > 11000) return null;
  let bytes;
  try {
    const bin = atob(raw.replace(/-/g, '+').replace(/_/g, '/'));
    bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  } catch (e) {
    return null;
  }
  if (bytes.byteLength < 18 || bytes.byteLength > 8192) return null;
  if (bytes[0] === 0 || studioIsTrojanHeader(bytes)) return bytes;
  return null;
}

/**
 * Where a tunnel connection's bytes go, in the order a config asks for it.
 *
 * A location config leaves through its country or not at all (04j › no leak). Otherwise directly,
 * then -- only for a destination the Worker cannot reach itself, which is Cloudflare's own address
 * space -- through the operator's own exits, and last through the relay setting.
 */
async function studioDialTunnel(env, ctx, t, host, port, payload, allowFallback) {
  if (t.exitCc) {
    return await studioDialExit(env, ctx, t.exitCc, t.stickyKey, host, port, payload, t.pin);
  }
  if (t.userProxyIP) {
    try {
      return await connectDirect(t.userProxyIP, port, payload);
    } catch (proxyErr) {
      return await connectDirect(host, port, payload);
    }
  }
  try {
    return await connectDirect(host, port, payload);
  } catch (err) {
    if (!allowFallback) throw err;
    // The operator's own exits before the shared public relay, unless the operator chose a relay of
    // their own (04j-studio-exits.js › studioDialOwnRelay).
    const own = t.globalProxyIP === DEFAULT_RELAY
      ? await studioDialOwnRelay(env, ctx, t.stickyKey, host, port, payload)
      : null;
    if (own) return own;
    if (t.globalProxyIP && t.globalProxyIP !== 'none' && t.userProxyIP !== 'none') {
      return await connectDirect(t.globalProxyIP, port, payload);
    }
    throw err;
  }
}

// `request` arrives so this path can hash the device for the admission check and read early data.
// It is optional: the legacy call site passed three arguments, and without it a connection is
// admitted the way an installation with no Durable Object admits it.
async function handleWsTunnel(env, storedData = null, ctx = null, request = null) {
  const socketPair = new WebSocketPair();
  const [clientSock, serverSock] = Object.values(socketPair);
  serverSock.accept();
  serverSock.binaryType = 'arraybuffer';

  const early = studioEarlyData(request);

  /** What this connection is, once it has been admitted. */
  const t = {
    user: null,
    stickyKey: null,
    exitCc: null,
    pin: null,
    userProxyIP: null,
    globalProxyIP: storedData?.proxy_ip || DEFAULT_RELAY,
  };
  let meter = null;
  let presence = null;
  let isDns = false;
  let dns = null;
  let headerDone = false;
  let chunkBuffer = new Uint8Array(0);

  let remoteConnWrapper = { socket: null, connectingPromise: null, retryConnect: null };
  let wsChain = Promise.resolve();
  let wsStopped = false;
  let finished = false;
  let wsQueueBytes = 0, wsQueueItems = 0;
  let currentSocketWriter = null, activeRemoteWriter = null;

  const count = (bytes) => { if (meter) studioMeterAdd(env, ctx, meter, bytes); };

  /**
   * The ONE way this connection ends, whoever ends it: the client, the destination, the quota, a
   * refusal after admission, or the heartbeat finding the socket gone.
   *
   * Until build 18 each of those paths did its own subset of the cleanup, and the ones closed from
   * the server side never released the connection's count -- so a person's last connection in an
   * isolate never "closed", and their usage below the commit threshold was never written.
   */
  const finish = () => {
    if (finished) return;
    finished = true;
    wsStopped = true;
    clearInterval(heartbeat);
    wsQueueBytes = 0;
    wsQueueItems = 0;
    upstreamQueue.clear();
    releaseRemoteWriter();
    try { remoteConnWrapper.socket?.close(); } catch (e) { }
    if (dns) dns.close();
    closeSocketQuietly(serverSock);
    if (meter) {
      if (presence && presence.device) studioMeterDropDevice(meter.meter, presence.device);
      studioMeterLeave(env, ctx, meter);
    }
  };

  const heartbeat = setInterval(() => {
    if (finished) { clearInterval(heartbeat); return; }
    if (serverSock.readyState !== WebSocket.OPEN) { finish(); return; }
    try { serverSock.send(new Uint8Array(0)); } catch (e) { finish(); return; }
    // The meter rate-limits this per person: a flush every 90 s while bytes are pending, a re-read of
    // the row once a minute, the device kept counted as connected.
    if (meter) studioMeterTick(env, ctx, meter);
  }, 15000);

  const releaseRemoteWriter = () => {
    if (activeRemoteWriter) {
      try { activeRemoteWriter.releaseLock(); } catch (e) { }
      activeRemoteWriter = null;
    }
    currentSocketWriter = null;
  };

  const getRemoteWriter = () => {
    const s = remoteConnWrapper.socket;
    if (!s) return null;
    if (s !== currentSocketWriter) {
      releaseRemoteWriter();
      currentSocketWriter = s;
      activeRemoteWriter = s.writable.getWriter();
    }
    return activeRemoteWriter;
  };

  // No reconnect on a failed write. Re-dialling the destination mid-stream hands it the middle of a
  // conversation -- half a TLS record on a fresh TCP connection -- which only ever fails, and slowly.
  // A write that fails ends this connection, and the client opens a new one as it would anyway.
  const upstreamQueue = createUpstreamQueue({
    getWriter: getRemoteWriter,
    releaseWriter: releaseRemoteWriter,
    retryConnect: null,
    closeConnection: () => finish(),
    name: 'WsQueue'
  });

  /**
   * Refuse after the header: logged with its reason, then closed. Before admission nothing has been
   * counted and nothing needs releasing, so `finish()` is safe either way.
   */
  const refuse = (kind, uid, detail) => {
    if (kind) studioLogActivity(env, ctx, { kind, user_uid: uid || null, severity: 'warn', detail: detail || null });
    finish();
  };

  const processWsMessage = async (chunk) => {
    if (finished) return;
    const bytes = chunk.byteLength || 0;

    if (isDns) {
      count(bytes);
      await dns.write(chunk);
      return;
    }

    if (headerDone) {
      count(bytes);
      await upstreamQueue.writeAndAwait(chunk, true);
      return;
    }

    chunkBuffer = concatBytes(chunkBuffer, chunk);
    if (chunkBuffer.byteLength < 24) return;

    // Which protocol is on this socket, decided from the bytes rather than from the address. One
    // endpoint serves both, because a config's transport is what the operator chose and a second
    // protocol should not need a second hostname to reach.
    const isTrojan = studioIsTrojanHeader(chunkBuffer);

    // The whole request line before anything else, for Trojan: it can arrive split across frames,
    // and admitting on the first half ran admission twice -- two counts, two device reports, and a
    // connection that could never be released.
    let req = null;
    if (isTrojan) {
      req = studioParseTrojanRequest(chunkBuffer);
      if (!req) return;
    }

    // The config's own credential first, the user's uuid second (10a-trojan.js › studioVlessUser),
    // or the Trojan hash. A few seconds of cache: this read stood in front of every site a person
    // loads (10a › studioCachedUser).
    let found;
    if (isTrojan) {
      const auth = studioTrojanAuth(chunkBuffer);
      found = await studioCachedUser('t', auth, () => studioTrojanUser(env, auth));
    } else {
      const reqUUID = extractSessionId(chunkBuffer);
      if (!reqUUID) { finish(); return; }
      found = await studioCachedUser('v', reqUUID, () => studioVlessUser(env, reqUUID));
    }

    const admission = await studioAdmitConnection(env, ctx, found, request);
    if (!admission.ok) {
      refuse(admission.kind, admission.uid, admission.detail);
      return;
    }
    const user = admission.user;
    presence = admission.presence;
    t.user = user;
    t.stickyKey = user.uid || user.username;
    t.exitCc = studioValidCc(user.studio_exit_cc) ? user.studio_exit_cc : null;
    t.pin = t.exitCc && user.studio_exit_pin
      ? { configId: user.studio_config_id, url: user.studio_exit_pin }
      : (t.exitCc ? { configId: user.studio_config_id, url: null } : null);
    if (user.proxy_ip) t.userProxyIP = user.proxy_ip;

    // What this connection is, for the session row the meter writes when the last one closes. The
    // protocol letter is the one `configs.protocol` stores (R2); the wire is the fallback source.
    meter = studioMeterJoin(user, () => finish(), {
      configId: user.studio_config_id || null,
      protocol: user.studio_protocol || (isTrojan ? atob('dA==') : atob('dg==')),
      transport: user.studio_transport || 'ws',
    }, admission.rowAt);
    if (presence && presence.device) studioMeterAddDevice(meter.meter, presence.device, presence.ip);
    studioMarkOnline(env, ctx, user, meter);
    headerDone = true;

    try {
      let cmd, port, addr, rawData, respHeader;
      if (isTrojan) {
        cmd = req.cmd;
        port = req.port;
        addr = req.addr;
        rawData = req.payload;
        // Trojan's server says nothing before the payload, where VLESS answers with its version byte
        // and a zero. Empty rather than absent, so `connectStreams` stays one function.
        respHeader = req.respHeader;
        // Trojan spells "UDP associate" 0x03 where VLESS spells it 0x02.
        if (cmd === 3) cmd = 2;
      } else {
        let offset = 17;
        const optLen = chunkBuffer[offset++];
        offset += optLen;
        cmd = chunkBuffer[offset++];
        port = (chunkBuffer[offset++] << 8) | chunkBuffer[offset++];
        const addrType = chunkBuffer[offset++];

        addr = '';
        if (addrType === 1) {
          addr = `${chunkBuffer[offset++]}.${chunkBuffer[offset++]}.${chunkBuffer[offset++]}.${chunkBuffer[offset++]}`;
        } else if (addrType === 2) {
          const domainLen = chunkBuffer[offset++];
          addr = new TextDecoder().decode(chunkBuffer.slice(offset, offset + domainLen));
          offset += domainLen;
        } else if (addrType === 3) {
          // IPv6. Refused until build 18, so an app that dials an IPv6 literal simply failed.
          addr = studioIpv6At(chunkBuffer, offset);
          offset += 16;
        }

        rawData = chunkBuffer.slice(offset);
        respHeader = new Uint8Array([chunkBuffer[0], 0]);
      }
      chunkBuffer = new Uint8Array(0);
      count(rawData.byteLength);

      if (cmd === 2) {
        if (port !== 53) {
          // Only DNS travels as UDP here. Closing at once is what makes an app give QUIC up and fall
          // back to TCP quickly instead of waiting on a stream that will never answer.
          finish();
          return;
        }
        // One resolver connection for the whole stream, pipelined (13-streams.js › studioDnsStream).
        // Answered from the Worker for location configs too: public exits often refuse port 53, and
        // "connected, no internet" was what that looked like.
        isDns = true;
        dns = studioDnsStream(serverSock, respHeader, count);
        if (rawData.byteLength) await dns.write(rawData);
        return;
      }

      // What this engine will not carry at all (10b-guard.js): torrent, mail, private addresses and
      // connection floods -- the traffic that gets a Cloudflare account reported and suspended.
      const refused = studioGuardVerdict(t.stickyKey, addr, port, rawData);
      if (refused) {
        refuse('guard.' + refused, t.stickyKey, user.username);
        return;
      }

      const s = await studioDialTunnel(env, ctx, t, addr, port, rawData, true);
      if (finished) { try { s.close(); } catch (e) { } return; }
      remoteConnWrapper.socket = s;
      // The WebSocket is closed when the DOWNLOAD is done -- after the last bytes were flushed -- not
      // when the remote socket reports closed. The old order raced the two, and the tail of a
      // response that ended with the server's FIN could be dropped on its way out.
      connectStreams(s, serverSock, respHeader, null, count).then(() => finish(), () => finish());
    } catch (e) {
      finish();
    }
  };

  const pushToChain = (task) => {
    wsChain = wsChain.then(task).catch(() => finish());
  };

  serverSock.addEventListener('message', (event) => {
    if (wsStopped || finished) return;
    const size = event.data.byteLength || 0;
    const nextBytes = wsQueueBytes + size;
    const nextItems = wsQueueItems + 1;
    if (nextBytes > UPSTREAM_QUEUE_MAX_BYTES || nextItems > UPSTREAM_QUEUE_MAX_ITEMS) {
      finish();
      return;
    }
    wsQueueBytes = nextBytes;
    wsQueueItems = nextItems;
    pushToChain(async () => {
      wsQueueBytes = Math.max(0, wsQueueBytes - size);
      wsQueueItems = Math.max(0, wsQueueItems - 1);
      if (finished) return;
      await processWsMessage(event.data);
    });
  });

  serverSock.addEventListener('close', () => {
    // Anything the client sent before closing still goes out, then everything is released.
    if (finished) return;
    wsStopped = true;
    pushToChain(async () => {
      if (!finished) await upstreamQueue.awaitEmpty();
      finish();
    });
  });

  serverSock.addEventListener('error', () => finish());

  // The early data is the first message, ahead of anything the socket delivers.
  if (early) pushToChain(async () => { if (!finished) await processWsMessage(early); });

  return new Response(null, { status: 101, webSocket: clientSock });
}

/**
 * Write `last_active` when a person's first connection in this isolate opens, if the row does not
 * already say they were here in the last minute. The meter's flushes keep it fresh after that.
 */
function studioMarkOnline(env, ctx, user, h) {
  if (!h || h.meter.conns.size !== 1) return;
  const now = Date.now();
  if (user.last_active && now - Number(user.last_active) < 60000) return;
  const task = env.DB.prepare('UPDATE users SET last_active = ? WHERE ' + (user.uid ? 'uid = ?' : 'username = ?'))
    .bind(now, user.uid || user.username).run().catch(() => { });
  if (ctx && ctx.waitUntil) ctx.waitUntil(task);
}

// ==========================================================
// موتور انتقال داده (درخواست POST دوطرفه روی HTTP/2)
// ==========================================================
function denyTransport(reason) {
  if (reason) LOG('deny:', reason);
  // پاسخی که شبیه یک سرویس وب عادی است (بدون افشای ماهیت)
  return new Response("OK", { status: 200, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

