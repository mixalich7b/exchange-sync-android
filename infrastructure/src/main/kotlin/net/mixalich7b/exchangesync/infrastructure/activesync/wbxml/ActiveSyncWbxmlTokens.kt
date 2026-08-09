package net.mixalich7b.exchangesync.infrastructure.activesync.wbxml

data class WbxmlTag(
    val codePage: Int,
    val token: Int,
    val name: String,
) {
    init {
        require(codePage in 0..255)
        require(token in 0x05..0x3F)
        require(name.isNotBlank())
    }
}

object ActiveSyncWbxmlTokens {
    object AirSync {
        val SYNC = tag(0, 0x05, "Sync")
        val RESPONSES = tag(0, 0x06, "Responses")
        val ADD = tag(0, 0x07, "Add")
        val CHANGE = tag(0, 0x08, "Change")
        val DELETE = tag(0, 0x09, "Delete")
        val FETCH = tag(0, 0x0A, "Fetch")
        val SYNC_KEY = tag(0, 0x0B, "SyncKey")
        val CLIENT_ID = tag(0, 0x0C, "ClientId")
        val SERVER_ID = tag(0, 0x0D, "ServerId")
        val STATUS = tag(0, 0x0E, "Status")
        val COLLECTION = tag(0, 0x0F, "Collection")
        val CLASS = tag(0, 0x10, "Class")
        val COLLECTION_ID = tag(0, 0x12, "CollectionId")
        val GET_CHANGES = tag(0, 0x13, "GetChanges")
        val MORE_AVAILABLE = tag(0, 0x14, "MoreAvailable")
        val WINDOW_SIZE = tag(0, 0x15, "WindowSize")
        val COMMANDS = tag(0, 0x16, "Commands")
        val OPTIONS = tag(0, 0x17, "Options")
        val FILTER_TYPE = tag(0, 0x18, "FilterType")
        val TRUNCATION = tag(0, 0x19, "Truncation")
        val CONFLICT = tag(0, 0x1B, "Conflict")
        val COLLECTIONS = tag(0, 0x1C, "Collections")
        val APPLICATION_DATA = tag(0, 0x1D, "ApplicationData")
        val DELETES_AS_MOVES = tag(0, 0x1E, "DeletesAsMoves")
        val SUPPORTED = tag(0, 0x20, "Supported")
        val SOFT_DELETE = tag(0, 0x21, "SoftDelete")
        val MIME_SUPPORT = tag(0, 0x22, "MIMESupport")
        val MIME_TRUNCATION = tag(0, 0x23, "MIMETruncation")
        val WAIT = tag(0, 0x24, "Wait")
        val LIMIT = tag(0, 0x25, "Limit")
        val PARTIAL = tag(0, 0x26, "Partial")
        val CONVERSATION_MODE = tag(0, 0x27, "ConversationMode")
        val MAX_ITEMS = tag(0, 0x28, "MaxItems")
        val HEARTBEAT_INTERVAL = tag(0, 0x29, "HeartbeatInterval")

        internal val all =
            listOf(
                SYNC,
                RESPONSES,
                ADD,
                CHANGE,
                DELETE,
                FETCH,
                SYNC_KEY,
                CLIENT_ID,
                SERVER_ID,
                STATUS,
                COLLECTION,
                CLASS,
                COLLECTION_ID,
                GET_CHANGES,
                MORE_AVAILABLE,
                WINDOW_SIZE,
                COMMANDS,
                OPTIONS,
                FILTER_TYPE,
                TRUNCATION,
                CONFLICT,
                COLLECTIONS,
                APPLICATION_DATA,
                DELETES_AS_MOVES,
                SUPPORTED,
                SOFT_DELETE,
                MIME_SUPPORT,
                MIME_TRUNCATION,
                WAIT,
                LIMIT,
                PARTIAL,
                CONVERSATION_MODE,
                MAX_ITEMS,
                HEARTBEAT_INTERVAL,
            )
    }

