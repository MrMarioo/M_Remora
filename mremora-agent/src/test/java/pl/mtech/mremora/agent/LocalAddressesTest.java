package pl.mtech.mremora.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.mtech.mremora.agent.LocalAddresses.Candidate;

class LocalAddressesTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Candidate nic(String name, String address) {
        return nic(name, name, address);
    }

    private static Candidate nic(String name, String displayName, String address) {
        return new Candidate(name, displayName, ip(address), true, false, false);
    }

    @Test
    void loopbackAndIpv6Skipped() {
        List<Candidate> candidates = List.of(
                new Candidate("lo", "lo", ip("127.0.0.1"), true, true, false),
                nic("wlan0", "fe80::1"),
                nic("eth0", "2001:db8::1"),
                nic("wlan0", "192.168.1.20"));

        assertThat(LocalAddresses.pick(candidates)).containsExactly("192.168.1.20");
    }

    @Test
    void siteLocalFirst() {
        List<Candidate> candidates = List.of(
                nic("eth0", "8.8.4.4"),
                nic("eth1", "169.254.3.4"),
                nic("eth2", "10.0.0.5"),
                nic("wlan0", "172.20.0.2"),
                nic("wlan1", "192.168.1.20"));

        assertThat(LocalAddresses.pick(candidates))
                .containsExactly("10.0.0.5", "172.20.0.2", "192.168.1.20", "8.8.4.4", "169.254.3.4");
    }

    @Test
    void dockerBridgeLast() {
        List<Candidate> candidates = List.of(nic("docker0", "172.17.0.1"), nic("wlan0", "192.168.1.20"));

        assertThat(LocalAddresses.pick(candidates)).containsExactly("192.168.1.20", "172.17.0.1");
    }

    @Test
    void virtualNamesAndFlagGoLast() {
        List<Candidate> candidates = List.of(
                nic("br-1a2b", "172.18.0.1"),
                nic("veth123", "10.1.1.1"),
                nic("virbr0", "192.168.122.1"),
                nic("vmnet8", "192.168.56.1"),
                nic("vboxnet0", "192.168.57.1"),
                nic("utun3", "10.9.0.2"),
                nic("wg0", "10.10.0.2"),
                new Candidate("tun0", "tun0", ip("10.8.0.2"), true, false, false),
                new Candidate("ppp0", "ppp0", ip("10.7.0.2"), true, false, true),
                nic("eth0", "8.8.4.4"));

        assertThat(LocalAddresses.pick(candidates))
                .containsExactly(
                        "8.8.4.4",
                        "172.18.0.1",
                        "10.1.1.1",
                        "192.168.122.1",
                        "192.168.56.1",
                        "192.168.57.1",
                        "10.9.0.2",
                        "10.10.0.2",
                        "10.8.0.2",
                        "10.7.0.2");
    }

    @Test
    void windowsHyperVByDisplayNameGoesLast() {
        List<Candidate> candidates = List.of(
                nic("eth3", "Hyper-V Virtual Ethernet Adapter", "172.24.0.1"),
                nic("wlan1", "Intel(R) Wi-Fi 6", "192.168.1.20"));

        assertThat(LocalAddresses.pick(candidates)).containsExactly("192.168.1.20", "172.24.0.1");
    }

    @Test
    void windowsVirtualBoxByDisplayNameGoesLast() {
        List<Candidate> candidates = List.of(
                nic("ethernet_32769", "VirtualBox Host-Only Ethernet Adapter", "192.168.56.1"),
                nic("ethernet_32770", "Realtek PCIe GbE Family Controller", "192.168.1.30"));

        assertThat(LocalAddresses.pick(candidates)).containsExactly("192.168.1.30", "192.168.56.1");
    }

    @Test
    void windowsOtherVirtualDisplayNamesGoLast() {
        List<Candidate> candidates = List.of(
                nic("eth4", "Hyper-V Virtual Ethernet Adapter #2", "172.28.0.1"),
                nic("eth5", "VMware Virtual Ethernet Adapter for VMnet8", "192.168.80.1"),
                nic("net5", "TAP-Windows Adapter V9", "10.8.0.6"),
                nic("net6", "WireGuard Tunnel", "10.10.0.3"),
                nic("eth6", "vEthernet (WSL)", "172.29.0.1"),
                nic("eth7", "Intel(R) Ethernet Connection I219-V", "192.168.1.40"));

        assertThat(LocalAddresses.pick(candidates))
                .containsExactly(
                        "192.168.1.40", "172.28.0.1", "192.168.80.1", "10.8.0.6", "10.10.0.3", "172.29.0.1");
    }

    @Test
    void downInterfaceSkipped() {
        List<Candidate> candidates = List.of(
                new Candidate("eth0", "eth0", ip("192.168.1.5"), false, false, false), nic("wlan0", "192.168.1.20"));

        assertThat(LocalAddresses.pick(candidates)).containsExactly("192.168.1.20");
    }

    @Test
    void duplicatesRemoved() {
        List<Candidate> candidates = List.of(nic("eth0", "192.168.1.20"), nic("wlan0", "192.168.1.20"));

        assertThat(LocalAddresses.pick(candidates)).containsExactly("192.168.1.20");
    }

    @Test
    void emptyWhenNothing() {
        assertThat(LocalAddresses.pick(List.of())).isEmpty();
    }
}
