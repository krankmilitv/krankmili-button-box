package com.krankmili.buttonbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectScreen(
    host: String,
    port: String,
    token: String,
    statusText: String,
    busy: Boolean,
    searching: Boolean,
    onDiscover: () -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onToken: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(shape)
                .background(Palette.Panel)
                .border(1.dp, Palette.PanelBorder, shape)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append("KRANKMILI & O.K ")
                    withStyle(SpanStyle(color = Palette.Accent)) { append("BUTTON BOX") }
                },
                color = Palette.Text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Text(
                "Tablet ve PC aynı Wi-Fi ağında olmalı. PC'de server'ı aç, sonra aşağıdaki butona bas.",
                color = Palette.Muted,
                fontSize = 15.sp,
            )
            Button(
                onClick = onDiscover,
                enabled = !busy && !searching,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Accent,
                    contentColor = Palette.OnAccent,
                ),
            ) {
                Text(if (searching) "PC ARANIYOR…" else "PC'Yİ OTOMATİK BUL", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Text("Bulunamazsa IP ve portu elle girebilirsin:", color = Palette.Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = onHost, singleLine = true, enabled = !busy,
                    label = { Text("PC IP adresi") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port, onValueChange = onPort, singleLine = true, enabled = !busy,
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = token, onValueChange = onToken, singleLine = true, enabled = !busy,
                label = { Text("Token (opsiyonel)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = if (busy) onCancel else onConnect,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Accent,
                    contentColor = Palette.OnAccent,
                ),
            ) {
                Text(if (busy) "İPTAL" else "BAĞLAN", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (busy) Palette.Accent else Palette.Bad))
                Text(statusText, color = Palette.Muted, fontSize = 14.sp)
            }
        }
    }
}
