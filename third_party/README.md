# Upstream artifacts, kept pristine

## `psiphontunnel-2.0.39.aar`

Psiphon's Go controller as published. **It is not the copy the app builds against.**

It cannot be, because this APK also carries `libv2ray.aar` and both are gomobile builds:
each ships the Java classes `go.Seq` / `go.Universe` / `go.error` and a native library
called `libgojni.so`. Two of each in one APK is a duplicate-class error and a duplicate
native-library error, and neither can be resolved by picking one — the two `libgojni.so`
are different Go programs (Xray, Psiphon), so whichever lost would leave every native
method of its own library unresolvable at runtime.

`app/libs/psiphontunnel-2.0.39-ns.aar` is this file with its gomobile runtime moved from
`go` to `pg` and its native library renamed to `libpgjni.so`. To regenerate it:

```bash
cp third_party/psiphontunnel-2.0.39.aar app/libs/
python scripts/repackage-psiphon-aar.py app/libs/psiphontunnel-2.0.39.aar
rm app/libs/psiphontunnel-2.0.39.aar
```

The script explains every substitution and verifies its own output. Run it again after
upgrading psiphon-tunnel-core, from the new upstream file rather than from the patched one.
