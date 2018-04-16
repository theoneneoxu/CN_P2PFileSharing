import p2p.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.io.FileNotFoundException;

public class peerProcess {

    //Default config file paths.
    private final String commonConfigPath = "Common.cfg";
    private final String peerInformationConfigPath = "PeerInfo.cfg";

    //Default common config settings.
    private String fileName = null;
    private int fileSize = -1;
    private int pieceSize = 65536;					//Best speed if using size around this value.
    private int preferredNeighborCount = 5;
    private int preferredUnchokingInterval = 10;
    private int optimisticNeighborCount = 1;
    private int optimisticUnchokingInterval = 20;

    //Default peer information config settings.
    private ArrayList<Peer> peerList = new ArrayList<>();
    private int hostPeerIndex = -1;

    private int hostPeerID;
    private String fileDirectory;
    private HostPeer hostPeer;
    private P2PLogger p2pLogger;
    private PeerMonitor peerMonitor;

    public static void main(String[] args) {
        int hostPeerID;
        int downloadingSpeedLimit;
        int uploadingSpeedLimit;
        peerProcess process;

        if (args.length < 1 || args.length > 3) {
            System.out.println("Invalid number of parameters. Command format: peerProcess PeerID [Downloading Speed Limit in KB] [Uploading Speed Limit in KB]");
            return;
        }
        try {
            hostPeerID = Integer.parseInt(args[0]);
            switch (args.length) {
            case 1:
                downloadingSpeedLimit = -1;
                uploadingSpeedLimit = -1;
                break;
            case 2:
                downloadingSpeedLimit = Integer.parseInt(args[1]) * 1024;
                uploadingSpeedLimit = -1;
                break;
            case 3:
                downloadingSpeedLimit = Integer.parseInt(args[1]) * 1024;
                uploadingSpeedLimit = Integer.parseInt(args[2]) * 1024;
                break;
            default:
                return;
            }
        }
        catch (NumberFormatException e) {
            System.out.println("Invalid parameter format. Must be number.");
            return;
        }
        if (hostPeerID < 0) {
            System.out.println("Invalid host peer ID. Host peer ID cannot be negative.");
            return;
        }

        try {
            process = new peerProcess(hostPeerID, downloadingSpeedLimit, uploadingSpeedLimit);
        }
        catch (IOException e) {
            System.out.println("IOException happens when creating peerProcess.");
            return;
        }

        process.getHostPeer().startRunning();
        process.getPeerMonitor().run();			//Main thread turns into monitor thread.
        process.close();
    }

    public peerProcess(int hostPeerID, int downloadingSpeedLimit, int uploadingSpeedLimit) throws IOException {
        if (hostPeerID < 0) {
            throw new IllegalArgumentException("Invalid hostPeerID happens when creating peerProcess.");
        }

        this.hostPeerID = hostPeerID;
        fileDirectory = "peer_" + hostPeerID;
        p2pLogger = new P2PLogger("log_peer_" + hostPeerID + ".log");

        if (loadCommonConfig() != 0) {
            String string = "Error happens when loading \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return;
        }
        if (loadPeerInformationConfig() != 0) {
            String string = "Error happens when loading \"" + peerInformationConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return;
        }

        try {
            SharedFile sharedFile = new SharedFile(fileName, fileDirectory, fileSize, pieceSize);
            ProgressFile progressFile = loadProgressFile(peerList.get(hostPeerIndex));
            ArrayList<Peer> knownPeerList = new ArrayList<>(peerList.subList(0, hostPeerIndex));
            hostPeer = new HostPeer(peerList.get(hostPeerIndex),
                    preferredNeighborCount,
                    preferredUnchokingInterval,
                    optimisticNeighborCount,
                    optimisticUnchokingInterval,
                    sharedFile,
                    progressFile,
                    knownPeerList,
                    downloadingSpeedLimit,
                    uploadingSpeedLimit);
        }
        catch (IOException e) {
            String string = "IOException happens when creating hostPeer.";
            P2PLogger.log(string);
            System.out.println(string);
            throw e;
        }
        peerMonitor = new PeerMonitor(hostPeer, peerList.size());
    }

