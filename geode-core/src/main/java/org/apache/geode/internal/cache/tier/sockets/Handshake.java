/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache.tier.sockets;

import static org.apache.geode.distributed.ConfigurationProperties.CONFLATE_EVENTS;
import static org.apache.geode.distributed.ConfigurationProperties.SECURITY_CLIENT_AUTHENTICATOR;
import static org.apache.geode.distributed.ConfigurationProperties.SECURITY_CLIENT_AUTH_INIT;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.Socket;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.InternalGemFireException;
import org.apache.geode.cache.client.PoolFactory;
import org.apache.geode.cache.client.ServerRefusedConnectionException;
import org.apache.geode.cache.client.internal.ServerHandshakeImpl;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.ClassLoadUtil;
import org.apache.geode.internal.HeapDataOutputStream;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.Version;
import org.apache.geode.internal.VersionedDataInputStream;
import org.apache.geode.internal.VersionedDataOutputStream;
import org.apache.geode.internal.cache.tier.ClientHandshake;
import org.apache.geode.internal.cache.tier.CommunicationMode;
import org.apache.geode.internal.cache.tier.ConnectionProxy;
import org.apache.geode.internal.cache.tier.Encryptor;
import org.apache.geode.internal.i18n.LocalizedStrings;
import org.apache.geode.internal.logging.InternalLogWriter;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.security.CallbackInstantiator;
import org.apache.geode.internal.security.Credentials;
import org.apache.geode.internal.security.SecurityService;
import org.apache.geode.internal.security.SecurityServiceFactory;
import org.apache.geode.pdx.internal.PeerTypeRegistration;
import org.apache.geode.security.AuthInitialize;
import org.apache.geode.security.AuthenticationFailedException;
import org.apache.geode.security.AuthenticationRequiredException;
import org.apache.geode.security.Authenticator;
import org.apache.geode.security.GemFireSecurityException;

public class Handshake implements ClientHandshake {
  private static final Logger logger = LogService.getLogger();

  protected static final byte REPLY_OK = (byte) 59;

  protected static final byte REPLY_REFUSED = (byte) 60;

  protected static final byte REPLY_INVALID = (byte) 61;

  protected static final byte REPLY_EXCEPTION_AUTHENTICATION_REQUIRED = (byte) 62;

  protected static final byte REPLY_EXCEPTION_AUTHENTICATION_FAILED = (byte) 63;

  protected static final byte REPLY_EXCEPTION_DUPLICATE_DURABLE_CLIENT = (byte) 64;

  protected static final byte REPLY_WAN_CREDENTIALS = (byte) 65;

  protected static final byte REPLY_AUTH_NOT_REQUIRED = (byte) 66;

  public static final byte REPLY_SERVER_IS_LOCATOR = (byte) 67;
  /**
   * Test hook for client version support
   *
   * @since GemFire 5.7
   */
  protected static Version currentClientVersion = ConnectionProxy.VERSION;

  protected SecurityService securityService;

  protected byte code;

  protected int clientReadTimeout = PoolFactory.DEFAULT_READ_TIMEOUT;

  private boolean isRead = false;

  protected DistributedSystem system;

  protected ClientProxyMembershipID id;

  protected Properties credentials;

  private Version clientVersion;

  // Security mode flags

  /** No credentials being sent */
  public static final byte CREDENTIALS_NONE = (byte) 0;

  /** Credentials being sent without encryption on the wire */
  public static final byte CREDENTIALS_NORMAL = (byte) 1;

  /** Credentials being sent with Diffie-Hellman key encryption */
  public static final byte CREDENTIALS_DHENCRYPT = (byte) 2;

  public static final byte SECURITY_MULTIUSER_NOTIFICATIONCHANNEL = (byte) 3;

  private byte appSecureMode = (byte) 0;

  private PublicKey clientPublicKey = null;

  private String clientSKAlgo = null;

  // Parameters for the Diffie-Hellman key exchange
  private static final BigInteger dhP =
      new BigInteger("13528702063991073999718992897071702177131142188276542919088770094024269"
          + "73079899070080419278066109785292538223079165925365098181867673946"
          + "34756714063947534092593553024224277712367371302394452615862654308"
          + "11180902979719649450105660478776364198726078338308557022096810447"
          + "3500348898008043285865193451061481841186553");

  private static final BigInteger dhG =
      new BigInteger("13058345680719715096166513407513969537624553636623932169016704425008150"
          + "56576152779768716554354314319087014857769741104157332735258102835"
          + "93126577393912282416840649805564834470583437473176415335737232689"
          + "81480201869671811010996732593655666464627559582258861254878896534"
          + "1273697569202082715873518528062345259949959");

  private static final int dhL = 1023;

  private static PrivateKey dhPrivateKey = null;

  private static PublicKey dhPublicKey = null;

  private static String dhSKAlgo = null;

  // Members for server authentication using digital signature

  private static String certificateFilePath = null;

  private static HashMap certificateMap = null;

  private static String privateKeyAlias = null;

  private static String privateKeySubject = null;

  private static PrivateKey privateKeyEncrypt = null;

  private static String privateKeySignAlgo = null;

  private static SecureRandom random = null;

  public static final String PUBLIC_KEY_FILE_PROP = "security-client-kspath";

  public static final String PUBLIC_KEY_PASSWD_PROP = "security-client-kspasswd";

  public static final String PRIVATE_KEY_FILE_PROP = "security-server-kspath";

  public static final String PRIVATE_KEY_ALIAS_PROP = "security-server-ksalias";

  public static final String PRIVATE_KEY_PASSWD_PROP = "security-server-kspasswd";

  /** @since GemFire 5.7 */
  public static final byte CONFLATION_DEFAULT = 0;
  /** @since GemFire 5.7 */
  public static final byte CONFLATION_ON = 1;
  /** @since GemFire 5.7 */
  public static final byte CONFLATION_OFF = 2;
  /** @since GemFire 5.7 */
  protected byte clientConflation = CONFLATION_DEFAULT;

  /**
   * @since GemFire 6.0.3 List of per client property override bits.
   */
  private byte[] overrides;

  /**
   * Test hooks for per client conflation
   *
   * @since GemFire 5.7
   */
  public static byte clientConflationForTesting = 0;
  public static boolean setClientConflationForTesting = false;

  /** Constructor used for mocking */
  protected Handshake() {
    system = null;
    id = null;
    this.securityService = SecurityServiceFactory.create();
  }

