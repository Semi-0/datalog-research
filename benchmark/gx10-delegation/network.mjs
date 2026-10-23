import { lookup } from 'node:dns/promises';
import { BlockList, isIP } from 'node:net';

const blocked = new BlockList();
for (const [address, prefix] of [['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10],
  ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.168.0.0', 16],
  ['192.0.0.0', 24], ['192.0.2.0', 24], ['198.18.0.0', 15], ['198.51.100.0', 24],
  ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4]]) { blocked.addSubnet(address, prefix); }
const publicIPv6 = new BlockList();
publicIPv6.addSubnet('2000::', 3, 'ipv6');
blocked.addSubnet('2001:db8::', 32, 'ipv6');

export function publicAddress(address) {
  if (isIP(address) === 4) { return !blocked.check(address, 'ipv4'); }
  if (isIP(address) === 6) {
    return publicIPv6.check(address, 'ipv6') && !blocked.check(address, 'ipv6');
  }
  return false;
}

export async function reviewPublicEndpoint(endpoint, resolve = lookup) {
  if (![80, 443].includes(endpoint.port) || !['http', 'https', 'tcp'].includes(endpoint.protocol)) { return 'deny'; }
  const host = endpoint.hostname.toLowerCase().replace(/\.$/, '');
  if (host === 'localhost' || /\.(localhost|local|internal)$/.test(host)) { return 'deny'; }
  if (isIP(host)) {
    if (publicAddress(host)) { return 'allow-once'; }
    return 'deny';
  }
  try {
    const addresses = await resolve(host, { all: true });
    if (addresses.length > 0 && addresses.every(row => publicAddress(row.address))) { return 'allow-once'; }
  } catch { return 'deny'; }
  return 'deny';
}
