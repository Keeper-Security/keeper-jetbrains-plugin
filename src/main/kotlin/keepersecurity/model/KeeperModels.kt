package keepersecurity.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KeeperFolder(
    @SerialName("folder_uid") val folderUid: String,
    val name: String,
    val flags: String? = null,
    @SerialName("parent_uid") val parentUid: String? = null
)

@Serializable
data class KeeperRecord(
    @SerialName("record_uid") val recordUid: String,
    val title: String,
    val fields: List<KeeperField>? = null,
    val custom: List<KeeperCustomField>? = null
)

@Serializable
data class KeeperField(
    val type: String,
    val value: List<String>? = null
)

@Serializable
data class KeeperCustomField(
    val label: String,
    val value: List<String>? = null
)

@Serializable
data class KeeperSecret(
    val password: String? = null,
    // Add other fields as needed
)

@Serializable
data class GeneratedPassword(
    val password: String
)