  /**
   * HandShake Constructor used by server side connection
   */
  public Handshake(Socket sock, int timeout, DistributedSystem sys, Version clientVersion,
      CommunicationMode communicationMode, SecurityService securityService)
      throws IOException, AuthenticationRequiredException {

    this.clientVersion = clientVersion;
    this.system = sys;
    this.securityService = securityService;

    {
      int soTimeout = -1;
      try {
        soTimeout = sock.getSoTimeout();
        sock.setSoTimeout(timeout);
        InputStream is = sock.getInputStream();
        int valRead = is.read();
        // this.code = (byte)is.read();
        if (valRead == -1) {
          throw new EOFException(
              LocalizedStrings.HandShake_HANDSHAKE_EOF_REACHED_BEFORE_CLIENT_CODE_COULD_BE_READ
                  .toLocalizedString());
        }
        this.code = (byte) valRead;
        if (this.code != REPLY_OK) {
          throw new IOException(
              LocalizedStrings.HandShake_HANDSHAKE_REPLY_CODE_IS_NOT_OK.toLocalizedString());
        }
        try {
          DataInputStream dis = new DataInputStream(is);
          DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
          this.clientReadTimeout = dis.readInt();
          if (clientVersion.compareTo(Version.CURRENT) < 0) {
            // versioned streams allow object serialization code to deal with older clients
            dis = new VersionedDataInputStream(dis, clientVersion);
            dos = new VersionedDataOutputStream(dos, clientVersion);
          }
          this.id = ClientProxyMembershipID.readCanonicalized(dis);
          // Note: credentials should always be the last piece in handshake for
          // Diffie-Hellman key exchange to work
          if (clientVersion.compareTo(Version.GFE_603) >= 0) {
            setOverrides(new byte[] {dis.readByte()});
          } else {
            setClientConflation(dis.readByte());
          }
          // Hitesh
          if (this.clientVersion.compareTo(Version.GFE_65) < 0 || communicationMode.isWAN()) {
            this.credentials = readCredentials(dis, dos, sys, this.securityService);
          } else {
            this.credentials = this.readCredential(dis, dos, sys);
          }
        } catch (IOException ioe) {
          this.code = -2;
          throw ioe;
        } catch (ClassNotFoundException cnfe) {
          this.code = -3;
          throw new IOException(
              LocalizedStrings.HandShake_CLIENTPROXYMEMBERSHIPID_CLASS_COULD_NOT_BE_FOUND_WHILE_DESERIALIZING_THE_OBJECT
                  .toLocalizedString());
        }
      } finally {
        if (soTimeout != -1) {
          try {
            sock.setSoTimeout(soTimeout);
          } catch (IOException ignore) {
          }
        }
      }
    }
  }

  /**
   * Clone a HandShake to be used in creating other connections
   */
  protected Handshake(Handshake handShake) {
    this.appSecureMode = handShake.appSecureMode;
    this.clientConflation = handShake.clientConflation;
    this.clientPublicKey = null;
    this.clientReadTimeout = handShake.clientReadTimeout;
    this.clientSKAlgo = null;
    this.clientVersion = handShake.clientVersion;
    this.code = handShake.code;
    this.credentials = handShake.credentials;
    this.isRead = handShake.isRead;
    this.overrides = handShake.overrides;
    this.system = handShake.system;
    this.id = handShake.id;
    this.securityService = handShake.securityService;
    // create new one
    this._decrypt = null;
    this._encrypt = null;
  }

  public Version getClientVersion() {
    return this.clientVersion;
  }


  public void updateProxyID(InternalDistributedMember idm) {
    this.id.updateID(idm);
  }

  // used by the server side
  private void setClientConflation(byte value) {
    this.clientConflation = value;
    switch (this.clientConflation) {
      case CONFLATION_DEFAULT:
      case CONFLATION_OFF:
      case CONFLATION_ON:
        break;
      default:
        throw new IllegalArgumentException("Illegal clientConflation");
    }
  }

  protected byte[] getOverrides() {
    return overrides;
  }

  // used by the server side
  protected void setOverrides(byte[] values) {
    byte override = values[0];
    setClientConflation(((byte) (override & 0x03)));
    /*
     * override = (byte)(override >>> 2); setRemoveUnresponsiveClientOverride(((byte)(override &
     * 0x03))); override = (byte)(override >>> 2); setNotifyBySubscriptionOverride(((byte)(override
     * & 0x03)));
     */
  }

  // used by CacheClientNotifier's handshake reading code
  public static byte[] extractOverrides(byte[] values) {
    byte override = values[0];
    byte[] overrides = new byte[1];
    for (int item = 0; item < overrides.length; item++) {
      overrides[item] = (byte) (override & 0x03);
      override = (byte) (override >>> 2);
    }
    return overrides;
  }

