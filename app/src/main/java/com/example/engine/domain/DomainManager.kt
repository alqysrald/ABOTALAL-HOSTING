package com.example.engine.domain

import android.content.Context
import android.net.Uri
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.DomainDao
import com.example.core.database.DomainEntity
import com.example.core.database.SiteEntity
import com.example.core.security.SecurityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

class DomainManager(
    private val context: Context,
    private val domainDao: DomainDao,
    private val logDao: AppLogDao
) {
    private val domainRegex = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,12}$")

    suspend fun addOrUpdateDomain(
        rawDomain: String,
        site: SiteEntity,
        customDocumentRoot: String,
        dnsARecord: String,
        dnsCnameRecord: String,
        dnsTxtRecord: String,
        sslEnabled: Boolean
    ): Result<DomainEntity> = withContext(Dispatchers.IO) {
        val domain = rawDomain.trim().lowercase()
        if (!domainRegex.matches(domain)) {
            return@withContext Result.failure(IllegalArgumentException("صيغة الدومين غير صالحة (مثال: project.test أو sub.domain.com)"))
        }

        val siteRoot = File(site.rootFolder).canonicalFile
        val docRootRel = customDocumentRoot.trim().ifBlank { "." }
        val resolvedDocRoot = if (docRootRel == "." || docRootRel == "/") {
            siteRoot
        } else {
            SecurityHelper.resolveSafeChild(siteRoot, docRootRel)
                ?: return@withContext Result.failure(SecurityException("Document Root خارج المجلد المعزول للموقع"))
        }
        if (!resolvedDocRoot.exists()) resolvedDocRoot.mkdirs()

        val labels = domain.split('.')
        val tld = labels.last()
        val isLocal = tld in setOf("test", "local", "localhost", "lan")
        val isSubdomain = labels.size >= 3
        val parent = if (isSubdomain) labels.drop(1).joinToString(".") else ""

        val existing = domainDao.getByName(domain)
        val entity = DomainEntity(
            id = existing?.id ?: "dom_${UUID.randomUUID().toString().take(8)}",
            domainName = domain,
            isLocalDomain = isLocal,
            parentDomain = parent,
            siteId = site.id,
            documentRoot = resolvedDocRoot.absolutePath,
            dnsARecord = dnsARecord.ifBlank { if (isLocal) "127.0.0.1" else "" },
            dnsCnameRecord = dnsCnameRecord.trim(),
            dnsTxtRecord = dnsTxtRecord.trim(),
            sslEnabled = sslEnabled && !(existing?.sslCertPath.isNullOrBlank() && !isLocal),
            sslCertPath = existing?.sslCertPath ?: "",
            sslSubject = existing?.sslSubject ?: "",
            tunnelLinked = existing?.tunnelLinked ?: false,
            tunnelHostname = existing?.tunnelHostname ?: "",
            dnsResolvedGlobally = false // Strictly verified via probe, never assumed true locally
        )
        domainDao.upsert(entity)
        logDao.insert(
            AppLogEntity(
                level = "INFO",
                source = "DOMAIN",
                message = "تم ربط النطاق '$domain' بالموقع '${site.name}' (DocumentRoot: ${resolvedDocRoot.name})"
            )
        )
        Result.success(entity)
    }

    suspend fun verifyGlobalDnsResolution(domain: DomainEntity): DomainEntity = withContext(Dispatchers.IO) {
        if (domain.isLocalDomain) {
            return@withContext domain.copy(dnsResolvedGlobally = false)
        }
        val resolvedIps = try {
            InetAddress.getAllByName(domain.domainName)
                .mapNotNull { it.hostAddress }
                .filter { !it.startsWith("127.") && it != "::1" }
        } catch (_: Exception) {
            emptyList()
        }
        val updated = domain.copy(
            dnsResolvedGlobally = resolvedIps.isNotEmpty(),
            dnsARecord = resolvedIps.firstOrNull() ?: domain.dnsARecord
        )
        domainDao.upsert(updated)
        logDao.insert(
            AppLogEntity(
                level = if (updated.dnsResolvedGlobally) "INFO" else "WARN",
                source = "DOMAIN",
                message = if (updated.dnsResolvedGlobally) {
                    "تم التحقق من DNS العام للنطاق ${domain.domainName}: ${resolvedIps.joinToString()}"
                } else {
                    "النطاق ${domain.domainName} غير موجه عالمياً حالياً عبر DNS العام"
                }
            )
        )
        updated
    }

    suspend fun importSslCertificate(domain: DomainEntity, certUri: Uri): Result<DomainEntity> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val certsDir = File(context.filesDir, "ssl/${domain.id}").apply { mkdirs() }
                val destCert = File(certsDir, "server.crt")
                context.contentResolver.openInputStream(certUri)?.use { input ->
                    destCert.outputStream().use { input.copyTo(it) }
                } ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة ملف الشهادة"))

                val cf = CertificateFactory.getInstance("X.509")
                val x509 = destCert.inputStream().use { cf.generateCertificate(it) as X509Certificate }
                x509.checkValidity()

                val updated = domain.copy(
                    sslEnabled = true,
                    sslCertPath = destCert.absolutePath,
                    sslSubject = x509.subjectX500Principal.name
                )
                domainDao.upsert(updated)
                logDao.insert(
                    AppLogEntity(
                        level = "SECURITY",
                        source = "SSL",
                        message = "تم التحقق من شهادة SSL وتثبيتها للنطاق ${domain.domainName}: ${x509.subjectX500Principal.name}"
                    )
                )
                Result.success(updated)
            } catch (e: Exception) {
                logDao.insert(
                    AppLogEntity(
                        level = "ERROR",
                        source = "SSL",
                        message = "فشل التحقق من شهادة SSL للنطاق ${domain.domainName}: ${e.message}"
                    )
                )
                Result.failure(IllegalStateException("شهادة SSL غير صالحة أو منتهية: ${e.message}"))
            }
        }

    suspend fun linkDomainToTunnel(domain: DomainEntity, tunnelHost: String, linked: Boolean): DomainEntity {
        val updated = domain.copy(
            tunnelLinked = linked && tunnelHost.isNotBlank(),
            tunnelHostname = tunnelHost.trim()
        )
        domainDao.upsert(updated)
        logDao.insert(
            AppLogEntity(
                level = "INFO",
                source = "DOMAIN",
                message = if (updated.tunnelLinked) {
                    "تم ربط النطاق ${domain.domainName} بنفق المشاركة ($tunnelHost)"
                } else {
                    "تم فصل النطاق ${domain.domainName} عن النفق الخارجي"
                }
            )
        )
        return updated
    }

    suspend fun deleteDomain(domain: DomainEntity) {
        File(context.filesDir, "ssl/${domain.id}").deleteRecursively()
        domainDao.deleteById(domain.id)
    }
}
