package com.harkertech.migratefluttersecurestorage.ciphers;

public interface StorageCipher {
  byte[] encrypt(byte[] input) throws Exception;

  byte[] decrypt(byte[] input) throws Exception;
}
