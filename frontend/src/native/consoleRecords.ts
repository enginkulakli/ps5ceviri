export type ConsoleRecord = {
  id: string;
  name: string;
  host: string;
  registered: boolean;
  identity?: string | null;
  pairingReference?: string;
};

export type ConsolePairing = { reference: string; identity: string | null; registered: boolean };
export type DiscoveredConsole = { name: string; host: string; state: "ready" | "standby"; identity: string | null };
export type ConsoleRecords = { consoles: ConsoleRecord[]; selectedConsoleId: string | null };

export function validConsoleAddress(address: string): boolean {
  const octets = address.split(".");
  return octets.length === 4 &&
    octets.every(part => /^(0|[1-9][0-9]{0,2})$/.test(part) && Number(part) <= 255) &&
    Number(octets[0]) >= 1 && Number(octets[0]) <= 223 && octets[0] !== "127";
}

export function credentialSlot(console: ConsoleRecord): string {
  return console.pairingReference ?? (validConsoleAddress(console.id) ? console.id : console.host);
}

export function nextConsoleId(consoles: ConsoleRecord[], prefix: string): string {
  let id = prefix;
  for (let suffix = 2; consoles.some(console => console.id === id); suffix++) id = prefix + ":" + suffix;
  return id;
}

export function mergeByConsoleIdentity({ consoles, selectedConsoleId }: ConsoleRecords): ConsoleRecords {
  const groups = new Map<string, ConsoleRecord[]>();
  for (const console of consoles) {
    const key = console.identity ? "mac:" + console.identity : "record:" + console.id;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(console);
  }
  const merged = Array.from(groups.values(), group => {
    const selected = group.find(console => console.id === selectedConsoleId);
    const keeper = (selected?.registered ? selected : group.find(console => console.registered)) ?? selected ?? group[0];
    if (selected) selectedConsoleId = keeper.id;
    return keeper;
  });
  return { consoles: merged, selectedConsoleId };
}

export function applyPairingRecords(records: ConsoleRecords, pairings: ConsolePairing[]): ConsoleRecords {
  const byReference = new Map(pairings.map(pairing => [pairing.reference, pairing]));
  const consoles = records.consoles.map(console => {
    const pairing = byReference.get(credentialSlot(console));
    if (!pairing) return console;
    const identity = pairing.identity ?? console.identity;
    if (identity === console.identity && pairing.registered === console.registered) return console;
    return { ...console, identity, registered: pairing.registered, pairingReference: pairing.reference };
  });
  return mergeByConsoleIdentity({ ...records, consoles });
}

export function applyConsoleDiscovery(records: ConsoleRecords, found: DiscoveredConsole[],
  pairings: ConsolePairing[]): ConsoleRecords {
  if (!found.length) return { consoles: records.consoles, selectedConsoleId: records.selectedConsoleId };
  const saved = mergeByConsoleIdentity(records);
  const consoles = [...saved.consoles];
  const identities = new Map<string, DiscoveredConsole[]>();
  for (const sighting of found) {
    const key = sighting.identity ?? sighting.host;
    if (!identities.has(key)) identities.set(key, []);
    identities.get(key)!.push(sighting);
  }
  for (const sightings of identities.values()) {
    const identity = sightings[0].identity;
    const known = identity ? consoles.find(console => console.identity === identity) : undefined;
    const sighting = sightings.find(found => found.host === known?.host && found.state === "ready") ??
      sightings.find(found => found.state === "ready") ?? sightings.find(found => found.host === known?.host) ?? sightings[0];
    const atAddress = consoles.filter(console => console.host === sighting.host);
    if (!identity && atAddress.length) continue;
    const reference = identity ? "mac-" + identity : sighting.host;
    const paired = pairings.some(pairing => pairing.reference === reference && pairing.registered &&
      identity !== null && pairing.identity === identity);
    const legacy = atAddress.find(console => !console.identity && console.registered);
    // A legacy pairing contains no hardware ID. Let a successful session prove it.
    if (!known && legacy && !paired) continue;
    const unpaired = atAddress.find(console => !console.identity && !console.registered);
    const target = known ?? unpaired;
    if (target) {
      consoles[consoles.indexOf(target)] = { ...target, host: sighting.host, identity,
        registered: target.registered || paired, pairingReference: target.registered ? credentialSlot(target) : reference };
    } else {
      const id = nextConsoleId(consoles, identity ? "ps5:" + identity : "lan:" + sighting.host);
      consoles.push({ id, name: sighting.name, host: sighting.host, identity, registered: paired, pairingReference: reference });
    }
  }
  return mergeByConsoleIdentity({ consoles, selectedConsoleId: saved.selectedConsoleId ?? consoles[0]?.id ?? null });
}

export function changeConsoleAddress(records: ConsoleRecords, id: string, address: string): ConsoleRecords {
  if (!validConsoleAddress(address)) throw new Error("Geçerli bir PS5 IPv4 adresi girin.");
  const current = records.consoles.find(console => console.id === id);
  if (!current) throw new Error("Bu PS5 kaydı artık bulunamıyor.");
  const conflict = records.consoles.some(console => console.id !== id && console.host === address &&
    (console.identity ? Boolean(current.identity && console.identity !== current.identity) : console.registered));
  if (conflict) throw new Error("Bu IP adresi başka bir PS5 kaydıyla eşleşiyor.");
  const reference = current.registered || current.identity ? credentialSlot(current) : address;
  return {
    consoles: records.consoles.map(console => console.id === id
      ? { ...current, host: address, pairingReference: reference } : console),
    selectedConsoleId: records.selectedConsoleId,
  };
}
