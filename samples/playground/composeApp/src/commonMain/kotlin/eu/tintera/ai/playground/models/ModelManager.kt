package eu.tintera.ai.playground.models

import eu.tintera.ai.core.AiFailureReason
import eu.tintera.ai.core.DownloadStoppedException
import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.ModelDownload
import eu.tintera.ai.core.ModelVariant
import eu.tintera.ai.core.NotEnoughSpaceException
import eu.tintera.ai.core.OnDeviceAiException
import eu.tintera.ai.core.OnDeviceAiStatus
import eu.tintera.ai.core.RuntimeInstallException
import eu.tintera.ai.playground.CustomModel
import eu.tintera.ai.playground.PlaygroundEnvironment
import eu.tintera.ai.playground.Storage
import eu.tintera.ai.playground.catalog.Catalog
import eu.tintera.ai.playground.catalog.CatalogVariant
import eu.tintera.ai.playground.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Co obrazovka ukazuje u jednoho modelu. [status] `null` = ještě se neví. */
data class ModelState(
    val status: OnDeviceAiStatus? = null,
    val progress: ModelDownload? = null,
    val error: String? = null,
)

/**
 * Správa modelů nad `OnDeviceAi`: stav, stažení, pozastavení, pokračování a smazání
 * (`status`, `download`, `stopDownload`, `delete`) a volba „jen přes Wi-Fi" (`setDownloadNetwork`).
 *
 * Stahování drží knihovna (na Androidu úloha WorkManageru, na iOS sezení na pozadí, na desktopu
 * korutina procesu); tahle třída jen sbírá průběh pro obrazovku. Všechno běží na hlavním vlákně
 * ([scope]), takže [jobs] nepotřebuje zámek.
 */
