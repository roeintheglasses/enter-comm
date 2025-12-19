# Expose Accessibility Settings in Android Settings Screen

## Overview

The app has comprehensive AccessibilityManager features (voice feedback, enhanced haptics, large text mode, high contrast mode, volume button PTT) but the Settings screen only shows Profile info and Network Topology - none of the accessibility options are exposed to users.

## Rationale

Accessibility features are implemented in code (AccessibilityManager.kt) but users have no way to enable them. This is especially important for riders who may need hands-free operation (volumeButtonPtt), voice feedback while riding, or bone conduction headset optimization.

---
*This spec was created from ideation and is pending detailed specification.*