  /**
   * This method writes what readCredential() method expects to read. (Note the use of singular
   * credential). It is similar to writeCredentials(), except that it doesn't write
   * credential-properties.
   *
   * This is only used by the {@link ServerHandshakeImpl}.
   */
  protected byte writeCredential(DataOutputStream dos, DataInputStream dis, String authInit,
      boolean isNotification, DistributedMember member, HeapDataOutputStream heapdos)
      throws IOException, GemFireSecurityException {

    if (dhSKAlgo == null || dhSKAlgo.length() == 0) {
      // Normal credentials without encryption indicator
      heapdos.writeByte(CREDENTIALS_NORMAL);
      this.appSecureMode = CREDENTIALS_NORMAL;
      // DataSerializer.writeProperties(p_credentials, heapdos);
      heapdos.flush();
      dos.write(heapdos.toByteArray());
      dos.flush();
      return -1;
    }
    byte acceptanceCode = -1;
    try {
      InternalLogWriter securityLogWriter = (InternalLogWriter) this.system.getSecurityLogWriter();
      securityLogWriter.fine("HandShake: using Diffie-Hellman key exchange with algo " + dhSKAlgo);
      boolean requireAuthentication =
          (certificateFilePath != null && certificateFilePath.length() > 0);
      if (requireAuthentication) {
        securityLogWriter
            .fine("HandShake: server authentication using digital " + "signature required");
      }
      // Credentials with encryption indicator
      heapdos.writeByte(CREDENTIALS_DHENCRYPT);
      this.appSecureMode = CREDENTIALS_DHENCRYPT;
      heapdos.writeBoolean(requireAuthentication);
      // Send the symmetric encryption algorithm name
      DataSerializer.writeString(dhSKAlgo, heapdos);
      // Send the DH public key
      byte[] keyBytes = dhPublicKey.getEncoded();
      DataSerializer.writeByteArray(keyBytes, heapdos);
      byte[] clientChallenge = null;
      if (requireAuthentication) {
        // Authentication of server should be with the client supplied
        // challenge
        clientChallenge = new byte[64];
        random.nextBytes(clientChallenge);
        DataSerializer.writeByteArray(clientChallenge, heapdos);
      }
      heapdos.flush();
      dos.write(heapdos.toByteArray());
      dos.flush();

      // Expect the alias and signature in the reply
      acceptanceCode = dis.readByte();
      if (acceptanceCode != REPLY_OK && acceptanceCode != REPLY_AUTH_NOT_REQUIRED) {
        // Ignore the useless data
        dis.readByte();
        dis.readInt();
        if (!isNotification) {
          DataSerializer.readByteArray(dis);
        }
        readMessage(dis, dos, acceptanceCode, member);
      } else if (acceptanceCode == REPLY_OK) {
        // Get the public key of the other side
        keyBytes = DataSerializer.readByteArray(dis);
        if (requireAuthentication) {
          String subject = DataSerializer.readString(dis);
          byte[] signatureBytes = DataSerializer.readByteArray(dis);
          if (!certificateMap.containsKey(subject)) {
            throw new AuthenticationFailedException(
                LocalizedStrings.HandShake_HANDSHAKE_FAILED_TO_FIND_PUBLIC_KEY_FOR_SERVER_WITH_SUBJECT_0
                    .toLocalizedString(subject));
          }

          // Check the signature with the public key
          X509Certificate cert = (X509Certificate) certificateMap.get(subject);
          Signature sig = Signature.getInstance(cert.getSigAlgName());
          sig.initVerify(cert);
          sig.update(clientChallenge);
          // Check the challenge string
          if (!sig.verify(signatureBytes)) {
            throw new AuthenticationFailedException(
                "Mismatch in client " + "challenge bytes. Malicious server?");
          }
          securityLogWriter
              .fine("HandShake: Successfully verified the " + "digital signature from server");
        }

        // Read server challenge bytes
        byte[] serverChallenge = DataSerializer.readByteArray(dis);
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFact = KeyFactory.getInstance("DH");
        // PublicKey pubKey = keyFact.generatePublic(x509KeySpec);
        this.clientPublicKey = keyFact.generatePublic(x509KeySpec);

        HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
        try {
          // Add the challenge string
          DataSerializer.writeByteArray(serverChallenge, hdos);
          // byte[] encBytes = encrypt.doFinal(hdos.toByteArray());
          byte[] encBytes =
              encryptBytes(hdos.toByteArray(), getEncryptCipher(dhSKAlgo, this.clientPublicKey));
          DataSerializer.writeByteArray(encBytes, dos);
        } finally {
          hdos.close();
        }
      }
    } catch (IOException ex) {
      throw ex;
    } catch (GemFireSecurityException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AuthenticationFailedException("HandShake failed in Diffie-Hellman key exchange",
          ex);
    }
    dos.flush();
    return acceptanceCode;
  }

  public void writeCredentials(DataOutputStream dos, DataInputStream dis, Properties p_credentials,
      boolean isNotification, DistributedMember member)
      throws IOException, GemFireSecurityException {
    HeapDataOutputStream hdos = new HeapDataOutputStream(32, Version.CURRENT);
    try {
      writeCredentials(dos, dis, p_credentials, isNotification, member, hdos);
    } finally {
      hdos.close();
    }
  }

  /**
   * This assumes that authentication is the last piece of info in handshake
   */
  public void writeCredentials(DataOutputStream dos, DataInputStream dis, Properties p_credentials,
      boolean isNotification, DistributedMember member, HeapDataOutputStream heapdos)
      throws IOException, GemFireSecurityException {

    if (p_credentials == null) {
      // No credentials indicator
      heapdos.writeByte(CREDENTIALS_NONE);
      heapdos.flush();
      dos.write(heapdos.toByteArray());
      dos.flush();
      return;
    }

    if (dhSKAlgo == null || dhSKAlgo.length() == 0) {
      // Normal credentials without encryption indicator
      heapdos.writeByte(CREDENTIALS_NORMAL);
      DataSerializer.writeProperties(p_credentials, heapdos);
      heapdos.flush();
      dos.write(heapdos.toByteArray());
      dos.flush();
      return;
    }

    try {
      InternalLogWriter securityLogWriter = (InternalLogWriter) this.system.getSecurityLogWriter();
      securityLogWriter.fine("HandShake: using Diffie-Hellman key exchange with algo " + dhSKAlgo);
      boolean requireAuthentication =
          (certificateFilePath != null && certificateFilePath.length() > 0);
      if (requireAuthentication) {
        securityLogWriter
            .fine("HandShake: server authentication using digital " + "signature required");
      }
      // Credentials with encryption indicator
      heapdos.writeByte(CREDENTIALS_DHENCRYPT);
      heapdos.writeBoolean(requireAuthentication);
      // Send the symmetric encryption algorithm name
      DataSerializer.writeString(dhSKAlgo, heapdos);
      // Send the DH public key
      byte[] keyBytes = dhPublicKey.getEncoded();
      DataSerializer.writeByteArray(keyBytes, heapdos);
      byte[] clientChallenge = null;
      if (requireAuthentication) {
        // Authentication of server should be with the client supplied
        // challenge
        clientChallenge = new byte[64];
        random.nextBytes(clientChallenge);
        DataSerializer.writeByteArray(clientChallenge, heapdos);
      }
      heapdos.flush();
      dos.write(heapdos.toByteArray());
      dos.flush();

      // Expect the alias and signature in the reply
      byte acceptanceCode = dis.readByte();
      if (acceptanceCode != REPLY_OK && acceptanceCode != REPLY_AUTH_NOT_REQUIRED) {
        // Ignore the useless data
        dis.readByte();
        dis.readInt();
        if (!isNotification) {
          DataSerializer.readByteArray(dis);
        }
        readMessage(dis, dos, acceptanceCode, member);
      } else if (acceptanceCode == REPLY_OK) {
        // Get the public key of the other side
        keyBytes = DataSerializer.readByteArray(dis);
        if (requireAuthentication) {
          String subject = DataSerializer.readString(dis);
          byte[] signatureBytes = DataSerializer.readByteArray(dis);
          if (!certificateMap.containsKey(subject)) {
            throw new AuthenticationFailedException(
                LocalizedStrings.HandShake_HANDSHAKE_FAILED_TO_FIND_PUBLIC_KEY_FOR_SERVER_WITH_SUBJECT_0
                    .toLocalizedString(subject));
          }

          // Check the signature with the public key
          X509Certificate cert = (X509Certificate) certificateMap.get(subject);
          Signature sig = Signature.getInstance(cert.getSigAlgName());
          sig.initVerify(cert);
          sig.update(clientChallenge);
          // Check the challenge string
          if (!sig.verify(signatureBytes)) {
            throw new AuthenticationFailedException(
                "Mismatch in client " + "challenge bytes. Malicious server?");
          }
          securityLogWriter
              .fine("HandShake: Successfully verified the " + "digital signature from server");
        }

        byte[] challenge = DataSerializer.readByteArray(dis);
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFact = KeyFactory.getInstance("DH");
        // PublicKey pubKey = keyFact.generatePublic(x509KeySpec);
        this.clientPublicKey = keyFact.generatePublic(x509KeySpec);



        HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
        try {
          DataSerializer.writeProperties(p_credentials, hdos);
          // Also add the challenge string
          DataSerializer.writeByteArray(challenge, hdos);

          // byte[] encBytes = encrypt.doFinal(hdos.toByteArray());
          byte[] encBytes =
              encryptBytes(hdos.toByteArray(), getEncryptCipher(dhSKAlgo, this.clientPublicKey));
          DataSerializer.writeByteArray(encBytes, dos);
        } finally {
          hdos.close();
        }
      }
    } catch (IOException ex) {
      throw ex;
    } catch (GemFireSecurityException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AuthenticationFailedException("HandShake failed in Diffie-Hellman key exchange",
          ex);
    }
    dos.flush();
  }

