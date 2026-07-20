package com.example.aichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * 真实终端：在 workDir 内启动一个交互式 `sh` 进程，
 * 实时回显输出、可用输入框发送命令。等同于一个真实的本机终端。
 */
@Composable
fun TerminalView(workDir: String) {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf<List<String>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var process by remember { mutableStateOf<Process?>(null) }
    var writer by remember { mutableStateOf<BufferedWriter?>(null) }
    val listState = rememberLazyListState()

    DisposableEffect(workDir) {
        val pb = ProcessBuilder("sh", "-i")
            .directory(File(workDir))
            .redirectErrorStream(true)
        val proc = pb.start()
        val reader: BufferedReader = proc.inputStream.bufferedReader()
        val w: BufferedWriter = proc.outputStream.bufferedWriter()
        val outLines = mutableListOf<String>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    outLines.add(line)
                    withContext(Dispatchers.Main) {
                        output = outLines.toList()
                        if (outLines.isNotEmpty()) listState.scrollToItem(outLines.lastIndex)
                    }
                }
            } catch (_: Exception) {
            }
        }
        process = proc
        writer = w
        onDispose {
            try { w.close() } catch (_: Exception) { }
            try { proc.destroy() } catch (_: Exception) { }
            job.cancel()
        }
    }

    fun send(cmd: String) {
        val line = cmd.trim()
        if (line.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    writer?.write(line + "\n")
                    writer?.flush()
                } catch (_: Exception) { }
            }
        }
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color(0xFF0B0F0B))
            .padding(8.dp)
    ) {
        Text(
            text = "真实终端  ·  $workDir",
            color = Color(0xFF8BC34A),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(output) { line ->
                Text(
                    text = line,
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$ ",
                color = Color(0xFF00E676),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入命令后回车", color = Color.Gray) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFFE0E0E0)
                )
            )
            Spacer(Modifier.widthIn(4.dp))
            TextButton(onClick = { send(input) }) {
                Text("运行", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
