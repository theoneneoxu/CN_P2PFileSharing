package p2p;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class P2PLogger {

    private static PrintWriter fileIO;
    private static final Object fileIOLock;

    static {
        fileIOLock = new Object();
    }

    public P2PLogger(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("Invalid fileName happens when creating P2PLogger.");
        }

        try{
            fileIO = new PrintWriter(fileName);
        }
        catch (FileNotFoundException e) {
            fileIO = null;
        }
    }

    public static void log(String string) {
        synchronized (fileIOLock) {
            fileIO.println(getCurrentTime() + ": " + string);
            fileIO.flush();
        }
    }

    private static String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss.SSS"));
    }

    public void closeFile() {
        fileIO.close();
    }

}