  public byte[] encryptBytes(byte[] data) throws Exception {
    if (this.appSecureMode == CREDENTIALS_DHENCRYPT) {
      String algo = null;
      if (this.clientSKAlgo != null) {
        algo = this.clientSKAlgo;
      } else {
        algo = dhSKAlgo;
      }
      return encryptBytes(data, getEncryptCipher(algo, this.clientPublicKey));
    } else {
      return data;
    }
  }

  public static byte[] encryptBytes(byte[] data, Cipher encrypt) throws Exception {


    try {
      byte[] encBytes = encrypt.doFinal(data);
      return encBytes;
    } catch (Exception ex) {
      throw ex;
    }
  }

  private Cipher _encrypt;

  private Cipher getEncryptCipher(String dhSKAlgo, PublicKey publicKey) throws Exception {
    try {
      if (_encrypt == null) {
        KeyAgreement ka = KeyAgreement.getInstance("DH");
        ka.init(dhPrivateKey);
        ka.doPhase(publicKey, true);

        Cipher encrypt;

        int keysize = getKeySize(dhSKAlgo);
        int blocksize = getBlockSize(dhSKAlgo);

        if (keysize == -1 || blocksize == -1) {
          SecretKey sKey = ka.generateSecret(dhSKAlgo);
          encrypt = Cipher.getInstance(dhSKAlgo);
          encrypt.init(Cipher.ENCRYPT_MODE, sKey);
        } else {
          String dhAlgoStr = getDhAlgoStr(dhSKAlgo);

          byte[] sKeyBytes = ka.generateSecret();
          SecretKeySpec sks = new SecretKeySpec(sKeyBytes, 0, keysize, dhAlgoStr);
          IvParameterSpec ivps = new IvParameterSpec(sKeyBytes, keysize, blocksize);

          encrypt = Cipher.getInstance(dhAlgoStr + "/CBC/PKCS5Padding");
          encrypt.init(Cipher.ENCRYPT_MODE, sks, ivps);
        }
        _encrypt = encrypt;
      }
    } catch (Exception ex) {
      throw ex;
    }
    return _encrypt;
  }

  /**
   * Throws AuthenticationRequiredException if authentication is required but there are no
   * credentials.
   */
  static void throwIfMissingRequiredCredentials(boolean requireAuthentication,
      boolean hasCredentials) {
    if (requireAuthentication && !hasCredentials) {
      throw new AuthenticationRequiredException(
          LocalizedStrings.HandShake_NO_SECURITY_CREDENTIALS_ARE_PROVIDED.toLocalizedString());
    }
  }

  // This assumes that authentication is the last piece of info in handshake
  public Properties readCredential(DataInputStream dis, DataOutputStream dos,
      DistributedSystem system) throws GemFireSecurityException, IOException {

    Properties credentials = null;
    boolean requireAuthentication = securityService.isClientSecurityRequired();
    try {
      byte secureMode = dis.readByte();
      throwIfMissingRequiredCredentials(requireAuthentication, secureMode != CREDENTIALS_NONE);
      if (secureMode == CREDENTIALS_NORMAL) {
        this.appSecureMode = CREDENTIALS_NORMAL;
        /*
         * if (requireAuthentication) { credentials = DataSerializer.readProperties(dis); } else {
         * DataSerializer.readProperties(dis); // ignore the credentials }
         */
      } else if (secureMode == CREDENTIALS_DHENCRYPT) {
        this.appSecureMode = CREDENTIALS_DHENCRYPT;
        boolean sendAuthentication = dis.readBoolean();
        InternalLogWriter securityLogWriter = (InternalLogWriter) system.getSecurityLogWriter();
        // Get the symmetric encryption algorithm to be used
        // String skAlgo = DataSerializer.readString(dis);
        this.clientSKAlgo = DataSerializer.readString(dis);
        // Get the public key of the other side
        byte[] keyBytes = DataSerializer.readByteArray(dis);
        byte[] challenge = null;
        // PublicKey pubKey = null;
        if (requireAuthentication) {
          // Generate PublicKey from encoded form
          X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(keyBytes);
          KeyFactory keyFact = KeyFactory.getInstance("DH");
          this.clientPublicKey = keyFact.generatePublic(x509KeySpec);

          // Send the public key to other side
          keyBytes = dhPublicKey.getEncoded();
          challenge = new byte[64];
          random.nextBytes(challenge);

          // If the server has to also authenticate itself then
          // sign the challenge from client.
          if (sendAuthentication) {
            // Get the challenge string from client
            byte[] clientChallenge = DataSerializer.readByteArray(dis);
            if (privateKeyEncrypt == null) {
              throw new AuthenticationFailedException(
                  LocalizedStrings.HandShake_SERVER_PRIVATE_KEY_NOT_AVAILABLE_FOR_CREATING_SIGNATURE
                      .toLocalizedString());
            }
            // Sign the challenge from client and send it to the client
            Signature sig = Signature.getInstance(privateKeySignAlgo);
            sig.initSign(privateKeyEncrypt);
            sig.update(clientChallenge);
            byte[] signedBytes = sig.sign();
            dos.writeByte(REPLY_OK);
            DataSerializer.writeByteArray(keyBytes, dos);
            // DataSerializer.writeString(privateKeyAlias, dos);
            DataSerializer.writeString(privateKeySubject, dos);
            DataSerializer.writeByteArray(signedBytes, dos);
            securityLogWriter.fine("HandShake: sent the signed client challenge");
          } else {
            // These two lines should not be moved before the if{} statement in
            // a common block for both if...then...else parts. This is to handle
            // the case when an AuthenticationFailedException is thrown by the
            // if...then part when sending the signature.
            dos.writeByte(REPLY_OK);
            DataSerializer.writeByteArray(keyBytes, dos);
          }
          // Now send the server challenge
          DataSerializer.writeByteArray(challenge, dos);
          securityLogWriter.fine("HandShake: sent the public key and challenge");
          dos.flush();

          // Read and decrypt the credentials
          byte[] encBytes = DataSerializer.readByteArray(dis);
          Cipher c = getDecryptCipher(this.clientSKAlgo, this.clientPublicKey);
          byte[] credentialBytes = decryptBytes(encBytes, c);
          ByteArrayInputStream bis = new ByteArrayInputStream(credentialBytes);
          DataInputStream dinp = new DataInputStream(bis);
          // credentials = DataSerializer.readProperties(dinp);//Hitesh: we don't send in handshake
          // now
          byte[] challengeRes = DataSerializer.readByteArray(dinp);
          // Check the challenge string
          if (!Arrays.equals(challenge, challengeRes)) {
            throw new AuthenticationFailedException(
                LocalizedStrings.HandShake_MISMATCH_IN_CHALLENGE_BYTES_MALICIOUS_CLIENT
                    .toLocalizedString());
          }
          dinp.close();
        } else {
          if (sendAuthentication) {
            // Read and ignore the client challenge
            DataSerializer.readByteArray(dis);
          }
          dos.writeByte(REPLY_AUTH_NOT_REQUIRED);
          dos.flush();
        }
      }
    } catch (IOException ex) {
      throw ex;
    } catch (GemFireSecurityException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AuthenticationFailedException(
          LocalizedStrings.HandShake_FAILURE_IN_READING_CREDENTIALS.toLocalizedString(), ex);
    }
    return credentials;
  }

