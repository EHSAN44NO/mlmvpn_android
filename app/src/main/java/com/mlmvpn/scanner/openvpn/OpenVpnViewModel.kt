package com.mlmvpn.scanner.openvpn

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class OpenVpnViewModel(app: Application) : AndroidViewModel(app) {
    private val state = MutableStateFlow<OpenVpnData?>(null)
    val data = state.asStateFlow()
    val message = MutableStateFlow<String?>(null)
    val testing = MutableStateFlow<Set<String>>(emptySet())
    private var probes: Job? = null
    private val repo get() = OpenVpnRepository.get(getApplication())
    init { viewModelScope.launch(Dispatchers.IO) { repo.data.collect { state.value = it } } }
    fun change(action: (OpenVpnRepository) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { action(repo) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "SAVE_FAILED" }
        }
    }
    fun refresh() = change { r -> r.data.value.accounts.forEach { r.refreshAccount(it.id) } }
    fun import(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                require(uris.size <= 100)
                val resolver = getApplication<Application>().contentResolver
                val files = linkedMapOf<String, String>()
                var total = 0
                for (uri in uris) {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: error("Missing name")
                    require(name !in files && name.length <= 180 && !name.contains('/') && !name.contains('\\'))
                    val bytes = resolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            require(buffer.size() + n <= ProfileImporter.MAX_BYTES)
                            buffer.write(chunk, 0, n)
                        }
                        buffer.toByteArray()
                    } ?: error("Unreadable file")
                    total += bytes.size
                    require(total <= 8_388_608)
                    files[name] = bytes.toString(Charsets.UTF_8)
                }
                val (count, errors) = repo.importFiles(files)
                message.value = if (errors.isEmpty()) "IMPORTED:$count" else "IMPORTED:$count\n" + errors.take(6).joinToString("\n")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "IMPORT_FAILED" }
        }
    }
    fun test(profiles: List<Profile>) {
        if (probes?.isActive == true) return
        testing.value = profiles.map { it.id }.toSet()
        probes = viewModelScope.launch {
            val semaphore = Semaphore(4)
            try {
                coroutineScope {
                    profiles.map { p -> launch {
                        semaphore.withPermit {
                            val result = OpenVpnLatency.measure(p)
                            withContext(Dispatchers.IO) { repo.updateProfile(p.id) { it.withProbe(result) } }
                            testing.value = testing.value - p.id
                        }
                    } }.joinAll()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "TEST_FAILED" }
            finally { testing.value = emptySet() }
        }
    }
    fun cancelTests() { probes?.cancel() }
}
