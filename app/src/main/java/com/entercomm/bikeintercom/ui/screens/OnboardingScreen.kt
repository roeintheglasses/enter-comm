package com.entercomm.bikeintercom.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.onboarding.OnboardingManager
import com.entercomm.bikeintercom.ui.theme.*

/**
 * Onboarding step enum.
 */
enum class OnboardingStep {
    WELCOME,
    NICKNAME,
    CONNECTION_INFO,
    TUTORIAL,
}

/**
 * Main onboarding screen that manages the flow.
 */
@Composable
fun OnboardingScreen(onboardingManager: OnboardingManager, onComplete: (groupCode: String?, isCreator: Boolean) -> Unit) {
    var currentStep by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var nickname by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack),
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                slideInHorizontally { width -> width } + fadeIn() togetherWith
                    slideOutHorizontally { width -> -width } + fadeOut()
            },
            label = "onboarding",
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeScreen(
                    onNext = { currentStep = OnboardingStep.NICKNAME },
                )
                OnboardingStep.NICKNAME -> NicknameScreen(
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    onNext = {
                        onboardingManager.setNickname(nickname)
                        currentStep = OnboardingStep.CONNECTION_INFO
                    },
                    onBack = { currentStep = OnboardingStep.WELCOME },
                )
                OnboardingStep.CONNECTION_INFO -> ConnectionInfoScreen(
                    onNext = { currentStep = OnboardingStep.TUTORIAL },
                    onBack = { currentStep = OnboardingStep.NICKNAME },
                )
                OnboardingStep.TUTORIAL -> TutorialScreen(
                    onComplete = {
                        onboardingManager.completeOnboarding()
                        onComplete(null, false)
                    },
                    onBack = { currentStep = OnboardingStep.CONNECTION_INFO },
                )
            }
        }

        // Progress indicator
        OnboardingProgress(
            currentStep = currentStep,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
        )
    }
}

/**
 * Progress dots indicator.
 */
@Composable
private fun OnboardingProgress(currentStep: OnboardingStep, modifier: Modifier = Modifier) {
    val steps = listOf(
        OnboardingStep.WELCOME,
        OnboardingStep.NICKNAME,
        OnboardingStep.CONNECTION_INFO,
        OnboardingStep.TUTORIAL,
    )
    val currentIndex = when (currentStep) {
        OnboardingStep.WELCOME -> 0
        OnboardingStep.NICKNAME -> 1
        OnboardingStep.CONNECTION_INFO -> 2
        OnboardingStep.TUTORIAL -> 3
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.forEachIndexed { index, _ ->
            val isActive = index <= currentIndex
            val size by animateDpAsState(
                targetValue = if (index == currentIndex) 10.dp else 8.dp,
                label = "dotSize",
            )
            val color by animateColorAsState(
                targetValue = if (isActive) TechGreen else TextTertiary,
                label = "dotColor",
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Welcome screen - introduces the app.
 */
@Composable
private fun WelcomeScreen(onNext: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "welcome")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(0.2f))

        // App icon/logo
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            TechGreen.copy(alpha = 0.3f),
                            TechGreen.copy(alpha = 0.1f),
                            Color.Transparent,
                        ),
                    ),
                )
                .border(2.dp, TechGreen, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Headphones,
                contentDescription = null,
                tint = TechGreen,
                modifier = Modifier.size(56.dp),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Enter-Comm",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Mesh Intercom for Riders",
            style = MaterialTheme.typography.titleMedium,
            color = TechGreen,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connect with your group using WiFi Direct mesh networking. No internet or cell service required.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )

        Spacer(modifier = Modifier.weight(0.3f))

        // Features list
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FeatureItem(icon = Icons.Rounded.WifiOff, text = "Works offline - no internet needed")
            FeatureItem(icon = Icons.Rounded.Group, text = "Private group communication")
            FeatureItem(icon = Icons.Rounded.Security, text = "Secure mesh networking")
        }

        Spacer(modifier = Modifier.weight(0.3f))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = "Get Started",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun FeatureItem(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TechCyan,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

/**
 * Nickname setup screen.
 */
@Composable
private fun NicknameScreen(nickname: String, onNicknameChange: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = TechCyan,
            modifier = Modifier.size(64.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "What's your rider name?",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This is how other riders will see you",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = nickname,
            onValueChange = { onNicknameChange(it.take(20)) },
            label = { Text("Nickname") },
            placeholder = { Text("e.g., Speedy, Maverick, Road Runner") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TechCyan,
                unfocusedBorderColor = DarkBorder,
                focusedLabelColor = TechCyan,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (nickname.isNotBlank()) onNext()
                },
            ),
        )

        Text(
            text = "${nickname.length}/20 characters",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.weight(0.5f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = nickname.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Next")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

/**
 * Connection info screen - explains group functionality.
 */
@Composable
private fun ConnectionInfoScreen(onNext: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        Icon(
            imageVector = Icons.Rounded.Group,
            contentDescription = null,
            tint = TechOrange,
            modifier = Modifier.size(64.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Groups",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect with your group members",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Info card about groups
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GroupInfoItem(
                    icon = Icons.Rounded.Add,
                    title = "Create a Group",
                    description = "Start a new group and share the code with friends",
                    accentColor = TechGreen,
                )

                GroupInfoItem(
                    icon = Icons.Rounded.Login,
                    title = "Join a Group",
                    description = "Enter a code shared by your group leader",
                    accentColor = TechCyan,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = TechCyan.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "You can create or join a group anytime from the Group tab after setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TechCyan,
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Got it!")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun GroupInfoItem(icon: ImageVector, title: String, description: String, accentColor: Color) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/**
 * Tutorial screen - how to use the app.
 */
@Composable
private fun TutorialScreen(onComplete: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.weight(0.2f))

        Text(
            text = "How to Use",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Tutorial steps
        TutorialStep(
            number = 1,
            icon = Icons.Rounded.PowerSettingsNew,
            title = "Start the Network",
            description = "Tap the START button to connect to nearby riders in your group",
        )

        Spacer(modifier = Modifier.height(20.dp))

        TutorialStep(
            number = 2,
            icon = Icons.Rounded.Mic,
            title = "Push to Talk",
            description = "Press and hold the mic button to transmit your voice",
        )

        Spacer(modifier = Modifier.height(20.dp))

        TutorialStep(
            number = 3,
            icon = Icons.Rounded.Group,
            title = "Share Your Code",
            description = "Invite friends by sharing your group code from the Group tab",
        )

        Spacer(modifier = Modifier.height(20.dp))

        TutorialStep(
            number = 4,
            icon = Icons.Rounded.MyLocation,
            title = "Track Your Group",
            description = "Enable GPS in the Radar tab to see nearby group members",
        )

        Spacer(modifier = Modifier.weight(0.3f))

        Card(
            colors = CardDefaults.cardColors(containerColor = TechGreen.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = TechGreen,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "You're all set! Tap below to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TechGreen,
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.2f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            Button(
                onClick = onComplete,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Let's Go!")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun TutorialStep(number: Int, icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(DarkSurface)
                .border(1.dp, TechGreen.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = TechGreen,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
