/*
 * Copyright (c) 2026 CodeCatalyst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codecatalyst.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Fetches the TLS certificate from a remote host:port.
 * Uses a trust-all TrustManager so self-signed and expired certs are accepted
 * (we are inspecting, not validating).
 */
public class FetchCertificates {

    private static final Logger logger = LoggerFactory.getLogger(FetchCertificates.class);

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
    };

    private final String host;
    private final int port;

    public FetchCertificates(String host) {
        this.host = host;
        this.port = 443;
    }

    public FetchCertificates(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Connects to the target and returns the leaf X.509 certificate.
     *
     * @return the server's leaf certificate, or null if none was presented
     * @throws CertificateException if the connection or handshake fails
     */
    public X509Certificate fetchCertMetadata() throws CertificateException {
        logger.info("Fetching certificate from {}:{}", host, port);
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, TRUST_ALL, new java.security.SecureRandom());
            SSLSocketFactory factory = sc.getSocketFactory();

            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 1500);

            SSLSocket sslSocket = (SSLSocket) factory.createSocket(socket, host, port, true);
            sslSocket.startHandshake();

            java.security.cert.Certificate[] serverCerts = sslSocket.getSession().getPeerCertificates();
            sslSocket.close();

            return (serverCerts.length > 0) ? (X509Certificate) serverCerts[0] : null;
        } catch (KeyManagementException | IOException | NoSuchAlgorithmException e) {
            logger.error("Failed to fetch certificate from {}:{} — {}", host, port, e.getMessage());
            throw new CertificateException("Error fetching certificate from " + host + ":" + port, e);
        }
    }
}