    object Calendar {
        val TIMEZONE = tag(4, 0x05, "Timezone")
        val ALL_DAY_EVENT = tag(4, 0x06, "AllDayEvent")
        val ATTENDEES = tag(4, 0x07, "Attendees")
        val ATTENDEE = tag(4, 0x08, "Attendee")
        val EMAIL = tag(4, 0x09, "Email")
        val NAME = tag(4, 0x0A, "Name")
        val BODY = tag(4, 0x0B, "Body")
        val BODY_TRUNCATED = tag(4, 0x0C, "BodyTruncated")
        val BUSY_STATUS = tag(4, 0x0D, "BusyStatus")
        val CATEGORIES = tag(4, 0x0E, "Categories")
        val CATEGORY = tag(4, 0x0F, "Category")
        val DT_STAMP = tag(4, 0x11, "DtStamp")
        val END_TIME = tag(4, 0x12, "EndTime")
        val EXCEPTION = tag(4, 0x13, "Exception")
        val EXCEPTIONS = tag(4, 0x14, "Exceptions")
        val DELETED = tag(4, 0x15, "Deleted")
        val EXCEPTION_START_TIME = tag(4, 0x16, "ExceptionStartTime")
        val LOCATION = tag(4, 0x17, "Location")
        val MEETING_STATUS = tag(4, 0x18, "MeetingStatus")
        val ORGANIZER_EMAIL = tag(4, 0x19, "OrganizerEmail")
        val ORGANIZER_NAME = tag(4, 0x1A, "OrganizerName")
        val RECURRENCE = tag(4, 0x1B, "Recurrence")
        val TYPE = tag(4, 0x1C, "Type")
        val UNTIL = tag(4, 0x1D, "Until")
        val OCCURRENCES = tag(4, 0x1E, "Occurrences")
        val INTERVAL = tag(4, 0x1F, "Interval")
        val DAY_OF_WEEK = tag(4, 0x20, "DayOfWeek")
        val DAY_OF_MONTH = tag(4, 0x21, "DayOfMonth")
        val WEEK_OF_MONTH = tag(4, 0x22, "WeekOfMonth")
        val MONTH_OF_YEAR = tag(4, 0x23, "MonthOfYear")
        val REMINDER = tag(4, 0x24, "Reminder")
        val SENSITIVITY = tag(4, 0x25, "Sensitivity")
        val SUBJECT = tag(4, 0x26, "Subject")
        val START_TIME = tag(4, 0x27, "StartTime")
        val UID = tag(4, 0x28, "UID")
        val ATTENDEE_STATUS = tag(4, 0x29, "AttendeeStatus")
        val ATTENDEE_TYPE = tag(4, 0x2A, "AttendeeType")
        val DISALLOW_NEW_TIME_PROPOSAL = tag(4, 0x33, "DisallowNewTimeProposal")
        val RESPONSE_REQUESTED = tag(4, 0x34, "ResponseRequested")
        val APPOINTMENT_REPLY_TIME = tag(4, 0x35, "AppointmentReplyTime")
        val RESPONSE_TYPE = tag(4, 0x36, "ResponseType")
        val CALENDAR_TYPE = tag(4, 0x37, "CalendarType")
        val IS_LEAP_MONTH = tag(4, 0x38, "IsLeapMonth")
        val FIRST_DAY_OF_WEEK = tag(4, 0x39, "FirstDayOfWeek")
        val ONLINE_MEETING_CONF_LINK = tag(4, 0x3A, "OnlineMeetingConfLink")
        val ONLINE_MEETING_EXTERNAL_LINK = tag(4, 0x3B, "OnlineMeetingExternalLink")
        val CLIENT_UID = tag(4, 0x3C, "ClientUid")

        internal val all =
            listOf(
                TIMEZONE,
                ALL_DAY_EVENT,
                ATTENDEES,
                ATTENDEE,
                EMAIL,
                NAME,
                BODY,
                BODY_TRUNCATED,
                BUSY_STATUS,
                CATEGORIES,
                CATEGORY,
                DT_STAMP,
                END_TIME,
                EXCEPTION,
                EXCEPTIONS,
                DELETED,
                EXCEPTION_START_TIME,
                LOCATION,
                MEETING_STATUS,
                ORGANIZER_EMAIL,
                ORGANIZER_NAME,
                RECURRENCE,
                TYPE,
                UNTIL,
                OCCURRENCES,
                INTERVAL,
                DAY_OF_WEEK,
                DAY_OF_MONTH,
                WEEK_OF_MONTH,
                MONTH_OF_YEAR,
                REMINDER,
                SENSITIVITY,
                SUBJECT,
                START_TIME,
                UID,
                ATTENDEE_STATUS,
                ATTENDEE_TYPE,
                DISALLOW_NEW_TIME_PROPOSAL,
                RESPONSE_REQUESTED,
                APPOINTMENT_REPLY_TIME,
                RESPONSE_TYPE,
                CALENDAR_TYPE,
                IS_LEAP_MONTH,
                FIRST_DAY_OF_WEEK,
                ONLINE_MEETING_CONF_LINK,
                ONLINE_MEETING_EXTERNAL_LINK,
                CLIENT_UID,
            )
    }

    object FolderHierarchy {
        val DISPLAY_NAME = tag(7, 0x07, "DisplayName")
        val SERVER_ID = tag(7, 0x08, "ServerId")
        val PARENT_ID = tag(7, 0x09, "ParentId")
        val TYPE = tag(7, 0x0A, "Type")
        val STATUS = tag(7, 0x0C, "Status")
        val CHANGES = tag(7, 0x0E, "Changes")
        val ADD = tag(7, 0x0F, "Add")
        val DELETE = tag(7, 0x10, "Delete")
        val UPDATE = tag(7, 0x11, "Update")
        val SYNC_KEY = tag(7, 0x12, "SyncKey")
        val FOLDER_CREATE = tag(7, 0x13, "FolderCreate")
        val FOLDER_DELETE = tag(7, 0x14, "FolderDelete")
        val FOLDER_UPDATE = tag(7, 0x15, "FolderUpdate")
        val FOLDER_SYNC = tag(7, 0x16, "FolderSync")
        val COUNT = tag(7, 0x17, "Count")