    private int loadCommonConfig() {
        String line;
        BufferedReader commonConfigReader;

        try {
            commonConfigReader = new BufferedReader(new FileReader(commonConfigPath));
        }
        catch (FileNotFoundException e) {
            String string = "File not found: \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        try {
            while ((line = commonConfigReader.readLine()) != null) {
                String[] strings = line.split(" ");
                if (strings.length >= 2) {
                    switch(strings[0]) {
                    case "NumberOfPreferredNeighbors":
                        preferredNeighborCount = Integer.parseInt(strings[1]);
                        break;
                    case "UnchokingInterval":
                        preferredUnchokingInterval = Integer.parseInt(strings[1]);
                        break;
                    case "NumberOfOptimisticNeighbors":
                        optimisticNeighborCount = Integer.parseInt(strings[1]);
                        break;
                    case "OptimisticUnchokingInterval":
                        optimisticUnchokingInterval = Integer.parseInt(strings[1]);
                        break;
                    case "FileName":
                        fileName = strings[1];
                        break;
                    case "FileSize":
                        fileSize = Integer.parseInt(strings[1]);
                        break;
                    case "PieceSize":
                        pieceSize = Integer.parseInt(strings[1]);
                        break;
                    default:
                        break;
                    }
                }
            }
        }
        catch (IOException e) {
            String string = "IOException happens when loading \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        catch (NumberFormatException e) {
            String string = "NumberFormatException happens when loading \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        finally {
            try {
                commonConfigReader.close();
            }
            catch (IOException e) {
                String string = "IOException happens when closing commonConfigReader.";
                P2PLogger.log(string);
                System.out.println(string);
            }
        }

        if (fileName == null || fileName.length() == 0) {
            String string = "FileName is required in \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        if (fileSize < 1) {
            String string = "FileSize is required and must be greater than 0 in \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        if (pieceSize < 1) {
            String string = "PieceSize must be greater than 0 in \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        if (preferredNeighborCount < 1) {
            String string = "NumberOfPreferredNeighbors must be greater than 0 in \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        if (preferredUnchokingInterval < 1) {
            String string = "UnchokingInterval must be greater than 0 in \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        if (optimisticNeighborCount < 1) {
            String string = "NumberOfOptimisticNeighbors must be greater than 0 in \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        if (optimisticUnchokingInterval < 1) {
            String string = "OptimisticUnchokingInterval must be greater than 0 in \"" + commonConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }

        return 0;
    }

