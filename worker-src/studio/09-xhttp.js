// ==========================================================
// XHTTP: packet-up, and stream-one for clients that insist on it
// ==========================================================
//
// **packet-up** splits a connection into a download GET and a stream of upload POSTs that meet in
// the same isolate. It is what the subscription serves (04c-studio-sub.js), because it is the only
// mode a workers.dev address can carry: every request in it is an ordinary one.
//
// **stream-one** is one POST whose body is the upload and whose response is the download. It is
// served below for a client whose link asks for it, but on workers.dev it cannot connect: measured on
// 2026-09-26, Cloudflare answers the gRPC-typed request Xray sends for it with 403, and without that
// type it holds the request body until the request is complete, so nothing flows both ways. Build 18
// handed it out and XHTTP stopped connecting; the subscription no longer does.
//
// Until build 18 packet-up could not hold a connection much past a minute:
//
//  * a session was deleted 60 seconds after it was CREATED, however busy it was, and every upload
//    POST after that found nothing and was answered OK with its data dropped;
//  * the whole connection ran inside the first POST's `waitUntil`, which Cloudflare ends about thirty
//    seconds after that POST is answered -- taking the destination socket with it;
//  * upload POSTs were written in the order they happened to arrive, not the order they were sent.
//
// Fixed in build 18: the connection runs in the GET's own request (which lives as long as the
// download does), sessions expire on inactivity, and uploads are delivered in sequence. What it cannot
// fix is Cloudflare routing a session's requests to two different isolates, which is rare on one
// HTTP/2 connection and ends that one connection when it happens.

/**
 * Read a VLESS request header off [reader]. `{ version, credential, cmd, port, addr, rawData }`, or
 * null for anything that is not one.
 */
async function studioXhttpReadHeader(reader) {
  let buf = new Uint8Array(0);
  let finished = false;
  const need = async (n) => {
    while (buf.byteLength < n && !finished) {
      const r = await reader.read();
      if (r.done) { finished = true; break; }
      if (r.value && r.value.byteLength) buf = concatBytes(buf, convertToUint8Array(r.value));
    }
    return buf.byteLength >= n;
  };

  if (!(await need(18))) return null;
  const version = buf[0];
  const credential = extractSessionId(buf);
  const optLen = buf[17];
  let offset = 18 + optLen;
  if (!(await need(offset + 4))) return null;
  const cmd = buf[offset++];
  const port = (buf[offset++] << 8) | buf[offset++];
  const addrType = buf[offset++];

  let addr = '';
  if (addrType === 1) {
    if (!(await need(offset + 4))) return null;
    addr = `${buf[offset++]}.${buf[offset++]}.${buf[offset++]}.${buf[offset++]}`;
  } else if (addrType === 2) {
    if (!(await need(offset + 1))) return null;
    const domainLen = buf[offset++];
    if (!(await need(offset + domainLen))) return null;
    addr = new TextDecoder().decode(buf.slice(offset, offset + domainLen));
    offset += domainLen;
  } else if (addrType === 3) {
    if (!(await need(offset + 16))) return null;
    addr = studioIpv6At(buf, offset);
    offset += 16;
  } else {
    return null;
  }
  return { version, credential, cmd, port, addr, rawData: buf.slice(offset) };
}

/**
 * Authenticate and admit, exactly as the WebSocket path does (10-caps.js › studioAdmitConnection):
 * the config's own credential first, so an XHTTP config can carry a credential and a country of its
 * own -- until build 18 this path only knew `users.uuid`, so it could do neither.
 */
async function studioXhttpAdmit(env, ctx, request, hdr) {
  const found = await studioCachedUser('v', hdr.credential, () => studioVlessUser(env, hdr.credential));
  const admission = await studioAdmitConnection(env, ctx, found, request);
  if (!admission.ok && admission.kind) {
    studioLogActivity(env, ctx, { kind: admission.kind, user_uid: admission.uid || null, severity: 'warn', detail: admission.detail || null });
  }
  return admission;
}

