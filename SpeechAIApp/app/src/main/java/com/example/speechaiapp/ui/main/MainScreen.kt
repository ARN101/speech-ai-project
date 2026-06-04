package com.example.speechaiapp.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required to record audio", Toast.LENGTH_LONG).show()
        }
    }

    // Dynamic gradient color palette
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F172A), // Very dark slate
            Color(0xFF1E293B)  // Dark slate
        )
    )

    val neonCyan = Color(0xFF06B6D4)
    val neonPurple = Color(0xFF8B5CF6)
    val activeRed = Color(0xFFEF4444)

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Bangla Speech AI",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        containerColor = Color.Transparent,
        modifier = modifier.background(backgroundGradient)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            
            // Transcription Card Display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 32.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    if (state.transcription.isEmpty()) {
                        Text(
                            text = when (state.status) {
                                ASRStatus.LISTENING -> "শুনছি... অনুগ্রহ করে কথা বলুন।"
                                ASRStatus.TRANSCRIBING -> "অনুবাদ করা হচ্ছে... দয়া করে অপেক্ষা করুন।"
                                else -> "আপনার কথা এখানে বাংলা টেক্সটে রূপান্তরিত হবে..."
                            },
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = state.transcription,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                            
                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(state.transcription))
                                    Toast.makeText(context, "টেক্সট কপি করা হয়েছে!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.1f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("কপি করুন", color = Color.White)
                            }
                        }
                    }
                }
            }

            // Status Indicator & Error Message
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage ?: "",
                        color = Color(0xFFF87171),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                } else {
                    Text(
                        text = when (state.status) {
                            ASRStatus.LISTENING -> "রেকর্ডিং হচ্ছে..."
                            ASRStatus.TRANSCRIBING -> "প্রসেসিং হচ্ছে..."
                            ASRStatus.ERROR -> "ত্রুটি ঘটেছে"
                            ASRStatus.IDLE -> "কথা বলতে বাটনে চাপুন"
                        },
                        color = when (state.status) {
                            ASRStatus.LISTENING -> activeRed
                            ASRStatus.TRANSCRIBING -> neonPurple
                            else -> Color.White.copy(alpha = 0.7f)
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }

            // Pulsing Record Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // Pulse ripples
                if (state.status == ASRStatus.LISTENING) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(activeRed.copy(alpha = 0.2f))
                    )
                } else if (state.status == ASRStatus.TRANSCRIBING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(105.dp),
                        color = neonPurple,
                        strokeWidth = 3.dp
                    )
                }

                // Main Button
                val buttonBrush = when (state.status) {
                    ASRStatus.LISTENING -> Brush.linearGradient(listOf(activeRed, Color(0xFFF87171)))
                    else -> Brush.linearGradient(listOf(neonCyan, neonPurple))
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(85.dp)
                        .clip(CircleShape)
                        .background(buttonBrush)
                        .clickable {
                            if (state.status == ASRStatus.LISTENING) {
                                viewModel.stopRecording()
                            } else if (state.status == ASRStatus.IDLE || state.status == ASRStatus.ERROR) {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasPermission) {
                                    viewModel.startRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                ) {
                    // Record icon
                    if (state.status == ASRStatus.LISTENING) {
                        // Stop square icon
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                        )
                    } else {
                        // Mic icon (rendered as shape)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 14.dp, height = 24.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color.White)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(width = 20.dp, height = 2.dp)
                                    .background(Color.White)
                            )
                        }
                    }
                }
            }
        }
    }
}