  public byte[] decryptBytes(byte[] data) throws Exception {
    if (this.appSecureMode == CREDENTIALS_DHENCRYPT) {
      String algo = null;
      if (this.clientSKAlgo != null) {
        algo = this.clientSKAlgo;
      } else {
        algo = dhSKAlgo;
      }
      Cipher c = getDecryptCipher(algo, this.clientPublicKey);
      return decryptBytes(data, c);
    } else {
      return data;
    }
  }



  public static byte[] decryptBytes(byte[] data, Cipher decrypt) throws Exception {
    try {
      byte[] decrptBytes = decrypt.doFinal(data);
      return decrptBytes;
    } catch (Exception ex) {
      throw ex;
    }
  }

  private Cipher _decrypt = null;

  private Cipher getDecryptCipher(String dhSKAlgo, PublicKey publicKey) throws Exception {
    if (_decrypt == null) {
      try {
        KeyAgreement ka = KeyAgreement.getInstance("DH");
        ka.init(dhPrivateKey);
        ka.doPhase(publicKey, true);

        Cipher decrypt;

        int keysize = getKeySize(dhSKAlgo);
        int blocksize = getBlockSize(dhSKAlgo);

        if (keysize == -1 || blocksize == -1) {
          SecretKey sKey = ka.generateSecret(dhSKAlgo);
          decrypt = Cipher.getInstance(dhSKAlgo);
          decrypt.init(Cipher.DECRYPT_MODE, sKey);
        } else {
          String algoStr = getDhAlgoStr(dhSKAlgo);

          byte[] sKeyBytes = ka.generateSecret();
          SecretKeySpec sks = new SecretKeySpec(sKeyBytes, 0, keysize, algoStr);
          IvParameterSpec ivps = new IvParameterSpec(sKeyBytes, keysize, blocksize);

          decrypt = Cipher.getInstance(algoStr + "/CBC/PKCS5Padding");
          decrypt.init(Cipher.DECRYPT_MODE, sks, ivps);
        }

        _decrypt = decrypt;
      } catch (Exception ex) {
        throw ex;
      }
    }
    return _decrypt;
  }

  /**
   * Populate the available server public keys into a local static HashMap. This method is not
   * thread safe.
   */
  public static void initCertsMap(Properties props) throws Exception {

    certificateMap = new HashMap();
    certificateFilePath = props.getProperty(PUBLIC_KEY_FILE_PROP);
    if (certificateFilePath != null && certificateFilePath.length() > 0) {
      KeyStore ks = KeyStore.getInstance("JKS");
      String keyStorePass = props.getProperty(PUBLIC_KEY_PASSWD_PROP);
      char[] passPhrase = (keyStorePass != null ? keyStorePass.toCharArray() : null);
      FileInputStream keystorefile = new FileInputStream(certificateFilePath);
      try {
        ks.load(keystorefile, passPhrase);
      } finally {
        keystorefile.close();
      }
      Enumeration aliases = ks.aliases();
      while (aliases.hasMoreElements()) {
        String alias = (String) aliases.nextElement();
        Certificate cert = ks.getCertificate(alias);
        if (cert instanceof X509Certificate) {
          String subject = ((X509Certificate) cert).getSubjectDN().getName();
          certificateMap.put(subject, cert);
        }
      }
    }
  }

  /**
   * Load the private key of the server. This method is not thread safe.
   */
  public static void initPrivateKey(Properties props) throws Exception {

    String privateKeyFilePath = props.getProperty(PRIVATE_KEY_FILE_PROP);
    privateKeyAlias = "";
    privateKeyEncrypt = null;
    if (privateKeyFilePath != null && privateKeyFilePath.length() > 0) {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      privateKeyAlias = props.getProperty(PRIVATE_KEY_ALIAS_PROP);
      if (privateKeyAlias == null) {
        privateKeyAlias = "";
      }
      String keyStorePass = props.getProperty(PRIVATE_KEY_PASSWD_PROP);
      char[] passPhrase = (keyStorePass != null ? keyStorePass.toCharArray() : null);
      FileInputStream privateKeyFile = new FileInputStream(privateKeyFilePath);
      try {
        ks.load(privateKeyFile, passPhrase);
      } finally {
        privateKeyFile.close();
      }
      Key key = ks.getKey(privateKeyAlias, passPhrase);
      Certificate keyCert = ks.getCertificate(privateKeyAlias);
      if (key instanceof PrivateKey && keyCert instanceof X509Certificate) {
        privateKeyEncrypt = (PrivateKey) key;
        privateKeySignAlgo = ((X509Certificate) keyCert).getSigAlgName();
        privateKeySubject = ((X509Certificate) keyCert).getSubjectDN().getName();
      }
    }
  }

