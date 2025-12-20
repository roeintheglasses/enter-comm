package com.entercomm.bikeintercom.mesh

import org.junit.Assert.*
import org.junit.Test

class GroupEventTest {

    // === GroupEvent Sealed Class Structure Tests ===

    @Test
    fun `all expected GroupEvent types can be instantiated`() {
        // Create test data
        val testMember = GroupMember(nodeId = "node-123", nickname = "TestRider")
        val testGroup = MeshGroup(
            groupId = "group-abc",
            groupName = "Test Group",
            channelNumber = 1,
            ownerId = "owner-123",
        )

        // Verify all expected GroupEvent types exist and can be created
        val allEventTypes: List<GroupEvent> = listOf(
            // Regular events
            GroupEvent.MemberJoined(testMember),
            GroupEvent.MemberLeft("node-123", "TestRider"),
            GroupEvent.MemberKicked("node-123", "TestRider"),
            GroupEvent.MemberBanned("node-123", "TestRider"),
            GroupEvent.GroupCreated(testGroup),
            GroupEvent.GroupJoined(testGroup),
            GroupEvent.GroupLeft("group-abc"),
            GroupEvent.NicknameChanged("node-123", "NewNickname"),
            GroupEvent.ChannelChanged(5),
            // Typed error events
            GroupEvent.GroupNotFound("group-abc"),
            GroupEvent.Banned("You are banned"),
            GroupEvent.WrongPassword("group-abc"),
            GroupEvent.GroupIsFull("group-abc"),
            GroupEvent.PermissionDenied("kick members"),
            GroupEvent.Kicked("You were kicked"),
            // Deprecated error event (still valid)
            @Suppress("DEPRECATION")
            GroupEvent.Error("Generic error"),
        )

        assertEquals("Should have 16 GroupEvent types", 16, allEventTypes.size)

        // Verify all are instances of GroupEvent
        for (event in allEventTypes) {
            assertTrue(
                "All types should be instances of GroupEvent",
                event is GroupEvent,
            )
        }
    }

    // === MemberJoined Event Tests ===

    @Test
    fun `MemberJoined stores member correctly`() {
        val member = GroupMember(
            nodeId = "node-abc",
            nickname = "RiderOne",
            role = MemberRole.MEMBER,
        )
        val event = GroupEvent.MemberJoined(member)

        assertEquals(member, event.member)
        assertEquals("node-abc", event.member.nodeId)
        assertEquals("RiderOne", event.member.nickname)
    }

    @Test
    fun `MemberJoined is instance of GroupEvent`() {
        val member = GroupMember(nodeId = "node-123", nickname = "Rider")
        val event = GroupEvent.MemberJoined(member)

        assertTrue(event is GroupEvent)
    }

    // === MemberLeft Event Tests ===

    @Test
    fun `MemberLeft stores nodeId and nickname correctly`() {
        val nodeId = "node-xyz"
        val nickname = "LeavingRider"
        val event = GroupEvent.MemberLeft(nodeId, nickname)

        assertEquals(nodeId, event.nodeId)
        assertEquals(nickname, event.nickname)
    }

    @Test
    fun `MemberLeft is instance of GroupEvent`() {
        val event = GroupEvent.MemberLeft("node-123", "Rider")

        assertTrue(event is GroupEvent)
    }

    // === MemberKicked Event Tests ===

    @Test
    fun `MemberKicked stores nodeId and nickname correctly`() {
        val nodeId = "node-kicked"
        val nickname = "KickedRider"
        val event = GroupEvent.MemberKicked(nodeId, nickname)

        assertEquals(nodeId, event.nodeId)
        assertEquals(nickname, event.nickname)
    }

    @Test
    fun `MemberKicked is instance of GroupEvent`() {
        val event = GroupEvent.MemberKicked("node-123", "Rider")

        assertTrue(event is GroupEvent)
    }

    // === MemberBanned Event Tests ===

    @Test
    fun `MemberBanned stores nodeId and nickname correctly`() {
        val nodeId = "node-banned"
        val nickname = "BannedRider"
        val event = GroupEvent.MemberBanned(nodeId, nickname)

        assertEquals(nodeId, event.nodeId)
        assertEquals(nickname, event.nickname)
    }

    @Test
    fun `MemberBanned is instance of GroupEvent`() {
        val event = GroupEvent.MemberBanned("node-123", "Rider")

        assertTrue(event is GroupEvent)
    }

    // === GroupCreated Event Tests ===

    @Test
    fun `GroupCreated stores group correctly`() {
        val group = MeshGroup(
            groupId = "new-group",
            groupName = "New Group",
            channelNumber = 3,
            ownerId = "owner-123",
            maxSize = 8,
        )
        val event = GroupEvent.GroupCreated(group)

        assertEquals(group, event.group)
        assertEquals("new-group", event.group.groupId)
        assertEquals("New Group", event.group.groupName)
    }

