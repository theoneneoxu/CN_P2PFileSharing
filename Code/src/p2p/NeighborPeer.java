package p2p;

import static p2p.Peer.MessageType.*;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;

public class NeighborPeer extends Peer {

	private volatile boolean preferredByHost;
	private volatile boolean optimisticByHost;
	private volatile boolean previousInterestOfHost;
	private volatile boolean interestedInHost;
	private volatile boolean unchokedHost;
	private long sentToHostTotalCount;
	private long sentToHostSubCount;
	private long receivedFromHostTotalCount;
	private long receivedFromHostSubCount;
	private final Timestamp creationTimestamp;
	private final Timestamp subCountTimestamp;
	private final HostPeer hostPeer;
	private final MessageHandler messageHandler;

	NeighborPeer(int peerID, HostPeer hostPeer, Socket socket) {
		super(peerID, socket.getInetAddress().getCanonicalHostName(), socket.getPort(), hostPeer.getPieceCount(), false);

		this.hostPeer = hostPeer;
		preferredByHost = false;
		optimisticByHost = false;
		previousInterestOfHost = false;
		interestedInHost = false;
		unchokedHost = false;
		sentToHostTotalCount = 0;
		sentToHostSubCount = 0;
		receivedFromHostTotalCount = 0;
		receivedFromHostSubCount = 0;
		creationTimestamp = new Timestamp(System.currentTimeMillis());
		subCountTimestamp = new Timestamp(System.currentTimeMillis());
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
		messageHandler.replaceSocket(socket);
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

	public int addSentToHostCount(long count) {
		if (count < 0) {
			return -1;
		}

		synchronized (creationTimestamp) {
			sentToHostTotalCount += count;
			sentToHostSubCount += count;
		}
		return 0;
	}
	
	public int addReceivedFromHostCount(long count) {
		if (count < 0) {
			return -1;
		}

		synchronized (creationTimestamp) {
			receivedFromHostTotalCount += count;
			receivedFromHostSubCount += count;
		}
		return 0;
	}

	public long getSentToHostTotalCount() {
		synchronized (creationTimestamp) {
			return sentToHostTotalCount;
		}
	}
	
	public long getSentToHostSubCount() {
		synchronized (creationTimestamp) {
			return sentToHostSubCount;	
		}
	}
	
	public long getReceivedFromHostTotalCount() {
		synchronized (creationTimestamp) {
			return receivedFromHostTotalCount;
		}
	}
	
	public long getReceivedFromHostSubCount() {
		synchronized (creationTimestamp) {
			return receivedFromHostSubCount;
		}
	}
	
	public long getSentToHostTotalRate() {
		long rate;
		synchronized (creationTimestamp) {
			long timeInterval = System.currentTimeMillis() - creationTimestamp.getTime();
			if (timeInterval > 0) {
				rate = sentToHostTotalCount * 1000 / timeInterval;
			} else {
				rate = 0;
			}
		}
		return rate;
	}
	
	public long getSentToHostSubRate() {
		long rate;
		synchronized (creationTimestamp) {
			long timeInterval = System.currentTimeMillis() - subCountTimestamp.getTime();
			if (timeInterval > 0) {
				rate = sentToHostSubCount * 1000 / timeInterval;
			} else {
				rate = 0;
			}
		}
		return rate;
	}
	
	public long getReceivedFromHostTotalRate() {
		long rate;
		synchronized (creationTimestamp) {
			long timeInterval = System.currentTimeMillis() - creationTimestamp.getTime();
			if (timeInterval > 0) {
				rate = receivedFromHostTotalCount * 1000 / timeInterval;
			} else {
				rate = 0;
			}
		}
		return rate;
	}
	
	public long getReceivedFromHostSubRate() {
		long rate;
		synchronized (creationTimestamp) {
			long timeInterval = System.currentTimeMillis() - subCountTimestamp.getTime();
			if (timeInterval > 0) {
				rate = receivedFromHostSubCount * 1000 / timeInterval;
			} else {
				rate = 0;
			}
		}
		return rate;
	}
	
	public void resetSubCount() {
		synchronized (creationTimestamp) {
			sentToHostSubCount = 0;
			receivedFromHostSubCount = 0;
			subCountTimestamp.setTime(System.currentTimeMillis());
		}
	}
	
	public boolean hasReachedDownloadingLimit() {
		return hostPeer.getSpeedLimiter().hasReachedDownloadingLimit(this);
	}
	
	public boolean hasReachedUploadingLimit() {
		return hostPeer.getSpeedLimiter().hasReachedUploadingLimit(this);
	}
	
	public MessageHandler getMessageHandler() {
		return messageHandler;
	}
	
	@Override
	public String getIPAddress() {
		return messageHandler.getIPAddress();
	}

	//Handles actual messages exchanged after handshake between host and neighbor.
	public class MessageHandler implements Runnable {
		
		private final HostPeer hostPeer;
		private final NeighborPeer neighborPeer;
		private Socket socket;
		private DataInputStream input;
		private DataOutputStream output;
		private final Object socketLock;
		private final Object outputLock;
		
		MessageHandler(HostPeer hostPeer, NeighborPeer neighborPeer, Socket socket) {
			if (hostPeer == null) {
				throw new IllegalArgumentException("Invalid hostPeer happens when creating MessageHandler.");
			}
			if (neighborPeer == null) {
				throw new IllegalArgumentException("Invalid neighborPeer happens when creating MessageHandler.");
			}
			if (socket == null) {
				throw new IllegalArgumentException("Invalid socket happens when creating MessageHandler.");
			}	
			
			this.hostPeer = hostPeer;
			this.neighborPeer = neighborPeer;
			this.socket = socket;
			try {
				input = new DataInputStream(socket.getInputStream());
				output = new DataOutputStream(socket.getOutputStream());
			}
			catch (IOException e) {
				P2PLogger.log("IOException happens when creating MessageHandler. Exception is not rethrown.");
				closeSocket();
			}
			socketLock = new Object();
			outputLock = new Object();
		}
		
		//Message listener.
		@Override
		public void run() {
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
				}
				catch (IOException e) {
					break;
				}

				switch(messageType) {
				case CHOKE:
					neighborPeer.setUnchokedHost(false);
					P2PLogger.log("Peer " + hostPeer.getPeerID() + " is choked by " + neighborPeer.getPeerID() + ".");
					break;
				case UNCHOKE:
					neighborPeer.setUnchokedHost(true);
					P2PLogger.log("Peer " + hostPeer.getPeerID() + " is unchoked by " + neighborPeer.getPeerID() + ".");
					if (hostPeer.isInterested(neighborPeer)) {
						sendMessage(REQUEST, hostPeer.findNextInterestingPiece(neighborPeer));	
					} else {
						sendMessage(NOT_INTERESTED);
					}
					break;
				case INTERESTED:
					neighborPeer.setInterestedInHost(true);
					P2PLogger.log("Peer " + hostPeer.getPeerID() + " received the 'interested' message from " + neighborPeer.getPeerID() + ".");
					break;
				case NOT_INTERESTED:
					neighborPeer.setInterestedInHost(false);
					P2PLogger.log("Peer " + hostPeer.getPeerID() + " received the 'not interested' message from " + neighborPeer.getPeerID() + ".");
					break;
				case HAVE:
					if (messagePayload.length == 4) {
						pieceIndex = ByteBuffer.wrap(messagePayload).getInt();
						P2PLogger.log("Peer " + hostPeer.getPeerID() + " received the 'have' message from " + neighborPeer.getPeerID() + " for the piece " + pieceIndex + ".");
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
						sendMessage(CHOKE);
					}
					break;
				case PIECE:
					if (!hostPeer.hasPiece(pieceIndex)) {
						if (hostPeer.getSharedFile().writePiece(pieceIndex, piece) == 0) {
							hostPeer.markPieceComplete(pieceIndex);
							neighborPeer.addSentToHostCount(piece.length);
							P2PLogger.log("Peer " + hostPeer.getPeerID() + " has downloaded the piece " + pieceIndex + " from Peer " + neighborPeer.getPeerID() + ". Now the number of pieces it has is " + hostPeer.getCompletePieceCount() + ".");
							if (hostPeer.hasCompleteFile()) {
								P2PLogger.log("Peer " + hostPeer.getPeerID() + " has downloaded the complete file.");
							}
							//Iterate over copy of active neighbor list to prevent from blocking the list for too long. Because sendMessage may take a long time.
							ArrayList<NeighborPeer> snapshotOfActiveNeighborList;
							synchronized (hostPeer.getActiveNeighborList()) {
								snapshotOfActiveNeighborList = new ArrayList<>(hostPeer.getActiveNeighborList());
							}
							for (NeighborPeer np : snapshotOfActiveNeighborList) {
								np.getMessageHandler().sendMessage(HAVE, pieceIndex);
								if (np.isPreviousInterestOfHost() && !hostPeer.isInterested(np)) {
									np.getMessageHandler().sendMessage(NOT_INTERESTED);
								}
							}
						}
					}
					if (hostPeer.isInterested(neighborPeer) && neighborPeer.isUnchokedHost()) {
						sendMessage(REQUEST, hostPeer.findNextInterestingPiece(neighborPeer));
					}
					break;
				default:
					P2PLogger.log("Invalid messageType happens when processing message for peer " + neighborPeer.getPeerID() + ".");
					break;
				}
			}

			closeSocket();

			if (hostPeer.isRunning()) {
				P2PLogger.log("Connection is lost for Peer " + neighborPeer.getPeerID() + ".");
				hostPeer.getActiveNeighborList().remove(neighborPeer);
				hostPeer.getInactiveNeighborList().add(neighborPeer);
				hostPeer.getConnectionStarter().addConnectingPeer(neighborPeer);
			}
			//P2PLogger.log("Thread exists for peer " + neighborPeer.getPeerID() + ".");
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
			case REQUEST:
				if (neighborPeer.hasReachedDownloadingLimit()) {
					hostPeer.getSpeedLimiter().delayRequestMessage(neighborPeer, pieceIndex);
					return;
				}
				messagePayload = ByteBuffer.allocate(4).putInt(pieceIndex).array();
				messageLength += messagePayload.length;
				break;
			case BITFIELD:
				messagePayload = hostPeer.getPieceStatusAsBitfield();
				messageLength += messagePayload.length;
				break;
			case PIECE:
				if (neighborPeer.hasReachedUploadingLimit()) {
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
			//P2PLogger.log("[DEBUG] Peer " + hostPeer.getPeerID() + " is sending " + messageType + " Message to Peer " + neighborPeer.getPeerID() + " for piece " + pieceIndex + ".");
			
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
			}
			catch (IOException e) {
				//P2PLogger.log("IOException happens when sending message for peer " + neighborPeer.getPeerID() + ". Exception is not rethrown.");
			}
		}
		
		public void sendMessage(MessageType messageType) {
			sendMessage(messageType, -1);
		}

		public final void closeSocket(){
			try {
				synchronized (socketLock) {
					socket.close();
				}
			}
			catch (IOException e) {
				P2PLogger.log("IOException happens when closing socket for peer " + neighborPeer.getPeerID() + ". Exception is not rethrown.");
			}
		}
		
		public String getIPAddress() {
			synchronized (socketLock) {
				return socket.getInetAddress().getHostAddress();
			}
		}
		
		//Only call this method when neighbor is reconnected.
		private void replaceSocket(Socket socket) {
			if (socket == null) {
				return;
			}

			synchronized (socketLock) {
				this.socket = socket;
			}
			try {
				input = new DataInputStream(socket.getInputStream());
				synchronized (outputLock) {
					output = new DataOutputStream(socket.getOutputStream());				
				}
			}
			catch (IOException e) {
				P2PLogger.log("IOException happens when replacing socket. Exception is not rethrown.");
				closeSocket();
			}
		}		

	}
		
}
