package p2p;

import static p2p.Peer.MessageType.*;
import static p2p.P2PLogger.DEBUG;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("unused")
public final class NeighborPeer extends Peer {

    private volatile boolean preferredByHost;
    private volatile boolean optimisticByHost;
    private volatile boolean previousInterestOfHost;
    private volatile boolean interestedInHost;
    private volatile boolean unchokedHost;
    private final long creationTimestamp;
    private final AtomicLong subCountTimestamp;
    private final AtomicLong sentToHostTotalCount;
    private final AtomicLong sentToHostSubCount;
    private final AtomicLong receivedFromHostTotalCount;
    private final AtomicLong receivedFromHostSubCount;
    private final HostPeer hostPeer;
    private final MessageHandler messageHandler;

    public NeighborPeer(int peerID, HostPeer hostPeer, Socket socket) {
        super(peerID, socket.getInetAddress().getCanonicalHostName(), socket.getPort(), hostPeer.getPieceCount(), false);

        this.hostPeer = hostPeer;
        preferredByHost = false;
        optimisticByHost = false;
        previousInterestOfHost = false;
        interestedInHost = false;
        unchokedHost = false;
        creationTimestamp = System.currentTimeMillis();
        subCountTimestamp = new AtomicLong(System.currentTimeMillis());
        sentToHostTotalCount = new AtomicLong();
        sentToHostSubCount = new AtomicLong();
        receivedFromHostTotalCount = new AtomicLong();
        receivedFromHostSubCount = new AtomicLong();
        messageHandler = new MessageHandler(hostPeer, this, socket);
    }

    public void reactivatePeer(Socket socket) {
        //Update of peer hostname and port is skipped.
        preferredByHost = false;
        optimisticByHost = false;
        previousInterestOfHost = false;
        interestedInHost = false;
        unchokedHost = false;
        resetSubCount();
        messageHandler.resetMessageHandler(socket);
    }

    public boolean isUnchokedByHost() {
        return preferredByHost || optimisticByHost;
    }

    public void setPreferredByHost(boolean b) {
        preferredByHost = b;
    }

    public boolean isPreferredByHost() {
        return preferredByHost;
    }

    public void setOptimisticByHost(boolean b) {
        optimisticByHost = b;
    }

    public boolean isOptimisticByHost() {
        return optimisticByHost;
    }

    public void setPreviousInterestOfHost(boolean b) {
        previousInterestOfHost = b;
    }

    public boolean isPreviousInterestOfHost() {
        return previousInterestOfHost;
    }

    public void setInterestedInHost(boolean b) {
        interestedInHost = b;
    }

    public boolean isInterestedInHost() {
        return interestedInHost;
    }

    public void setUnchokedHost(boolean b) {
        unchokedHost = b;
    }

    public boolean isUnchokedHost() {
        return unchokedHost;
    }

    @SuppressWarnings("UnusedReturnValue")
    public int addSentToHostCount(long count) {
        if (count < 0) {
            return -1;
        }

        sentToHostTotalCount.getAndAdd(count);
        sentToHostSubCount.getAndAdd(count);
        return 0;
    }

    @SuppressWarnings("UnusedReturnValue")
    public int addReceivedFromHostCount(long count) {
        if (count < 0) {
            return -1;
        }

        receivedFromHostTotalCount.getAndAdd(count);
        receivedFromHostSubCount.getAndAdd(count);
        return 0;
    }

    public long getSentToHostTotalCount() {
        return sentToHostTotalCount.get();
    }

    public long getSentToHostSubCount() {
        return sentToHostSubCount.get();
    }

    public long getReceivedFromHostTotalCount() {
        return receivedFromHostTotalCount.get();
    }

    public long getReceivedFromHostSubCount() {
        return receivedFromHostSubCount.get();
    }

    public long getSentToHostTotalRate() {
        long interval = System.currentTimeMillis() - creationTimestamp;
        return interval > 0 ? sentToHostTotalCount.get() * 1000 / interval : 0;
    }