    private int loadPeerInformationConfig() {
        String line;
        BufferedReader peerInformationConfigReader;

        try {
            peerInformationConfigReader = new BufferedReader(new FileReader(peerInformationConfigPath));
        }
        catch (FileNotFoundException e) {
            String string = "File not found: \"" + peerInformationConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        try {
            for (int index = 0; (line = peerInformationConfigReader.readLine()) != null; index++) {
                String[] strings = line.split(" ");
                if (strings.length >= 4) {
                    Peer peer = new Peer(Integer.parseInt(strings[0]),
                            strings[1],
                            Integer.parseInt(strings[2]),
                            (fileSize + pieceSize - 1) / pieceSize,
                            strings[3].equals("1"));
                    peerList.add(peer);
                    if (peer.getPeerID() == hostPeerID) {
                        hostPeerIndex = index;
                    }
                }
            }
        }
        catch (IOException e) {
            String string = "IOException happens when loading \"" + peerInformationConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        catch (NumberFormatException e) {
            String string = "NumberFormatException happens when loading \"" + peerInformationConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        catch (IllegalArgumentException e) {
            String string = "IllegalArgumentException happens when loading \"" + peerInformationConfigPath + "\". " + e.getMessage();
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }
        finally {
            try {
                peerInformationConfigReader.close();
            }
            catch (IOException e) {
                String string = "IOException happens when closing peerInformationConfigReader.";
                P2PLogger.log(string);
                System.out.println(string);
            }
        }

        if (hostPeerIndex < 0) {
            String string = "Host peer config is required in \"" + peerInformationConfigPath + "\".";
            P2PLogger.log(string);
            System.out.println(string);
            return -1;
        }

        return 0;
    }

    private ProgressFile loadProgressFile(Peer peer) throws IOException {
        if (peer == null) {
            return null;
        }

        String progressFileName = fileName + ".bitfield";
        boolean hasFile = new File(fileDirectory, progressFileName).isFile();	//Check if file exists and is a normal file, not a directory.
        ProgressFile progressFile = new ProgressFile(progressFileName, fileDirectory, (peer.getPieceCount() + 7) / 8);	//Open or create progress file.
        if(hasFile) {
            peer.setPieceStatus(progressFile.readFile());
        }
        return progressFile;
    }

    public HostPeer getHostPeer() {
        return hostPeer;
    }

    public PeerMonitor getPeerMonitor() {
        return peerMonitor;
    }

    public void close() {
        //Closing of hostPeer is handled by peer monitor.
        p2pLogger.closeFile();
    }

    @SuppressWarnings("CatchMayIgnoreException")
    public class PeerMonitor implements Runnable {

        private final int peerCount;
        private final HostPeer hostPeer;
        private final BufferedReader consoleReader;
        private Peer showDetailPeer;
        private boolean showHelp;

        public PeerMonitor(HostPeer hostPeer, int peerCount) {
            if (hostPeer == null) {
                throw new IllegalArgumentException("Invalid hostPeer happens when creating HostPeerMonitor.");
            }
            if (peerCount < 1) {
                throw new IllegalArgumentException("Invalid totalPeerCount happens when creating HostPeerMonitor.");
            }

            this.hostPeer = hostPeer;
            this.peerCount = peerCount;
            this.consoleReader = new BufferedReader(new InputStreamReader(System.in));
            showDetailPeer = null;
            showHelp = false;
        }

        @Override
        public void run() {
            int threadSleep = 200;
            long threadSleepCount = 0;

            while (hostPeer.isRunning()) {
                if (threadSleepCount % 200 == 0) {
                    processConsoleInput();
                }
                if (threadSleepCount % 2000 == 0) {
                    displayConsoleOutput();
                }
                if (threadSleepCount % 2000 == 0) {     //Stop determination interval must be >= console display interval.
                    if (needToStopRunning()) {
                        hostPeer.stopRunning();
                        break;
                    }
                }

                try {
                    Thread.sleep(threadSleep);
                }
                catch (InterruptedException e) {
                    break;
                }
                threadSleepCount += threadSleep;
            }

            try {
                consoleReader.close();
            }
            catch (IOException e) {
            }
        }

        private boolean needToStopRunning() {
            int count = hostPeer.hasCompleteFile() ? 1 : 0;
            synchronized (hostPeer.getActiveNeighborList()) {
                count += hostPeer.getActiveNeighborList().stream().filter(p -> p.hasCompleteFile()).count();
            }
            synchronized (hostPeer.getInactiveNeighborList()) {
                count += hostPeer.getInactiveNeighborList().stream().filter(p -> p.hasCompleteFile()).count();
            }
            return count == peerCount;
        }

        private void processConsoleInput() {
            String inputString = null;

            try {
                if (consoleReader.ready()) {
                    inputString = consoleReader.readLine();
                }
            }
            catch (IOException e) {
            }
            if (inputString == null || inputString.length() == 0) {
                return;
            }

            String stringArray[] = inputString.split(" ");
            switch (stringArray[0].toLowerCase()) {
            case "e":
            case "exit":
                hostPeer.stopRunning();
                return;
            case "p":
            case "pause":
                if (!hostPeer.isPaused()) {
                    hostPeer.pauseRunning();
                }
                return;
            case "r":
            case "resume":
                if (hostPeer.isPaused()) {
                    hostPeer.resumeRunning();
                }
                return;
            case "d":
            case "download":
                if (hostPeer.isPaused()) {
                    hostPeer.resumeRunning();
                }
                if (stringArray.length == 1) {
                    hostPeer.changeDownloadingSpeedLimit(-1);
                } else if (stringArray.length == 2) {
                    try {
                        int limit = Integer.parseInt(stringArray[1]);
                        hostPeer.changeDownloadingSpeedLimit(limit * 1024);
                    }
                    catch (NumberFormatException e) {
                    }
                }
                return;
            case "u":
            case "upload":
                if (hostPeer.isPaused()) {
                    hostPeer.resumeRunning();
                }
                if (stringArray.length == 1) {
                    hostPeer.changeUploadingSpeedLimit(-1);
                } else if (stringArray.length == 2) {
                    try {
                        int limit = Integer.parseInt(stringArray[1]);
                        hostPeer.changeUploadingSpeedLimit(limit * 1024);
                    }
                    catch (NumberFormatException e) {
                    }
                }
                return;
            case "h":
            case "help":
                showHelp = !showHelp;
                return;
            default:
                break;
            }

            try {
                int peerID = Integer.parseInt(inputString);
                Peer peer = peerID == hostPeer.getPeerID() ? hostPeer : null;
                if (peer == null) {
                    synchronized (hostPeer.getActiveNeighborList()) {
                        peer = hostPeer.getActiveNeighborList().stream().filter(p -> p.getPeerID() == peerID).findFirst().orElse(null);
                    }
                }
                if (peer == null) {
                    synchronized (hostPeer.getInactiveNeighborList()) {
                        peer = hostPeer.getInactiveNeighborList().stream().filter(p -> p.getPeerID() == peerID).findFirst().orElse(null);
                    }
                }
                showDetailPeer = peer;
            }
            catch (NumberFormatException e) {
            }
        }

        private void displayConsoleOutput() {
            String string = "";
            string += getBasicInformation();
            string += "\n";
            string += getPeerTable();
            string += "\n";
            string += getPeerDetails();
            string += showHelp ? "Available commands: (e)xit; (p)ause; (r)esume; (d)ownload limit_in_KB, (u)pload limit_in_KB. Enter (h)elp to disable this message.\n" : "";
            string += hostPeer.isPaused() ? "Download / Upload Paused.\n" : "";
            string += "Enter help for available commands; enter Peer ID for more details: ";

            if (clearConsole() == 0) {
                System.out.print(string);
            }

            /*Print out content design.
            File Name: xxx.mp4    File Size: 1250 MB (1250000000 B)    File Health: 12345%
            Piece Size: 64 KB (65536 B)    Piece Count: 6725    Download Limit: 5000 KB/s per Neighbor    Upload Limit: 1600 KB/s per Neighbor

                                               Download    Upload      Selected    Unchoked    Interest    Interested    Total       Total
            Peer        Peer ID    Progress    Speed       Speed       by Host     Host        of Host     in Host       Download    Upload
            -----------------------------------------------------------------------------------------------------------------------------------
            Host        1001       100%        0           1234560     12345678    12345678    12345678    12345678      1234567890  1234567890
            -----------------------------------------------------------------------------------------------------------------------------------
            Neighbor    10021234   15%         6543 KB/s   850 KB/s    P           Yes         Yes         Yes           1234 MB     21 GB
            Neighbor    1003       50%         1234567890  10000000                Yes         Yes                       1234567890  1234567890
            Neighbor    1004       3%          123456000   10000000    P O         Yes         Yes         Yes           1234567890  1234567890
            Neighbor    1005       20%         123456000   10000000    P                                                 1234567890  1234567890
            Neighbor    1006       0%          123456000   10000000                Yes                                   1234567890  1234567890
            Offline     1007       35%         -           -                                                             1234567890  1234567890
            Offline     1008       25%         -           -                                                             1234567890  1234567890
            Offline     1009       55%         -           -                                                             1234567890  1234567890

            PeerID: 1006    Hostname: localhost    IP: 127.0.0.1     Port: 5995    Complete Piece Count: 8325
            Available commands: (e)xit; (p)ause; (r)esume; (d)ownload limit_in_KB, (u)pload limit_in_KB. Enter (h)elp to disable this message.
            Enter help for available commands; enter Peer ID for more details:
            */
        }

        //Returns 0 if cleared.
        private int clearConsole() {
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("windows")) {
                try {
                    new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
                }
                catch (InterruptedException | IOException e) {
                    return -1;
                }
            } else if (osName.contains("linux") || osName.contains("unix")) {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            } else {
                return -1;
            }
            return 0;
        }

        private String getBasicInformation() {
            String string = "";
            string += "File Name: " + hostPeer.getSharedFile().getFileName() + "    ";
            string += "File Size: " + getSizeString(hostPeer.getSharedFile().getFileSize()) + " (" + hostPeer.getSharedFile().getFileSize() + " B)    ";
            string += "File Health: " + hostPeer.getFileHealthPercentage() + "%";
            string += "\n";
            string += "Piece Size: " + getSizeString(hostPeer.getSharedFile().getPieceSize()) + " (" + hostPeer.getSharedFile().getPieceSize() + " B)    ";
            string += "Piece Count: " + hostPeer.getSharedFile().getPieceCount() + "    ";
            string += "Download Limit: " + (hostPeer.getSpeedLimiter().getDownloadingSpeedLimit() >= 0 ? getSizeString(hostPeer.getSpeedLimiter().getDownloadingSpeedLimit()) + "/s per Neighbor" : "No Limit") + "    ";
            string += "Upload Limit: " + (hostPeer.getSpeedLimiter().getUploadingSpeedLimit() >= 0 ? getSizeString(hostPeer.getSpeedLimiter().getUploadingSpeedLimit()) + "/s per Neighbor" : "No Limit");
            string +=  "\n";
            return string;
        }

        @SuppressWarnings("StringConcatenationInLoop")
        private String getPeerTable() {
            ArrayList<NeighborInfo> activeNeighborInfoList = new ArrayList<>();
            synchronized (hostPeer.getActiveNeighborList()) {
                for (NeighborPeer np : hostPeer.getActiveNeighborList()) {
                    NeighborInfo neighborInfo = new NeighborInfo();
                    neighborInfo.peerID = np.getPeerID();
                    neighborInfo.progressPercentage = np.getCompletePieceCount() * 100 / np.getPieceCount();
                    neighborInfo.downloadSpeed = np.isUnchokedHost() ? np.getSentToHostSubRate() : 0;
                    neighborInfo.uploadSpeed = np.isUnchokedByHost() ? np.getReceivedFromHostSubRate() : 0;
                    if (np.isPreferredByHost() && np.isOptimisticByHost()) {
                        neighborInfo.selectedByHost = "P O";
                    } else if (np.isPreferredByHost()) {
                        neighborInfo.selectedByHost = "P  ";
                    } else if (np.isOptimisticByHost()) {
                        neighborInfo.selectedByHost = "O  ";
                    } else {
                        neighborInfo.selectedByHost = "   ";
                    }
                    neighborInfo.unchokedHost = np.isUnchokedHost() ? "Yes" : "   ";
                    neighborInfo.interestOfHost = np.isPreviousInterestOfHost() ? "Yes" : "   ";
                    neighborInfo.interestedInHost = np.isInterestedInHost() ? "Yes" : "   ";
                    neighborInfo.totalDownload = np.getSentToHostTotalCount();
                    neighborInfo.totalUpload = np.getReceivedFromHostTotalCount();
                    activeNeighborInfoList.add(neighborInfo);
                }
            }

            ArrayList<NeighborInfo> inactiveNeighborInfoList = new ArrayList<>();
            synchronized (hostPeer.getInactiveNeighborList()) {
                for (NeighborPeer np : hostPeer.getInactiveNeighborList()) {
                    NeighborInfo neighborInfo = new NeighborInfo();
                    neighborInfo.peerID = np.getPeerID();
                    neighborInfo.progressPercentage = np.getCompletePieceCount() * 100 / np.getPieceCount();
                    neighborInfo.downloadSpeed = 0;
                    neighborInfo.uploadSpeed = 0;
                    neighborInfo.selectedByHost = "   ";
                    neighborInfo.unchokedHost = "   ";
                    neighborInfo.interestOfHost = "   ";
                    neighborInfo.interestedInHost = "   ";
                    neighborInfo.totalDownload = np.getSentToHostTotalCount();
                    neighborInfo.totalUpload = np.getReceivedFromHostTotalCount();
                    inactiveNeighborInfoList.add(neighborInfo);
                }
            }

            HostInfo hostInfo = new HostInfo();
            hostInfo.peerID = hostPeer.getPeerID();
            hostInfo.progressPercentage = hostPeer.getCompletePieceCount() * 100 / hostPeer.getPieceCount();
            hostInfo.downloadSpeed = activeNeighborInfoList.stream().mapToLong(n -> n.downloadSpeed).sum();
            hostInfo.uploadSpeed = activeNeighborInfoList.stream().mapToLong(n -> n.uploadSpeed).sum();
            hostInfo.selectedByHostCount = (int)activeNeighborInfoList.stream().filter(n -> !n.selectedByHost.equals("   ")).count();
            hostInfo.unchokedHostCount = (int)activeNeighborInfoList.stream().filter(n -> n.unchokedHost.equals("Yes")).count();
            hostInfo.interestOfHostCount = (int)activeNeighborInfoList.stream().filter(n -> n.interestOfHost.equals("Yes")).count();
            hostInfo.interestedInHostCount = (int)activeNeighborInfoList.stream().filter(n -> n.interestedInHost.equals("Yes")).count();
            hostInfo.totalDownload = activeNeighborInfoList.stream().mapToLong(n -> n.totalDownload).sum() + inactiveNeighborInfoList.stream().mapToLong(n -> n.totalDownload).sum();
            hostInfo.totalUpload = activeNeighborInfoList.stream().mapToLong(n -> n.totalUpload).sum() + inactiveNeighborInfoList.stream().mapToLong(n -> n.totalUpload).sum();

            String string = "";
            string += "                                   Download    Upload      Selected    Unchoked    Interest    Interested    Total       Total\n";
            string += "Peer        Peer ID    Progress    Speed       Speed       by Host     Host        of Host     in Host       Download    Upload\n";
            string += "-----------------------------------------------------------------------------------------------------------------------------------\n";
            string += String.format("Host        %-10d %-4s        %-12s%-12s%-8d    %-8d    %-8d    %-8d      %-12s%-12s\n",
                    hostInfo.peerID,
                    hostInfo.progressPercentage + "%",
                    getSizeString(hostInfo.downloadSpeed) + "/s",
                    getSizeString(hostInfo.uploadSpeed) + "/s",
                    hostInfo.selectedByHostCount,
                    hostInfo.unchokedHostCount,
                    hostInfo.interestOfHostCount,
                    hostInfo.interestedInHostCount,
                    getSizeString(hostInfo.totalDownload),
                    getSizeString(hostInfo.totalUpload));
            string += "-----------------------------------------------------------------------------------------------------------------------------------\n";
            for (NeighborInfo ni : activeNeighborInfoList) {
                string += String.format("Neighbor    %-10d %-4s        %-12s%-12s%3s         %3s         %3s         %3s           %-12s%-12s\n",
                        ni.peerID,
                        ni.progressPercentage + "%",
                        getSizeString(ni.downloadSpeed) + "/s",
                        getSizeString(ni.uploadSpeed) + "/s",
                        ni.selectedByHost,
                        ni.unchokedHost,
                        ni.interestOfHost,
                        ni.interestedInHost,
                        getSizeString(ni.totalDownload),
                        getSizeString(ni.totalUpload));
            }
            for (NeighborInfo ni : inactiveNeighborInfoList) {
                string += String.format("Offline     %-10d %-4s        %-12s%-12s%3s         %3s         %3s         %3s           %-12s%-12s\n",
                        ni.peerID,
                        ni.progressPercentage + "%",
                        "-",
                        "-",
                        ni.selectedByHost,
                        ni.unchokedHost,
                        ni.interestOfHost,
                        ni.interestedInHost,
                        getSizeString(ni.totalDownload),
                        getSizeString(ni.totalUpload));
            }
            return string;
        }

        private String getPeerDetails() {
            String string = "";
            if (showDetailPeer != null) {
                string += "PeerID: " + showDetailPeer.getPeerID() + "    ";
                string += "Hostname: " + showDetailPeer.getHostname() + "    ";
                string += "IP: " + showDetailPeer.getIPAddress() + "    ";
                string += "Port: " + showDetailPeer.getPort() + "    ";
                string += "Complete Piece Count: " + showDetailPeer.getCompletePieceCount();
                string += "\n";
            }
            return string;
        }

        private String getSizeString(long size) {
            if (size < 0) {
                return "";
            }

            final String units[] = {" B", " KB", " MB", " GB", " TB"};
            int level = 0;
            while (size >= 10000 && level < units.length) {
                size /= 1024;
                level++;
            }
            return size + units[level];
        }

        private class HostInfo {
            public int peerID;
            public int progressPercentage;
            public long downloadSpeed;
            public long uploadSpeed;
            public int selectedByHostCount;
            public int unchokedHostCount;
            public int interestOfHostCount;
            public int interestedInHostCount;
            public long totalDownload;
            public long totalUpload;
        }

        private class NeighborInfo {
            public int peerID;
            public int progressPercentage;
            public long downloadSpeed;
            public long uploadSpeed;
            public String selectedByHost;
            public String unchokedHost;
            public String interestOfHost;
            public String interestedInHost;
            public long totalDownload;
            public long totalUpload;
        }

    }

}
