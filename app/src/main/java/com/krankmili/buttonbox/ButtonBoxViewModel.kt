package com.krankmili.buttonbox

import androidx.lifecycle.ViewModel
import com.krankmili.buttonbox.data.BoxGroup
import com.krankmili.buttonbox.data.ButtonBoxClient
import com.krankmili.buttonbox.data.ConnectionState
import com.krankmili.buttonbox.data.TruckStatus
import com.krankmili.buttonbox.data.Discovery
import com.krankmili.buttonbox.data.FoundServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

class ButtonBoxViewModel : ViewModel() {

    // Own scope instead of viewModelScope: avoids depending on lifecycle-viewmodel-ktx.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = ButtonBoxClient(scope)

    val state: StateFlow<ConnectionState> = client.state
    val layout: StateFlow<List<BoxGroup>> = client.layout
    val truck: StateFlow<TruckStatus?> = client.truck

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _found = MutableStateFlow<FoundServer?>(null)
    val found: StateFlow<FoundServer?> = _found
    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed

    fun discover() {
        if (_searching.value) return
        _searching.value = true
        _searchFailed.value = false
        scope.launch {
            val r = Discovery.find()
            _found.value = r
            _searchFailed.value = r == null
            _searching.value = false
        }
    }

    fun consumeFound() { _found.value = null }

    fun connect(host: String, port: Int, token: String) = client.connect(host, port, token)
    fun disconnect() = client.disconnect()
    fun press(id: String) = client.press(id)
    fun release(id: String) = client.release(id)

    override fun onCleared() {
        client.disconnect()
        scope.cancel()
    }
}