  /**
   * Initialize the Diffie-Hellman keys. This method is not thread safe
   */
  public static void initDHKeys(DistributionConfig config) throws Exception {

    dhSKAlgo = config.getSecurityClientDHAlgo();
    dhPrivateKey = null;
    dhPublicKey = null;
    // Initialize the keys when either the host is a client that has
    // non-blank setting for DH symmetric algo, or this is a server
    // that has authenticator defined.
    if ((dhSKAlgo != null
        && dhSKAlgo.length() > 0) /* || securityService.isClientSecurityRequired() */) {
      KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
      DHParameterSpec dhSpec = new DHParameterSpec(dhP, dhG, dhL);
      keyGen.initialize(dhSpec);
      KeyPair keypair = keyGen.generateKeyPair();

      // Get the generated public and private keys
      dhPrivateKey = keypair.getPrivate();
      dhPublicKey = keypair.getPublic();

      random = new SecureRandom();
      // Force the random generator to seed itself.
      byte[] someBytes = new byte[48];
      random.nextBytes(someBytes);
    }
  }

  public void accept(OutputStream out, InputStream in, byte epType, int qSize,
      CommunicationMode communicationMode, Principal principal) throws IOException {
    DataOutputStream dos = new DataOutputStream(out);
    DataInputStream dis;
    if (clientVersion.compareTo(Version.CURRENT) < 0) {
      dis = new VersionedDataInputStream(in, clientVersion);
      dos = new VersionedDataOutputStream(dos, clientVersion);
    } else {
      dis = new DataInputStream(in);
    }
    // Write ok reply
    if (communicationMode.isWAN() && principal != null) {
      dos.writeByte(REPLY_WAN_CREDENTIALS);
    } else {
      dos.writeByte(REPLY_OK);// byte 59
    }


    // additional byte of wan site needs to send for Gateway BC
    if (communicationMode.isWAN()) {
      Version.writeOrdinal(dos, ServerHandshakeProcessor.currentServerVersion.ordinal(), true);
    }

    dos.writeByte(epType);
    dos.writeInt(qSize);

    // Write the server's member
    DistributedMember member = this.system.getDistributedMember();
    ServerHandshakeProcessor.writeServerMember(member, dos);

    // Write no message
    dos.writeUTF("");

    // Write delta-propagation property value if this is not WAN.
    if (!communicationMode.isWAN() && this.clientVersion.compareTo(Version.GFE_61) >= 0) {
      dos.writeBoolean(((InternalDistributedSystem) this.system).getConfig().getDeltaPropagation());
    }

    // Neeraj: Now if the communication mode is GATEWAY_TO_GATEWAY
    // and principal not equal to null then send the credentials also
    if (communicationMode.isWAN() && principal != null) {
      sendCredentialsForWan(dos, dis);
    }

    // Write the distributed system id if this is a 6.6 or greater client
    // on the remote side of the gateway
    if (communicationMode.isWAN() && this.clientVersion.compareTo(Version.GFE_66) >= 0
        && ServerHandshakeProcessor.currentServerVersion.compareTo(Version.GFE_66) >= 0) {
      dos.writeByte(((InternalDistributedSystem) this.system).getDistributionManager()
          .getDistributedSystemId());
    }

    if ((communicationMode.isWAN()) && this.clientVersion.compareTo(Version.GFE_80) >= 0
        && ServerHandshakeProcessor.currentServerVersion.compareTo(Version.GFE_80) >= 0) {
      int pdxSize = PeerTypeRegistration.getPdxRegistrySize();
      dos.writeInt(pdxSize);
    }

    // Flush
    dos.flush();
  }

  /**
   * Handshake implements the Diffie-Hellman encryption algorithms
   *
   * @return
   */
  public Encryptor getEncryptor() {
    return this;
  }

  protected DistributedMember readServerMember(DataInputStream p_dis) throws IOException {

    byte[] memberBytes = DataSerializer.readByteArray(p_dis);
    ByteArrayInputStream bais = new ByteArrayInputStream(memberBytes);
    DataInputStream dis = new DataInputStream(bais);
    Version v = InternalDataSerializer.getVersionForDataStreamOrNull(p_dis);
    if (v != null) {
      dis = new VersionedDataInputStream(dis, v);
    }
    try {
      return DataSerializer.readObject(dis);
    } catch (EOFException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalGemFireException(
          LocalizedStrings.HandShake_UNABLE_TO_DESERIALIZE_MEMBER.toLocalizedString(), e);
    }
  }

  protected void readMessage(DataInputStream dis, DataOutputStream dos, byte acceptanceCode,
      DistributedMember member) throws IOException, AuthenticationRequiredException,
      AuthenticationFailedException, ServerRefusedConnectionException {

    String message = dis.readUTF();
    if (message.length() == 0 && acceptanceCode != REPLY_WAN_CREDENTIALS) {
      return; // success
    }

    switch (acceptanceCode) {
      case REPLY_EXCEPTION_AUTHENTICATION_REQUIRED:
        throw new AuthenticationRequiredException(message);
      case REPLY_EXCEPTION_AUTHENTICATION_FAILED:
        throw new AuthenticationFailedException(message);
      case REPLY_EXCEPTION_DUPLICATE_DURABLE_CLIENT:
        throw new ServerRefusedConnectionException(member, message);
      case REPLY_WAN_CREDENTIALS:
        checkIfAuthenticWanSite(dis, dos, member);
        break;
      default:
        throw new ServerRefusedConnectionException(member, message);
    }
  }

  public byte getCode() {
    return this.code;
  }

  public boolean isRead() {
    return this.isRead;
  }

  public boolean isOK() {
    return getCode() == REPLY_OK;
  }

  public void setClientReadTimeout(int clientReadTimeout) {
    this.clientReadTimeout = clientReadTimeout;
  }

  public int getClientReadTimeout() {
    return this.clientReadTimeout;
  }

  /**
   * Indicates whether some other object is "equal to" this one.
   *
   * @param other the reference object with which to compare.
   * @return true if this object is the same as the obj argument; false otherwise.
   */
  @Override
  public boolean equals(Object other) {
    if (other == this)
      return true;
    // if (other == null) return false;
    if (!(other instanceof Handshake))
      return false;
    final Handshake that = (Handshake) other;

    if (this.id.isSameDSMember(that.id) && this.code == that.code) {
      return true;
    } else {
      return false;
    }
  }