    public long getSentToHostSubRate() {
        long interval = System.currentTimeMillis() - subCountTimestamp.get();
        return interval > 0 ? sentToHostSubCount.get() * 1000 / interval : 0;
    }

    public long getReceivedFromHostTotalRate() {
        long interval = System.currentTimeMillis() - creationTimestamp;
        return interval > 0 ? receivedFromHostTotalCount.get() * 1000 / interval : 0;
    }

    public long getReceivedFromHostSubRate() {
        long interval = System.currentTimeMillis() - subCountTimestamp.get();
        return interval > 0 ? receivedFromHostSubCount.get() * 1000 / interval : 0;
    }

    public void resetSubCount() {
        sentToHostSubCount.set(0);
        receivedFromHostSubCount.set(0);
        subCountTimestamp.set(System.currentTimeMillis());
    }

    public boolean hasReachedDownloadingLimit() {
        return hostPeer.getSpeedLimiter().hasReachedDownloadingLimit(this);
    }

    public boolean hasReachedUploadingLimit() {
        return hostPeer.getSpeedLimiter().hasReachedUploadingLimit(this);
    }

    public boolean hasOtherPendingPieceMessage(int pieceIndex) {
        return hostPeer.getSpeedLimiter().hasOtherPendingPieceMessage(this, pieceIndex);
    }

    public MessageHandler getMessageHandler() {
        return messageHandler;
    }

    @Override
    public String getIPAddress() {
        return messageHandler.getIPAddress();
    }

    //Handles actual messages exchanged after handshake between host and neighbor.
    public final class MessageHandler implements Callable<MessageHandler.MessageHandlerResult> {

        private volatile long estimatedRTT;      //in milliseconds
        private volatile long deviationRTT;      //in milliseconds
        private final HostPeer hostPeer;
        private final NeighborPeer neighborPeer;
        private Socket socket;
        private DataInputStream input;
        private DataOutputStream output;
        private final Object socketLock;
        private final Object outputLock;
        private final ConcurrentLinkedQueue<RequestedPiece> requestedPieceQueue;

        public MessageHandler(HostPeer hostPeer, NeighborPeer neighborPeer, Socket socket) {
            if (hostPeer == null) {
                throw new IllegalArgumentException("Invalid hostPeer happens when creating MessageHandler.");
            }
            if (neighborPeer == null) {
                throw new IllegalArgumentException("Invalid neighborPeer happens when creating MessageHandler.");
            }
            if (socket == null) {
                throw new IllegalArgumentException("Invalid socket happens when creating MessageHandler.");
            }

            estimatedRTT = 0;
            deviationRTT = 0;
            this.hostPeer = hostPeer;
            this.neighborPeer = neighborPeer;
            this.socket = socket;
            try {
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                P2PLogger.log("IOException happens when creating MessageHandler. Exception is not rethrown.");
                closeSocket();
            }
            socketLock = new Object();
            outputLock = new Object();
            requestedPieceQueue = new ConcurrentLinkedQueue<>();
        }

