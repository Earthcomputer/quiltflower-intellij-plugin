package org.jetbrains.java.decompiler.main.extern;

import java.util.jar.Manifest;

// Stub class
public interface IResultSaver {
    void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping);
    void saveFolder(String path);
    void copyFile(String source, String path, String entryName);
    void createArchive(String path, String archiveName, Manifest manifest);
    void saveDirEntry(String path, String archiveName, String entryName);
    void copyEntry(String source, String path, String archiveName, String entry);
    void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content);
    void closeArchive(String path, String archiveName);
}
