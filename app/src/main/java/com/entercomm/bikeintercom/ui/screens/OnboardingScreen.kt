package com.entercomm.bikeintercom.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entercomm.bikeintercom.onboarding.ConnectionMode
import com.entercomm.bikeintercom.onboarding.OnboardingManager
import com.entercomm.bikeintercom.ui.theme.*

/**
 * Onboarding step enum.
 */
enum class OnboardingStep {
    WELCOME,
    NICKNAME,
    GROUP_CHOICE,
    CREATE_GROUP,
    JOIN_GROUP,
    TUTORIAL
}

/**
 * Main onboarding screen that manages the flow.
 */
@Composable
fun OnboardingScreen(
    onboardingManager: OnboardingManager,
    onComplete: (groupCode: String?, isCreator: Boolean) -> Unit
) {
    var currentStep by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var nickname by remember { mutableStateOf("") }
    var groupCode by remember { mutableStateOf("") }
    var isGroupCreator by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                slideInHorizontally { width -> width } + fadeIn() togetherWith
                    slideOutHorizontally { width -> -width } + fadeOut()
            },
            label = "onboarding"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeScreen(
                    onNext = { currentStep = OnboardingStep.NICKNAME }
                )
                OnboardingStep.NICKNAME -> NicknameScreen(
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    onNext = {
                        onboardingManager.setNickname(nickname)
                        currentStep = OnboardingStep.GROUP_CHOICE
                    },
                    onBack = { currentStep = OnboardingStep.WELCOME }
                )
                OnboardingStep.GROUP_CHOICE -> GroupChoiceScreen(
                    onCreateGroup = {
                        isGroupCreator = true
                        groupCode = onboardingManager.generateGroupCode()
                        currentStep = OnboardingStep.CREATE_GROUP
                    },
                    onJoinGroup = {
                        isGroupCreator = false
                        currentStep = OnboardingStep.JOIN_GROUP
                    },
                    onBack = { currentStep = OnboardingStep.NICKNAME }
                )
                OnboardingStep.CREATE_GROUP -> CreateGroupScreen(
                    groupCode = groupCode,
                    onNext = { currentStep = OnboardingStep.TUTORIAL },
                    onBack = { currentStep = OnboardingStep.GROUP_CHOICE }
                )
                OnboardingStep.JOIN_GROUP -> JoinGroupScreen(
                    groupCode = groupCode,
                    onGroupCodeChange = { groupCode = it },
                    onJoin = {
                        if (onboardingManager.isValidGroupCode(groupCode)) {
                            groupCode = onboardingManager.normalizeGroupCode(groupCode)
                            currentStep = OnboardingStep.TUTORIAL
                        }
                    },
                    onBack = { currentStep = OnboardingStep.GROUP_CHOICE },
                    isValidCode = onboardingManager.isValidGroupCode(groupCode)
                )
                OnboardingStep.TUTORIAL -> TutorialScreen(
                    onComplete = {
                        onboardingManager.setCurrentGroupCode(groupCode)
                        onboardingManager.completeOnboarding()
                        onComplete(groupCode, isGroupCreator)
                    },
                    onBack = {
                        currentStep = if (isGroupCreator) OnboardingStep.CREATE_GROUP else OnboardingStep.JOIN_GROUP
                    }
                )
            }
        }

        // Progress indicator
        OnboardingProgress(
            currentStep = currentStep,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        )
    }
}

/**
 * Progress dots indicator.
 */
@Composable
private fun OnboardingProgress(
    currentStep: OnboardingStep,
    modifier: Modifier = Modifier
) {
    val steps = listOf(
        OnboardingStep.WELCOME,
        OnboardingStep.NICKNAME,
        OnboardingStep.GROUP_CHOICE,
        OnboardingStep.TUTORIAL
    )
    val currentIndex = when (currentStep) {
        OnboardingStep.WELCOME -> 0
        OnboardingStep.NICKNAME -> 1
        OnboardingStep.GROUP_CHOICE, OnboardingStep.CREATE_GROUP, OnboardingStep.JOIN_GROUP -> 2
        OnboardingStep.TUTORIAL -> 3
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEachIndexed { index, _ ->
            val isActive = index <= currentIndex
            val size by animateDpAsState(
                targetValue = if (index == currentIndex) 10.dp else 8.dp,
                label = "dotSize"
            )
            val color by animateColorAsState(
                targetValue = if (isActive) TechGreen else TextTertiary,
                label = "dotColor"
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * Welcome screen - introduces the app.
 */
@Composable
private fun WelcomeScreen(
    onNext: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "welcome")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
                            Color.Transparent
                        )
                    )
                )
                .border(2.dp, TechGreen, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Headphones,
                contentDescription = null,
                tint = TechGreen,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Enter-Comm",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Mesh Intercom for Riders",
            style = MaterialTheme.typography.titleMedium,
            color = TechGreen
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connect with your riding group using WiFi Direct mesh networking. No internet or cell service required.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.weight(0.3f))

        // Features list
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "Get Started",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TechCyan,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

/**
 * Nickname setup screen.
 */
@Composable
private fun NicknameScreen(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = TechCyan,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "What's your rider name?",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This is how other riders will see you",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
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
                focusedLabelColor = TechCyan
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (nickname.isNotBlank()) onNext()
                }
            )
        )

        Text(
            text = "${nickname.length}/20 characters",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.weight(0.5f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
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
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Next")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

/**
 * Group choice screen - create or join.
 */
@Composable
private fun GroupChoiceScreen(
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        Icon(
            imageVector = Icons.Rounded.Group,
            contentDescription = null,
            tint = TechOrange,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Join a Riding Group",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "You'll only hear riders in your group",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Create group option
        GroupOptionCard(
            icon = Icons.Rounded.Add,
            title = "Create a Group",
            description = "Start a new group and share the code with your friends",
            accentColor = TechGreen,
            onClick = onCreateGroup
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "or",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Join group option
        GroupOptionCard(
            icon = Icons.Rounded.Login,
            title = "Join a Group",
            description = "Enter a group code shared by your ride leader",
            accentColor = TechCyan,
            onClick = onJoinGroup
        )

        Spacer(modifier = Modifier.weight(0.5f))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Back")
        }
    }
}

@Composable
private fun GroupOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = accentColor
            )
        }
    }
}