        //Message listener.
        @SuppressWarnings("ConstantConditions")
        @Override
        public MessageHandlerResult call() {
            int resultCode = 0;
            int messageLength;
            int pieceIndex = -1;
            byte[] piece = null;
            byte[] messagePayload = null;
            MessageType messageType;

            while (hostPeer.isRunning()) {
                try {
                    messageLength = input.readInt();
                    messageType = MessageType.typeOf(input.readByte());
                    if (messageType == PIECE) {
                        pieceIndex = input.readInt();
                        piece = new byte[Math.max(0, messageLength - messageType.length() - 4)];
                        input.readFully(piece);
                    } else {
                        messagePayload = new byte[Math.max(0, messageLength - messageType.length())];
                        input.readFully(messagePayload);
                    }
                } catch (IOException e) {
                    if (hostPeer.isRunning()) {
                        P2PLogger.log("Connection is lost for Peer " + neighborPeer.getPeerID() + ".");
                        resultCode = 1;
                    }
                    break;
                }

                switch (messageType) {
                    case CHOKE:
                        neighborPeer.setUnchokedHost(false);
                        P2PLogger.log("Peer " + hostPeer.getPeerID() + " is choked by Peer " + neighborPeer.getPeerID() + ".");
                        break;
                    case UNCHOKE:
                        neighborPeer.setUnchokedHost(true);
                        P2PLogger.log("Peer " + hostPeer.getPeerID() + " is unchoked by Peer " + neighborPeer.getPeerID() + ".");
                        if (hostPeer.isInterested(neighborPeer)) {
                            sendMessage(REQUEST, hostPeer.findNextInterestingPiece(neighborPeer));
                        } else {
                            sendMessage(NOT_INTERESTED);
                        }
                        break;
                    case INTERESTED:
                        neighborPeer.setInterestedInHost(true);
                        P2PLogger.log("Peer " + hostPeer.getPeerID() + " received the 'interested' message from Peer " + neighborPeer.getPeerID() + ".");
                        break;
                    case NOT_INTERESTED:
                        neighborPeer.setInterestedInHost(false);
                        P2PLogger.log("Peer " + hostPeer.getPeerID() + " received the 'not interested' message from Peer " + neighborPeer.getPeerID() + ".");
                        break;
                    case HAVE:
                        if (messagePayload.length == 4) {
                            pieceIndex = ByteBuffer.wrap(messagePayload).getInt();
                            P2PLogger.log("Peer " + hostPeer.getPeerID() + " received the 'have' message from Peer " + neighborPeer.getPeerID() + " for the piece " + pieceIndex + ".");
                        } else {
                            pieceIndex = -1;
                        }
                        neighborPeer.markPieceComplete(pieceIndex);
                        if (!neighborPeer.isPreviousInterestOfHost() && hostPeer.isInterested(neighborPeer)) {
                            sendMessage(INTERESTED);
                            if (neighborPeer.isUnchokedHost()) {
                                sendMessage(REQUEST, hostPeer.findNextInterestingPiece(neighborPeer));
                            }
                        }
                        break;
                    case BITFIELD:
                        neighborPeer.setPieceStatus(messagePayload);
                        if (hostPeer.isInterested(neighborPeer)) {
                            sendMessage(INTERESTED);
                        } else {
                            sendMessage(NOT_INTERESTED);
                        }
                        break;
                    case REQUEST:
                        if (messagePayload.length == 4) {
                            pieceIndex = ByteBuffer.wrap(messagePayload).getInt();
                        } else {
                            pieceIndex = -1;
                        }
                        if (neighborPeer.isUnchokedByHost()) {
                            sendMessage(PIECE, pieceIndex);
                        } else {
                            hostPeer.getSpeedLimiter().delayPieceMessage(neighborPeer, pieceIndex);
                            sendMessage(CHOKE);
                        }
                        break;
                    case PIECE:
                        if (!isPieceRequested(pieceIndex)) {
                            continue;       //Ignore the piece that was not requested before.
                        }
                        int requestSendingTimes = checkPieceReceived(pieceIndex);
                        if (!hostPeer.hasPiece(pieceIndex)) {
                            if (hostPeer.getSharedFile().writePiece(pieceIndex, piece) == 0) {
                                hostPeer.markPieceComplete(pieceIndex);
                                neighborPeer.addSentToHostCount(piece.length);
                                P2PLogger.log("Peer " + hostPeer.getPeerID() + " has downloaded the piece " + pieceIndex + " from Peer " + neighborPeer.getPeerID() + ". Now the number of pieces it has is " + hostPeer.getCompletePieceCount() + ".");
                                if (hostPeer.hasCompleteFile()) {
                                    P2PLogger.log("Peer " + hostPeer.getPeerID() + " has downloaded the complete file.");
                                }
                                for (NeighborPeer p : hostPeer.getActiveNeighborList()) {
                                    p.getMessageHandler().sendMessage(HAVE, pieceIndex);
                                    if (p.isPreviousInterestOfHost() && !hostPeer.isInterested(p)) {
                                        p.getMessageHandler().sendMessage(NOT_INTERESTED);
                                    }
                                }
                            }
                        }
                        if (hostPeer.isInterested(neighborPeer) && neighborPeer.isUnchokedHost()) {
                            for (int i = 0; i < requestSendingTimes; i++) {
                                sendMessage(REQUEST, hostPeer.findNextInterestingPiece(neighborPeer));
                            }
                        }
                        if (DEBUG) {
                            P2PLogger.log("[DEBUG] Peer " + hostPeer.getPeerID() + " has Peer " + neighborPeer.getPeerID() + ": Requested Queue Size = " + requestedPieceQueue.size() + ".");
                        }
                        break;
                    default:
                        P2PLogger.log("Invalid messageType happens when processing message for peer " + neighborPeer.getPeerID() + ".");
                        break;
                }
            }

            closeSocket();
            if (DEBUG) {
                P2PLogger.log("[DEBUG] Thread exists for MessageHandler of Peer " + neighborPeer.getPeerID() + " with result code " + resultCode + ".");
            }
            return new MessageHandlerResult(resultCode, neighborPeer);
        }