  /**
   * Returns a hash code for the object. This method is supported for the benefit of hashtables such
   * as those provided by java.util.Hashtable.
   *
   * @return the integer 0 if description is null; otherwise a unique integer.
   */
  @Override
  public int hashCode() {
    int result = 17;
    final int mult = 37;

    /*
     * if (this.identity != null && this.identity.length > 0) { for (int i = 0; i <
     * this.identity.length; i++) { result = mult * result + (int) this.identity[i]; } }
     */
    result = this.id.hashCode();
    result = mult * result + this.code;

    return result;
  }

  @Override
  public String toString() {
    StringBuffer buf = new StringBuffer().append("HandShake@").append(System.identityHashCode(this))
        .append(" code: ").append(this.code);
    if (this.id != null) {
      buf.append(" identity: ");
      /*
       * for(int i=0; i<this.identity.length; ++i) { buf.append(this.identity[i]); }
       */
      buf.append(this.id.toString());
    }
    return buf.toString();
  }

  public ClientProxyMembershipID getMembership() {
    return this.id;
  }

  public static Properties getCredentials(String authInitMethod, Properties securityProperties,
      DistributedMember server, boolean isPeer, InternalLogWriter logWriter,
      InternalLogWriter securityLogWriter) throws AuthenticationRequiredException {

    Properties credentials = null;
    // if no authInit, Try to extract the credentials directly from securityProps
    if (StringUtils.isBlank(authInitMethod)) {
      return Credentials.getCredentials(securityProperties);
    }

    // if authInit exists
    try {
      AuthInitialize auth =
          CallbackInstantiator.getObjectOfType(authInitMethod, AuthInitialize.class);
      auth.init(logWriter, securityLogWriter);
      try {
        credentials = auth.getCredentials(securityProperties, server, isPeer);
      } finally {
        auth.close();
      }
    } catch (GemFireSecurityException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AuthenticationRequiredException(
          LocalizedStrings.HandShake_FAILED_TO_ACQUIRE_AUTHINITIALIZE_METHOD_0
              .toLocalizedString(authInitMethod),
          ex);
    }
    return credentials;
  }

  protected Properties getCredentials(DistributedMember member) {

    String authInitMethod = this.system.getProperties().getProperty(SECURITY_CLIENT_AUTH_INIT);
    return getCredentials(authInitMethod, this.system.getSecurityProperties(), member, false,
        (InternalLogWriter) this.system.getLogWriter(),
        (InternalLogWriter) this.system.getSecurityLogWriter());
  }

  // This assumes that authentication is the last piece of info in handshake
  public static Properties readCredentials(DataInputStream dis, DataOutputStream dos,
      DistributedSystem system, SecurityService securityService)
      throws GemFireSecurityException, IOException {

    boolean requireAuthentication = securityService.isClientSecurityRequired();
    Properties credentials = null;
    try {
      byte secureMode = dis.readByte();
      throwIfMissingRequiredCredentials(requireAuthentication, secureMode != CREDENTIALS_NONE);
      if (secureMode == CREDENTIALS_NORMAL) {
        if (requireAuthentication) {
          credentials = DataSerializer.readProperties(dis);
        } else {
          DataSerializer.readProperties(dis); // ignore the credentials
        }
      } else if (secureMode == CREDENTIALS_DHENCRYPT) {
        boolean sendAuthentication = dis.readBoolean();
        InternalLogWriter securityLogWriter = (InternalLogWriter) system.getSecurityLogWriter();
        // Get the symmetric encryption algorithm to be used
        String skAlgo = DataSerializer.readString(dis);
        // Get the public key of the other side
        byte[] keyBytes = DataSerializer.readByteArray(dis);
        byte[] challenge = null;
        PublicKey pubKey = null;
        if (requireAuthentication) {
          // Generate PublicKey from encoded form
          X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(keyBytes);
          KeyFactory keyFact = KeyFactory.getInstance("DH");
          pubKey = keyFact.generatePublic(x509KeySpec);

          // Send the public key to other side
          keyBytes = dhPublicKey.getEncoded();
          challenge = new byte[64];
          random.nextBytes(challenge);

          // If the server has to also authenticate itself then
          // sign the challenge from client.
          if (sendAuthentication) {
            // Get the challenge string from client
            byte[] clientChallenge = DataSerializer.readByteArray(dis);
            if (privateKeyEncrypt == null) {
              throw new AuthenticationFailedException(
                  LocalizedStrings.HandShake_SERVER_PRIVATE_KEY_NOT_AVAILABLE_FOR_CREATING_SIGNATURE
                      .toLocalizedString());
            }
            // Sign the challenge from client and send it to the client
            Signature sig = Signature.getInstance(privateKeySignAlgo);
            sig.initSign(privateKeyEncrypt);
            sig.update(clientChallenge);
            byte[] signedBytes = sig.sign();
            dos.writeByte(REPLY_OK);
            DataSerializer.writeByteArray(keyBytes, dos);
            // DataSerializer.writeString(privateKeyAlias, dos);
            DataSerializer.writeString(privateKeySubject, dos);
            DataSerializer.writeByteArray(signedBytes, dos);
            securityLogWriter.fine("HandShake: sent the signed client challenge");
          } else {
            // These two lines should not be moved before the if{} statement in
            // a common block for both if...then...else parts. This is to handle
            // the case when an AuthenticationFailedException is thrown by the
            // if...then part when sending the signature.
            dos.writeByte(REPLY_OK);
            DataSerializer.writeByteArray(keyBytes, dos);
          }
          // Now send the server challenge
          DataSerializer.writeByteArray(challenge, dos);
          securityLogWriter.fine("HandShake: sent the public key and challenge");
          dos.flush();

          // Read and decrypt the credentials
          byte[] encBytes = DataSerializer.readByteArray(dis);
          KeyAgreement ka = KeyAgreement.getInstance("DH");
          ka.init(dhPrivateKey);
          ka.doPhase(pubKey, true);

          Cipher decrypt;

          int keysize = getKeySize(skAlgo);
          int blocksize = getBlockSize(skAlgo);

          if (keysize == -1 || blocksize == -1) {
            SecretKey sKey = ka.generateSecret(skAlgo);
            decrypt = Cipher.getInstance(skAlgo);
            decrypt.init(Cipher.DECRYPT_MODE, sKey);
          } else {
            String algoStr = getDhAlgoStr(skAlgo);

            byte[] sKeyBytes = ka.generateSecret();
            SecretKeySpec sks = new SecretKeySpec(sKeyBytes, 0, keysize, algoStr);
            IvParameterSpec ivps = new IvParameterSpec(sKeyBytes, keysize, blocksize);

            decrypt = Cipher.getInstance(algoStr + "/CBC/PKCS5Padding");
            decrypt.init(Cipher.DECRYPT_MODE, sks, ivps);
          }

          byte[] credentialBytes = decrypt.doFinal(encBytes);
          ByteArrayInputStream bis = new ByteArrayInputStream(credentialBytes);
          DataInputStream dinp = new DataInputStream(bis);
          credentials = DataSerializer.readProperties(dinp);
          byte[] challengeRes = DataSerializer.readByteArray(dinp);
          // Check the challenge string
          if (!Arrays.equals(challenge, challengeRes)) {
            throw new AuthenticationFailedException(
                LocalizedStrings.HandShake_MISMATCH_IN_CHALLENGE_BYTES_MALICIOUS_CLIENT
                    .toLocalizedString());
          }
          dinp.close();
        } else {
          if (sendAuthentication) {
            // Read and ignore the client challenge
            DataSerializer.readByteArray(dis);
          }
          dos.writeByte(REPLY_AUTH_NOT_REQUIRED);
          dos.flush();
        }
      } else if (secureMode == SECURITY_MULTIUSER_NOTIFICATIONCHANNEL) {
        // hitesh there will be no credential CCP will get credential(Principal) using
        // ServerConnection..
        logger.debug("readCredential where multiuser mode creating callback connection");
      }
    } catch (IOException ex) {
      throw ex;
    } catch (GemFireSecurityException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AuthenticationFailedException(
          LocalizedStrings.HandShake_FAILURE_IN_READING_CREDENTIALS.toLocalizedString(), ex);
    }
    return credentials;
  }