/**
 * Create group screen - shows the generated code.
 */
@Composable
private fun CreateGroupScreen(
    groupCode: String,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = TechGreen,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Your Group Code",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Share this code with your riding group",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Group code display
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = groupCode,
                    style = MaterialTheme.typography.displaySmall,
                    color = TechGreen,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(groupCode))
                        copied = true
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (copied) "Copied!" else "Copy Code")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = TechCyan.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Other riders can join by entering this code. You'll be the group leader.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TechCyan
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
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
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Continue")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

/**
 * Join group screen - enter code.
 */
@Composable
private fun JoinGroupScreen(
    groupCode: String,
    onGroupCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
    isValidCode: Boolean
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        Icon(
            imageVector = Icons.Rounded.Login,
            contentDescription = null,
            tint = TechCyan,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Enter Group Code",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Ask your ride leader for the code",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = groupCode,
            onValueChange = { onGroupCodeChange(it.uppercase().take(7)) },
            label = { Text("Group Code") },
            placeholder = { Text("XXXX-XX") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (groupCode.isNotEmpty() && isValidCode) TechGreen else TechCyan,
                unfocusedBorderColor = DarkBorder,
                focusedLabelColor = TechCyan
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (isValidCode) onJoin()
                }
            ),
            trailingIcon = {
                if (groupCode.isNotEmpty()) {
                    Icon(
                        imageVector = if (isValidCode) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (isValidCode) TechGreen else TechRed
                    )
                }
            }
        )

        if (groupCode.isNotEmpty() && !isValidCode) {
            Text(
                text = "Invalid code format. Codes are 6 characters (e.g., ABCD-EF)",
                style = MaterialTheme.typography.labelSmall,
                color = TechRed,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.weight(0.5f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back")
            }

            Button(
                onClick = onJoin,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = isValidCode,
                colors = ButtonDefaults.buttonColors(containerColor = TechGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Join")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

/**
 * Tutorial screen - how to use the app.
 */
@Composable
private fun TutorialScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.2f))

        Text(
            text = "How to Use",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Tutorial steps
        TutorialStep(
            number = 1,
            icon = Icons.Rounded.PowerSettingsNew,
            title = "Start the Network",
            description = "Tap the START button to connect to nearby riders in your group"
        )

        Spacer(modifier = Modifier.height(20.dp))

        TutorialStep(
            number = 2,
            icon = Icons.Rounded.Mic,
            title = "Push to Talk",
            description = "Press and hold the mic button to transmit your voice"
        )

        Spacer(modifier = Modifier.height(20.dp))

        TutorialStep(
            number = 3,
            icon = Icons.Rounded.Group,
            title = "Share Your Code",
            description = "Invite friends by sharing your group code from the Group tab"
        )

        Spacer(modifier = Modifier.height(20.dp))

        TutorialStep(
            number = 4,
            icon = Icons.Rounded.MyLocation,
            title = "Track Your Group",
            description = "Enable GPS in the Radar tab to see nearby group members"
        )

        Spacer(modifier = Modifier.weight(0.3f))

        Card(
            colors = CardDefaults.cardColors(containerColor = TechGreen.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = TechGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "You're all set! Tap below to start riding.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TechGreen
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.2f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
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
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Let's Ride!")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Rounded.TwoWheeler, contentDescription = null)
            }
        }
    }
}

@Composable
private fun TutorialStep(
    number: Int,
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(DarkSurface)
                .border(1.dp, TechGreen.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = TechGreen,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TechCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
