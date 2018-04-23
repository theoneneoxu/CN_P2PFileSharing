package p2p;

import static p2p.Peer.MessageType.*;
import static p2p.P2PLogger.DEBUG;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;

public class HostPeer extends Peer {

    private volatile boolean runningIndicator;
    private volatile boolean pauseIndicator;
    private volatile int downloadingSpeedLimit;
    private volatile int uploadingSpeedLimit;
    private final SharedFile sharedFile;
    private final ProgressFile progressFile;
    private final PeerManager peerManager;
    private final SpeedLimiter speedLimiter;
    private final ConnectionListener connectionListener;
    private final ConnectionStarter connectionStarter;
    private final ArrayList<Peer> knownPeerList;
    private final CopyOnWriteArrayList<NeighborPeer> activeNeighborList;
    private final CopyOnWriteArrayList<NeighborPeer> inactiveNeighborList;
    private final ExecutorService neighborThreadPool;

    public HostPeer(Peer peer, int preferredNeighborCount, int preferredUnchokingInterval, int optimisticNeighborCount, int optimisticUnchokingInterval,
                    SharedFile sharedFile, ProgressFile progressFile, ArrayList<Peer> knownPeerList, int downloadingSpeedLimit, int uploadingSpeedLimit) throws IOException {
        super(peer);

        if (sharedFile == null) {
            throw new IllegalArgumentException("Invalid sharedFile happens when creating HostPeer.");
        }
        if (progressFile == null) {
            throw new IllegalArgumentException("Invalid progressFile happens when creating HostPeer.");
        }
        if (knownPeerList == null) {
            throw new IllegalArgumentException("Invalid knownPeerList happens when creating HostPeer.");
        }

        this.downloadingSpeedLimit = downloadingSpeedLimit;
        this.uploadingSpeedLimit = uploadingSpeedLimit;
        this.sharedFile = sharedFile;
        this.progressFile = progressFile;
        this.knownPeerList = knownPeerList;
        peerManager = new PeerManager(this, preferredNeighborCount, preferredUnchokingInterval, optimisticNeighborCount, optimisticUnchokingInterval);
        speedLimiter = new SpeedLimiter(this, downloadingSpeedLimit, uploadingSpeedLimit);
        connectionListener = new ConnectionListener(this);
        connectionStarter = new ConnectionStarter(this, knownPeerList);
        activeNeighborList = new CopyOnWriteArrayList<>();
        inactiveNeighborList = new CopyOnWriteArrayList<>();
        neighborThreadPool = Executors.newFixedThreadPool(10);
    }

    public void startRunning() {
        runningIndicator = true;
        pauseIndicator = false;

        new Thread(peerManager).start();
        new Thread(speedLimiter).start();
        new Thread(connectionListener).start();
        new Thread(connectionStarter).start();
    }

    public void stopRunning() {
        runningIndicator = false;

        //Wait a while before close sockets. There may be ongoing processing like neighbor listener sending message via output stream.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            P2PLogger.log("InterruptedException happens when stop running host peer. Exception is not rethrown.");
        }

        //Close sockets to force threads to get out of blocking on input stream.
        connectionListener.closeSocket();
        activeNeighborList.forEach(p -> p.getMessageHandler().closeSocket());

        //Shutdown thread pool.
        neighborThreadPool.shutdown();
        try {
            if (!neighborThreadPool.awaitTermination(2000, TimeUnit.MILLISECONDS)) {
                neighborThreadPool.shutdownNow();
                //P2PLogger.log("Neighbor Thread Pool did not terminate in 1s. Forced to shutdown.");
            }
        } catch (InterruptedException e) {
            neighborThreadPool.shutdownNow();
            String string = "Neighbor Thread Pool shutdown waiting is interrupted. Forced to shutdown.";
            P2PLogger.log(string);
            System.out.println(string);

        }

