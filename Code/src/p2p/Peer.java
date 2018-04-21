package p2p;

import java.util.BitSet;
import java.util.Random;

public class Peer {

    private final int peerID;
    private final String hostname;
    private final int port;
    private final int pieceCount;
    private final BitSet pieceStatus;        //bit representation of file piece completion status

    public static final boolean DEBUG = false;

    public enum MessageType {

        CHOKE((byte) 0),
        UNCHOKE((byte) 1),
        INTERESTED((byte) 2),
        NOT_INTERESTED((byte) 3),
        HAVE((byte) 4),
        BITFIELD((byte) 5),
        REQUEST((byte) 6),
        PIECE((byte) 7);

        private final byte b;

        MessageType(byte b) {
            if (b > 7) {
                throw new IllegalArgumentException("Invalid b happens when creating MessageType.");
            }

            this.b = b;
        }

        public int length() {
            return 1;
        }

        public byte getValue() {
            return b;
        }

        public static MessageType typeOf(byte b) {
            if (b <= 7) {
                return values()[b];
            } else {
                return null;
            }
        }

    }

    public Peer(int peerID, String hostname, int port, int pieceCount, boolean hasFile) {
        if (peerID < 0) {
            throw new IllegalArgumentException("Invalid peerID happens when creating Peer.");
        }
        if (hostname == null || hostname.length() == 0) {
            throw new IllegalArgumentException("Invalid hostname happens when creating Peer.");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port happens when creating Peer.");
        }
        if (pieceCount < 1) {
            throw new IllegalArgumentException("Invalid pieceCount happens when creating Peer.");
        }

        this.peerID = peerID;
        this.hostname = hostname;
        this.port = port;
        this.pieceCount = pieceCount;
        pieceStatus = new BitSet();
        if (hasFile) {
            pieceStatus.set(0, pieceCount);
        }
    }

    public Peer(Peer peer) {
        if (peer == null) {
            throw new IllegalArgumentException("Invalid peer happens when creating Peer.");
        }

        peerID = peer.getPeerID();
        hostname = peer.getHostname();
        port = peer.getPort();
        pieceCount = peer.getPieceCount();
        pieceStatus = peer.getPieceStatus();
    }

    public int getPeerID() {
        return peerID;
    }

    public String getHostname() {
        return hostname;
    }

    //To be overridden.
    public String getIPAddress() {
        return "";
    }

    public int getPort() {
        return port;
    }

    public int getPieceCount() {
        return pieceCount;
    }

    public BitSet getPieceStatus() {
        return pieceStatus;
    }

    public byte[] getPieceStatusAsBitfield() {
        byte[] bitfield;
        synchronized (pieceStatus) {
            bitfield = pieceStatus.toByteArray();
        }
        //P2P protocol bitfield uses big-endian. Need to switch.
        return switchBitEndian(bitfield);
    }

    //Returns 0 if piece status is set successfully.
    //Returns -1 if input is invalid. No piece status is set.
    @SuppressWarnings("UnusedReturnValue")
    public int setPieceStatus(byte[] bitfield) {
        if (bitfield == null) {
            return -1;
        }

        BitSet bitSet = BitSet.valueOf(switchBitEndian(bitfield));    //P2P protocol bitfield uses big-endian. Need to switch.
        if (bitSet.length() > pieceCount) {
            return -1;
        }
        synchronized (pieceStatus) {
            pieceStatus.clear();
            pieceStatus.or(bitSet);
        }
        return 0;
    }

    //Returns 0 if piece is marked successfully.
    //Returns -1 if pieceIndex is invalid. No piece will be marked.
    @SuppressWarnings("UnusedReturnValue")
    public int markPieceComplete(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= pieceCount) {
            return -1;
        }

        synchronized (pieceStatus) {
            pieceStatus.set(pieceIndex);
        }
        return 0;
    }

    public int getCompletePieceCount() {
        synchronized (pieceStatus) {
            return pieceStatus.cardinality();
        }
    }

    public boolean hasPiece(int pieceIndex) {
        boolean hasPiece;
        if (pieceIndex < 0 || pieceIndex >= pieceCount) {
            return false;
        }

        synchronized (pieceStatus) {
            hasPiece = pieceStatus.get(pieceIndex);
        }
        return hasPiece;
    }

    public boolean hasCompleteFile() {
        boolean hasCompleteFile;
        synchronized (pieceStatus) {
            hasCompleteFile = (pieceStatus.cardinality() == pieceCount);
        }
        return hasCompleteFile;
    }

    //Returns true if this peer is interested in the argument peer.
    public boolean isInterested(Peer peer) {
        BitSet bitSet;
        if (peer == null) {
            return false;
        }

        synchronized (peer.getPieceStatus()) {
            bitSet = (BitSet) peer.getPieceStatus().clone();
        }
        synchronized (pieceStatus) {
            bitSet.andNot(pieceStatus);
        }
        return !bitSet.isEmpty();
    }

    //Returns a piece index for this peer to request from the argument peer.
    //Returns -1 if this peer has no interest in the argument peer.
    public int findNextInterestingPiece(Peer peer) {
        BitSet bitSet;
        if (peer == null) {
            return -1;
        }

        synchronized (peer.getPieceStatus()) {
            bitSet = (BitSet) peer.getPieceStatus().clone();
        }
        synchronized (pieceStatus) {
            bitSet.andNot(pieceStatus);
        }
        if (bitSet.isEmpty()) {
            return -1;
        }

        //BitSet toString() returns a string like: {1, 2, 4, 5, 9, 10, 11, 12}
        String pieceIndexString = bitSet.toString();
        String[] pieceIndexStringArray = pieceIndexString.substring(1, pieceIndexString.length() - 1).split(", ");
        //Randomly select a piece index. This could prevent requesting the same piece from multiple neighbor peers.
        int arrayIndex = new Random().nextInt(pieceIndexStringArray.length);
        return Integer.parseInt(pieceIndexStringArray[arrayIndex]);
    }

    private byte[] switchBitEndian(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            bytes[i] = (byte) ((b & 0b00000001) << 7 | (b & 0b00000010) << 5 | (b & 0b00000100) << 3 | (b & 0b00001000) << 1 | (b & 0b00010000) >> 1 | (b & 0b00100000) >> 3 | (b & 0b01000000) >> 5 | (b & 0b10000000) >> 7);
        }
        return bytes;
    }

}
