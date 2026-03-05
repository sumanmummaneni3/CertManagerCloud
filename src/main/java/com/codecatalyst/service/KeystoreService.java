package com.codecatalyst.service;

import com.codecatalyst.exception.KeystoreAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.UUID;

/**
 * Manages per-organisation Java Keystores (JKS format).
 *
 * Security model:
 *   - Each organisation gets its own keystore file, named by org UUID.
 *   - The keystore password is NEVER stored on the server.
 *   - The caller must supply the password on every operation that opens the keystore.
 *   - On org creation, the caller supplies the desired password and the keystore
 *     is initialised with it. Only the file PATH is persisted in the database.
 */
@Service
public class KeystoreService {

    private static final Logger logger = LoggerFactory.getLogger(KeystoreService.class);
    private static final String KEYSTORE_TYPE = "JKS";

    @Value("${certmonitor.keystore.base-dir:./keystores}")
    private String keystoreBaseDir;

    // ── Keystore lifecycle ────────────────────────────────────────────────────

    /**
     * Creates a brand-new, empty keystore for a new organisation.
     * The file is written to disk immediately so the path can be persisted.
     *
     * @param orgId    the organisation UUID (used as the filename)
     * @param password the keystore password chosen by the user — never stored
     * @return the absolute path of the created keystore file
     */
    public String createKeystoreForOrg(UUID orgId, char[] password) {
        try {
            Path dir = Paths.get(keystoreBaseDir);
            Files.createDirectories(dir);

            String filename = "org-" + orgId.toString() + ".jks";
            Path keystorePath = dir.resolve(filename);

            KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
            ks.load(null, password);   // initialise empty keystore

            try (FileOutputStream fos = new FileOutputStream(keystorePath.toFile())) {
                ks.store(fos, password);
            }

            // Clear password from memory immediately after use
            java.util.Arrays.fill(password, '\0');

            logger.info("Created keystore for org {} at {}", orgId, keystorePath.toAbsolutePath());
            return keystorePath.toAbsolutePath().toString();

        } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
            throw new KeystoreAccessException("Failed to create keystore for org " + orgId, e);
        }
    }

    /**
     * Stores an X.509 certificate into an existing org keystore.
     * The certificate is stored under an alias derived from the target host and port.
     *
     * @param keystorePath absolute path to the org's keystore file
     * @param password     keystore password supplied by the caller — never stored
     * @param alias        alias to store the certificate under (e.g. "example.com:443")
     * @param certificate  the X.509 certificate to store
     */
    public void storeCertificate(String keystorePath, char[] password,
                                 String alias, X509Certificate certificate) {
        try {
            KeyStore ks = loadKeystore(keystorePath, password);

            ks.setCertificateEntry(alias, certificate);

            try (FileOutputStream fos = new FileOutputStream(keystorePath)) {
                ks.store(fos, password);
            }

            java.util.Arrays.fill(password, '\0');
            logger.info("Stored certificate '{}' in keystore {}", alias, keystorePath);

        } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
            throw new KeystoreAccessException("Failed to store certificate '" + alias + "'", e);
        }
    }

    /**
     * Retrieves a certificate from the keystore by alias.
     *
     * @param keystorePath absolute path to the org's keystore file
     * @param password     keystore password supplied by the caller
     * @param alias        alias of the certificate to retrieve
     * @return the X.509 certificate, or null if the alias does not exist
     */
    public X509Certificate getCertificate(String keystorePath, char[] password, String alias) {
        try {
            KeyStore ks = loadKeystore(keystorePath, password);
            java.util.Arrays.fill(password, '\0');
            return (X509Certificate) ks.getCertificate(alias);

        } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
            throw new KeystoreAccessException("Failed to retrieve certificate '" + alias + "'", e);
        }
    }

    /**
     * Checks whether the keystore file exists on disk for a given path.
     */
    public boolean keystoreExists(String keystorePath) {
        return keystorePath != null && Files.exists(Paths.get(keystorePath));
    }

    /**
     * Validates that a supplied password can actually open the keystore.
     * Use this to verify user-supplied passwords before attempting operations.
     *
     * @return true if the password is correct, false otherwise
     */
    public boolean verifyPassword(String keystorePath, char[] password) {
        try {
            loadKeystore(keystorePath, password);
            java.util.Arrays.fill(password, '\0');
            return true;
        } catch (KeystoreAccessException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private KeyStore loadKeystore(String keystorePath, char[] password)
            throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {

        if (!Files.exists(Paths.get(keystorePath))) {
            throw new KeystoreAccessException("Keystore file not found: " + keystorePath);
        }

        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, password);
        }
        return ks;
    }

    /**
     * Builds a consistent alias string from a host and port.
     * e.g. "example.com:443"
     */
    public static String buildAlias(String host, int port) {
        return host.toLowerCase() + ":" + port;
    }
}
