# Third-party notices for the optional libVLC adapter

The iOS dynamic-loader bridge vendors declarations and constants from
KMediaVlc's public `kmediavlc_client.h` stable ABI 2 header. KMediaVlc offers
that client header under the ISC license so independent adapters can use it.

The complete ISC notice is packaged at
`META-INF/LICENSES/kmediavlc-client-api-ISC.txt`.

Upstream declaration:

- https://github.com/SuvioMedia/KMediaVlc/blob/94bbfb82c27a4f3ac96f619e700dc253cc31729e/native/include/kmediavlc_client.h

The `composemediaplayer-libvlc` adapter does not embed or download libVLC or a
KMediaVlc native payload. Platform dependencies supply those separately and
retain their own licenses, notices, corresponding source, relinking material,
and release evidence.
