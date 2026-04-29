// IN2011 Computer Networks
// Coursework 2024/2025
//
// Submission by
//  JACK CLAYTON
//  240019186
//  jack.clayton@city.ac.uk

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

// DO NOT EDIT starts
// This gives the interface that your code must implement.
// These descriptions are intended to help you understand how the interface
// will be used. See the RFC for how the protocol works.
interface NodeInterface {

    /* These methods configure your node.
     * They must both be called once after the node has been created but
     * before it is used. */
    
    // Set the name of the node.
    public void setNodeName(String nodeName) throws Exception;

    // Open a UDP port for sending and receiving messages.
    public void openPort(int portNumber) throws Exception;


    /*
     * These methods query and change how the network is used.
     */

    // Handle all incoming messages.
    // If you wait for more than delay miliseconds and
    // there are no new incoming messages return.
    // If delay is zero then wait for an unlimited amount of time.
    public void handleIncomingMessages(int delay) throws Exception;
    
    // Determines if a node can be contacted and is responding correctly.
    // Handles any messages that have arrived.
    public boolean isActive(String nodeName) throws Exception;

    // You need to keep a stack of nodes that are used to relay messages.
    // The base of the stack is the first node to be used as a relay.
    // The first node must relay to the second node and so on.
    
    // Adds a node name to a stack of nodes used to relay all future messages.
    public void pushRelay(String nodeName) throws Exception;

    // Pops the top entry from the stack of nodes used for relaying.
    // No effect if the stack is empty
    public void popRelay() throws Exception;
    

    /*
     * These methods provide access to the basic functionality of
     * CRN-25 network.
     */

    // Checks if there is an entry in the network with the given key.
    // Handles any messages that have arrived.
    public boolean exists(String key) throws Exception;
    
    // Reads the entry stored in the network for key.
    // If there is a value, return it.
    // If there isn't a value, return null.
    // Handles any messages that have arrived.
    public String read(String key) throws Exception;

    // Sets key to be value.
    // Returns true if it worked, false if it didn't.
    // Handles any messages that have arrived.
    public boolean write(String key, String value) throws Exception;

    // If key is set to currentValue change it to newValue.
    // Returns true if it worked, false if it didn't.
    // Handles any messages that have arrived.
    public boolean CAS(String key, String currentValue, String newValue) throws Exception;

}
// DO NOT EDIT ends

// Complete this!
public class Node implements NodeInterface {
    private String nodeName;
    private DatagramSocket socket;
    // stores both address pairs (N: keys) and data pairs (D: keys)
    private HashMap<String, String> dataStore = new HashMap<>();
    // when we send a request we store the response here keyed by txID so we can find it
    private HashMap<String, String> pendingResponses = new HashMap<>();
    private Random random = new Random();

    public void setNodeName(String nodeName) throws Exception {
        this.nodeName = nodeName;
        System.out.println("node name set: " + nodeName);
    }

    public void openPort(int portNumber) throws Exception {
        socket = new DatagramSocket(portNumber);
        System.out.println("port:" + portNumber + " opened");
    }

    public void handleIncomingMessages(int delay) throws Exception {
        long endTime = System.currentTimeMillis() + delay;
        // delay == 0 means run forever, otherwise run until time is up
	    while (delay == 0 || System.currentTimeMillis() <= endTime) {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            // 1 second timeout so we can re-check the loop condition
            socket.setSoTimeout(1000);
            try {
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("recieved message: " + message);
                // all CRN messages start with: txID space type space body
                String[] parts = message.split(" ", 3);
                String ID = parts[0];
                String type = parts[1];
                String body = parts.length > 2 ? parts[2] : "";
                dispatchMessage(ID, type, body, packet);
            } catch (SocketTimeoutException e) {
                // no message received, loop again
            } catch (Exception e) {
                System.err.println("Error handling message: " + e.getMessage());
            }
        }
    }

    public void handleCAS(String id, String[] payload, DatagramPacket packet){

    }
    public void handleRelay(String id, String[] payload, DatagramPacket packet){

    }

