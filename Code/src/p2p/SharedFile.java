package p2p;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

@SuppressWarnings("unused")
public final class SharedFile {

    private final String fileName;
    private final String fileDirectory;
    private final long fileSize;
    private final int pieceSize;
    private final int pieceCount;
    private final RandomAccessFile fileIO;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public SharedFile(String fileName, String fileDirectory, long fileSize, int pieceSize) throws IOException {
        if (fileName == null || fileName.length() == 0) {
            throw new IllegalArgumentException("Invalid fileName happens when creating SharedFile.");
        }
        if (fileSize < 1) {
            throw new IllegalArgumentException("Invalid fileSize happens when creating SharedFile.");
        }
        if (pieceSize < 1) {
            throw new IllegalArgumentException("Invalid pieceSize happens when creating SharedFile.");
        }

        this.fileName = fileName;
        this.fileDirectory = fileDirectory;
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
        pieceCount = (int) ((fileSize + pieceSize - 1) / pieceSize);

        File file = new File(fileDirectory, fileName);
        if (file.isFile()) {    //Check if file exists and is a normal file, not a directory.
            if (file.length() != fileSize) {
                P2PLogger.log("[" + getFilePath() + "] File size is inconsistent. Reset size to " + fileSize + ".");
            }
        } else {
            new File(fileDirectory).mkdirs();
        }

        try {
            fileIO = new RandomAccessFile(file, "rw");    //Open or create the file.
            fileIO.setLength(fileSize);
        } catch (FileNotFoundException e) {
            P2PLogger.log("[" + getFilePath() + "] FileNotFoundException happens when opening or creating file.");
            throw e;
        } catch (IOException e) {
            P2PLogger.log("[" + getFilePath() + "] IOException happens when setting file length.");
            closeFile();
            throw e;
        }
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileDirectory() {
        return fileDirectory;
    }

    public final String getFilePath() {
        return fileDirectory + File.separator + fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public int getPieceSize() {
        return pieceSize;
    }

    public int getPieceCount() {
        return pieceCount;
    }

    //Returns actual piece size.
    //Returns -1 if piece index is invalid.
    public int getActualPieceSize(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= pieceCount) {
            return -1;
        }
        return pieceIndex == pieceCount - 1 ? (int) (fileSize % pieceSize) : pieceSize;
    }

    //Returns data piece of the file. Returned array length is 0 if pieceIndex is invalid.
    public byte[] readPiece(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= pieceCount) {
            //P2PLogger.log("[" + getFilePath() + "] Invalid piece index " + pieceIndex + " happens when reading file piece.");
            return new byte[0];
        }

        byte[] piece = new byte[getActualPieceSize(pieceIndex)];
        try {
            synchronized (fileIO) {
                fileIO.seek((long) pieceIndex * pieceSize);
                fileIO.readFully(piece);
            }
        } catch (IOException e) {
            P2PLogger.log("[" + getFilePath() + "] IOException happens when reading file piece. Exception is not rethrown.");
            return new byte[0];
        }
        return piece;
    }

    //Returns 0 if writing is successful.
    //Returns -1 if writing is not successful.
    public int writePiece(int pieceIndex, byte[] piece) {
        if (pieceIndex < 0 || pieceIndex >= pieceCount) {
            //P2PLogger.log("[" + getFilePath() + "] Invalid piece index " + pieceIndex + " happens when writing file piece.");
            return -1;
        }
        if (piece == null || piece.length != getActualPieceSize(pieceIndex)) {
            P2PLogger.log("[" + getFilePath() + "] Invalid piece happens when writing file piece.");
            return -1;
        }

        try {
            synchronized (fileIO) {
                fileIO.seek((long) pieceIndex * pieceSize);
                fileIO.write(piece);
            }
        } catch (IOException e) {
            P2PLogger.log("[" + getFilePath() + "] IOException happens when writing file piece. Exception is not rethrown.");
            return -1;
        }
        return 0;
    }

    public final void closeFile() {
        try {
            synchronized (fileIO) {
                fileIO.close();
            }
        } catch (IOException e) {
            P2PLogger.log("[" + getFilePath() + "] IOException happens when closing file. Exception is not rethrown.");
        }
    }

}
