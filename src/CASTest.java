// IN2011 Computer Networks
// Coursework 2024/2025
//
// This is a test program to show how Node.java can be used.
// It creates a number of instances of Node.java, each in their own thread.
// A bootstrapping stage gives each of the nodes the addresses of a few others.
// Then it performs some basic tests on the network.
//
// Running this test is not enough to check that all of the features of your
// implementation work.  You will need to do your own testing as well.
// You are welcome to edit this but your submission must also work with the unedited version.
// You can run this on your own computer or on the virtual lab computers.
// Even when run on the virtual lab computers it will still only communicate between your nodes.
// So it is not suitable to record the wireshark evidence of things working.

import java.lang.Math;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Random;
import java.util.ArrayList;

class CASTest {
    public static void main (String [] args) {
        try {
            int numberOfNodes = 10;

            // If you want to test with more nodes,
            // set the number as a command line argument
            if (args.length > 0) {
                int n = Integer.parseInt(args[0]);
                if (n >= 2 && n <= 10) {
                    numberOfNodes = n;
                } else {
                    // If you want more than 10 nodes, you will need
                    // to change how bootstrapping is done
                }
            }

            // Create an array of nodes and initialise them
            Node [] nodes = new Node [numberOfNodes];
            for (int i = 0; i < numberOfNodes; ++i) {
                nodes[i] = new Node();
                nodes[i].setNodeName("N:test" + i);
                nodes[i].openPort(20110 + i);
            }

            // Bootstrapping so that nodes know the addresses of some of the others
            bootstrap(nodes);

            // Start each of the nodes running in a thread
            // nodes[0] is handled by this program rather than a thread
            for (int i = 1; i < numberOfNodes; ++i) {
                Integer j = i;
                Runnable r = () -> {
                    try {
                        // These nodes just respond to messages
                        nodes[j].handleIncomingMessages(0);
                    } catch (Exception e) {
                        System.err.println("Unhandled exception in node " + j );
                        e.printStackTrace(System.err);
                    }
                };
                Thread t = new Thread(r);
                t.start();
            }

            // test CAS functionality on node[0]
            int passed = 0;
            int total = 0;

            String casKey = "D:cas-test";
            String original = "original";
            String updated = "updated";
            String again = "again";

            nodes[0].write(casKey, original);

            // Test 1: CAS with wrong current value - should return false
            total++;
            boolean result1 = nodes[0].CAS(casKey, "wrong", updated);
            if (!result1) {
                System.out.println("Test 1 PASS");
                passed++;
            } else {
                System.out.println("Test 1 FAIL");
            }

            // Test 2: value should be unchanged after failed CAS
            total++;
            String val2 = nodes[0].read(casKey);
            if (original.equals(val2)) {
                System.out.println("Test 2 PASS");
                passed++;
            } else {
                System.out.println("Test 2 FAIL");
            }

            // Test 3: CAS with correct current value - should return true
            total++;
            boolean result3 = nodes[0].CAS(casKey, original, updated);
            if (result3) {
                System.out.println("Test 3 PASS");
                passed++;
            } else {
                System.out.println("Test 3 FAIL");
            }

            // Test 4: value should now be the new value
            total++;
            String val4 = nodes[0].read(casKey);
            if (updated.equals(val4)) {
                System.out.println("Test 4 PASS");
                passed++;
            } else {
                System.out.println("Test 4 FAIL");
            }

            // Test 5: CAS the updated value again to confirm repeated swaps work
            total++;
            boolean result5 = nodes[0].CAS(casKey, updated, again);
            if (result5) {
                System.out.println("Test 5 PASS");
                passed++;
            } else {
                System.out.println("Test 5 FAIL");
            }

            // Test 6: final value should be 'again'
            total++;
            String val6 = nodes[0].read(casKey);
            if (again.equals(val6)) {
                System.out.println("Test 6 PASS");
                passed++;
            } else {
                System.out.println("Test 6 FAIL");
            }

            System.out.println("\nCAS tests: " + passed + "/" + total + " passed");

            // exists() tests
            int existsPassed = 0;
            int existsTotal = 0;
            System.out.println("\n--- exists() tests ---");

            String existsKey = "D:exists-test";
            nodes[0].write(existsKey, "hello");

            // Test: key we just wrote should exist
            existsTotal++;
            boolean e1 = nodes[0].exists(existsKey);
            if (e1) {
                System.out.println("exists Test 1 PASS");
                existsPassed++;
            } else {
                System.out.println("exists Test 1 FAIL");
            }

            // Test: key that was never written should not exist
            existsTotal++;
            boolean e2 = nodes[0].exists("D:definitely-not-a-key-" + System.currentTimeMillis());
            if (!e2) {
                System.out.println("exists Test 2 PASS");
                existsPassed++;
            } else {
                System.out.println("exists Test 2 FAIL");
            }

            System.out.println("\nexists() tests: " + existsPassed + "/" + existsTotal + " passed");

            // isActive() tests
            int activePassed = 0;
            int activeTotal = 0;
            System.out.println("\n--- isActive() tests ---");

            // Test: a known running node should be active (nodes[1] is N:test1 on port 20111)
            activeTotal++;
            boolean a1 = nodes[0].isActive("N:test1");
            if (a1) {
                System.out.println("isActive Test 1 PASS");
                activePassed++;
            } else {
                System.out.println("isActive Test 1 FAIL");
            }

            // Test: a node name not in dataStore should not be active
            activeTotal++;
            boolean a2 = nodes[0].isActive("N:definitely-not-a-node");
            if (!a2) {
                System.out.println("isActive Test 2 PASS");
                activePassed++;
            } else {
                System.out.println("isActive Test 2 FAIL");
            }

            System.out.println("\nisActive() tests: " + activePassed + "/" + activeTotal + " passed");

        } catch (Exception e) {
            System.err.println("Exception during localTest");
            e.printStackTrace(System.err);
            return;
        }
    }

    // This sends gives each node some initial address key/value pairs
    // You don't need to know how this works
    public static void bootstrap (Node [] nodes) throws Exception {
        int seed = 23; // Change for different initial network topologies
        Random r = new Random(seed);
        int n = nodes.length;
        double p =  Math.log( (double) n+5 ) / (double)n;

        DatagramSocket ds = new DatagramSocket(20099);
        byte[] contents = {0x30, 0x30, 0x20, 0x57, 0x20, 0x30, 0x20, 0x4E, 0x3A, 0x74, 0x65, 0x73, 0x74, 0x21, 0x20, 0x30, 0x20, 0x31, 0x32, 0x37, 0x2E, 0x30, 0x2E, 0x30, 0x2E, 0x31, 0x3A, 0x32, 0x30, 0x31, 0x31, 0x21, 0x20 };

        for (int i = 0; i < n; ++i) {
            for (int j = 0; j < n; ++j) {
                if (i == j) {
                    // Skip
                } else {
                    if (r.nextDouble() <= p) {
                        contents[0x00] = (byte)(0x41 + i);
                        contents[0x01] = (byte)(0x42 + j);
                        contents[0x0D] = (byte)(0x30 + j);
                        contents[0x1F] = (byte)(0x30 + j);
                        DatagramPacket packet = new DatagramPacket(contents, contents.length, InetAddress.getLocalHost(), 20110 + i);
                        ds.send(packet);
                    }
                }
            }
        }
        return;
    }
}
