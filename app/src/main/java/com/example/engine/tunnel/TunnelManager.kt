package com.example.engine.tunnel

import android.content.Context
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.TunnelConfigDao
import com.example.core.database.TunnelConfigEntity
import com.example.core.model.TunnelProvider
import com.example.core.model.TunnelStatus
import com.example.core.security.SecurityHelper
import com.example.engine.core.ProcessProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class TunnelRuntimeState(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val verifiedPublicUrl: String? = null,
    val detailMessage: String = "المشاركة الخارجية متوقفة حالياً. لا يتم فتح أي منافذ خارجية تلقائياً.",
    val activeSessionsCount: Int = 0
)

class TunnelManager(
    private val context: Context,
    private val tunnelDao: TunnelConfigDao,
    private val logDao: AppLogDao
) {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val _runtimeStates = MutableStateFlow<Map<String, TunnelRuntimeState>>(emptyMap())
    val runtimeStates: StateFlow<Map<String, TunnelRuntimeState>> = _runtimeStates.asStateFlow()

    suspend fun saveTunnelConfig(
        siteId: String,
        provider: TunnelProvider,
        controlEndpoint: String,
        enforceHttps: Boolean,
        requireAuth: Boolean,
        authUsername: String,
        authPasswordPlain: String?,
        allowedIpCidr: String
    ): TunnelConfigEntity = withContext(Dispatchers.IO) {
        val existing = tunnelDao.getBySiteId(siteId)
        val passHash = if (!authPasswordPlain.isNullOrBlank()) {
            SecurityHelper.hashSecret(authPasswordPlain)
        } else {
            existing?.authPasswordHash ?: ""
        }
        val entity = TunnelConfigEntity(
            id = existing?.id ?: "tunnel_$siteId",
            siteId = siteId,
            provider = provider.name,
            controlEndpoint = controlEndpoint.trim(),
            publicUrl = "",
            enforceHttps = enforceHttps,
            requireAuth = requireAuth,
            authUsername = authUsername.ifBlank { "admin" },
            authPasswordHash = passHash,
            allowedIpCidr = allowedIpCidr.trim(),
            userApproved = existing?.userApproved ?: false,
            isActive = false,
            statusMessage = "تم حفظ إعدادات الأمان والنفق.",
            updatedAt = System.currentTimeMillis()
        )
        tunnelDao.upsert(entity)
        logDao.insert(
            AppLogEntity(
                level = "SECURITY",
                source = "TUNNEL",
                message = "تم تحديث إعدادات المشاركة للموقع ($siteId): HTTPS=$enforceHttps, Auth=$requireAuth"
            )
        )
        entity
    }

    suspend fun startSharingWithApproval(
        config: TunnelConfigEntity,
        sitePort: Int,
        explicitUserApproval: Boolean
    ): TunnelRuntimeState = withContext(Dispatchers.IO) {
        if (!explicitUserApproval) {
            val state = TunnelRuntimeState(
                status = TunnelStatus.AWAITING_APPROVAL,
                verifiedPublicUrl = null,
                detailMessage = "يتطلب النظام موافقة المستخدم الصريحة قبل تفعيل المشاركة الخارجية."
            )
            updateState(config.siteId, state)
            return@withContext state
        }

        if (!ProcessProbe.isTcpPortListening("127.0.0.1", sitePort, 300)) {
            val state = TunnelRuntimeState(
                status = TunnelStatus.ERROR,
                verifiedPublicUrl = null,
                detailMessage = "يجب تشغيل الموقع محلياً على المنفذ $sitePort قبل بدء المشاركة عبر الإنترنت."
            )
            updateState(config.siteId, state)
            return@withContext state
        }

        if (config.enforceHttps && config.controlEndpoint.startsWith("http://")) {
            val state = TunnelRuntimeState(
                status = TunnelStatus.ERROR,
                verifiedPublicUrl = null,
                detailMessage = "سياسة HTTPS الإلزامية تمنع الاتصال عبر روابط http:// غير المشفرة."
            )
            updateState(config.siteId, state)
            return@withContext state
        }

        val provider = TunnelProvider.entries.firstOrNull { it.name == config.provider }
            ?: TunnelProvider.CLOUDFLARED

        if (provider == TunnelProvider.CLOUDFLARED || provider == TunnelProvider.SSH_REVERSE) {
            val binNames = if (provider == TunnelProvider.CLOUDFLARED) listOf("cloudflared") else listOf("ssh", "autossh")
            val bin = ProcessProbe.findBinary(context, binNames, "tunnel")
            if (bin == null) {
                val msg = "عميل ${provider.titleAr} غير مثبت على الجهاز (${ProcessProbe.getDeviceAbi()}). لم يتم إنشاء أي رابط عام وهمي."
                tunnelDao.upsert(config.copy(userApproved = true, isActive = false, publicUrl = "", statusMessage = msg))
                logDao.insert(AppLogEntity(level = "WARN", source = "TUNNEL", message = msg))
                val state = TunnelRuntimeState(
                    status = TunnelStatus.AWAITING_CLIENT,
                    verifiedPublicUrl = null,
                    detailMessage = msg
                )
                updateState(config.siteId, state)
                return@withContext state
            }
        }

        if (provider == TunnelProvider.CUSTOM_REVERSE_PROXY) {
            val endpoint = config.controlEndpoint
            if (!endpoint.startsWith("https://")) {
                val msg = "يرجى إدخال عنوان وسيط عكسي صالح يبدأ بـ https:// لإتمام التحقق."
                val state = TunnelRuntimeState(status = TunnelStatus.ERROR, verifiedPublicUrl = null, detailMessage = msg)
                updateState(config.siteId, state)
                return@withContext state
            }
            return@withContext try {
                val req = Request.Builder().url(endpoint).head().build()
                okHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful || resp.code in 200..399) {
                        tunnelDao.upsert(
                            config.copy(
                                userApproved = true,
                                isActive = true,
                                publicUrl = endpoint,
                                statusMessage = "متصل بالوسيط الآمن (HTTP ${resp.code})"
                            )
                        )
                        logDao.insert(
                            AppLogEntity(
                                level = "SECURITY",
                                source = "TUNNEL",
                                message = "تم تفعيل مشاركة HTTPS للموقع ${config.siteId} عبر $endpoint بموافقة المستخدم"
                            )
                        )
                        TunnelRuntimeState(
                            status = TunnelStatus.CONNECTED,
                            verifiedPublicUrl = endpoint,
                            detailMessage = "تم التحقق من استجابة HTTPS للوسيط العكسي ($endpoint).",
                            activeSessionsCount = 1
                        ).also { updateState(config.siteId, it) }
                    } else {
                        val msg = "الوسيط العكسي أرجع رمز HTTP ${resp.code}"
                        TunnelRuntimeState(status = TunnelStatus.ERROR, verifiedPublicUrl = null, detailMessage = msg)
                            .also { updateState(config.siteId, it) }
                    }
                }
            } catch (e: Exception) {
                val msg = "تعذر الاتصال بالوسيط الآمن: ${e.message}"
                logDao.insert(AppLogEntity(level = "ERROR", source = "TUNNEL", message = msg))
                TunnelRuntimeState(status = TunnelStatus.ERROR, verifiedPublicUrl = null, detailMessage = msg)
                    .also { updateState(config.siteId, it) }
            }
        }

        val fallbackState = TunnelRuntimeState(
            status = TunnelStatus.AWAITING_CLIENT,
            verifiedPublicUrl = null,
            detailMessage = "الخدمة بانتظار تثبيت عميل النفق المعتمد."
        )
        updateState(config.siteId, fallbackState)
        fallbackState
    }

    suspend fun stopSharing(config: TunnelConfigEntity) = withContext(Dispatchers.IO) {
        tunnelDao.upsert(
            config.copy(
                isActive = false,
                publicUrl = "",
                statusMessage = "تم إيقاف المشاركة الخارجية وإغلاق الجلسة"
            )
        )
        logDao.insert(
            AppLogEntity(
                level = "INFO",
                source = "TUNNEL",
                message = "تم إيقاف المشاركة عبر الإنترنت للموقع (${config.siteId})"
            )
        )
        updateState(
            config.siteId,
            TunnelRuntimeState(
                status = TunnelStatus.DISCONNECTED,
                verifiedPublicUrl = null,
                detailMessage = "تم إيقاف المشاركة الخارجية بنجاح."
            )
        )
    }

    private fun updateState(siteId: String, state: TunnelRuntimeState) {
        _runtimeStates.value = _runtimeStates.value + (siteId to state)
    }
}