    @Test
    fun `GroupCreated is instance of GroupEvent`() {
        val group = MeshGroup(
            groupId = "test",
            groupName = "Test",
            channelNumber = 1,
            ownerId = "owner",
        )
        val event = GroupEvent.GroupCreated(group)

        assertTrue(event is GroupEvent)
    }

    // === GroupJoined Event Tests ===

    @Test
    fun `GroupJoined stores group correctly`() {
        val group = MeshGroup(
            groupId = "joined-group",
            groupName = "Joined Group",
            channelNumber = 5,
            ownerId = "owner-456",
        )
        val event = GroupEvent.GroupJoined(group)

        assertEquals(group, event.group)
        assertEquals("joined-group", event.group.groupId)
    }

    @Test
    fun `GroupJoined is instance of GroupEvent`() {
        val group = MeshGroup(
            groupId = "test",
            groupName = "Test",
            channelNumber = 1,
            ownerId = "owner",
        )
        val event = GroupEvent.GroupJoined(group)

        assertTrue(event is GroupEvent)
    }

    // === GroupLeft Event Tests ===

    @Test
    fun `GroupLeft stores groupId correctly`() {
        val groupId = "left-group-123"
        val event = GroupEvent.GroupLeft(groupId)

        assertEquals(groupId, event.groupId)
    }

    @Test
    fun `GroupLeft is instance of GroupEvent`() {
        val event = GroupEvent.GroupLeft("group-123")

        assertTrue(event is GroupEvent)
    }

    // === NicknameChanged Event Tests ===

    @Test
    fun `NicknameChanged stores nodeId and newNickname correctly`() {
        val nodeId = "node-123"
        val newNickname = "SpeedRider"
        val event = GroupEvent.NicknameChanged(nodeId, newNickname)

        assertEquals(nodeId, event.nodeId)
        assertEquals(newNickname, event.newNickname)
    }

    @Test
    fun `NicknameChanged is instance of GroupEvent`() {
        val event = GroupEvent.NicknameChanged("node-123", "NewName")

        assertTrue(event is GroupEvent)
    }

    // === ChannelChanged Event Tests ===

    @Test
    fun `ChannelChanged stores newChannel correctly`() {
        val newChannel = 7
        val event = GroupEvent.ChannelChanged(newChannel)

        assertEquals(newChannel, event.newChannel)
    }

    @Test
    fun `ChannelChanged is instance of GroupEvent`() {
        val event = GroupEvent.ChannelChanged(5)

        assertTrue(event is GroupEvent)
    }

    @Test
    fun `ChannelChanged accepts valid channel range`() {
        val minChannel = GroupEvent.ChannelChanged(1)
        val maxChannel = GroupEvent.ChannelChanged(10)

        assertEquals(1, minChannel.newChannel)
        assertEquals(10, maxChannel.newChannel)
    }

    // === GroupNotFound Error Event Tests ===

    @Test
    fun `GroupNotFound stores groupId correctly`() {
        val groupId = "missing-group"
        val event = GroupEvent.GroupNotFound(groupId)

        assertEquals(groupId, event.groupId)
    }

    @Test
    fun `GroupNotFound is instance of GroupEvent`() {
        val event = GroupEvent.GroupNotFound("group-abc")

        assertTrue(event is GroupEvent)
    }

    @Test
    fun `GroupNotFound with empty groupId is valid`() {
        val event = GroupEvent.GroupNotFound("")

        assertEquals("", event.groupId)
    }

    // === Banned Error Event Tests ===

    @Test
    fun `Banned stores message correctly`() {
        val message = "You are banned from this group"
        val event = GroupEvent.Banned(message)

        assertEquals(message, event.message)
    }

    @Test
    fun `Banned is instance of GroupEvent`() {
        val event = GroupEvent.Banned("Banned")

        assertTrue(event is GroupEvent)
    }

    @Test
    fun `Banned with empty message is valid`() {
        val event = GroupEvent.Banned("")

        assertEquals("", event.message)
    }

    // === WrongPassword Error Event Tests ===

    @Test
    fun `WrongPassword stores groupId correctly`() {
        val groupId = "protected-group"
        val event = GroupEvent.WrongPassword(groupId)

        assertEquals(groupId, event.groupId)
    }

    @Test
    fun `WrongPassword is instance of GroupEvent`() {
        val event = GroupEvent.WrongPassword("group-123")

        assertTrue(event is GroupEvent)
    }

    // === GroupIsFull Error Event Tests ===