class ModelManager(
    private val environment: PlaygroundEnvironment,
    private val storage: Storage,
    private val scope: CoroutineScope,
) {
    private val ai get() = environment.ai

    private val _states = MutableStateFlow<Map<ModelVariant, ModelState>>(emptyMap())
    val states: StateFlow<Map<ModelVariant, ModelState>> = _states.asStateFlow()

    private val _wifiOnly = MutableStateFlow(storage.state.wifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _custom = MutableStateFlow(storage.state.customModels)
    val customModels: StateFlow<List<CustomModel>> = _custom.asStateFlow()

    /** Sběr průběhu stahování podle modelu. */
    private val jobs = mutableMapOf<ModelVariant, Job>()

    init {
        scope.launch { refreshAll(attach = true) }
    }

    private fun allVariants(): List<ModelVariant> =
        environment.systemModels.map { it.variant } +
            Catalog.flatMap { family -> family.models.flatMap { model -> model.variants.map { it.model.variant } } } +
            _custom.value.map { it.variant.model.variant }

    /**
     * Zjistí stav všech modelů. [attach]: k modelům, které se stahují (z minulého běhu procesu
     * nebo úloha na pozadí), se připojí — `download` ve stavu `Downloading` jen sleduje.
     */
    suspend fun refreshAll(attach: Boolean = false) {
        for (variant in allVariants()) {
            val status = refresh(variant)
            if (attach && status == OnDeviceAiStatus.Downloading) download(variant)
        }
    }

    private suspend fun refresh(variant: ModelVariant): OnDeviceAiStatus? {
        val status = runCatching { ai.status(variant) }.getOrNull()
        _states.update { it + (variant to (it[variant] ?: ModelState()).copy(status = status)) }
        return status
    }

    fun download(variant: ModelVariant) {
        if (jobs[variant]?.isActive == true) return
        set(variant) { it.copy(status = OnDeviceAiStatus.Downloading, error = null) }
        jobs[variant] = scope.launch {
            try {
                ai.download(variant, mobileData = !_wifiOnly.value).collect { progress ->
                    set(variant) { it.copy(status = OnDeviceAiStatus.Downloading, progress = progress) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadStoppedException) {
                // Pozastavil uživatel: stažená část zůstává, stav (Paused) zjistí `refresh` níž.
            } catch (e: Exception) {
                set(variant) { it.copy(error = describe(e, variant.model)) }
            } finally {
                jobs.remove(variant)
                set(variant) { it.copy(progress = null) }
                refresh(variant)
            }
        }
    }

    fun pause(variant: ModelVariant) {
        scope.launch {
            ai.stopDownload(variant)
            if (jobs[variant] == null) refresh(variant)
        }
    }

    fun delete(variant: ModelVariant) {
        scope.launch {
            jobs.remove(variant)?.cancel()
            ai.stopDownload(variant)
            runCatching { ai.delete(variant) }
                .onFailure { e -> set(variant) { it.copy(error = "Delete failed: ${e.message}") } }
            refresh(variant)
        }
    }

    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        storage.update { it.copy(wifiOnly = value) }
        // Rozběhnutá stahování přejdou na novou podmínku bez zastavení (iOS je přenese do
        // druhého sezení, Android změní podmínku úlohy).
        scope.launch { jobs.keys.toList().forEach { ai.setDownloadNetwork(it, mobileData = !value) } }
    }

    fun dismissError(variant: ModelVariant) = set(variant) { it.copy(error = null) }

    fun addCustom(model: CustomModel) {
        if (_custom.value.any { it.id == model.id }) return
        _custom.update { it + model }
        storage.update { it.copy(customModels = _custom.value) }
        scope.launch { refresh(model.variant.model.variant) }
    }

    /** Odebere vlastní model ze seznamu a smaže jeho soubor. */
    fun removeCustom(model: CustomModel) {
        val variant = model.variant.model.variant
        scope.launch {
            jobs.remove(variant)?.cancel()
            ai.stopDownload(variant)
            runCatching { ai.delete(variant) }
            _custom.update { list -> list.filterNot { it.id == model.id } }
            storage.update { it.copy(customModels = _custom.value) }
            _states.update { it - variant }
        }
    }

    /** Varianta z katalogu nebo vlastní, k níž patří [variant]; pro titulky konverzace. */
    fun catalogVariant(variant: ModelVariant): CatalogVariant? =
        Catalog.asSequence().flatMap { it.models }.flatMap { it.variants }.firstOrNull { it.model.variant == variant }
            ?: _custom.value.map { it.variant }.firstOrNull { it.model.variant == variant }

    private fun set(variant: ModelVariant, change: (ModelState) -> ModelState) {
        _states.update { it + (variant to change(it[variant] ?: ModelState())) }
    }

    /** Hláška pro obrazovku; knihovna texty nenese, jen typy selhání (README). */
    private fun describe(e: Exception, model: DownloadedModel?): String = when (e) {
        is NotEnoughSpaceException -> "Not enough storage: ${formatBytes(e.neededBytes)} needed."
        is RuntimeInstallException ->
            "The model runtime could not be installed" + (e.code?.let { " (Play error $it)" } ?: "") +
                ". Outside Google Play, install the runtime feature modules together with the app (README)."
        is OnDeviceAiException -> "Failed: ${e.reason.describe()}"
        else -> "Download of ${model?.displayName ?: "the model"} failed: ${e.message ?: e::class.simpleName}"
    }
}

/** Důvod selhání pro člověka (aplikace mapuje [AiFailureReason] na své texty). */
fun AiFailureReason.describe(): String = when (this) {
    AiFailureReason.Busy -> "the model is busy, try again in a moment."
    AiFailureReason.TooLarge -> "the input is too long for the model."
    AiFailureReason.NotEnoughSpace -> "not enough storage."
    AiFailureReason.NeedsUpdate -> "the system model needs an update."
    AiFailureReason.Unavailable -> "the model is not available right now."
    AiFailureReason.UnsupportedLanguage -> "the model does not support this language."
    AiFailureReason.Refused -> "the model refused to answer."
    AiFailureReason.ModelError -> "the model failed (it may be corrupted or not supported by the runtime)."
}