        public void sendMessage(MessageType messageType, int pieceIndex) {
            int messageLength = messageType.length();
            byte[] messagePayload = new byte[0];
            byte[] piece = null;

            switch (messageType) {
                case CHOKE:
                case UNCHOKE:
                    break;
                case INTERESTED:
                    neighborPeer.setPreviousInterestOfHost(true);
                    break;
                case NOT_INTERESTED:
                    neighborPeer.setPreviousInterestOfHost(false);
                    break;
                case HAVE:
                    messagePayload = ByteBuffer.allocate(4).putInt(pieceIndex).array();
                    messageLength += messagePayload.length;
                    break;
                case REQUEST:
                    if (neighborPeer.hasReachedDownloadingLimit()) {
                        hostPeer.getSpeedLimiter().delayRequestMessage(neighborPeer, pieceIndex);
                        return;
                    }
                    if (isPieceRequested(pieceIndex)) {
                        return;
                    }
                    requestedPieceQueue.add(new RequestedPiece(pieceIndex, System.currentTimeMillis()));
                    messagePayload = ByteBuffer.allocate(4).putInt(pieceIndex).array();
                    messageLength += messagePayload.length;
                    break;
                case BITFIELD:
                    messagePayload = hostPeer.getPieceStatusAsBitfield();
                    messageLength += messagePayload.length;
                    break;
                case PIECE:
                    if (neighborPeer.hasReachedUploadingLimit() || neighborPeer.hasOtherPendingPieceMessage(pieceIndex)) {
                        hostPeer.getSpeedLimiter().delayPieceMessage(neighborPeer, pieceIndex);
                        return;
                    }
                    piece = hostPeer.getSharedFile().readPiece(pieceIndex);
                    messageLength += 4 + piece.length;
                    neighborPeer.addReceivedFromHostCount(piece.length);
                    break;
                default:
                    P2PLogger.log("Invalid messageType happens when sending message for peer " + neighborPeer.getPeerID() + ". No message is sent.");
                    return;
            }
            if (DEBUG) {
                P2PLogger.log("[DEBUG] Peer " + hostPeer.getPeerID() + " is sending " + messageType + " Message to Peer " + neighborPeer.getPeerID() + " with piece index " + pieceIndex + ".");
            }

            try {
                synchronized (outputLock) {
                    output.writeInt(messageLength);
                    output.writeByte(messageType.getValue());
                    if (messageType == PIECE) {
                        output.writeInt(pieceIndex);
                        output.write(piece);
                    } else {
                        output.write(messagePayload);
                    }
                    output.flush();
                }
            } catch (IOException ignored) {
            }
        }

        public void sendMessage(MessageType messageType) {
            sendMessage(messageType, -1);
        }

