package com.codeweave.config;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecretCryptoService {
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH = 128;
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public SecretCryptoService(SecurityProperties properties) {
    if (properties.encryptionKey() == null || properties.encryptionKey().isBlank()) throw new IllegalStateException("codeweave.security.encryption-key 未配置");
    try { this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(properties.encryptionKey().getBytes(StandardCharsets.UTF_8)), "AES"); }
    catch (Exception ex) { throw new IllegalStateException("初始化加密服务失败", ex); }
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_LENGTH]; random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
    } catch (Exception ex) { throw new IllegalStateException("密钥加密失败", ex); }
  }

  public String decrypt(String encoded) {
    try {
      byte[] packed = Base64.getDecoder().decode(encoded);
      byte[] iv = new byte[IV_LENGTH]; byte[] ciphertext = new byte[packed.length - IV_LENGTH];
      System.arraycopy(packed, 0, iv, 0, IV_LENGTH); System.arraycopy(packed, IV_LENGTH, ciphertext, 0, ciphertext.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (Exception ex) { throw new IllegalStateException("密钥解密失败", ex); }
  }
}
