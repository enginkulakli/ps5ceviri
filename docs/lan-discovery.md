# Local TV discovery v1

The translation server listens on TCP port 8790 at `/ps5-discovery` while the subtitle service runs. The TV asks Tizen SystemInfo for the current IPv4 address and subnet mask, tries its saved server first if it is still on that subnet, then probes its local IPv4 subnet (at most 253 other hosts in its /24). This works with Tizen 4 WebSockets. The TV's request advertises its identity to Android. Both applications retain the manual IP and port option. Wireless client isolation and different VLANs can prevent discovery.

A first-time pairing starts from “Ağda Cihaz Bul” on the TV. The TV displays an ID and a random 128-bit code. Use the ID to choose the request in Android and enter the displayed code. The code is entered on the two devices and never sent on the wire. Android stores each key encrypted by Android Keystore and the TV stores its key in the widget's local storage. Use the action again to replace a pairing. Each application should use a new random secret for a new pairing.

| Direction | Message |
| --- | --- |
| Client → server | `{"type":"discover","version":1,"tvId":"…","nonce":"…","pair":true}` |
| Server → client | `{"type":"pending"}` (first pairing only, while Android waits for approval) |
| Server → client | `{"type":"challenge","serverId":"…","nonce":"…","host":"…","port":8787,"proof":"…"}` |
| Client → server | `{"type":"authenticate","proof":"…"}` |
| Server → client | `{"type":"accepted","proof":"…"}` |

IDs and nonces are independent 16-byte values encoded as lowercase hexadecimal. Each proof is lowercase HMAC-SHA256 of UTF-8 `ps5-discovery-v1|ROLE|serverId|tvId|tvNonce|serverNonce|host|port`. The key is the decoded 16-byte pairing code. The roles are `server` (challenge), `tv` (authenticate) and `accepted` (accepted). Compare proofs before trusting the address and require the host to match the address probed. Existing clients send `pair:false` and match the stored server ID. Fresh nonces prevent replay.

After verification, close the discovery socket and connect to the advertised subtitle port. The subtitle stream uses the existing WebSocket protocol and remains unencrypted on the local network. If a manual TV-IP filter is configured, a TV that proves its pairing can connect from its verified new IP during a 20-second window. Existing manual clients remain compatible. Reserve 8790 for discovery when selecting a subtitle port.