/**
 * One XHTTP connection, from an admitted header to the end.
 *
 * [controller] is the download stream: this request's own response in stream-one, the GET's in
 * packet-up. [nextUp] returns the next upload chunk, or null when the upload is over. Runs in the
 * context of whichever request owns [controller], which is what lets it outlive the first POST.
 */
async function studioXhttpBridge(env, ctx, storedData, hdr, admission, controller, nextUp) {
  const user = admission.user;
  const presence = admission.presence;
  const t = {
    user,
    stickyKey: user.uid || user.username,
    exitCc: studioValidCc(user.studio_exit_cc) ? user.studio_exit_cc : null,
    pin: null,
    userProxyIP: user.proxy_ip || null,
    globalProxyIP: (storedData && storedData.proxy_ip) || DEFAULT_RELAY,
  };
  if (t.exitCc) t.pin = { configId: user.studio_config_id, url: user.studio_exit_pin || null };

  let finished = false;
  let socket = null;
  let dns = null;
  let meter = null;
  let tick = null;

  const bridge = {
    readyState: 1,
    isXHTTP: true,
    get bufferedAmount() { return 0; },
    send(data) {
      try { controller.enqueue(convertToUint8Array(data)); } catch (e) { this.readyState = 3; }
    },
    close() {
      if (this.readyState === 3) return;
      this.readyState = 3;
      try { controller.close(); } catch (e) { }
    },
  };

  const finish = () => {
    if (finished) return;
    finished = true;
    if (tick) clearInterval(tick);
    try { socket && socket.close(); } catch (e) { }
    if (dns) dns.close();
    bridge.close();
    if (meter) {
      if (presence && presence.device) studioMeterDropDevice(meter.meter, presence.device);
      studioMeterLeave(env, ctx, meter);
    }
  };
  const count = (bytes) => { if (meter) studioMeterAdd(env, ctx, meter, bytes); };

  meter = studioMeterJoin(user, () => finish(), {
    configId: user.studio_config_id || null,
    protocol: user.studio_protocol || atob('dg=='),
    transport: 'xhttp',
  }, admission.rowAt);
  if (presence && presence.device) studioMeterAddDevice(meter.meter, presence.device, presence.ip);
  studioMarkOnline(env, ctx, user, meter);
  // No WebSocket heartbeat here, so the meter's periodic half gets a timer of its own.
  tick = setInterval(() => { if (!finished) studioMeterTick(env, ctx, meter); }, 15000);

  const respHeader = new Uint8Array([hdr.version, 0]);
  try {
    count(hdr.rawData.byteLength);

    if (hdr.cmd === 2) {
      if (hdr.port !== 53) return;
      dns = studioDnsStream(bridge, respHeader, count);
      if (hdr.rawData.byteLength) await dns.write(hdr.rawData);
      while (!finished) {
        const chunk = await nextUp();
        if (!chunk) break;
        count(chunk.byteLength);
        await dns.write(chunk);
      }
      return;
    }

    const refused = studioGuardVerdict(t.stickyKey, hdr.addr, hdr.port, hdr.rawData);
    if (refused) {
      studioLogActivity(env, ctx, { kind: 'guard.' + refused, user_uid: t.stickyKey, severity: 'warn', detail: user.username });
      return;
    }

    socket = await studioDialTunnel(env, ctx, t, hdr.addr, hdr.port, hdr.rawData, true);
    if (finished) return;

    // The client waits for the response header before it reads anything else.
    bridge.send(respHeader);

    const upPump = (async () => {
      const writer = socket.writable.getWriter();
      try {
        while (!finished) {
          const chunk = await nextUp();
          if (!chunk) break;
          if (!chunk.byteLength) continue;
          count(chunk.byteLength);
          await writer.write(chunk);
        }
      } catch (e) {
      } finally {
        try { writer.releaseLock(); } catch (e) { }
      }
    })();

    await connectStreams(socket, bridge, null, null, count);
    await Promise.race([upPump, new Promise((r) => setTimeout(r, 1000))]);
  } catch (e) {
    dbg(env, ctx, 'xhttp bridge: ' + (e && e.message || e));
  } finally {
    finish();
  }
}