    public boolean isActive(String nodeName) throws Exception {
        // look up the node's address from our dataStore - can't contact it without one
        String address = dataStore.get(nodeName);
        if (address == null) return false;
        String[] addrParts = address.split(":");
        InetAddress inetAddr = InetAddress.getByName(addrParts[0]);
        int port = Integer.parseInt(addrParts[1]);
        // send a G (name request) and wait for an H response
        String txID = generateTxID();
        sendMessage(inetAddr, port, txID + " G");
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            socket.setSoTimeout(500);
            try {
                byte[] buffer = new byte[1024];
                DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                socket.receive(pkt);
                String msg = new String(pkt.getData(), 0, pkt.getLength());
                String[] parts = msg.split(" ", 3);
                String id = parts[0];
                String type = parts[1];
                String body = parts.length > 2 ? parts[2] : "";
                dispatchMessage(id, type, body, pkt);
            } catch (SocketTimeoutException e) { /* continue waiting */ }
            // check if the H response for our txID arrived
            String response = pendingResponses.remove(txID);
            if (response != null) {
                // decode the name and check it matches what we expected
                String[] decoded = decodeString(response);
                return decoded[0].equals(nodeName);
            }
        }
        return false;
    }
    
    public void pushRelay(String nodeName) throws Exception {
	throw new Exception("Not implemented");
    }

    public void popRelay() throws Exception {
        throw new Exception("Not implemented");
    }

    public boolean exists(String key) throws Exception {
        // process any queued messages first so dataStore is up to date
        drainIncoming();
        List<String[]> closest = findClosestNodes(key);
        for (String[] node : closest) {
            String txID = generateTxID();
            String[] addrParts = node[1].split(":");
            InetAddress addr = InetAddress.getByName(addrParts[0]);
            int port = Integer.parseInt(addrParts[1]);
            sendMessage(addr, port, txID + " E " + encodeString(key));
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                socket.setSoTimeout(500);
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                    socket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength());
                    String[] parts = msg.split(" ", 3);
                    dispatchMessage(parts[0], parts[1], parts.length > 2 ? parts[2] : "", pkt);
                } catch (SocketTimeoutException e) { /* continue */ }
                String response = pendingResponses.remove(txID);
                if (response != null) {
                    char code = response.charAt(0);
                    if (code == 'Y') return true;   // node has the key
                    if (code == 'N') return false;  // node is closest and doesn't have it
                    // '?' means this node isn't one of the 3 closest, try next
                }
            }
        }
        return false;
    }
    
    public String read(String key) throws Exception {
        drainIncoming();
        List<String[]> closest = findClosestNodes(key);
        for (String[] node : closest) {
            String[] addrParts = node[1].split(":");
            InetAddress addr = InetAddress.getByName(addrParts[0]);
            int port = Integer.parseInt(addrParts[1]);
            // retry up to 3 times per node to handle packet loss
            for (int attempt = 0; attempt < 3; attempt++) {
                String txID = generateTxID();
                sendMessage(addr, port, txID + " R " + encodeString(key));
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    socket.setSoTimeout(500);
                    try {
                        byte[] buffer = new byte[1024];
                        DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                        socket.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength());
                        String[] parts = msg.split(" ", 3);
                        dispatchMessage(parts[0], parts[1], parts.length > 2 ? parts[2] : "", pkt);
                    } catch (SocketTimeoutException e) { /* continue */ }
                    String response = pendingResponses.remove(txID);
                    if (response != null) {
                        char code = response.charAt(0);
                        // response body is "Y<encoded value>" so skip the Y to decode the value
                        if (code == 'Y') {
                            int start = 1;
                            while (start < response.length() && response.charAt(start) == ' ') start++;
                            return decodeString(response.substring(start))[0];
                        }
                        if (code == 'N') return null;
                        // '?' means not one of 3 closest, try next node
                        break;
                    }
                }
            }
        }
        return null;
    }

    public boolean write(String key, String value) throws Exception {
        drainIncoming();
        List<String[]> closest = findClosestNodes(key);
        boolean anySuccess = false;
        // try to write to all 3 closest nodes as the RFC says we should
        for (String[] node : closest) {
            String[] addrParts = node[1].split(":");
            InetAddress addr = InetAddress.getByName(addrParts[0]);
            int port = Integer.parseInt(addrParts[1]);
            // retry up to 3 times per node to handle packet loss
            for (int attempt = 0; attempt < 3; attempt++) {
                String txID = generateTxID();
                sendMessage(addr, port, txID + " W " + encodeString(key) + encodeString(value));
                long deadline = System.currentTimeMillis() + 2000;
                boolean gotResponse = false;
                while (System.currentTimeMillis() < deadline) {
                    socket.setSoTimeout(500);
                    try {
                        byte[] buffer = new byte[1024];
                        DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                        socket.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength());
                        String[] parts = msg.split(" ", 3);
                        dispatchMessage(parts[0], parts[1], parts.length > 2 ? parts[2] : "", pkt);
                    } catch (SocketTimeoutException e) { /* continue */ }
                    String response = pendingResponses.remove(txID);
                    if (response != null) {
                        char code = response.charAt(0);
                        // R = replaced existing, A = added new - both count as success
                        // X means the node thinks it isn't one of the 3 closest - try next node
                        if (code == 'R' || code == 'A') anySuccess = true;
                        gotResponse = true;
                        break;
                    }
                }
                if (gotResponse) break; // got a response (even X), no need to retry this node
            }
        }
        // if all 3 closest nodes rejected with X, fall back and store on the closest one anyway
        // this handles the case where the network view is incomplete
        if (!anySuccess && !closest.isEmpty()) {
            String[] node = closest.get(0);
            String[] addrParts = node[1].split(":");
            InetAddress addr = InetAddress.getByName(addrParts[0]);
            int port = Integer.parseInt(addrParts[1]);
            for (int attempt = 0; attempt < 3; attempt++) {
                String txID = generateTxID();
                sendMessage(addr, port, txID + " W " + encodeString(key) + encodeString(value));
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    socket.setSoTimeout(500);
                    try {
                        byte[] buffer = new byte[1024];
                        DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                        socket.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength());
                        String[] parts = msg.split(" ", 3);
                        dispatchMessage(parts[0], parts[1], parts.length > 2 ? parts[2] : "", pkt);
                    } catch (SocketTimeoutException e) { /* continue */ }
                    String response = pendingResponses.remove(txID);
                    if (response != null) {
                        char code = response.charAt(0);
                        if (code == 'R' || code == 'A') { anySuccess = true; }
                        break;
                    }
                }
                if (anySuccess) break;
            }
        }
        return anySuccess;
    }

    public boolean CAS(String key, String currentValue, String newValue) throws Exception {
	throw new Exception("Not implemented");
    }

    // transaction IDs are 2 bytes - the RFC says they must not be spaces
    private String generateTxID() {
        byte[] id = new byte[2];
        for (int i = 0; i < 2; i++) {
            int b;
            do { b = random.nextInt(254) + 1; } while (b == 0x20);
            id[i] = (byte) b;
        }
        return new String(id, StandardCharsets.ISO_8859_1);
    }

    private void sendMessage(InetAddress address, int port, String message) throws Exception {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket pkt = new DatagramPacket(data, data.length, address, port);
        socket.send(pkt);
    }

    private void handleName(String txID, DatagramPacket packet) throws Exception {
        String response = txID + " H " + encodeString(nodeName);
        sendMessage(packet.getAddress(), packet.getPort(), response);
    }

    // routes an incoming message to the right handler based on the type byte
    private void dispatchMessage(String txID, String type, String body, DatagramPacket packet) throws Exception {
        switch (type) {
            case "G": handleName(txID, packet); break;         // name request
            case "H": pendingResponses.put(txID, body); break; // name response
            case "N": handleNearest(txID, body, packet); break;// nearest request
            case "O": pendingResponses.put(txID, body); break; // nearest response
            case "E": handleExists(txID, body, packet); break; // key existence request
            case "F": pendingResponses.put(txID, body); break; // key existence response
            case "R": handleRead(txID, body, packet); break;   // read request
            case "S": pendingResponses.put(txID, body); break; // read response
            case "W": handleWrite(txID, body, packet); break;  // write request
            case "X": pendingResponses.put(txID, body); break; // write response
        }
    }

    // flush any messages already in the socket buffer before doing outgoing operations
    // needed so the dataStore is populated before we try to find closest nodes
    private void drainIncoming() {
        try {
            socket.setSoTimeout(100);
            while (true) {
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                    socket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength());
                    String[] parts = msg.split(" ", 3);
                    dispatchMessage(parts[0], parts[1], parts.length > 2 ? parts[2] : "", pkt);
                } catch (SocketTimeoutException e) {
                    break; // nothing left in the buffer
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    // RFC says reject a write only if we know 3 strictly closer nodes
    // so we check how many nodes in our dataStore are closer than us to the key
    private boolean isAmongThreeClosest(String key) throws Exception {
        byte[] keyHash = HashID.computeHashID(key);
        byte[] myHash = HashID.computeHashID(nodeName);
        int myDist = nodeDistance(myHash, keyHash);
        int strictlyCloser = 0;
        for (Map.Entry<String, String> entry : dataStore.entrySet()) {
            if (entry.getKey().startsWith("N:") && !entry.getKey().equals(nodeName)) {
                byte[] hash = HashID.computeHashID(entry.getKey());
                if (nodeDistance(hash, keyHash) < myDist) strictlyCloser++;
            }
        }
        return strictlyCloser < 3;
    }

    private void handleRead(String txID, String body, DatagramPacket packet) throws Exception {
        String key = decodeString(body)[0];
        boolean hasKey = dataStore.containsKey(key);
        boolean isClosest = isAmongThreeClosest(key);
        String response;
        if (hasKey) response = txID + " S Y" + encodeString(dataStore.get(key));
        else if (isClosest) response = txID + " S N";
        else response = txID + " S ?";
        sendMessage(packet.getAddress(), packet.getPort(), response);
    }

    private void handleWrite(String txID, String body, DatagramPacket packet) throws Exception {
        String[] keyParsed = decodeString(body);
        String key = keyParsed[0];
        String value = decodeString(keyParsed[1])[0];
        boolean hasKey = dataStore.containsKey(key);
        boolean isClosest = isAmongThreeClosest(key);
        String code;
        if (hasKey) {
            dataStore.put(key, value);
            code = "R";
        } else if (isClosest) {
            dataStore.put(key, value);
            code = "A";
        } else {
            code = "X";
        }
        sendMessage(packet.getAddress(), packet.getPort(), txID + " X " + code);
    }

    private void handleExists(String txID, String body, DatagramPacket packet) throws Exception {
        String key = decodeString(body)[0];
        boolean hasKey = dataStore.containsKey(key);
        boolean isClosest = isAmongThreeClosest(key);
        String code;
        if (hasKey) code = "Y";
        else if (isClosest) code = "N";
        else code = "?";
        sendMessage(packet.getAddress(), packet.getPort(), txID + " F " + code);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // someone asked for the nearest nodes to a hashID - look through our dataStore and return up to 3
    private void handleNearest(String txID, String body, DatagramPacket packet) throws Exception {
        byte[] targetHash = hexToBytes(body.trim());
        List<String[]> closest = getClosestFrom(dataStore, targetHash, 3);
        StringBuilder response = new StringBuilder(txID + " O ");
        for (String[] pair : closest) {
            response.append(encodeString(pair[0]));
            response.append(encodeString(pair[1]));
        }
        sendMessage(packet.getAddress(), packet.getPort(), response.toString());
    }

    // parse the body of an O response - it's a sequence of name/address pairs encoded as CRN strings
    private HashMap<String, String> parseAddressPairs(String body) {
        HashMap<String, String> pairs = new HashMap<>();
        String remaining = body;
        while (!remaining.isEmpty()) {
            try {
                String[] name = decodeString(remaining);
                if (name[1].isEmpty()) break;
                String[] addr = decodeString(name[1]);
                pairs.put(name[0], addr[0]);
                remaining = addr[1];
            } catch (Exception e) { break; }
        }
        return pairs;
    }

    // sort all N: entries in a map by distance to the target hash and return the top n
    private List<String[]> getClosestFrom(HashMap<String, String> map, byte[] targetHash, int n) throws Exception {
        List<String[]> allPairs = new ArrayList<>();
        List<int[]> distances = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey().startsWith("N:")) {
                byte[] hash = HashID.computeHashID(entry.getKey());
                int dist = nodeDistance(hash, targetHash);
                distances.add(new int[]{dist, allPairs.size()});
                allPairs.add(new String[]{entry.getKey(), entry.getValue()});
            }
        }
        distances.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<String[]> result = new ArrayList<>();
        for (int i = 0; i < Math.min(n, distances.size()); i++) {
            result.add(allPairs.get(distances.get(i)[1]));
        }
        return result;
    }

    private HashMap<String, String> sendNearest(InetAddress addr, int port, String hashIDHex) throws Exception {
        // retry up to 3 times to handle packet loss on the real network
        for (int attempt = 0; attempt < 3; attempt++) {
            String txID = generateTxID();
            sendMessage(addr, port, txID + " N " + hashIDHex);
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                socket.setSoTimeout(500);
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                    socket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength());
                    String[] parts = msg.split(" ", 3);
                    dispatchMessage(parts[0], parts[1], parts.length > 2 ? parts[2] : "", pkt);
                } catch (SocketTimeoutException e) { /* continue */ }
                String response = pendingResponses.remove(txID);
                if (response != null) return parseAddressPairs(response);
            }
        }
        return new HashMap<>();
    }

    // iterative lookup - start from known nodes, keep asking the closest unqueried node
    // for their nearest nodes until we stop finding anything closer
    public List<String[]> findClosestNodes(String key) throws Exception {
        byte[] targetHash = HashID.computeHashID(key);
        String targetHashHex = bytesToHex(targetHash);
        // seed the known set with address pairs we already have
        HashMap<String, String> known = new HashMap<>();
        for (Map.Entry<String, String> entry : dataStore.entrySet()) {
            if (entry.getKey().startsWith("N:")) known.put(entry.getKey(), entry.getValue());
        }
        HashSet<String> queried = new HashSet<>();
        boolean improved = true;
        while (improved) {
            improved = false;
            List<String[]> candidates = getClosestFrom(known, targetHash, 3);
            for (String[] candidate : candidates) {
                if (!queried.contains(candidate[0])) {
                    queried.add(candidate[0]);
                    try {
                        String[] addrParts = candidate[1].split(":");
                        InetAddress addr = InetAddress.getByName(addrParts[0]);
                        int port = Integer.parseInt(addrParts[1]);
                        HashMap<String, String> newPairs = sendNearest(addr, port, targetHashHex);
                        for (Map.Entry<String, String> e : newPairs.entrySet()) {
                            if (!known.containsKey(e.getKey())) {
                                // found a new node - add to known and also remember it for future
                                known.put(e.getKey(), e.getValue());
                                dataStore.put(e.getKey(), e.getValue());
                                improved = true;
                            }
                        }
                    } catch (Exception e) { /* skip unreachable node */ }
                }
            }
        }
        return getClosestFrom(known, targetHash, 3);
    }

    // distance = 256 minus the number of matching leading bits (as per the RFC)
    // XOR the bytes and count leading zeros to find where they first differ
    public int nodeDistance(byte[] A, byte[] B) {
        int matchingBits = 0;
        for (int i = 0; i < A.length; i++) {
            int xor = (A[i] & 0xFF) ^ (B[i] & 0xFF);
            if (xor == 0) {
                matchingBits += 8; // all 8 bits in this byte matched
            } else {
                // numberOfLeadingZeros works on 32-bit int so subtract 24 to get just the byte
                matchingBits += Integer.numberOfLeadingZeros(xor) - 24;
                break;
            }
        }
        return 256 - matchingBits;
    }
    // CRN string format: <number of spaces in string> <space> <string> <space>
    // the space count lets the parser know when the string ends even if it contains spaces
    public static String encodeString(String s) {
        int spaces = s.length() - s.replace(" ", "").length();
        return spaces + " " + s + " ";
    }
    // returns [decoded string, remaining unparsed input]
    // so you can chain calls to decode multiple strings from one message
    public static String[] decodeString(String s) {
        int separatorIdx = s.indexOf(' ');
        int numSpaces = Integer.parseInt(s.substring(0, separatorIdx));
        int pos = separatorIdx + 1;
        int start = pos;
        int spacesFound = 0;
        // scan forward counting spaces - stop after we've seen numSpaces+1 (the terminator)
        while (pos < s.length()) {
            if (s.charAt(pos) == ' ') {
                spacesFound++;
                if (spacesFound == numSpaces + 1) {
                    return new String[]{ s.substring(start, pos), s.substring(pos + 1) };
                }
            }
            pos++;
        }
        return new String[]{ s.substring(start), "" };
    }
}
