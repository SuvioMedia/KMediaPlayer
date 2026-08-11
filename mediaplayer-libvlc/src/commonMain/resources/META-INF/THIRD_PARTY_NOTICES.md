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
and release evidence. KMediaVlc's project-authored runtime, native bridge, and
packaging code are licensed under LGPL-2.1-or-later; its stable client header is
ISC. KMediaPlayer remains an independently implemented adapter under its own
license.

Distributors of an application containing KMediaVlc/libVLC must ship the exact
runtime's license and notices, preserve the recipient's library replacement and
debugging rights, and make the release-bound corresponding source and relinking
material available under the applicable LGPL terms. See
`META-INF/LGPL_RUNTIME_BOUNDARY.md` in this artifact.
