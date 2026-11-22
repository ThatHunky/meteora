package dev.thathunky.meteora.update

import java.io.File

/** Where the updater is. Every state from Available on carries the release it is about. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val done: Long, val total: Long) : UpdateState {
        val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
    }
    data class Verifying(val release: Release) : UpdateState
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Installing(val release: Release) : UpdateState
    /** [release] is null when the check itself failed; [detail] is Android's own words for an install failure. */
    data class Failed(val reason: Failure, val release: Release?, val detail: String? = null) : UpdateState
}

val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Verifying -> release
        is UpdateState.Ready -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        else -> null
    }

enum class Failure {
    Network, Busy, Storage,
    Size, Digest, NotAnApk, WrongPackage, NotNewer, DifferentSigner,
    InstallConflict, InstallStorage, InstallBlocked, InstallIncompatible, InstallInvalid, InstallOther,
    ;

    /** Failures where installing by hand from the release page is the way out. */
    val needsManualInstall: Boolean get() = this == DifferentSigner || this == InstallConflict

    /** Failures that a fresh download can fix. */
    val retryable: Boolean get() = this != NotNewer && this != WrongPackage && !needsManualInstall

    companion object {
        fun of(r: Refusal): Failure = when (r) {
            Refusal.Size -> Size
            Refusal.Digest -> Digest
            Refusal.NotAnApk -> NotAnApk
            Refusal.WrongPackage -> WrongPackage
            Refusal.NotNewer -> NotNewer
            Refusal.DifferentSigner -> DifferentSigner
        }
    }
}
