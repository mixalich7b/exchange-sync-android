package net.mixalich7b.exchangesync.infrastructure.activesync

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.diagnostics.diagnosticHost
import net.mixalich7b.exchangesync.infrastructure.diagnostics.diagnosticPath

internal class ActiveSyncNetworkEventListener(
    private val diagnostics: DeviceDiagnostics,
    private val operation: DiagnosticOperation,
) : EventListener() {
    override fun dnsStart(
        call: Call,
        domainName: String,
    ) = emit(call.request(), DiagnosticStage.DNS, DiagnosticSeverity.INFO, outcome = "start")

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) = emit(call.request(), DiagnosticStage.DNS, DiagnosticSeverity.INFO, outcome = "success")

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) = emit(call.request(), DiagnosticStage.CONNECT, DiagnosticSeverity.INFO, outcome = "start")

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) = emit(call.request(), DiagnosticStage.CONNECT, DiagnosticSeverity.INFO, outcome = "success")

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) = emit(call.request(), DiagnosticStage.CONNECT, DiagnosticSeverity.ERROR, outcome = "failure", throwable = ioe)

    override fun secureConnectStart(call: Call) =
        emit(call.request(), DiagnosticStage.SECURE_CONNECT, DiagnosticSeverity.INFO, outcome = "start")

    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) = emit(call.request(), DiagnosticStage.SECURE_CONNECT, DiagnosticSeverity.INFO, outcome = "success")

    override fun requestHeadersEnd(
        call: Call,
        request: Request,
    ) = emit(request, DiagnosticStage.REQUEST, DiagnosticSeverity.INFO, outcome = "sent")

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) = emit(
        response.request,
        DiagnosticStage.RESPONSE,
        DiagnosticSeverity.INFO,
        status = response.code,
        outcome = "received",
    )

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) {
        if (!call.isCanceled()) {
            emit(call.request(), DiagnosticStage.FAILURE, DiagnosticSeverity.ERROR, outcome = "failure", throwable = ioe)
        }
    }

    override fun canceled(call: Call) =
        emit(call.request(), DiagnosticStage.CANCELLATION, DiagnosticSeverity.INFO, outcome = "cancelled")

    private fun emit(
        request: Request,
        stage: DiagnosticStage,
        severity: DiagnosticSeverity,
        status: Int? = null,
        outcome: String,
        throwable: Throwable? = null,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = severity,
                component = DiagnosticComponent.HTTP,
                stage = stage,
                operation = request.tag(DiagnosticOperation::class.java) ?: operation,
                method = request.method,
                command = request.url.queryParameter("Cmd"),
                host = request.url.diagnosticHost(),
                path = request.url.diagnosticPath(),
                status = status,
                outcome = outcome,
                throwable = throwable,
            ),
        )
    }
}