        //Close files.
        sharedFile.closeFile();
        progressFile.closeFile();
    }

    public boolean isRunning() {
        return runningIndicator;
    }

    public void pauseRunning() {
        pauseIndicator = true;
        speedLimiter.setDownloadingSpeedLimit(0);
        speedLimiter.setUploadingSpeedLimit(0);
    }

    public void resumeRunning() {
        pauseIndicator = false;
        speedLimiter.setDownloadingSpeedLimit(downloadingSpeedLimit);
        speedLimiter.setUploadingSpeedLimit(uploadingSpeedLimit);
    }

    public boolean isPaused() {
        return pauseIndicator;
    }

    public CopyOnWriteArrayList<NeighborPeer> getActiveNeighborList() {
        return activeNeighborList;
    }

    public CopyOnWriteArrayList<NeighborPeer> getInactiveNeighborList() {
        return inactiveNeighborList;
    }

    public SharedFile getSharedFile() {
        return sharedFile;
    }

    public SpeedLimiter getSpeedLimiter() {
        return speedLimiter;
    }

    public ConnectionStarter getConnectionStarter() {
        return connectionStarter;
    }

    @Override
    public String getIPAddress() {
        return connectionListener.getIPAddress();
    }

    public void changeDownloadingSpeedLimit(int limit) {
        downloadingSpeedLimit = limit;
        speedLimiter.setDownloadingSpeedLimit(limit);
    }

    public void changeUploadingSpeedLimit(int limit) {
        uploadingSpeedLimit = limit;
        speedLimiter.setUploadingSpeedLimit(limit);
    }

    public int getFileHealthPercentage() {
        ArrayList<BitSet> bitSetList = new ArrayList<>();

        synchronized (getPieceStatus()) {
            bitSetList.add((BitSet) getPieceStatus().clone());
        }
        activeNeighborList.forEach(p -> {
            synchronized (p.getPieceStatus()) {
                bitSetList.add((BitSet) p.getPieceStatus().clone());
            }
        });

        int countArray[] = new int[getPieceCount()];
        for (BitSet bitSet : bitSetList) {
            int i = bitSet.length() - 1;
            while (i >= 0) {
                if (bitSet.get(i)) {
                    countArray[i]++;
                }
                i--;
            }
        }
        int lowest = 1000000;
        for (int count : countArray) {
            lowest = Math.min(lowest, count);
        }
        int health = lowest * 100;
        int fractionCount = 0;
        for (int count : countArray) {
            if (count > lowest) {
                fractionCount++;
            }
        }
        health += fractionCount * 100 / getPieceCount();
        return health;
    }

    private void registerNeighbor(int peerID, Socket socket) {
        if (socket == null) {
            return;
        }
        if (peerID < 0 || activeNeighborList.stream().anyMatch(p -> p.getPeerID() == peerID)) {
            try {
                socket.close();
            } catch (IOException e) {
                P2PLogger.log("IOException happens when closing socket in registerNeighbor. Exception is not rethrown.");
            }
            return;
        }

        NeighborPeer neighborPeer = inactiveNeighborList.stream().filter(p -> p.getPeerID() == peerID).findFirst().orElse(null);
        inactiveNeighborList.remove(neighborPeer);
        if (neighborPeer == null) {
            neighborPeer = new NeighborPeer(peerID, this, socket);
        } else {
            neighborPeer.reactivatePeer(socket);
        }
        activeNeighborList.add(neighborPeer);
        neighborThreadPool.execute(neighborPeer.getMessageHandler());
        neighborPeer.getMessageHandler().sendMessage(BITFIELD);         //Once registered, send bitfield to neighbor.
    }

    public void deregisterNeighbor(NeighborPeer neighborPeer) {
        if (neighborPeer == null) {
            return;
        }

        activeNeighborList.remove(neighborPeer);
        inactiveNeighborList.add(neighborPeer);
        if (knownPeerList.stream().anyMatch(p -> p.getPeerID() == neighborPeer.getPeerID())) {      //If host is responsible for making connection to the neighbor, then add it to starter.
            connectionStarter.addConnectingPeer(neighborPeer);
        }
    }

    private class PeerManager implements Runnable {

        private final int preferredNeighborCount;
        private final int preferredUnchokingInterval;    //in seconds
        private final int optimisticNeighborCount;
        private final int optimisticUnchokingInterval;    //in seconds
        private final HostPeer hostPeer;

        public PeerManager(HostPeer hostPeer, int preferredNeighborCount, int preferredUnchokingInterval, int optimisticNeighborCount, int optimisticUnchokingInterval) {
            if (hostPeer == null) {
                throw new IllegalArgumentException("Invalid hostPeer happens when creating NeighborSelector.");
            }
            if (preferredNeighborCount < 1) {
                throw new IllegalArgumentException("Invalid preferredNeighborCount happens when creating NeighborSelector.");
            }
            if (preferredUnchokingInterval < 1) {
                throw new IllegalArgumentException("Invalid preferredUnchokingInterval happens when creating NeighborSelector.");
            }
            if (optimisticNeighborCount < 1) {
                throw new IllegalArgumentException("Invalid optimisticNeighborCount happens when creating NeighborSelector.");
            }
            if (optimisticUnchokingInterval < 1) {
                throw new IllegalArgumentException("Invalid optimisticUnchokingInterval happens when creating NeighborSelector.");
            }

            this.hostPeer = hostPeer;
            this.preferredNeighborCount = preferredNeighborCount;
            this.preferredUnchokingInterval = preferredUnchokingInterval;
            this.optimisticNeighborCount = optimisticNeighborCount;
            this.optimisticUnchokingInterval = optimisticUnchokingInterval;
        }

        @Override
        public void run() {
            int threadSleep = 200;
            long threadSleepCount = 0;

            while (hostPeer.isRunning()) {
                if (hostPeer.isPaused()) {
                    if (threadSleepCount % 1000 == 0) {
                        hostPeer.getActiveNeighborList().forEach(p -> {
                            p.setPreferredByHost(false);
                            p.setOptimisticByHost(false);
                            p.resetSubCount();
                        });
                    }
                } else {
                    if (threadSleepCount / 1000 % preferredUnchokingInterval == 0) {
                        selectPreferredNeighbors();
                    }
                    if (threadSleepCount / 1000 % optimisticUnchokingInterval == 0) {
                        selectOptimisticNeighbors();
                    }
                }
                if (threadSleepCount % 1000 == 0) {
                    saveHostProgress();
                }

                try {
                    Thread.sleep(threadSleep);
                } catch (InterruptedException e) {
                    break;
                }
                threadSleepCount += threadSleep;
            }

            if (DEBUG) {
                P2PLogger.log("[DEBUG] Thread exists for PeerManager.");
            }
        }

        @SuppressWarnings("StringConcatenationInLoop")
        private void selectPreferredNeighbors() {
            List<NeighborPeer> candidateList;

            List<NeighborPeer> oldPreferredList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isPreferredByHost()).collect(Collectors.toList());
            if (hostPeer.hasCompleteFile()) {
                candidateList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isInterestedInHost() && !p.isOptimisticByHost()).collect(Collectors.toList());
                Collections.shuffle(candidateList);
            } else {
                candidateList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isInterestedInHost()).collect(Collectors.toList());
                candidateList.sort(Comparator.comparing(NeighborPeer::getSentToHostSubRate).reversed());
            }
            hostPeer.getActiveNeighborList().forEach(p -> p.resetSubCount());
            ArrayList<NeighborPeer> newPreferredList = new ArrayList<>(candidateList.subList(0, Math.min(preferredNeighborCount, candidateList.size())));

            oldPreferredList.forEach(p -> {
                if (!newPreferredList.contains(p)) {
                    p.setPreferredByHost(false);
                    if (!p.isOptimisticByHost()) {
                        p.getMessageHandler().sendMessage(CHOKE);
                    }
                }
            });
            newPreferredList.forEach(p -> {
                if (!oldPreferredList.contains(p)) {
                    p.setPreferredByHost(true);
                    if (!p.isOptimisticByHost()) {
                        p.getMessageHandler().sendMessage(UNCHOKE);
                    }
                }
            });

            oldPreferredList.sort(Comparator.comparing(NeighborPeer::getPeerID));
            newPreferredList.sort(Comparator.comparing(NeighborPeer::getPeerID));
            if (!newPreferredList.equals(oldPreferredList)) {
                if (newPreferredList.isEmpty()) {
                    P2PLogger.log("Peer " + hostPeer.getPeerID() + " has no preferred neighbors.");
                } else {
                    String string = "";
                    for (NeighborPeer np : newPreferredList) {
                        string += np.getPeerID() + ", ";
                    }
                    P2PLogger.log("Peer " + hostPeer.getPeerID() + " has the preferred neighbor(s) " + string.substring(0, string.length() - 2) + ".");
                }
            }
        }

        @SuppressWarnings("StringConcatenationInLoop")
        private void selectOptimisticNeighbors() {
            List<NeighborPeer> oldOptimisticList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isOptimisticByHost()).collect(Collectors.toList());
            List<NeighborPeer> candidateList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isInterestedInHost() && !p.isUnchokedByHost()).collect(Collectors.toList());
            Collections.shuffle(candidateList);

            ArrayList<NeighborPeer> newOptimisticList = new ArrayList<>(candidateList.subList(0, Math.min(optimisticNeighborCount, candidateList.size())));
            int remainingCount = optimisticNeighborCount - candidateList.size();
            if (remainingCount > 0) {
                List<NeighborPeer> secondaryCandidateList = oldOptimisticList.stream().filter(p -> p.isInterestedInHost()).collect(Collectors.toList());
                secondaryCandidateList.removeAll(newOptimisticList);
                Collections.shuffle(secondaryCandidateList);
                newOptimisticList.addAll(secondaryCandidateList.subList(0, Math.min(remainingCount, secondaryCandidateList.size())));
            }

            oldOptimisticList.forEach(p -> {
                if (!newOptimisticList.contains(p)) {
                    p.setOptimisticByHost(false);
                    if (!p.isPreferredByHost()) {
                        p.getMessageHandler().sendMessage(CHOKE);
                    }
                }
            });
            newOptimisticList.forEach(p -> {
                if (!oldOptimisticList.contains(p)) {
                    p.setOptimisticByHost(true);
                    if (!p.isPreferredByHost()) {
                        p.getMessageHandler().sendMessage(UNCHOKE);
                    }
                }
            });

            oldOptimisticList.sort(Comparator.comparing(NeighborPeer::getPeerID));
            newOptimisticList.sort(Comparator.comparing(NeighborPeer::getPeerID));
            if (!newOptimisticList.equals(oldOptimisticList)) {
                if (newOptimisticList.isEmpty()) {
                    P2PLogger.log("Peer " + hostPeer.getPeerID() + " has no optimistic neighbors.");
                } else {
                    String string = "";
                    for (NeighborPeer np : newOptimisticList) {
                        string += np.getPeerID() + ", ";
                    }
                    P2PLogger.log("Peer " + hostPeer.getPeerID() + " has the optimistic neighbor(s) " + string.substring(0, string.length() - 2) + ".");
                }
            }
        }

        private void saveHostProgress() {
            progressFile.writeFile(getPieceStatusAsBitfield());
        }

    }

    public class SpeedLimiter implements Runnable {

        private volatile int downloadingSpeedLimit;
        private volatile int uploadingSpeedLimit;
        private final HostPeer hostPeer;
        private final ConcurrentHashMap<NeighborPeer, Integer> delayedRequestMessageMap;
        private final ConcurrentHashMap<NeighborPeer, ConcurrentLinkedQueue<Integer>> delayedPieceMessageMap;

        public SpeedLimiter(HostPeer hostPeer, int downloadingSpeedLimit, int uploadingSpeedLimit) {
            if (hostPeer == null) {
                throw new IllegalArgumentException("Invalid hostPeer happens when creating SpeedLimiter.");
            }

            this.hostPeer = hostPeer;
            this.downloadingSpeedLimit = downloadingSpeedLimit;
            this.uploadingSpeedLimit = uploadingSpeedLimit;
            delayedRequestMessageMap = new ConcurrentHashMap<>();
            delayedPieceMessageMap = new ConcurrentHashMap<>();
        }

        @Override
        public void run() {
            while (hostPeer.isRunning()) {
                checkDelayedRequestMessages();
                checkDelayedPieceMessages();

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }

            if (DEBUG) {
                P2PLogger.log("[DEBUG] Thread exists for SpeedLimiter.");
            }
        }

        private void checkDelayedRequestMessages() {
            Iterator<Map.Entry<NeighborPeer, Integer>> iterator = delayedRequestMessageMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<NeighborPeer, Integer> entry = iterator.next();
                NeighborPeer neighborPeer = entry.getKey();
                int pieceIndex = entry.getValue();

                if (neighborPeer.hasReachedDownloadingLimit()) {
                    continue;
                }
                neighborPeer.getMessageHandler().sendMessage(REQUEST, pieceIndex);
                iterator.remove();
            }
        }

        private void checkDelayedPieceMessages() {
            Iterator<Map.Entry<NeighborPeer, ConcurrentLinkedQueue<Integer>>> mapIterator = delayedPieceMessageMap.entrySet().iterator();
            while (mapIterator.hasNext()) {
                Map.Entry<NeighborPeer, ConcurrentLinkedQueue<Integer>> entry = mapIterator.next();
                NeighborPeer neighborPeer = entry.getKey();
                ConcurrentLinkedQueue<Integer> pieceIndexQueue = entry.getValue();

                if (neighborPeer.hasCompleteFile() || hostPeer.getInactiveNeighborList().contains(neighborPeer)) {
                    mapIterator.remove();
                    continue;
                }
                if (!neighborPeer.isUnchokedByHost()) {
                    continue;
                }

                Iterator<Integer> queueIterator = pieceIndexQueue.iterator();
                while (queueIterator.hasNext()) {
                    int pieceIndex = queueIterator.next();
                    if (neighborPeer.hasReachedUploadingLimit()) {
                        break;
                    }
                    neighborPeer.getMessageHandler().sendMessage(PIECE, pieceIndex);
                    queueIterator.remove();
                }
            }
        }

        public int getDownloadingSpeedLimit() {
            return downloadingSpeedLimit;
        }

        public void setDownloadingSpeedLimit(int limit) {
            downloadingSpeedLimit = limit;
        }

        public int getUploadingSpeedLimit() {
            return uploadingSpeedLimit;
        }

        public void setUploadingSpeedLimit(int limit) {
            uploadingSpeedLimit = limit;
        }

        public int getDelayedRequestMessageCount() {
            return delayedRequestMessageMap.size();
        }

        public int getDelayedPieceMessageCount() {
            return delayedPieceMessageMap.values().stream().mapToInt(q -> q.size()).sum();
        }

        public boolean hasReachedDownloadingLimit(NeighborPeer neighborPeer) {
            if (neighborPeer == null) {
                throw new IllegalArgumentException("Invalid neighborPeer happens when checking Downloading Limit.");
            }

            if (downloadingSpeedLimit == 0) {
                return true;
            } else if (downloadingSpeedLimit < 0) {
                return false;
            } else {
                return neighborPeer.getSentToHostSubRate() > downloadingSpeedLimit;
            }
        }

        public boolean hasReachedUploadingLimit(NeighborPeer neighborPeer) {
            if (neighborPeer == null) {
                throw new IllegalArgumentException("Invalid neighborPeer happens when checking Uploading Limit.");
            }

            if (uploadingSpeedLimit == 0) {
                return true;
            } else if (uploadingSpeedLimit < 0) {
                return false;
            } else {
                return neighborPeer.getReceivedFromHostSubRate() > uploadingSpeedLimit;
            }
        }

        //Returns if the neighbor has any other delayed Piece Message other than the one specified by pieceIndex.
        public boolean hasOtherPendingPieceMessage(NeighborPeer neighborPeer, int pieceIndex) {
            ConcurrentLinkedQueue<Integer> pieceIndexQueue = delayedPieceMessageMap.get(neighborPeer);
            if (pieceIndexQueue == null || pieceIndexQueue.isEmpty()) {
                return false;
            }
            return !pieceIndexQueue.contains(pieceIndex);
        }

        public void delayRequestMessage(NeighborPeer neighborPeer, int pieceIndex) {
            if (neighborPeer == null) {
                throw new IllegalArgumentException("Invalid neighborPeer happens when delaying Request Message.");
            }

            delayedRequestMessageMap.put(neighborPeer, pieceIndex);
        }

        public void delayPieceMessage(NeighborPeer neighborPeer, int pieceIndex) {
            if (neighborPeer == null) {
                throw new IllegalArgumentException("Invalid neighborPeer happens when delaying Piece Message.");
            }

            ConcurrentLinkedQueue<Integer> pieceIndexQueue = delayedPieceMessageMap.get(neighborPeer);
            if (pieceIndexQueue == null) {
                pieceIndexQueue = new ConcurrentLinkedQueue<>();
                delayedPieceMessageMap.put(neighborPeer, pieceIndexQueue);
            }
            pieceIndexQueue.add(pieceIndex);
        }

    }

    private class ConnectionListener extends ConnectionHandler implements Runnable {

        private final HostPeer hostPeer;
        private final ServerSocket serverSocket;

        public ConnectionListener(HostPeer hostPeer) throws IOException {
            if (hostPeer == null) {
                throw new IllegalArgumentException("Invalid hostPeer happens when creating ConnectionHandler.");
            }

            this.hostPeer = hostPeer;
            try {
                serverSocket = new ServerSocket(hostPeer.getPort());
            } catch (IOException e) {
                P2PLogger.log("IOException happens when creating ConnectionHandler.");
                throw e;
            }
        }

        @Override
        public void run() {
            Socket socket;

            while (hostPeer.isRunning()) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    break;
                }
                int peerID = verifyHandshake(socket);
                sendHandshake(socket, hostPeer.getPeerID());
                P2PLogger.log("Peer " + hostPeer.getPeerID() + " is connected from Peer " + peerID + ".");
                hostPeer.registerNeighbor(peerID, socket);
            }

            closeSocket();
            if (DEBUG) {
                P2PLogger.log("[DEBUG] Thread exists for ConnectionListener.");
            }
        }

        public void closeSocket() {
            try {
                serverSocket.close();
            } catch (IOException e) {
                P2PLogger.log("IOException happens when closing serverSocket. Exception is not rethrown.");
            }
        }

        public String getIPAddress() {
            return serverSocket.getInetAddress().getHostAddress();
        }

    }

    public class ConnectionStarter extends ConnectionHandler implements Runnable {

        private final HostPeer hostPeer;
        private final ConcurrentLinkedQueue<Peer> connectingPeerQueue;

        public ConnectionStarter(HostPeer hostPeer, ArrayList<Peer> knownPeerList) {
            if (hostPeer == null) {
                throw new IllegalArgumentException("Invalid hostPeer happens when creating ConnectionHandler.");
            }
            if (knownPeerList == null) {
                throw new IllegalArgumentException("Invalid knownPeerList happens when creating ConnectionHandler.");
            }

            this.hostPeer = hostPeer;
            connectingPeerQueue = new ConcurrentLinkedQueue<>(knownPeerList);
        }

        @Override
        public void run() {
            int threadSleep = 200;
            long threadSleepCount = 0;
            Socket socket;

            while (hostPeer.isRunning()) {
                if (threadSleepCount % 3000 == 0) {
                    Iterator<Peer> iterator = connectingPeerQueue.iterator();
                    while (iterator.hasNext() && hostPeer.isRunning()) {        //Prevent exit of program from waiting until all peers are tried. It could take N seconds.
                        Peer peer = iterator.next();
                        try {
                            socket = new Socket();
                            socket.connect(new InetSocketAddress(peer.getHostname(), peer.getPort()), 2000);
                        } catch (IOException e) {
                            continue;    //Unable to connect. Pass this peer.
                        }
                        sendHandshake(socket, hostPeer.getPeerID());
                        int peerID = verifyHandshake(socket);
                        P2PLogger.log("Peer " + hostPeer.getPeerID() + " makes a connection to Peer " + peerID + ".");
                        hostPeer.registerNeighbor(peerID, socket);
                        iterator.remove();
                    }
                }

                try {
                    Thread.sleep(threadSleep);
                } catch (InterruptedException e) {
                    break;
                }
                threadSleepCount += threadSleep;
            }

            if (DEBUG) {
                P2PLogger.log("[DEBUG] Thread exists for ConnectionStarter.");
            }
        }

        public void addConnectingPeer(Peer peer) {
            if (peer == null) {
                return;
            }

            connectingPeerQueue.add(peer);
        }

        public int getConnectingPeerCount() {
            return connectingPeerQueue.size();
        }

    }

    private abstract class ConnectionHandler {

        private static final String HANDSHAKE = "P2PFILESHARINGPROJ\0\0\0\0\0\0\0\0\0\0";

        //Returns non-negative peerID if verification passes; returns -1 if not passes.
        protected int verifyHandshake(Socket socket) {
            int peerID;
            DataInputStream input;

            if (socket == null) {
                return -1;
            }

            try {
                input = new DataInputStream(socket.getInputStream());
            } catch (IOException e) {
                P2PLogger.log("IOException happens when getting input stream. Exception is not rethrown.");
                return -1;
            }
            try {
                byte buffer[] = new byte[HANDSHAKE.length()];
                input.readFully(buffer);
                String receivedHandshake = new String(buffer, "UTF-8");
                if (!receivedHandshake.equals(HANDSHAKE)) {
                    return -1;
                }
                peerID = input.readInt();
            } catch (UnsupportedEncodingException e) {
                P2PLogger.log("UnsupportedEncodingException happens when verifying handshake. Exception is not rethrown.");
                return -1;
            } catch (IOException e) {
                P2PLogger.log("IOException happens when verifying handshake. Exception is not rethrown.");
                try {
                    input.close();
                } catch (IOException ex) {
                    P2PLogger.log("IOException happens when closing input stream. Exception is not rethrown.");
                }
                return -1;
            }

            return peerID;
        }

        //Returns 0 if sent.
        @SuppressWarnings("UnusedReturnValue")
        protected int sendHandshake(Socket socket, int hostPeerID) {
            DataOutputStream output;

            try {
                output = new DataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                P2PLogger.log("IOException happens when getting output stream. Exception is not rethrown.");
                return -1;
            }
            try {
                output.writeBytes(HANDSHAKE);
                output.writeInt(hostPeerID);
                output.flush();
            } catch (IOException e) {
                P2PLogger.log("IOException happens when sending handshake. Exception is not rethrown.");
                try {
                    output.close();
                } catch (IOException ex) {
                    P2PLogger.log("IOException happens when closing output stream. Exception is not rethrown.");
                }
                return -1;
            }

            return 0;
        }

    }

}