  /**
   * this could return either a Subject or a Principal depending on if it's integrated security or
   * not
   */
  public static Object verifyCredentials(String authenticatorMethod, Properties credentials,
      Properties securityProperties, InternalLogWriter logWriter,
      InternalLogWriter securityLogWriter, DistributedMember member,
      SecurityService securityService)
      throws AuthenticationRequiredException, AuthenticationFailedException {

    if (!AcceptorImpl.isAuthenticationRequired()) {
      return null;
    }

    Authenticator auth = null;
    try {
      if (securityService.isIntegratedSecurity()) {
        return securityService.login(credentials);
      } else {
        Method instanceGetter = ClassLoadUtil.methodFromName(authenticatorMethod);
        auth = (Authenticator) instanceGetter.invoke(null, (Object[]) null);
        auth.init(securityProperties, logWriter, securityLogWriter);
        return auth.authenticate(credentials, member);
      }
    } catch (AuthenticationFailedException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AuthenticationFailedException(ex.getMessage(), ex);
    } finally {
      if (auth != null) {
        auth.close();
      }
    }
  }

  public Object verifyCredentials()
      throws AuthenticationRequiredException, AuthenticationFailedException {

    String methodName = this.system.getProperties().getProperty(SECURITY_CLIENT_AUTHENTICATOR);
    return verifyCredentials(methodName, this.credentials, this.system.getSecurityProperties(),
        (InternalLogWriter) this.system.getLogWriter(),
        (InternalLogWriter) this.system.getSecurityLogWriter(), this.id.getDistributedMember(),
        this.securityService);
  }

  public void sendCredentialsForWan(OutputStream out, InputStream in) {

    try {
      Properties wanCredentials = getCredentials(this.id.getDistributedMember());
      DataOutputStream dos = new DataOutputStream(out);
      DataInputStream dis = new DataInputStream(in);
      writeCredentials(dos, dis, wanCredentials, false, this.system.getDistributedMember());
    }
    // The exception while getting the credentials is just logged as severe
    catch (Exception e) {
      this.system.getSecurityLogWriter().convertToLogWriterI18n().severe(
          LocalizedStrings.HandShake_AN_EXCEPTION_WAS_THROWN_WHILE_SENDING_WAN_CREDENTIALS_0,
          e.getLocalizedMessage());
    }
  }

  private void checkIfAuthenticWanSite(DataInputStream dis, DataOutputStream dos,
      DistributedMember member) throws GemFireSecurityException, IOException {

    if (this.credentials == null) {
      return;
    }
    String authenticator = this.system.getProperties().getProperty(SECURITY_CLIENT_AUTHENTICATOR);
    Properties peerWanProps = readCredentials(dis, dos, this.system, this.securityService);
    verifyCredentials(authenticator, peerWanProps, this.system.getSecurityProperties(),
        (InternalLogWriter) this.system.getLogWriter(),
        (InternalLogWriter) this.system.getSecurityLogWriter(), member, this.securityService);
  }

  private static int getKeySize(String skAlgo) {
    // skAlgo contain both algo and key size info
    int colIdx = skAlgo.indexOf(':');
    String algoStr;
    int algoKeySize = 0;
    if (colIdx >= 0) {
      algoStr = skAlgo.substring(0, colIdx);
      algoKeySize = Integer.parseInt(skAlgo.substring(colIdx + 1));
    } else {
      algoStr = skAlgo;
    }
    int keysize = -1;
    if (algoStr.equalsIgnoreCase("DESede")) {
      keysize = 24;
    } else if (algoStr.equalsIgnoreCase("Blowfish")) {
      keysize = algoKeySize > 128 ? algoKeySize / 8 : 16;
    } else if (algoStr.equalsIgnoreCase("AES")) {
      keysize = (algoKeySize != 192 && algoKeySize != 256) ? 16 : algoKeySize / 8;
    }
    return keysize;
  }

  private static String getDhAlgoStr(String skAlgo) {
    int colIdx = skAlgo.indexOf(':');
    String algoStr;
    if (colIdx >= 0) {
      algoStr = skAlgo.substring(0, colIdx);
    } else {
      algoStr = skAlgo;
    }
    return algoStr;
  }

  private static int getBlockSize(String skAlgo) {
    int blocksize = -1;
    String algoStr = getDhAlgoStr(skAlgo);
    if (algoStr.equalsIgnoreCase("DESede")) {
      blocksize = 8;
    } else if (algoStr.equalsIgnoreCase("Blowfish")) {
      blocksize = 8;
    } else if (algoStr.equalsIgnoreCase("AES")) {
      blocksize = 16;
    }
    return blocksize;
  }

  public Version getVersion() {
    return this.clientVersion;
  }

  public boolean hasCredentials() {
    return this.credentials != null;
  }
}