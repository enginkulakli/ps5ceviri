import assert from "node:assert/strict";
import test from "node:test";
import {
  applyConsoleDiscovery, applyPairingRecords, changeConsoleAddress, credentialSlot, validConsoleAddress,
} from "../../frontend/src/native/consoleRecords.ts";

const mac = "112233aabbcc";
const otherMac = "a1b2c3d4e5f6";
const oldAddress = "192.168.1.13";
const newAddress = "192.168.1.5";
const legacy = { id: oldAddress, host: oldAddress, name: "Salon PS5", registered: true };
const sighting = (identity, host, state = "ready") => ({ identity, host, state, name: "PS5" });
const records = console => ({ consoles: [console], selectedConsoleId: console.id });

test("A new DHCP lease and duplicate discovery responses keep one known paired record", () => {
  const paired = { ...legacy, identity: mac, pairingReference: "mac-" + mac };
  const before = { consoles: [paired, { ...paired, id: "copy", registered: false }], selectedConsoleId: "copy" };
  const next = applyConsoleDiscovery(before, [sighting(mac, oldAddress, "standby"),
    sighting(mac, newAddress), sighting(mac, newAddress)], []);
  assert.deepEqual(next, records({ ...paired, host: newAddress }));
  assert.equal(credentialSlot(next.consoles[0]), "mac-" + mac);
  assert.equal(before.consoles[0].host, oldAddress);
});

test("Manual edit survives serialization and still uses the original legacy credentials", () => {
  const edited = changeConsoleAddress(records(legacy), legacy.id, newAddress);
  const loaded = JSON.parse(JSON.stringify(edited));
  assert.equal(loaded.consoles[0].host, newAddress);
  assert.equal(loaded.consoles[0].registered, true);
  assert.equal(credentialSlot(loaded.consoles[0]), oldAddress);

  const learned = applyPairingRecords(loaded, [{ reference: oldAddress, identity: mac, registered: true }]);
  const next = applyConsoleDiscovery(learned, [sighting(mac, "192.168.1.28")], []);
  assert.equal(next.consoles[0].host, "192.168.1.28");
  assert.equal(credentialSlot(next.consoles[0]), oldAddress);
});

test("Legacy identity needs verification before merging a discovery record", () => {
  const before = records(legacy);
  assert.deepEqual(applyConsoleDiscovery(before, [sighting(mac, oldAddress)], []), before);
  assert.deepEqual(applyConsoleDiscovery(before, [], []), before);

  const both = applyConsoleDiscovery(before, [sighting(mac, newAddress)], []);
  assert.equal(both.consoles.length, 2);
  const moved = changeConsoleAddress(both, legacy.id, newAddress);
  const verified = applyPairingRecords(moved, [{ reference: oldAddress, identity: mac, registered: true }]);
  assert.equal(verified.consoles.length, 1);
  assert.equal(verified.consoles[0].name, "Salon PS5");
  assert.equal(credentialSlot(verified.consoles[0]), oldAddress);
});

test("Other consoles keep distinct identities even if their names or IPs match", () => {
  const known = records({ ...legacy, name: "PS5", identity: mac, pairingReference: "mac-" + mac });
  const found = applyConsoleDiscovery(known, [sighting(otherMac, oldAddress)], []);
  assert.deepEqual(found.consoles.map(console => console.identity), [mac, otherMac]);
  assert.equal(found.consoles[1].registered, false);
  assert.throws(() => changeConsoleAddress(found, found.consoles[1].id, oldAddress), /başka bir PS5/);
});

test("An unpaired manual address moves without acquiring credentials from its old address", () => {
  const manual = { ...legacy, registered: false, id: "saved" };
  const updated = changeConsoleAddress(records(manual), manual.id, newAddress);
  assert.equal(credentialSlot(updated.consoles[0]), newAddress);
  assert.equal(updated.consoles[0].registered, false);
});

test("Only the matching native slot restores pairing for a discovered console", () => {
  const found = applyConsoleDiscovery({ consoles: [], selectedConsoleId: null },
    [sighting(mac, newAddress), sighting(otherMac, oldAddress)], [
      { reference: "mac-" + mac, identity: mac, registered: true },
    ]);
  assert.deepEqual(found.consoles.map(console => console.registered), [true, false]);
});

test("Invalid, non-canonical, loopback and multicast addresses are rejected", () => {
  assert.equal(validConsoleAddress(newAddress), true);
  for (const address of ["192.168.01.5", "192.168.1.256", "192.168.1", "127.0.0.1", "224.0.0.1", "0.0.0.0", "PS5.local"]) {
    assert.equal(validConsoleAddress(address), false);
    assert.throws(() => changeConsoleAddress(records(legacy), legacy.id, address), /IPv4/);
  }
});
