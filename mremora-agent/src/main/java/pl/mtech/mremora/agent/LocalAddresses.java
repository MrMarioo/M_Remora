package pl.mtech.mremora.agent;

import java.lang.System.Logger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds the IPv4 addresses a viewer on the local network can use to reach this machine. */
final class LocalAddresses {

    private static final Logger LOG = System.getLogger(LocalAddresses.class.getName());

    /** Linux/macOS interface names ({@link NetworkInterface#getName()}) of bridges, VMs and tunnels. */
    private static final List<String> VIRTUAL_PREFIXES =
            List.of("docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "utun", "tun", "wg");

    /**
     * Lower-case fragments of {@link NetworkInterface#getDisplayName()} of virtual adapters. On Windows
     * {@code getName()} is synthetic ({@code eth3}, {@code wlan1}, {@code ethernet_32769}), so only the display
     * name tells a Hyper-V/WSL/VirtualBox/VMware/VPN adapter from a real one.
     */
    private static final List<String> VIRTUAL_DISPLAY_FRAGMENTS =
            List.of("hyper-v virtual", "virtualbox", "vmware", "tap-windows", "wireguard", "docker", "wsl");

    /** One address of one network interface, as reported by the OS. */
    record Candidate(
            String interfaceName,
            String displayName,
            InetAddress address,
            boolean up,
            boolean loopback,
            boolean virtual) {}

    private LocalAddresses() {}

    /**
     * Keeps usable IPv4 addresses (interface up, not loopback), without duplicates, ordered: site-local on a
     * physical interface, other physical addresses (incl. link-local), then virtual/bridge interfaces. Input
     * order is kept within each group.
     */
    static List<String> pick(List<Candidate> candidates) {
        Set<String> preferred = new LinkedHashSet<>();
        Set<String> other = new LinkedHashSet<>();
        Set<String> virtual = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (!c.up() || c.loopback() || !(c.address() instanceof Inet4Address)) {
                continue;
            }
            String text = c.address().getHostAddress();
            if (c.virtual() || hasVirtualName(c.interfaceName()) || hasVirtualDisplayName(c.displayName())) {
                virtual.add(text);
            } else if (c.address().isSiteLocalAddress()) {
                preferred.add(text);
            } else {
                other.add(text);
            }
        }
        Set<String> result = new LinkedHashSet<>(preferred);
        result.addAll(other);
        result.addAll(virtual);
        return List.copyOf(result);
    }

    /** Addresses of this machine's network interfaces, best first; empty when they cannot be listed. */
    static List<String> current() {
        try {
            List<Candidate> candidates = new ArrayList<>();
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                boolean up = nic.isUp();
                boolean loopback = nic.isLoopback();
                boolean virtual = nic.isVirtual();
                String displayName = nic.getDisplayName() == null ? "" : nic.getDisplayName();
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    candidates.add(new Candidate(nic.getName(), displayName, address, up, loopback, virtual));
                }
            }
            return pick(candidates);
        } catch (SocketException e) {
            LOG.log(Logger.Level.WARNING, "Cannot list network interfaces", e);
            return List.of();
        }
    }

    private static boolean hasVirtualName(String name) {
        return VIRTUAL_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static boolean hasVirtualDisplayName(String displayName) {
        String lower = displayName.toLowerCase(Locale.ROOT);
        return VIRTUAL_DISPLAY_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