        internal val all =
            listOf(
                DISPLAY_NAME,
                SERVER_ID,
                PARENT_ID,
                TYPE,
                STATUS,
                CHANGES,
                ADD,
                DELETE,
                UPDATE,
                SYNC_KEY,
                FOLDER_CREATE,
                FOLDER_DELETE,
                FOLDER_UPDATE,
                FOLDER_SYNC,
                COUNT,
            )
    }

    object AirSyncBase {
        val BODY_PREFERENCE = tag(17, 0x05, "BodyPreference")
        val TYPE = tag(17, 0x06, "Type")
        val TRUNCATION_SIZE = tag(17, 0x07, "TruncationSize")
        val ALL_OR_NONE = tag(17, 0x08, "AllOrNone")
        val BODY = tag(17, 0x0A, "Body")
        val DATA = tag(17, 0x0B, "Data")
        val ESTIMATED_DATA_SIZE = tag(17, 0x0C, "EstimatedDataSize")
        val TRUNCATED = tag(17, 0x0D, "Truncated")
        val ATTACHMENTS = tag(17, 0x0E, "Attachments")
        val ATTACHMENT = tag(17, 0x0F, "Attachment")
        val DISPLAY_NAME = tag(17, 0x10, "DisplayName")
        val FILE_REFERENCE = tag(17, 0x11, "FileReference")
        val METHOD = tag(17, 0x12, "Method")
        val CONTENT_ID = tag(17, 0x13, "ContentId")
        val CONTENT_LOCATION = tag(17, 0x14, "ContentLocation")
        val IS_INLINE = tag(17, 0x15, "IsInline")
        val NATIVE_BODY_TYPE = tag(17, 0x16, "NativeBodyType")
        val CONTENT_TYPE = tag(17, 0x17, "ContentType")
        val PREVIEW = tag(17, 0x18, "Preview")
        val BODY_PART_PREFERENCE = tag(17, 0x19, "BodyPartPreference")
        val BODY_PART = tag(17, 0x1A, "BodyPart")
        val STATUS = tag(17, 0x1B, "Status")
        val ADD = tag(17, 0x1C, "Add")
        val DELETE = tag(17, 0x1D, "Delete")
        val CLIENT_ID = tag(17, 0x1E, "ClientId")
        val CONTENT = tag(17, 0x1F, "Content")
        val LOCATION = tag(17, 0x20, "Location")
        val ANNOTATION = tag(17, 0x21, "Annotation")
        val STREET = tag(17, 0x22, "Street")
        val CITY = tag(17, 0x23, "City")
        val STATE = tag(17, 0x24, "State")
        val COUNTRY = tag(17, 0x25, "Country")
        val POSTAL_CODE = tag(17, 0x26, "PostalCode")
        val LATITUDE = tag(17, 0x27, "Latitude")
        val LONGITUDE = tag(17, 0x28, "Longitude")
        val ACCURACY = tag(17, 0x29, "Accuracy")
        val ALTITUDE = tag(17, 0x2A, "Altitude")
        val ALTITUDE_ACCURACY = tag(17, 0x2B, "AltitudeAccuracy")
        val LOCATION_URI = tag(17, 0x2C, "LocationUri")
        val INSTANCE_ID = tag(17, 0x2D, "InstanceId")

        internal val all =
            listOf(
                BODY_PREFERENCE,
                TYPE,
                TRUNCATION_SIZE,
                ALL_OR_NONE,
                BODY,
                DATA,
                ESTIMATED_DATA_SIZE,
                TRUNCATED,
                ATTACHMENTS,
                ATTACHMENT,
                DISPLAY_NAME,
                FILE_REFERENCE,
                METHOD,
                CONTENT_ID,
                CONTENT_LOCATION,
                IS_INLINE,
                NATIVE_BODY_TYPE,
                CONTENT_TYPE,
                PREVIEW,
                BODY_PART_PREFERENCE,
                BODY_PART,
                STATUS,
                ADD,
                DELETE,
                CLIENT_ID,
                CONTENT,
                LOCATION,
                ANNOTATION,
                STREET,
                CITY,
                STATE,
                COUNTRY,
                POSTAL_CODE,
                LATITUDE,
                LONGITUDE,
                ACCURACY,
                ALTITUDE,
                ALTITUDE_ACCURACY,
                LOCATION_URI,
                INSTANCE_ID,
            )
    }

    private val byCode: Map<Pair<Int, Int>, WbxmlTag> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        (AirSync.all + Calendar.all + FolderHierarchy.all + AirSyncBase.all)
            .associateBy { value -> value.codePage to value.token }
    }

    internal fun find(
        codePage: Int,
        token: Int,
    ): WbxmlTag? = byCode[codePage to token]

    internal fun contains(tag: WbxmlTag): Boolean = byCode[tag.codePage to tag.token] == tag

    private fun tag(
        codePage: Int,
        token: Int,
        name: String,
    ): WbxmlTag = WbxmlTag(codePage, token, name)
}
