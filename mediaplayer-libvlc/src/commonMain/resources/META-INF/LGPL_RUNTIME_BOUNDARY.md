# LGPL runtime distribution boundary

`composemediaplayer-libvlc` is an independently implemented KMediaPlayer
adapter. It does not contain the KMediaVlc or libVLC implementation. The
platform dependency graph supplies the separately published
LGPL-2.1-or-later KMediaVlc runtime, whose stable public client header is ISC.

Making the KMediaPlayer source repository private does not change the license
or source-availability requirements of a conveyed KMediaVlc/libVLC binary.
Before distributing an application, bind the application release to one exact
KMediaVlc release and retain all of that release's materials:

1. `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, and every referenced license
   text;
2. the complete corresponding-source archive and exact build recipes for every
   conveyed LGPL component;
3. the replacement/relinking instructions and, where static linkage is used,
   the relinkable application material required by the applicable license;
4. the native inventory and hashes proving which runtime was conveyed.

Application terms must not forbid replacement of an interface-compatible LGPL
library or reverse engineering performed solely to debug modifications to that
library. A locally built or unpublished candidate is not a release merely
because it compiles or passes a simulator test; use only a KMediaVlc artifact
whose platform publication gates are complete.

The controlling license text is included by KMediaVlc and is available at:
https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
