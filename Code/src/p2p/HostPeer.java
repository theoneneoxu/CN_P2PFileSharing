package p2p;

import static p2p.Peer.MessageType.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
	private final List<NeighborPeer> activeNeighborList;
	private final List<NeighborPeer> inactiveNeighborList;
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
		
		this.downloadingSpeedLimit = downloadingSpeedLimit;
		this.uploadingSpeedLimit = uploadingSpeedLimit;
		this.sharedFile = sharedFile;
		this.progressFile = progressFile;
		peerManager = new PeerManager(this, preferredNeighborCount, preferredUnchokingInterval, optimisticNeighborCount, optimisticUnchokingInterval);
		speedLimiter = new SpeedLimiter(this, downloadingSpeedLimit, uploadingSpeedLimit);
		connectionListener = new ConnectionListener(this);
		connectionStarter = new ConnectionStarter(this, knownPeerList);
		activeNeighborList = Collections.synchronizedList(new ArrayList<>());
		inactiveNeighborList = Collections.synchronizedList(new ArrayList<>());
		neighborThreadPool = Executors.newFixedThreadPool(10);
	}

	public void startRunning() {
		runningIndicator = true;
		pauseIndicator = false;
		
		//to be changed to use thread pool
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
		}
		catch (InterruptedException e) {
			P2PLogger.log("InterruptedException happens when stop running host peer. Exception is not rethrown.");
		}

		//Close sockets to force threads to get out of blocking on input stream.
		connectionListener.closeSocket();
		synchronized (activeNeighborList) {
			for (NeighborPeer np : activeNeighborList) {
				np.getMessageHandler().closeSocket();
			}
		}
		
		//Shutdown thread pool.
		neighborThreadPool.shutdown();
		try {
			if (!neighborThreadPool.awaitTermination(2000, TimeUnit.MILLISECONDS)) {
				neighborThreadPool.shutdownNow();
				//P2PLogger.log("Neighbor Thread Pool did not terminate in 1s. Forced to shutdown.");
			}
		}
		catch (InterruptedException e) {
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
	
	public List<NeighborPeer> getActiveNeighborList() {
		return activeNeighborList;
	}

	public List<NeighborPeer> getInactiveNeighborList() {
		return inactiveNeighborList;
	}

	public SharedFile getSharedFile() {
		return sharedFile;
	}
	
	public ConnectionStarter getConnectionStarter() {
		return connectionStarter;
	}
	
	public SpeedLimiter getSpeedLimiter() {
		return speedLimiter;
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
			bitSetList.add((BitSet)getPieceStatus().clone());			
		}
		synchronized (activeNeighborList) {
			for (NeighborPeer np : activeNeighborList) {
				synchronized (np.getPieceStatus()) {
					bitSetList.add((BitSet)np.getPieceStatus().clone());		
				}
			}
		}

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
		NeighborPeer neighborPeer;
		
		if (socket == null) {
			return;
		}
		synchronized (activeNeighborList) {
			if (peerID < 0 || activeNeighborList.stream().filter(p -> p.getPeerID() == peerID).count() > 0) {
				try {
					socket.close();
				}
				catch (IOException e) {
					P2PLogger.log("IOException happens when closing socket in registerNeighbor. Exception is not rethrown.");
				}
				return;
			}
		}

		synchronized (inactiveNeighborList) {
			neighborPeer = inactiveNeighborList.stream().filter(p -> p.getPeerID() == peerID).findFirst().orElse(null);
			inactiveNeighborList.remove(neighborPeer);
		}
		if (neighborPeer == null) {
			neighborPeer = new NeighborPeer(peerID, this, socket);
		} else {
			neighborPeer.reactivatePeer(socket);
		}
		neighborPeer.getMessageHandler().sendMessage(BITFIELD);
		activeNeighborList.add(neighborPeer);
		neighborThreadPool.execute(neighborPeer.getMessageHandler());
		//new Thread(neighborPeer.getMessageHandler()).start();
	}

	private class PeerManager implements Runnable {
		
		private final int preferredNeighborCount;
		private final int preferredUnchokingInterval;	//in seconds
		private final int optimisticNeighborCount;
		private final int optimisticUnchokingInterval;	//in seconds
		private final HostPeer hostPeer;
		
		PeerManager(HostPeer hostPeer, int preferredNeighborCount, int preferredUnchokingInterval, int optimisticNeighborCount, int optimisticUnchokingInterval) {
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
			int threadSleep = 1000;
			long threadSleepCount = 0;
			
			while (hostPeer.isRunning()) {
				if (hostPeer.isPaused()) {
					if (threadSleepCount % 1000 == 0) {
						synchronized (hostPeer.getActiveNeighborList()) {
							for (NeighborPeer np : hostPeer.getActiveNeighborList()) {
								np.setPreferredByHost(false);
								np.setOptimisticByHost(false);
								np.resetSubCount();
							}
						}
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
				}
				catch (InterruptedException e) {
					//P2PLogger.log("InterruptedException happens when sleeping PeerManager. Exception is not rethrown.");
				}
				threadSleepCount += threadSleep;
			}
			//P2PLogger.log("[DEBUG] Thread exists for PeerManager.");
		}

		private void selectPreferredNeighbors() {
			List<NeighborPeer> oldPreferredList;
			List<NeighborPeer> candidateList;

			synchronized (hostPeer.getActiveNeighborList()) {
				oldPreferredList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isPreferredByHost()).collect(Collectors.toList());
				if (hostPeer.hasCompleteFile()) {
					candidateList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isInterestedInHost() && !p.isOptimisticByHost()).collect(Collectors.toList());
					Collections.shuffle(candidateList);
				} else {
					candidateList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isInterestedInHost()).collect(Collectors.toList());
					candidateList.sort(Comparator.comparing(NeighborPeer::getSentToHostSubRate).reversed());
				}
				for (NeighborPeer np : hostPeer.getActiveNeighborList()) {
					np.resetSubCount();
				}
			}			

			ArrayList<NeighborPeer> newPreferredList = new ArrayList<>(candidateList.subList(0, Math.min(preferredNeighborCount, candidateList.size())));

			for (NeighborPeer np : oldPreferredList) {
				if (!newPreferredList.contains(np)) {
					np.setPreferredByHost(false);
					if (!np.isOptimisticByHost()) {
						np.getMessageHandler().sendMessage(CHOKE);
					}
				}
			}
			for (NeighborPeer np : newPreferredList) {
				if (!oldPreferredList.contains(np)) {
					np.setPreferredByHost(true);
					if (!np.isOptimisticByHost()) {
						np.getMessageHandler().sendMessage(UNCHOKE);
					}
				}
			}

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
		
		private void selectOptimisticNeighbors() {
			List<NeighborPeer> oldOptimisticList;
			List<NeighborPeer> candidateList;
			
			synchronized (hostPeer.getActiveNeighborList()) {
				oldOptimisticList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isOptimisticByHost()).collect(Collectors.toList());
				candidateList = hostPeer.getActiveNeighborList().stream().filter(p -> p.isInterestedInHost() && !p.isUnchokedByHost()).collect(Collectors.toList());
			}	
			Collections.shuffle(candidateList);

			ArrayList<NeighborPeer> newOptimisticList = new ArrayList<>(candidateList.subList(0, Math.min(optimisticNeighborCount, candidateList.size())));
			int remainingCount = optimisticNeighborCount - candidateList.size();
			if (remainingCount > 0) {
				List<NeighborPeer> secondaryCandidateList = oldOptimisticList.stream().filter(p -> p.isInterestedInHost()).collect(Collectors.toList());
				secondaryCandidateList.removeAll(newOptimisticList);
				Collections.shuffle(secondaryCandidateList);
				newOptimisticList.addAll(secondaryCandidateList.subList(0, Math.min(remainingCount, secondaryCandidateList.size())));
			}
			
			for (NeighborPeer np : oldOptimisticList) {
				if (!newOptimisticList.contains(np)) {
					np.setOptimisticByHost(false);
					if (!np.isPreferredByHost()) {
						np.getMessageHandler().sendMessage(CHOKE);
					}
				}
			}
			for (NeighborPeer np : newOptimisticList) {
				if (!oldOptimisticList.contains(np)) {
					np.setOptimisticByHost(true);
					if (!np.isPreferredByHost()) {
						np.getMessageHandler().sendMessage(UNCHOKE);
					}
				}
			}

			oldOptimisticList.sort(Comparator.comparing(NeighborPeer::getPeerID));
			newOptimisticList.sort(Comparator.comparing(NeighborPeer::getPeerID));
			if (!newOptimisticList.equals(oldOptimisticList)) {
				if (newOptimisticList.isEmpty()) {
					P2PLogger.log("Peer " + hostPeer.getPeerID() + " has no optimistical neighbors.");
				} else {
					String string = "";
					for (NeighborPeer np : newOptimisticList) {
						string += np.getPeerID() + ", ";
					}
					P2PLogger.log("Peer " + hostPeer.getPeerID() + " has the optimistical neighbor(s) " + string.substring(0, string.length() - 2) + ".");
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
		private final Map<NeighborPeer, Integer> delayedRequestMessageMap;
		private final Map<NeighborPeer, Integer> delayedPieceMessageMap;

		SpeedLimiter(HostPeer hostPeer, int downloadingSpeedLimit, int uploadingSpeedLimit) {
			if (hostPeer == null) {
				throw new IllegalArgumentException("Invalid hostPeer happens when creating SpeedLimiter.");
			}
			
			this.hostPeer = hostPeer;
			this.downloadingSpeedLimit = downloadingSpeedLimit;
			this.uploadingSpeedLimit = uploadingSpeedLimit;
			delayedRequestMessageMap = Collections.synchronizedMap(new HashMap<>());
			delayedPieceMessageMap = Collections.synchronizedMap(new HashMap<>());
		}

		@Override
		public void run() {
			HashMap<NeighborPeer, Integer> snapshot;
			
			while (hostPeer.isRunning()) {
				synchronized (delayedRequestMessageMap) {
					snapshot = new HashMap<>(delayedRequestMessageMap);
					delayedRequestMessageMap.clear();
				}
				for (Map.Entry<NeighborPeer, Integer> entry : snapshot.entrySet()) {
					entry.getKey().getMessageHandler().sendMessage(REQUEST, entry.getValue());		//Iterate over the copy due to sendMessage may add it back to the hash map.
				}
				
				synchronized (delayedPieceMessageMap) {
					snapshot = new HashMap<>(delayedPieceMessageMap);
					delayedPieceMessageMap.clear();
				}
				for (Map.Entry<NeighborPeer, Integer> entry : snapshot.entrySet()) {
					entry.getKey().getMessageHandler().sendMessage(PIECE, entry.getValue());		//Iterate over the copy due to sendMessage may add it back to the hash map.
				}

				try {
					Thread.sleep(100);
				}
				catch (InterruptedException e) {
				}
			}
			//P2PLogger.log("[DEBUG] Thread exists for SpeedLimiter.");
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
		
		public void delayRequestMessage(NeighborPeer neighborPeer, int pieceIndex) {
			if (neighborPeer == null) {
				throw new IllegalArgumentException("Invalid neighborPeer happens when adding SendingRequestNeighbor.");
			}
			
			delayedRequestMessageMap.put(neighborPeer, pieceIndex);
		}
		
		public void delayPieceMessage(NeighborPeer neighborPeer, int pieceIndex) {
			if (neighborPeer == null) {
				throw new IllegalArgumentException("Invalid neighborPeer happens when adding SendingRequestNeighbor.");
			}
			
			delayedPieceMessageMap.put(neighborPeer, pieceIndex);
		}
		
		public boolean hasReachedDownloadingLimit(NeighborPeer neighborPeer) {
			if (neighborPeer == null) {
				return true;
			}

			if (downloadingSpeedLimit == 0) {
				return true;
			}
			else if (downloadingSpeedLimit < 0) {
				return false;
			} else {
				return neighborPeer.getSentToHostSubRate() > downloadingSpeedLimit;
			}
		}
		
		public boolean hasReachedUploadingLimit(NeighborPeer neighborPeer) {
			if (neighborPeer == null) {
				return true;
			}
			
			if (uploadingSpeedLimit == 0) {
				return true;
			}
			else if (uploadingSpeedLimit < 0) {
				return false;
			} else {
				return neighborPeer.getReceivedFromHostSubRate() > uploadingSpeedLimit;				
			}
		}

	}
	
	private class ConnectionListener extends ConnectionHandler implements Runnable {
		
		private final HostPeer hostPeer;
		private final ServerSocket serverSocket;

		ConnectionListener(HostPeer hostPeer) throws IOException {
			if (hostPeer == null) {
				throw new IllegalArgumentException("Invalid hostPeer happens when creating ConnectionHandler.");
			}
			
			this.hostPeer = hostPeer;
			try {
				serverSocket = new ServerSocket(hostPeer.getPort());
			}
			catch (IOException e) {
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
					//socket.setReceiveBufferSize(1024 * 1024);
					//socket.setSendBufferSize(1024 * 1024);
				}
				catch (IOException e) {
					break;
				}
				int peerID = verifyHandshake(socket);
				sendHandshake(socket, hostPeer.getPeerID());
				P2PLogger.log("Peer " + hostPeer.getPeerID() + " is connected from Peer " + peerID + ".");
				hostPeer.registerNeighbor(peerID, socket);
			}
			
			closeSocket();
			//P2PLogger.log("[DEBUG] Thread exists for ConnectionListener.");
		}
		
		public void closeSocket() {
			try {
				serverSocket.close();
			}
			catch (IOException e) {
				P2PLogger.log("IOException happens when closing serverSocket. Exception is not rethrown.");
			}
		}
		
		public String getIPAddress() {
			return serverSocket.getInetAddress().getHostAddress();
		}

	}

	public class ConnectionStarter extends ConnectionHandler implements Runnable {

		private final HostPeer hostPeer;
		private final ArrayList<Peer> knownPeerList;	//Known peers from config file. Host is responsible to initiate connection to these peers. Only used by ConnectionStarter.
		private final List<Peer> connectingPeerList;

		ConnectionStarter(HostPeer hostPeer, ArrayList<Peer> knownPeerList) {
			if (hostPeer == null) {
				throw new IllegalArgumentException("Invalid hostPeer happens when creating ConnectionHandler.");
			}
			if (knownPeerList == null) {
				throw new IllegalArgumentException("Invalid knownPeerList happens when creating ConnectionHandler.");
			}

			this.hostPeer = hostPeer;
			this.knownPeerList = knownPeerList;
			connectingPeerList = Collections.synchronizedList(new ArrayList<>(knownPeerList));
		}
		
		@Override
		public void run() {
			Socket socket;

			while (hostPeer.isRunning()) {
				synchronized (connectingPeerList) {
					Iterator<Peer> iterator = connectingPeerList.iterator();
					while (iterator.hasNext()) {
						Peer peer = iterator.next();
						try {
							socket = new Socket(peer.getHostname(), peer.getPort());	//It would take about 1s to get IOException if unable to connect.
							//socket.setReceiveBufferSize(1024 * 1024);
							//socket.setSendBufferSize(1024 * 1024);
						}
						catch (IOException e) {
							continue;	//Unable to connect. Pass this peer.
						}
						sendHandshake(socket, hostPeer.getPeerID());
						int peerID = verifyHandshake(socket);
						P2PLogger.log("Peer " + hostPeer.getPeerID() + " makes a connection to Peer " + peerID + ".");
						hostPeer.registerNeighbor(peerID, socket);
						iterator.remove();
					}
				}

				try {
					Thread.sleep(3000);
				}
				catch (InterruptedException e) {
				}
			}
			//P2PLogger.log("[DEBUG] Thread exists for ConnectionStarter.");
		}

		public void addConnectingPeer(Peer peer) {
			if (peer == null) {
				return;
			}

			if (knownPeerList.stream().filter(p -> p.getPeerID() == peer.getPeerID()).count() > 0) {
				synchronized (connectingPeerList) {
					if (!connectingPeerList.contains(peer)) {
						connectingPeerList.add(peer);
					}
				}
			}
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
			}
			catch (IOException e) {
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
			}
			catch (UnsupportedEncodingException e) {
				P2PLogger.log("UnsupportedEncodingException happens when verifying handshake. Exception is not rethrown.");
				return -1;
			}
			catch (IOException e) {
				P2PLogger.log("IOException happens when verifying handshake. Exception is not rethrown.");
				try {
					input.close();
				}
				catch (IOException ex) {
					P2PLogger.log("IOException happens when closing input stream. Exception is not rethrown.");
				}
				return -1;
			}
			
			return peerID;
		}
		
		//Returns 0 if sent.
		protected int sendHandshake(Socket socket, int hostPeerID) {
			DataOutputStream output;
			
			try {
				output = new DataOutputStream(socket.getOutputStream());
			}
			catch (IOException e) {
				P2PLogger.log("IOException happens when getting output stream. Exception is not rethrown.");
				return -1;
			}
			try{
				output.writeBytes(HANDSHAKE);
				output.writeInt(hostPeerID);
				output.flush();
			}
			catch (IOException e) {
				P2PLogger.log("IOException happens when sending handshake. Exception is not rethrown.");
				try {
					output.close();
				}
				catch (IOException ex) {
					P2PLogger.log("IOException happens when closing output stream. Exception is not rethrown.");
				}
				return -1;
			}

			return 0;
		}

	}

}
