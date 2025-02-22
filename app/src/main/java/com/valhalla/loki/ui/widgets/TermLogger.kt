package com.valhalla.loki.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TermLoggerDialog(
    modifier: Modifier = Modifier,
    title: String = "Reinstalling..,",
    canExit: Boolean = false,
    logObserver: List<String>,
    done: () -> Unit
) {
    Dialog(
        onDismissRequest = {
            if (canExit) {
                done()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
            Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom){
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(topEnd = 20.dp, topStart = 20.dp)
                        )
                        .padding(10.dp).padding(bottom = 50.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!canExit)
                            CircularProgressIndicator()
                        Text(
                            if (!canExit) title else "",
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        items(logObserver) { logTxt ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "* $logTxt",
                                    softWrap = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 10,
                                    textAlign = TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                        }
                    }

                    if (canExit)
                        Button(
                            onClick = {
                                done()
                            }
                        ) {
                            Text("Close")
                        }
                }
            }
        }
}