/** The rest of a request body as `nextUp`, for stream-one. */
function studioXhttpBodyReader(reader) {
  let done = false;
  return async () => {
    if (done) return null;
    try {
      const r = await reader.read();
      if (r.done) { done = true; return null; }
      return convertToUint8Array(r.value || new Uint8Array(0));
    } catch (e) {
      done = true;
      return null;
    }
  };
}

/**
 * `POST` with a body on a non-reserved path: stream-one, or packet-up's first POST (`/{session}/0`).
 */
async function handleXHTTP(request, env, storedData = null, ctx = null) {
  if (!request.body) return denyTransport();
  const reader = request.body.getReader();
  const release = () => { try { reader.releaseLock(); } catch (e) { } };

  const hdr = await studioXhttpReadHeader(reader);
  if (!hdr) { release(); return denyTransport(); }
  dbg(env, ctx, 'HDR xhttp cmd=' + hdr.cmd + ' dst=' + hdr.addr + ':' + hdr.port + ' raw=' + hdr.rawData.byteLength);

  const reqUrl = new URL(request.url);
  const matchPost = reqUrl.pathname.match(/^\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/0$/i);

  const admission = await studioXhttpAdmit(env, ctx, request, hdr);
  if (!admission.ok) { release(); return denyTransport(); }

  if (matchPost) {
    // packet-up. Everything after the header in this POST belongs to the upload, ahead of seq 1.
    const session = getOrCreateSession(matchPost[1].toLowerCase());
    const extra = [];
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        if (value && value.byteLength) extra.push(convertToUint8Array(value));
      }
    } catch (e) { }
    release();
    for (const c of extra) studioXhttpQueueFirst(session, c);
    // The GET runs the connection (03-entry.js › the download route). This POST only hands over
    // the header and answers at once: the client does not send seq 1 until it has.
    session.resolveStart({ hdr, admission, storedData });
    return new Response('OK', { status: 200, headers: { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' } });
  }

  // stream-one: one request carries both directions, so the response IS the download.
  const nextUp = studioXhttpBodyReader(reader);
  return new Response(new ReadableStream({
    start(controller) {
      const work = studioXhttpBridge(env, ctx, storedData, hdr, admission, controller, nextUp);
      if (ctx && ctx.waitUntil) ctx.waitUntil(work.catch(() => { }));
    },
    cancel() {
      try { reader.cancel(); } catch (e) { }
    },
  }), {
    status: 200,
    headers: { 'Content-Type': 'application/octet-stream', 'X-Accel-Buffering': 'no', 'Cache-Control': 'no-store' },
  });
}

/**
 * packet-up's download GET: it waits for the session's header POST, then runs the connection in its
 * own request, whose response is the download.
 */
function studioXhttpDownload(env, ctx, sessionId) {
  const session = getOrCreateSession(sessionId);
  return new Response(new ReadableStream({
    start(controller) {
      const work = (async () => {
        const started = await Promise.race([
          session.started,
          new Promise((r) => setTimeout(() => r(null), 10000)),
        ]);
        if (!started) {
          // No header POST reached THIS isolate. An empty download is the honest answer.
          studioXhttpEndSession(session);
          try { controller.close(); } catch (e) { }
          return;
        }
        session.running = true;
        try {
          await studioXhttpBridge(env, ctx, started.storedData, started.hdr, started.admission, controller,
            () => studioXhttpNextUp(session));
        } finally {
          studioXhttpEndSession(session);
        }
      })();
      if (ctx && ctx.waitUntil) ctx.waitUntil(work.catch(() => { }));
    },
    cancel() { studioXhttpEndSession(session); },
  }, { highWaterMark: 1024 * 1024 }), {
    status: 200,
    headers: { 'Content-Type': 'application/octet-stream', 'X-Accel-Buffering': 'no', 'Cache-Control': 'no-store' },
  });
}

// بررسی اینکه آیا کاربر به سقف مصرف/انقضا رسیده است
