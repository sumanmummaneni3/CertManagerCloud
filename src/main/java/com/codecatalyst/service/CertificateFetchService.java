package com.codecatalyst.service;

import com.codecatalyst.entity.CertificateRecord;
import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Target;
import com.codecatalyst.exception.KeystoreAccessException;
import com.codecatalyst.net.FetchCertificates;
import com.codecatalyst.repository.CertificateRecordRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Orchestrates the full certificate fetch workflow:
 *  1. Connect to the target and retrieve the X.509 certificate
 *  2. Store the certificate in the org's keystore (password required from caller)
 *  3. Parse certificate metadata and persist a CertificateRecord to the database
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CertificateFetchService {

    private static final Logger logger = LoggerFactory.getLogger(CertificateFetchService.class);

    private final KeystoreService keystoreService;
    private final CertificateRecordRepository certRecordRepository;
    private final OrganizationService organizationService;
    private final TargetService targetService;

    /**
     * Full fetch-and-store pipeline for a single target.
     *
     * @param orgId           organisation UUID
     * @param targetId        target UUID
     * @param keystorePassword password supplied by the user — used to open the keystore,
     *                        never stored on the server
     * @return the saved CertificateRecord
     */
    @Transactional
    public CertificateRecord fetchAndStore(UUID orgId, UUID targetId, char[] keystorePassword) {

        Organization org    = organizationService.findById(orgId);
        Target       target = targetService.findById(targetId);

        // 1. Verify the keystore password before doing any network work
        if (!keystoreService.verifyPassword(org.getKeystoreLocation(), keystorePassword.clone())) {
            java.util.Arrays.fill(keystorePassword, '\0');
            throw new KeystoreAccessException(
                    "Invalid keystore password for organisation '" + org.getName() + "'");
        }

        // 2. Fetch the certificate from the live target
        X509Certificate cert;
        try {
            FetchCertificates fetcher = new FetchCertificates(target.getHost(), target.getPort());
            cert = fetcher.fetchCertMetadata();
        } catch (CertificateException e) {
            java.util.Arrays.fill(keystorePassword, '\0');
            throw new RuntimeException("Failed to fetch certificate from target: " + e.getMessage(), e);
        }

        if (cert == null) {
            java.util.Arrays.fill(keystorePassword, '\0');
            throw new RuntimeException("No certificate returned from " + target.getHost() + ":" + target.getPort());
        }

        // 3. Store the certificate in the org keystore
        String alias = KeystoreService.buildAlias(target.getHost(), target.getPort());
        keystoreService.storeCertificate(org.getKeystoreLocation(), keystorePassword.clone(), alias, cert);
        java.util.Arrays.fill(keystorePassword, '\0');

        // 4. Parse X.509 metadata
        CertificateMetadata meta = parseCertificate(cert);

        // 5. Persist CertificateRecord — update existing for this target or create new
        CertificateRecord record = certRecordRepository
                .findTopByTargetIdOrderByCreatedAtDesc(targetId)
                .orElse(new CertificateRecord());

        record.setTarget(target);
        record.setOrganization(org);
        record.setCommonName(meta.commonName());
        record.setIssuer(meta.issuer());
        record.setExpiryDate(meta.expiryDate());
        record.setClientOrgName(meta.organizationName());
        record.setDivisionName(meta.organizationalUnit());
        record.setStatus(deriveStatus(meta.expiryDate()));

        CertificateRecord saved = certRecordRepository.save(record);
        logger.info("Certificate record saved for target {}:{} (org {})",
                target.getHost(), target.getPort(), org.getName());
        return saved;
    }

    /**
     * Fetch certificates for ALL targets belonging to an organisation in one call.
     *
     * @return list of results, one per target (failed targets are included with error status)
     */
    @Transactional
    public List<FetchResult> fetchAllForOrg(UUID orgId, char[] keystorePassword) {

        Organization org = organizationService.findById(orgId);
        List<Target>  targets = targetService.findByOrg(orgId);

        if (targets.isEmpty()) {
            java.util.Arrays.fill(keystorePassword, '\0');
            return Collections.emptyList();
        }

        // Verify password once up front
        if (!keystoreService.verifyPassword(org.getKeystoreLocation(), keystorePassword.clone())) {
            java.util.Arrays.fill(keystorePassword, '\0');
            throw new KeystoreAccessException(
                    "Invalid keystore password for organisation '" + org.getName() + "'");
        }

        List<FetchResult> results = new ArrayList<>();
        for (Target target : targets) {
            try {
                CertificateRecord record = fetchAndStore(orgId, target.getId(), keystorePassword.clone());
                results.add(FetchResult.success(target, record));
            } catch (Exception e) {
                logger.warn("Failed to fetch cert for target {}:{} — {}",
                        target.getHost(), target.getPort(), e.getMessage());
                results.add(FetchResult.failure(target, e.getMessage()));
            }
        }

        java.util.Arrays.fill(keystorePassword, '\0');
        return results;
    }

    // ── Certificate parsing ───────────────────────────────────────────────────

    private CertificateMetadata parseCertificate(X509Certificate cert) {
        // Parse Subject DN fields
        String subjectDn = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
        String commonName        = extractDnField(subjectDn, "CN");
        String organizationName  = extractDnField(subjectDn, "O");
        String organizationalUnit= extractDnField(subjectDn, "OU");

        // Issuer
        String issuerDn = cert.getIssuerX500Principal().getName(X500Principal.RFC2253);
        String issuer = extractDnField(issuerDn, "CN");
        if (issuer == null) issuer = issuerDn; // fallback to full DN

        // Expiry
        LocalDate expiryDate = cert.getNotAfter()
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();

        return new CertificateMetadata(commonName, issuer, expiryDate, organizationName, organizationalUnit);
    }

    private String extractDnField(String dn, String field) {
        // DN format: "CN=example.com,O=Example Inc,C=US"
        for (String part : dn.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(field + "=")) {
                return trimmed.substring(field.length() + 1).replace("\"", "");
            }
        }
        return null;
    }

    private String deriveStatus(LocalDate expiryDate) {
        LocalDate today = LocalDate.now();
        if (expiryDate.isBefore(today)) return "EXPIRED";
        if (expiryDate.isBefore(today.plusDays(30))) return "EXPIRING_SOON";
        return "VALID";
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record CertificateMetadata(
            String commonName,
            String issuer,
            LocalDate expiryDate,
            String organizationName,
            String organizationalUnit
    ) {}

    /**
     * Represents the outcome of a single fetch attempt (success or failure).
     */
    public record FetchResult(
            UUID targetId,
            String host,
            int port,
            boolean success,
            CertificateRecord record,   // non-null on success
            String errorMessage          // non-null on failure
    ) {
        static FetchResult success(Target t, CertificateRecord r) {
            return new FetchResult(t.getId(), t.getHost(), t.getPort(), true, r, null);
        }
        static FetchResult failure(Target t, String msg) {
            return new FetchResult(t.getId(), t.getHost(), t.getPort(), false, null, msg);
        }
    }
}
