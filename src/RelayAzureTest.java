// IN2011 Computer Networks
// Coursework 2024/2025
//
// Azure relay smoke test.
// Tests that:
//   1. pushRelay / popRelay work without throwing
//   2. Sending through a relay emits V messages on the wire (visible in Wireshark)
//   3. Our node correctly handles incoming V messages from other nodes
//
// Usage:
//   javac *.java
//   java RelayAzureTest <email> <azure-ip> [port]
//
// NOTE: relay response messages go back to the relay node, not to us,
//       so relay-based read/write operations will time out on our end.
//       Verify relay forwarding via Wireshark rather than return values.

import java.net.InetAddress;

class RelayAzureTest {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java RelayAzureTest <email> <azure-ip> [port]");
            return;
        }
        String email   = args[0];
        String azureIP = args[1];
        int    port    = args.length > 2 ? Integer.parseInt(args[2]) : 20110;

        System.out.println("==================================================");
        System.out.println(" RelayAzureTest - CRN relay smoke test");
        System.out.println("==================================================");
        System.out.println("Email    : " + email);
        System.out.println("Azure IP : " + azureIP);
        System.out.println("Port     : " + port);
        System.out.println("==================================================\n");

        Node node = new Node();
        node.setNodeName("N:" + email);
        node.openPort(port);
        System.out.println("[OK] Node started\n");

        // --- STEP 1: bootstrap ---
        System.out.println("[STEP 1] Waiting for initial contact...");
        node.handleIncomingMessages(5000);
        System.out.println("[OK] Bootstrap complete\n");

        // --- STEP 2: pushRelay / popRelay sanity check ---
        System.out.println("[STEP 2] Testing pushRelay / popRelay...");
        node.pushRelay("N:cyan");
        node.pushRelay("N:violet");
        node.popRelay();
        node.popRelay();
        node.popRelay(); // extra pop on empty stack - should do nothing
        System.out.println("[OK] pushRelay / popRelay work without throwing\n");

        // --- STEP 3: send a message through cyan as relay to violet ---
        // The V message is visible in Wireshark even though we won't get a response
        // back (the response from violet goes to cyan, not us).
        System.out.println("[STEP 3] Sending G request via relay (N:cyan -> N:violet)...");
        System.out.println("         Wireshark should show a V message sent to cyan");
        System.out.println("         and cyan forwarding the inner G to violet.");
        node.pushRelay("N:cyan");
        boolean active = node.isActive("N:violet"); // internally sends G via V to cyan
        node.popRelay();
        System.out.println("[INFO] isActive(N:violet) via relay returned: " + active);
        System.out.println("       (false is expected - response goes to cyan, not us)\n");

        // --- STEP 4: write through relay, read back directly ---
        // The write goes via V so the X response lands on the relay.
        // We then try a direct read to see if the data was stored anyway.
        System.out.println("[STEP 4] Writing through relay then reading back directly...");
        String key   = "D:" + email + ":relay";
        String value = "relay-test-ok";

        node.pushRelay("N:cyan");
        boolean writeResult = node.write(key, value);
        node.popRelay();
        System.out.println("[INFO] write(" + key + ") via relay returned: " + writeResult);
        System.out.println("       (false is expected - X response went to relay)\n");

        // direct read to see if the key landed on the network anyway
        String readBack = node.read(key);
        if (value.equals(readBack)) {
            System.out.println("[OK] Read-back succeeded: " + readBack);
        } else {
            System.out.println("[INFO] Read-back returned: " + readBack);
            System.out.println("       (may be null if the write never reached a closest node)");
        }

        // --- STEP 5: handle incoming messages so our node can act as relay for others ---
        System.out.println("\n[STEP 5] Handling incoming messages (including any V relay requests)...");
        System.out.println("         Press Ctrl+C to stop.");
        node.handleIncomingMessages(0);
    }
}
