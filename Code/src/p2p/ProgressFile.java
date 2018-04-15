package p2p;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

public class ProgressFile {

	private final String fileName;
	private final String fileDirectory;
	private final int fileSize;
	private final RandomAccessFile fileIO;
	
	@SuppressWarnings("ResultOfMethodCallIgnored")
	public ProgressFile(String fileName, String fileDirectory, int fileSize) throws IOException {
		if (fileName == null || fileName.length() == 0) {
			throw new IllegalArgumentException("Invalid fileName happens when creating ProgressFile.");
		}
		if (fileSize < 1) {
			throw new IllegalArgumentException("Invalid fileSize happens when creating ProgressFile.");
		}

		this.fileName = fileName;
		this.fileDirectory = fileDirectory;
		this.fileSize = fileSize;

		File file = new File(fileDirectory, fileName);
		if (file.isFile()) {	//Check if file exists and is a normal file, not a directory.
			if (file.length() != fileSize) {
				P2PLogger.log("[" + getFilePath() + "] File size is inconsistent. Reset size to " + fileSize + ".");
			}
		} else {
			new File(fileDirectory).mkdirs();
		}
		
		try {
			fileIO = new RandomAccessFile(file, "rw");	//Open or create the file;
			fileIO.setLength(fileSize);
		}
		catch (FileNotFoundException e) {
			P2PLogger.log("[" + getFilePath() + "] FileNotFoundException happens when opening or creating file.");
			throw e;
		}
		catch (IOException e) {
			P2PLogger.log("[" + getFilePath() + "] IOException happens when setting file length.");
			closeFile();
			throw e;
		}
	}

	public final String getFilePath() {
		return fileDirectory + File.separator + fileName;
	}
	
	//Only read if file does exists. Check before read.
	public byte[] readFile() {
		byte[] bytes = new byte[fileSize];

		try {
			synchronized (fileIO) {
				fileIO.seek(0);
				fileIO.readFully(bytes);
			}
		}
		catch (IOException e) {
			P2PLogger.log("[" + getFilePath() + "] IOException happens when reading file. Exception is not rethrown.");
			return new byte[0];
		}
		return bytes;
	}

	//Returns 0 if writing is successful.
	//Returns -1 if writing is not successful.
	@SuppressWarnings("UnusedReturnValue")
	public int writeFile(byte[] bytes) {
		if (bytes == null || bytes.length > fileSize) {
			P2PLogger.log("[" + getFilePath() + "] Invalid bytes happens when writing file.");
			return -1;
		}

		try {
			synchronized (fileIO) {
				fileIO.seek(0);
				fileIO.write(bytes);
				fileIO.seek(bytes.length);
				fileIO.write(new byte[fileSize - bytes.length]);
			}
		}
		catch (IOException e) {
			P2PLogger.log("[" + getFilePath() + "] IOException happens when writing file. Exception is not rethrown.");
			return -1;
		}
		return 0;
	}
	
	public final void closeFile() {
		try {
			synchronized (fileIO) {
				fileIO.close();
			}
		}
		catch (IOException e) {
			P2PLogger.log("[" + getFilePath() + "] IOException happens when closing file. Exception is not rethrown.");
		}
	}
	
}