    @Test
    fun `GroupIsFull stores groupId correctly`() {
        val groupId = "full-group"
        val event = GroupEvent.GroupIsFull(groupId)

        assertEquals(groupId, event.groupId)
    }

    @Test
    fun `GroupIsFull is instance of GroupEvent`() {
        val event = GroupEvent.GroupIsFull("group-abc")

        assertTrue(event is GroupEvent)
    }

    // === PermissionDenied Error Event Tests ===

    @Test
    fun `PermissionDenied stores action correctly`() {
        val action = "kick members"
        val event = GroupEvent.PermissionDenied(action)

        assertEquals(action, event.action)
    }

    @Test
    fun `PermissionDenied is instance of GroupEvent`() {
        val event = GroupEvent.PermissionDenied("ban members")

        assertTrue(event is GroupEvent)
    }

    @Test
    fun `PermissionDenied with various actions is valid`() {
        val actions = listOf("kick members", "ban members", "change channel", "kick yourself", "ban yourself")

        for (action in actions) {
            val event = GroupEvent.PermissionDenied(action)
            assertEquals(action, event.action)
        }
    }

    // === Kicked Error Event Tests ===

    @Test
    fun `Kicked stores message correctly`() {
        val message = "You were kicked from the group"
        val event = GroupEvent.Kicked(message)

        assertEquals(message, event.message)
    }

    @Test
    fun `Kicked is instance of GroupEvent`() {
        val event = GroupEvent.Kicked("Kicked out")

        assertTrue(event is GroupEvent)
    }

    @Test
    fun `Kicked with empty message is valid`() {
        val event = GroupEvent.Kicked("")

        assertEquals("", event.message)
    }

    // === Deprecated Error Event Tests ===

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated Error event still works`() {
        val message = "Generic error message"
        val event = GroupEvent.Error(message)

        assertEquals(message, event.message)
        assertTrue(event is GroupEvent)
    }

    // === Pattern Matching Tests ===

    @Test
    @Suppress("CyclomaticComplexMethod") // Complex when expression is intentional for exhaustive testing
    fun `GroupEvent types can be matched with when expression`() {
        val testMember = GroupMember(nodeId = "node-123", nickname = "Rider")
        val testGroup = MeshGroup(
            groupId = "group-abc",
            groupName = "Test",
            channelNumber = 1,
            ownerId = "owner",
        )

        @Suppress("DEPRECATION")
        val events: List<GroupEvent> = listOf(
            GroupEvent.MemberJoined(testMember),
            GroupEvent.MemberLeft("node-123", "Rider"),
            GroupEvent.MemberKicked("node-123", "Rider"),
            GroupEvent.MemberBanned("node-123", "Rider"),
            GroupEvent.GroupCreated(testGroup),
            GroupEvent.GroupJoined(testGroup),
            GroupEvent.GroupLeft("group-abc"),
            GroupEvent.NicknameChanged("node-123", "NewName"),
            GroupEvent.ChannelChanged(5),
            GroupEvent.GroupNotFound("group-abc"),
            GroupEvent.Banned("Banned"),
            GroupEvent.WrongPassword("group-abc"),
            GroupEvent.GroupIsFull("group-abc"),
            GroupEvent.PermissionDenied("action"),
            GroupEvent.Kicked("Kicked"),
            GroupEvent.Error("Error"),
        )

        for (event in events) {
            @Suppress("DEPRECATION")
            val result = when (event) {
                is GroupEvent.MemberJoined -> "MemberJoined"
                is GroupEvent.MemberLeft -> "MemberLeft"
                is GroupEvent.MemberKicked -> "MemberKicked"
                is GroupEvent.MemberBanned -> "MemberBanned"
                is GroupEvent.GroupCreated -> "GroupCreated"
                is GroupEvent.GroupJoined -> "GroupJoined"
                is GroupEvent.GroupLeft -> "GroupLeft"
                is GroupEvent.NicknameChanged -> "NicknameChanged"
                is GroupEvent.ChannelChanged -> "ChannelChanged"
                is GroupEvent.GroupNotFound -> "GroupNotFound"
                is GroupEvent.Banned -> "Banned"
                is GroupEvent.WrongPassword -> "WrongPassword"
                is GroupEvent.GroupIsFull -> "GroupIsFull"
                is GroupEvent.PermissionDenied -> "PermissionDenied"
                is GroupEvent.Kicked -> "Kicked"
                is GroupEvent.Error -> "Error"
            }

            assertNotNull("Pattern matching should work for all types", result)
        }
    }

    @Test
    fun `typed error events can be distinguished from regular events`() {
        val testMember = GroupMember(nodeId = "node-123", nickname = "Rider")
        val testGroup = MeshGroup(
            groupId = "group-abc",
            groupName = "Test",
            channelNumber = 1,
            ownerId = "owner",
        )

        // Regular events
        val regularEvents: List<GroupEvent> = listOf(
            GroupEvent.MemberJoined(testMember),
            GroupEvent.MemberLeft("node-123", "Rider"),
            GroupEvent.GroupCreated(testGroup),
            GroupEvent.GroupJoined(testGroup),
            GroupEvent.GroupLeft("group-abc"),
            GroupEvent.NicknameChanged("node-123", "NewName"),
            GroupEvent.ChannelChanged(5),
        )

        // Typed error events
        val errorEvents: List<GroupEvent> = listOf(
            GroupEvent.GroupNotFound("group-abc"),
            GroupEvent.Banned("Banned"),
            GroupEvent.WrongPassword("group-abc"),
            GroupEvent.GroupIsFull("group-abc"),
            GroupEvent.PermissionDenied("action"),
            GroupEvent.Kicked("Kicked"),
        )

        // Verify we can distinguish them
        fun isErrorEvent(event: GroupEvent): Boolean = when (event) {
            is GroupEvent.GroupNotFound -> true
            is GroupEvent.Banned -> true
            is GroupEvent.WrongPassword -> true
            is GroupEvent.GroupIsFull -> true
            is GroupEvent.PermissionDenied -> true
            is GroupEvent.Kicked -> true
            else -> false
        }

        for (event in regularEvents) {
            assertFalse("Regular event should not be classified as error: $event", isErrorEvent(event))
        }

        for (event in errorEvents) {
            assertTrue("Error event should be classified as error: $event", isErrorEvent(event))
        }
    }

    // === Edge Cases ===

    @Test
    fun `GroupEvent with special characters in strings is valid`() {
        val specialChars = "!@#$%^&*()_+-={}|[]\\:\";'<>?,./`~"