        public long getEstimatedRTT() {
            return estimatedRTT;
        }

        public long getDeviationRTT() {
            return deviationRTT;
        }

        public int getRequestedPieceCount() {
            return requestedPieceQueue.size();
        }

        public String getIPAddress() {
            synchronized (socketLock) {
                return socket.getInetAddress().getHostAddress();
            }
        }

        public final void closeSocket() {
            try {
                synchronized (socketLock) {
                    socket.close();
                }
            } catch (IOException e) {
                P2PLogger.log("IOException happens when closing socket for peer " + neighborPeer.getPeerID() + ". Exception is not rethrown.");
            }
        }

        //Only call this method when neighbor is reconnected.
        private void resetMessageHandler(Socket socket) {
            if (socket == null) {
                return;
            }

            estimatedRTT = 0;
            deviationRTT = 0;
            requestedPieceQueue.clear();
            synchronized (socketLock) {
                this.socket = socket;
            }
            try {
                input = new DataInputStream(socket.getInputStream());
                synchronized (outputLock) {
                    output = new DataOutputStream(socket.getOutputStream());
                }
            } catch (IOException e) {
                P2PLogger.log("IOException happens when replacing socket. Exception is not rethrown.");
                closeSocket();
            }
        }

        private boolean isPieceRequested(int pieceIndex) {
            return requestedPieceQueue.stream().anyMatch(p -> p.getPieceIndex() == pieceIndex);
        }

        //Return the number of times that Request Message should be sent. The number is calculated based on network delay.
        @SuppressWarnings("NonAtomicOperationOnVolatileField")
        private int checkPieceReceived(int pieceIndex) {
            int requestSendingTimes;

            RequestedPiece requestedPiece = requestedPieceQueue.stream().filter(p -> p.getPieceIndex() == pieceIndex).findFirst().orElse(null);
            requestedPieceQueue.remove(requestedPiece);
            if (requestedPiece == null) {
                return -1;

            }

            long sampleRTT = System.currentTimeMillis() - requestedPiece.getSentTimestamp();
            if (sampleRTT > estimatedRTT + deviationRTT) {
                if (requestedPieceQueue.isEmpty()) {
                    requestSendingTimes = 1;
                } else {
                    requestSendingTimes = 0;
                }
            } else {
                if (requestedPieceQueue.size() > 200) {
                    requestSendingTimes = 0;
                } else {
                    requestSendingTimes = 2;
                }
            }

            estimatedRTT = (7 * estimatedRTT + sampleRTT) / 8;
            deviationRTT = (3 * deviationRTT + Math.abs(sampleRTT - estimatedRTT)) / 4;     //Use new estimatedRTT to calculate deviationRTT

            if (DEBUG) {
                P2PLogger.log("[DEBUG] Peer " + hostPeer.getPeerID() + " received piece " + pieceIndex + " from Peer " + neighborPeer.getPeerID() + ": Sample RTT = " + sampleRTT + "ms; New Estimated RTT = " + estimatedRTT + "ms; New Deviation RTT = " + deviationRTT + "ms; Request Sending Times = " + requestSendingTimes + ".");
            }
            return requestSendingTimes;
        }

        private final class RequestedPiece {

            private final int pieceIndex;
            private final long sentTimestamp;

            public RequestedPiece(int pieceIndex, long sentTimestamp) {
                this.pieceIndex = pieceIndex;
                this.sentTimestamp = sentTimestamp;
            }

            public int getPieceIndex() {
                return pieceIndex;
            }

            public long getSentTimestamp() {
                return sentTimestamp;
            }

        }

        public final class MessageHandlerResult {

            private final int code;
            private final NeighborPeer neighborPeer;

            public MessageHandlerResult(int code, NeighborPeer neighborPeer) {
                this.code = code;
                this.neighborPeer = neighborPeer;
            }

            public int getCode() {
                return code;
            }

            public NeighborPeer getNeighborPeer() {
                return neighborPeer;
            }

        }

    }

}