        val bannedEvent = GroupEvent.Banned(specialChars)
        val kickedEvent = GroupEvent.Kicked(specialChars)
        val permissionEvent = GroupEvent.PermissionDenied(specialChars)

        assertEquals(specialChars, bannedEvent.message)
        assertEquals(specialChars, kickedEvent.message)
        assertEquals(specialChars, permissionEvent.action)
    }

    @Test
    fun `GroupEvent with unicode in strings is valid`() {
        val unicodeMessage = "Error with unicode: 中文 евро 🏍️"

        val bannedEvent = GroupEvent.Banned(unicodeMessage)
        val kickedEvent = GroupEvent.Kicked(unicodeMessage)

        assertEquals(unicodeMessage, bannedEvent.message)
        assertEquals(unicodeMessage, kickedEvent.message)
    }

    @Test
    fun `GroupEvent with multiline message is valid`() {
        val multilineMessage = "Error on line 1\nError on line 2\nError on line 3"

        val bannedEvent = GroupEvent.Banned(multilineMessage)
        val kickedEvent = GroupEvent.Kicked(multilineMessage)

        assertEquals(multilineMessage, bannedEvent.message)
        assertEquals(multilineMessage, kickedEvent.message)
    }

    @Test
    fun `MemberKicked and MemberBanned are distinct events`() {
        val nodeId = "node-123"
        val nickname = "Rider"

        val kickedEvent = GroupEvent.MemberKicked(nodeId, nickname)
        val bannedEvent = GroupEvent.MemberBanned(nodeId, nickname)

        // Both have same data but are different types
        assertEquals(kickedEvent.nodeId, bannedEvent.nodeId)
        assertEquals(kickedEvent.nickname, bannedEvent.nickname)
        assertNotEquals(kickedEvent::class, bannedEvent::class)
    }

    @Test
    fun `GroupCreated and GroupJoined are distinct events with same group`() {
        val group = MeshGroup(
            groupId = "group-123",
            groupName = "Test Group",
            channelNumber = 1,
            ownerId = "owner-123",
        )

        val createdEvent = GroupEvent.GroupCreated(group)
        val joinedEvent = GroupEvent.GroupJoined(group)

        // Both have same group but are different types
        assertEquals(createdEvent.group, joinedEvent.group)
        assertNotEquals(createdEvent::class, joinedEvent::class)
    }

    @Test
    fun `Kicked event and Banned event are distinct despite similar purpose`() {
        val message = "You were removed from the group"

        val kickedEvent = GroupEvent.Kicked(message)
        val bannedEvent = GroupEvent.Banned(message)

        // Both have same message but are different types
        assertEquals(kickedEvent.message, bannedEvent.message)
        assertNotEquals(kickedEvent::class, bannedEvent::class)
    }
}
